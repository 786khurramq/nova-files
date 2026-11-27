package com.nova.files.cloud

import com.nova.files.cloud.auth.*
import com.nova.files.cloud.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CoreAndAuthTest {

    private fun file(kind: ItemKind, exports: List<ExportFormat> = emptyList(), thumb: String? = null) =
        CloudFile("acc", ProviderId.GOOGLE_DRIVE, "id", null, "Doc", null, kind, null, null, null, null, thumbnailRef = thumb, exportFormats = exports)

    private val fullProvider = object : CloudProviderAdapter {
        override val providerId = ProviderId.GOOGLE_DRIVE
        override val capabilities = setOf(Operation.BROWSE, Operation.DOWNLOAD, Operation.UPLOAD, Operation.THUMBNAIL)
        override suspend fun profile(session: AccountSession) = throw NotImplementedError()
        override suspend fun list(session: AccountSession, folderId: String?, cursor: String?) = throw NotImplementedError()
    }

    @Test fun capabilityChecksAreItemSpecific() {
        val pdf = ExportFormat("application/pdf", "pdf", "PDF")
        assertTrue(fullProvider.canExecute(file(ItemKind.FILE), Operation.DOWNLOAD).allowed)
        assertTrue(fullProvider.canExecute(file(ItemKind.NATIVE_DOCUMENT, listOf(pdf)), Operation.DOWNLOAD) is CapabilityResult.RequiresExport)
        assertTrue(fullProvider.canExecute(file(ItemKind.NATIVE_DOCUMENT), Operation.DOWNLOAD) is CapabilityResult.Unsupported)
        assertTrue(fullProvider.canExecute(file(ItemKind.SPECIAL), Operation.DOWNLOAD) is CapabilityResult.Unsupported)
        assertTrue(fullProvider.canExecute(file(ItemKind.FOLDER), Operation.DOWNLOAD) is CapabilityResult.Unsupported)
        assertTrue(fullProvider.canExecute(file(ItemKind.FOLDER), Operation.UPLOAD).allowed)
        assertTrue(fullProvider.canExecute(file(ItemKind.FILE), Operation.THUMBNAIL) is CapabilityResult.Unsupported)
        assertTrue(fullProvider.canExecute(file(ItemKind.FILE, thumb = "t"), Operation.THUMBNAIL).allowed)
        assertTrue(fullProvider.canExecute(null, Operation.RENAME) is CapabilityResult.Unsupported)   // not declared
    }

    @Test fun unsupportedOperationsFailWithTypedError() = runTest {
        val s = AccountSession("acc", ProviderId.GOOGLE_DRIVE, OkHttpClient())
        val e = runCatching { fullProvider.rename(s, file(ItemKind.FILE), "x") }.exceptionOrNull()
        assertTrue(e is CloudException.Unsupported)
    }

    @Test fun fileNames() {
        val decomposed = "Café.txt"
        assertEquals("Café.txt", FileNames.sanitize(decomposed, NameTarget.ANDROID))
        assertEquals("a_b_c.txt", FileNames.sanitize("a:b?c.txt", NameTarget.FAT))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt", NameTarget.FAT))
        assertEquals("CON.txt", FileNames.sanitize("CON.txt", NameTarget.ANDROID))
        assertEquals("report", FileNames.sanitize("report. . ", NameTarget.FAT))
        assertEquals("_", FileNames.sanitize("..", NameTarget.ANDROID))
        assertTrue(FileNames.sanitize("é".repeat(300) + ".jpeg", NameTarget.ANDROID).let { it.toByteArray().size <= 255 && it.endsWith(".jpeg") })
        assertEquals("a (2).txt", FileNames.keepBoth("a.txt") { it == "a.txt" || it == "a (1).txt" })
        assertTrue(FileNames.sameName("Photo.JPG", "photo.jpg", caseSensitive = false))
        assertFalse(FileNames.sameName("Photo.JPG", "photo.jpg", caseSensitive = true))
    }

    @Test fun credentialsAreEncryptedAndBoundToTheirAccount() = runBlocking {
        val dir = tempDir()
        val store = credentialStore(dir = dir)
        val a = Credentials.OAuth("access-A", "refresh-A", 1000)
        store.put("acct:A", a)
        store.put("acct:B", Credentials.Password("bob", "s3cret"))
        assertEquals(CredentialResult.Ok(a), store.get("acct:A"))
        // Nothing readable on disk.
        val raw = dir.listFiles()!!.first { it.name.endsWith(".preferences_pb") }.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("access-A") || raw.contains("s3cret") || raw.contains("refresh-A"))
        // Copying A's ciphertext into B's slot must not authenticate as A (AAD binding).
        val encA = (store.getRawEncrypted("acct:A"))
        store.putEncrypted("acct:B", encA)
        assertTrue(store.get("acct:B") is CredentialResult.Unreadable)
        assertEquals(CredentialResult.Missing, store.get("acct:none"))
        store.remove("acct:A")
        assertEquals(CredentialResult.Missing, store.get("acct:A"))
    }

    @Test fun lostKeyMakesCredentialsUnreadableNotCrash() = runBlocking {
        val keys = SoftwareKeyProvider()
        val store = credentialStore(keys)
        store.put("acct:A", Credentials.Session("s"))
        keys.current = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()  // e.g. Keystore wiped
        assertTrue(store.get("acct:A") is CredentialResult.Unreadable)
    }

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it in "-._~" })
        assertNotEquals(Pkce.state(), Pkce.state())
    }

    @Test fun pendingAuthSurvivesRestartIsSingleUseAndExpires() = runBlocking {
        // Pending state lives in the encrypted DataStore file, so it survives process death.
        val store = credentialStore(dir = tempDir())
        val pas = PendingAuthStore(store)
        assertNull(pas.consume("forged-state", now = 2_000))
        val q = pas.begin(ProviderId.DROPBOX, "nova://cb", now = 1_000)
        assertEquals(q.verifier, pas.consume(q.state, now = 2_000)?.verifier)
        assertNull(pas.consume(q.state, now = 3_000))                      // replay rejected
        val old = pas.begin(ProviderId.DROPBOX, "nova://cb", now = 0)
        assertNull(pas.consume(old.state, now = 16 * 60_000))              // expired
    }

    @Test fun tokenExchangeAndErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"access_token":"AT","refresh_token":"RT","expires_in":3600,"scope":"files"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request","error_description":"AADSTS65001: consent"}"""))
        server.start()
        val cfg = OAuthConfig(ProviderId.ONEDRIVE, server.url("/auth").toString(), server.url("/token").toString(), "client", "nova://cb", listOf("Files.ReadWrite"))
        val client = OAuthClient(OkHttpClient())
        val pending = PendingAuth("st", "verifier-123", ProviderId.ONEDRIVE, "nova://cb", 0)
        val url = client.authorizationUrl(cfg, pending)
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("state=st") && !url.contains("verifier-123"))
        val creds = client.exchangeCode(cfg, "CODE", pending)
        assertEquals("AT", creds.accessToken); assertEquals("RT", creds.refreshToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("code_verifier=verifier-123") && body.contains("grant_type=authorization_code") && !body.contains("client_secret"))
        assertTrue(runCatching { client.refresh(cfg, creds, "acc") }.exceptionOrNull() is CloudException.AuthRequired)
        assertTrue(runCatching { client.refresh(cfg, creds, "acc") }.exceptionOrNull() is CloudException.ConsentRequired)
        server.shutdown()
    }

    @Test fun refreshIsSingleFlightPerAccountAndAccountsAreIndependent() = runBlocking {
        val store = credentialStore()
        store.put("acct:A", Credentials.OAuth("old-A", "rA", Long.MAX_VALUE))
        store.put("acct:B", Credentials.OAuth("old-B", "rB", Long.MAX_VALUE))
        val refreshesA = AtomicInteger(); val gate = CompletableDeferred<Unit>()
        val a = AccountTokenProvider("A", store, "acct:A", { refreshesA.incrementAndGet(); gate.await(); it.copy(accessToken = "new-A") })
        val b = AccountTokenProvider("B", store, "acct:B", { it.copy(accessToken = "new-B") })
        val results = (1..50).map { async(Dispatchers.Default) { a.refreshAfterRejection("old-A") } }
        delay(100)
        // While A's refresh is blocked, B refreshes independently.
        assertEquals("new-B", withTimeout(2000) { b.refreshAfterRejection("old-B") })
        gate.complete(Unit)
        assertTrue(results.awaitAll().all { it == "new-A" })
        assertEquals(1, refreshesA.get())
        assertEquals("new-A", (store.get("acct:A") as CredentialResult.Ok).credentials.let { (it as Credentials.OAuth).accessToken })
        assertEquals("new-B", b.accessToken())
    }

    @Test fun revokedRefreshMarksAccountForReconnect() = runBlocking {
        val store = credentialStore()
        store.put("acct:A", Credentials.OAuth("old", "r", 0))   // already expired
        var lost: String? = null
        val a = AccountTokenProvider("A", store, "acct:A", { throw CloudException.AuthRequired("A") }, { lost = it })
        assertTrue(runCatching { a.accessToken() }.exceptionOrNull() is CloudException.AuthRequired)
        assertEquals("A", lost)
    }
}
