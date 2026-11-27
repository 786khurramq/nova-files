package com.nova.files.cloud

import com.nova.files.cloud.core.*
import com.nova.files.cloud.providers.dropbox.DropboxProvider
import com.nova.files.cloud.providers.google.GoogleDriveProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class ProvidersTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() = server.shutdown()

    private fun base() = server.url("").toString().trimEnd('/')
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    /** Independent reference implementation of Dropbox's content_hash. */
    private fun dbxHash(data: ByteArray): String {
        val blocks = data.toList().chunked(4 * 1024 * 1024).map { sha(it.toByteArray()) }
        return hex(sha(blocks.fold(ByteArray(0)) { a, b -> a + b }))
    }
    private fun md5(b: ByteArray) = hex(MessageDigest.getInstance("MD5").digest(b))
    private fun src(data: ByteArray) = ByteSource { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) }

    // ================================================================ hashing

    @Test fun dropboxContentHash() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DropboxContentHasher().digestHex())
        val data = kotlin.random.Random(3).nextBytes(9 * 1024 * 1024 + 17)       // 3 blocks
        val h = DropboxContentHasher(); var i = 0
        while (i < data.size) { val n = minOf(777_777, data.size - i); h.update(data, i, n); i += n }
        assertEquals(dbxHash(data), h.digestHex())
        assertNull(MultiHasher.mismatch(mapOf(HashAlgorithm.MD5 to "aa"), mapOf(HashAlgorithm.SHA256 to "bb")))   // never compare different algorithms
        assertEquals(HashAlgorithm.MD5, MultiHasher.mismatch(mapOf(HashAlgorithm.MD5 to "aa"), mapOf(HashAlgorithm.MD5 to "bb")))
    }

    // ================================================================ Dropbox

    private fun dropbox(chunk: Int = 8 * 1024 * 1024) = DropboxProvider(api = base(), content = base(), chunkBytes = chunk)
    private val dbxSession get() = AccountSession("acc-1", ProviderId.DROPBOX, http)

    @Test fun dropboxProfileQuotaAndPagination() = runBlocking {
        server.enqueue(json("""{"account_id":"dbid:AAH4","name":{"display_name":"Khurram"},"email":"k@example.com"}"""))
        server.enqueue(json("""{"used":1000,"allocation":{".tag":"individual","allocated":2000}}"""))
        server.enqueue(json("""{"entries":[{".tag":"folder","name":"Photos","path_lower":"/photos","id":"id:F1"},
            {".tag":"file","name":"a.jpg","path_lower":"/a.jpg","id":"id:A1","size":10,"rev":"r1","server_modified":"2026-01-02T03:04:05Z","content_hash":"abc","is_downloadable":true}],
            "cursor":"C1","has_more":true}"""))
        server.enqueue(json("""{"entries":[{".tag":"file","name":"Paper doc.paper","id":"id:P1","path_lower":"/paper doc.paper","is_downloadable":false},{".tag":"deleted","name":"gone"}],"cursor":"C2","has_more":false}"""))
        val p = dropbox()
        val prof = p.profile(dbxSession)
        assertEquals("dbid:AAH4", prof.providerAccountId); assertEquals("Khurram", prof.displayName)
        assertEquals(Quota(1000, 2000), p.quota(dbxSession))
        val page1 = p.list(dbxSession, null, null)
        assertEquals("C1", page1.nextCursor); assertEquals(2, page1.items.size)
        val file = page1.items[1]
        assertEquals("id:A1", file.id); assertEquals(ItemKind.FILE, file.kind); assertEquals(10L, file.size)
        assertEquals("abc", file.hashes[HashAlgorithm.DROPBOX_CONTENT_HASH]); assertEquals("acc-1", file.accountId)
        assertNotNull(file.thumbnailRef)
        assertEquals(ItemKind.FOLDER, page1.items[0].kind)
        val page2 = p.list(dbxSession, null, page1.nextCursor)
        assertNull(page2.nextCursor); assertEquals(1, page2.items.size)
        assertEquals(ItemKind.SPECIAL, page2.items[0].kind)                                  // Paper: not an ordinary file
        assertTrue(p.canExecute(page2.items[0], Operation.DOWNLOAD) is CapabilityResult.Unsupported)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"path\":\"\""))           // root = ""
        val cont = server.takeRequest()
        assertTrue(cont.path!!.endsWith("/2/files/list_folder/continue") && cont.body.readUtf8().contains("\"cursor\":\"C1\""))
    }

    @Test fun dropboxSmallUploadEscapesNamesAndVerifiesHash() = runBlocking {
        val data = "hello nova".toByteArray()
        server.enqueue(json("""{"name":"Café.txt","id":"id:U1","path_lower":"/café.txt","size":10,"content_hash":"${dbxHash(data)}"}"""))
        var progress = 0L
        val f = dropbox().upload(dbxSession, null, "Café.txt", data.size.toLong(), null, src(data), ConflictPolicy.KEEP_BOTH, null, { progress += it }, {})
        assertEquals("id:U1", f.id); assertEquals(10L, progress)
        val req = server.takeRequest()
        val arg = req.getHeader("Dropbox-API-Arg")!!
        assertTrue("ASCII-only header: $arg", arg.all { it.code < 128 } && arg.contains("\\u00e9"))
        assertTrue(arg.contains("\"mode\":\"add\"") && arg.contains("\"autorename\":true"))
        assertArrayEquals(data, req.body.readByteArray())

        server.enqueue(json("""{"name":"x","id":"id:U2","size":10,"content_hash":"0000"}"""))
        val e = runCatching { dropbox().upload(dbxSession, null, "x", data.size.toLong(), null, src(data), ConflictPolicy.KEEP_BOTH, null, {}, {}) }.exceptionOrNull()
        assertTrue(e is CloudException.Integrity)
    }

    @Test fun dropboxSessionUploadResumesFromCheckpoint() = runBlocking {
        val data = ByteArray(25) { it.toByte() }
        // First attempt: start (10 bytes) + one append (10), then the connection drops.
        server.enqueue(json("""{"session_id":"S1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val cps = mutableListOf<UploadCheckpoint>()
        val first = runCatching { dropbox(10).upload(dbxSession, "/docs", "big.bin", 25, null, src(data), ConflictPolicy.KEEP_BOTH, null, {}, { cps += it }) }
        assertTrue("${first.exceptionOrNull()}", first.exceptionOrNull() is CloudException.Network)
        assertEquals(20L, cps.last().confirmedBytes); assertEquals("S1", cps.last().sessionId); assertEquals("/docs/big.bin", cps.last().extra["path"])
        repeat(3) { server.takeRequest() }
        // Resume: appends the last 5 bytes at offset 20, then finishes.
        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))
        server.enqueue(json("""{"name":"big.bin","id":"id:B1","size":25,"content_hash":"${dbxHash(data)}"}"""))
        var progress = 0L
        val f = dropbox(10).upload(dbxSession, "/docs", "big.bin", 25, null, src(data), ConflictPolicy.KEEP_BOTH, cps.last(), { progress += it }, {})
        assertEquals("id:B1", f.id); assertEquals(25L, progress)
        val append = server.takeRequest()
        assertTrue(append.getHeader("Dropbox-API-Arg")!!.contains("\"offset\":20"))
        assertArrayEquals(data.copyOfRange(20, 25), append.body.readByteArray())
        assertTrue(server.takeRequest().getHeader("Dropbox-API-Arg")!!.contains("\"offset\":25"))
    }

    @Test fun dropboxConflictsAndErrors() = runBlocking {
        val data = "x".toByteArray()
        server.enqueue(json("""{"error_summary":"path/conflict/file/..","error":{}}""", 409))
        assertTrue(runCatching { dropbox().upload(dbxSession, null, "a.txt", 1, null, src(data), ConflictPolicy.ASK, null, {}, {}) }.exceptionOrNull() is CloudException.Conflict)
        assertTrue(server.takeRequest().getHeader("Dropbox-API-Arg")!!.contains("\"autorename\":false"))
        server.enqueue(json("""{"error_summary":"path/conflict/file/..","error":{}}""", 409))
        server.enqueue(json("""{".tag":"file","name":"a.txt","id":"id:E1","size":1}"""))
        val skipped = dropbox().upload(dbxSession, null, "a.txt", 1, null, src(data), ConflictPolicy.SKIP, null, {}, {})
        assertEquals("true", skipped.extra["skipped"])
        server.takeRequest(); server.takeRequest()
        server.enqueue(json("""{"name":"a.txt","id":"id:R1","size":1,"content_hash":"${dbxHash(data)}"}"""))
        dropbox().upload(dbxSession, null, "a.txt", 1, null, src(data), ConflictPolicy.REPLACE, null, {}, {})
        assertTrue(server.takeRequest().getHeader("Dropbox-API-Arg")!!.contains("\"mode\":\"overwrite\""))
        server.enqueue(json("""{"error_summary":"path/not_found/..","error":{}}""", 409))
        assertTrue(runCatching { dropbox().metadata(dbxSession, "id:zz") }.exceptionOrNull() is CloudException.NotFound)
        server.enqueue(json("""{"error_summary":"path/insufficient_space/..","error":{}}""", 409))
        assertTrue(runCatching { dropbox().createFolder(dbxSession, null, "n", ConflictPolicy.ASK) }.exceptionOrNull() is CloudException.QuotaExceeded)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        assertEquals(3000L, (runCatching { dropbox().quota(dbxSession) }.exceptionOrNull() as CloudException.RateLimited).retryAfterMs)
    }

    @Test fun dropboxRangedDownload() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody("world"))
        val f = CloudFile("acc-1", ProviderId.DROPBOX, "id:D1", null, "h.txt", null, ItemKind.FILE, 11, null, null, "r")
        val d = dropbox().download(dbxSession, f, 6, null)
        assertEquals(6L, d.startOffset); assertEquals("world", d.stream.readBytes().decodeToString()); assertEquals(11L, d.length)
        val req = server.takeRequest()
        assertEquals("bytes=6-", req.getHeader("Range")); assertTrue(req.getHeader("Dropbox-API-Arg")!!.contains("id:D1"))
    }

    // ================================================================ Google Drive

    private fun drive(chunk: Int = 8 * 1024 * 1024) = GoogleDriveProvider(api = base() + "/drive/v3", upload = base() + "/upload/drive/v3", chunkBytes = chunk)
    private val gSession get() = AccountSession("acc-g", ProviderId.GOOGLE_DRIVE, http)

    @Test fun driveListingParsesKindsHashesAndExports() = runBlocking {
        server.enqueue(json("""{"nextPageToken":"P2","files":[
            {"id":"F1","name":"Trips","mimeType":"application/vnd.google-apps.folder","parents":["root"]},
            {"id":"B1","name":"photo.jpg","mimeType":"image/jpeg","size":"2048","md5Checksum":"m5","sha256Checksum":"s2","version":"7","hasThumbnail":true,"thumbnailLink":"https://lh3.example/t=s220"},
            {"id":"D1","name":"Budget","mimeType":"application/vnd.google-apps.spreadsheet"},
            {"id":"X1","name":"Form","mimeType":"application/vnd.google-apps.form"}]}"""))
        val page = drive().list(gSession, null, null)
        assertEquals("P2", page.nextCursor)
        val (folder, photo, sheet, form) = page.items
        assertEquals(ItemKind.FOLDER, folder.kind)
        assertEquals(2048L, photo.size); assertEquals("m5", photo.hashes[HashAlgorithm.MD5]); assertEquals("7", photo.revision)
        assertEquals(ItemKind.NATIVE_DOCUMENT, sheet.kind); assertEquals(listOf("xlsx", "pdf"), sheet.exportFormats.map { it.extension })
        assertTrue(drive().canExecute(sheet, Operation.DOWNLOAD) is CapabilityResult.RequiresExport)
        assertEquals(ItemKind.SPECIAL, form.kind)
        val q = server.takeRequest().requestUrl!!
        assertEquals("'root' in parents and trashed = false", q.queryParameter("q"))
        assertTrue(q.queryParameter("fields")!!.contains("md5Checksum"))
        assertEquals("name contains 'O\\'Brien' and trashed = false", run {
            server.enqueue(json("""{"files":[]}""")); drive().search(gSession, "O'Brien", null); server.takeRequest().requestUrl!!.queryParameter("q")
        })
    }

    @Test fun driveResumableUploadWithInterruptionAndChecksum() = runBlocking {
        val data = ByteArray(20) { (it * 3).toByte() }
        val session = server.url("/upload-session/xyz").toString()
        server.enqueue(json("""{"files":[]}"""))                                                 // conflict check: none
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", session))       // init
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-7"))     // chunk 1 ok
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val cps = mutableListOf<UploadCheckpoint>()
        val e1 = runCatching { drive(8).upload(gSession, "F1", "v.bin", 20, "application/octet-stream", src(data), ConflictPolicy.KEEP_BOTH, null, {}, { cps += it }) }.exceptionOrNull()
        assertTrue("$e1", e1 is CloudException.Network)
        assertEquals(session, cps.last().sessionId); assertEquals(8L, cps.last().confirmedBytes)
        server.takeRequest()
        val init = server.takeRequest()
        assertEquals("resumable", init.requestUrl!!.queryParameter("uploadType"))
        assertEquals("20", init.getHeader("X-Upload-Content-Length"))
        assertTrue(init.body.readUtf8().contains("\"parents\":[\"F1\"]"))
        assertEquals("bytes 0-7/20", server.takeRequest().getHeader("Content-Range"))
        server.takeRequest()
        // Resume: ask Drive what it has, continue from byte 8.
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-7"))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-15"))
        server.enqueue(json("""{"id":"N1","name":"v.bin","mimeType":"application/octet-stream","size":"20","md5Checksum":"${md5(data)}"}"""))
        var progress = 0L
        val f = drive(8).upload(gSession, "F1", "v.bin", 20, null, src(data), ConflictPolicy.KEEP_BOTH, cps.last(), { progress += it }, {})
        assertEquals("N1", f.id); assertEquals(20L, progress)
        assertEquals("bytes */20", server.takeRequest().getHeader("Content-Range"))
        val c2 = server.takeRequest(); assertEquals("bytes 8-15/20", c2.getHeader("Content-Range")); assertArrayEquals(data.copyOfRange(8, 16), c2.body.readByteArray())
        assertEquals("bytes 16-19/20", server.takeRequest().getHeader("Content-Range"))
    }

    @Test fun driveDetectsCorruptUpload() = runBlocking {
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", server.url("/s").toString()))
        server.enqueue(json("""{"id":"N1","name":"a","size":"3","md5Checksum":"deadbeef"}"""))
        assertTrue(runCatching { drive().upload(gSession, null, "a", 3, null, src("abc".toByteArray()), ConflictPolicy.KEEP_BOTH, null, {}, {}) }.exceptionOrNull() is CloudException.Integrity)
    }

    @Test fun driveConflictPolicies() = runBlocking {
        val existing = """{"files":[{"id":"E1","name":"a.txt","mimeType":"text/plain","size":"1"}]}"""
        server.enqueue(json(existing))
        assertTrue(runCatching { drive().createFolder(gSession, null, "a.txt", ConflictPolicy.ASK) }.exceptionOrNull() is CloudException.Conflict)
        server.takeRequest()
        // KEEP_BOTH → "a (1).txt"
        server.enqueue(json(existing)); server.enqueue(json(existing))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", server.url("/s").toString()))
        server.enqueue(json("""{"id":"N2","name":"a (1).txt","size":"1","md5Checksum":"${md5("x".toByteArray())}"}"""))
        drive().upload(gSession, null, "a.txt", 1, null, src("x".toByteArray()), ConflictPolicy.KEEP_BOTH, null, {}, {})
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"name\":\"a (1).txt\""))
        server.takeRequest()
        // REPLACE → new revision of the same file id (PATCH)
        server.enqueue(json(existing))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", server.url("/s").toString()))
        server.enqueue(json("""{"id":"E1","name":"a.txt","size":"1","md5Checksum":"${md5("x".toByteArray())}"}"""))
        assertEquals("E1", drive().upload(gSession, null, "a.txt", 1, null, src("x".toByteArray()), ConflictPolicy.REPLACE, null, {}, {}).id)
        server.takeRequest()
        val patch = server.takeRequest(); assertEquals("PATCH", patch.method); assertTrue(patch.path!!.contains("/files/E1"))
        server.takeRequest()
        // SKIP → existing returned untouched
        server.enqueue(json(existing))
        assertEquals("true", drive().upload(gSession, null, "a.txt", 1, null, src("x".toByteArray()), ConflictPolicy.SKIP, null, {}, {}).extra["skipped"])
    }

    @Test fun driveExportTrashAndErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("%PDF"))
        val doc = CloudFile("acc-g", ProviderId.GOOGLE_DRIVE, "D1", null, "Notes", "application/vnd.google-apps.document", ItemKind.NATIVE_DOCUMENT,
            null, null, null, "1", exportFormats = GoogleDriveProvider.EXPORTS["application/vnd.google-apps.document"]!!)
        val d = drive().download(gSession, doc, 0, doc.exportFormats[1])
        assertEquals("%PDF", d.stream.readBytes().decodeToString())
        val r = server.takeRequest().requestUrl!!
        assertTrue(r.encodedPath.endsWith("/files/D1/export"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", r.queryParameter("mimeType"))
        server.enqueue(json("""{"id":"B1","name":"x","mimeType":"text/plain"}"""))
        drive().delete(gSession, CloudFile("acc-g", ProviderId.GOOGLE_DRIVE, "B1", null, "x", null, ItemKind.FILE, 1, null, null, null))
        val del = server.takeRequest(); assertEquals("PATCH", del.method); assertTrue(del.body.readUtf8().contains("\"trashed\":true"))
        server.enqueue(json("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", 403))
        assertTrue(runCatching { drive().createFolder(gSession, null, "n", ConflictPolicy.ASK) }.exceptionOrNull() is CloudException.QuotaExceeded)
        server.enqueue(json("{}", 404))
        assertTrue(runCatching { drive().metadata(gSession, "nope") }.exceptionOrNull() is CloudException.NotFound)
    }
}
