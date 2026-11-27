package com.nova.files.cloud

import com.nova.files.cloud.auth.AccountTokenProvider
import com.nova.files.cloud.auth.Credentials
import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class NetworkTest {

    @Test fun eachAccountClientSendsOnlyItsOwnToken() = runBlocking {
        val server = MockWebServer().apply { repeat(4) { enqueue(MockResponse().setBody("ok")) }; start() }
        val store = credentialStore()
        store.put("acct:A", Credentials.OAuth("token-A", "r", Long.MAX_VALUE))
        store.put("acct:B", Credentials.OAuth("token-B", "r", Long.MAX_VALUE))
        val http = CloudHttp()
        val a = http.forOAuthAccount(AccountTokenProvider("A", store, "acct:A", { it }))
        val b = http.forOAuthAccount(AccountTokenProvider("B", store, "acct:B", { it }))
        a.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        b.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-B", server.takeRequest().getHeader("Authorization"))
        // Shared connection pool and dispatcher, separate auth.
        assertSame(a.connectionPool, b.connectionPool); assertSame(a.dispatcher, b.dispatcher)
        server.shutdown()
    }

    @Test fun unauthorizedTriggersOneRefreshThenRetries() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401)); enqueue(MockResponse().setBody("ok")); start()
        }
        val store = credentialStore()
        store.put("acct:A", Credentials.OAuth("stale", "r", Long.MAX_VALUE))
        val refreshes = AtomicInteger()
        val client = CloudHttp().forOAuthAccount(AccountTokenProvider("A", store, "acct:A", { refreshes.incrementAndGet(); it.copy(accessToken = "fresh") }))
        val r = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertEquals(200, r.code); r.close()
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, refreshes.get())
        server.shutdown()
    }

    @Test fun repeated401DoesNotLoop() = runBlocking {
        val server = MockWebServer().apply { repeat(5) { enqueue(MockResponse().setResponseCode(401)) }; start() }
        val store = credentialStore(); store.put("acct:A", Credentials.OAuth("t", "r", Long.MAX_VALUE))
        val client = CloudHttp().forOAuthAccount(AccountTokenProvider("A", store, "acct:A", { it.copy(accessToken = it.accessToken + "x") }))
        val r = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertEquals(401, r.code); r.close()
        assertEquals(2, server.requestCount)   // original + exactly one retry
        server.shutdown()
    }

    @Test fun credentialsAreNotForwardedToAnotherHostOnRedirect() = runBlocking {
        val other = MockWebServer().apply { enqueue(MockResponse().setBody("elsewhere")); start() }
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen"))); start() }
        val store = credentialStore(); store.put("acct:A", Credentials.OAuth("secret-token", "r", Long.MAX_VALUE))
        val client = CloudHttp().forOAuthAccount(AccountTokenProvider("A", store, "acct:A", { it }))
        client.newCall(Request.Builder().url(server.url("/start")).build()).execute().close()
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
        assertNull(other.takeRequest().getHeader("Authorization"))
        // Basic auth is only ever added for the configured host.
        val basic = CloudHttp().forBasicAccount("u", "p", "dav.example.invalid")
        basic.newCall(Request.Builder().url(other.url("/x")).build()).also { other.enqueue(MockResponse()) }.execute().close()
        assertNull(other.takeRequest().getHeader("Authorization"))
        assertFalse("HTTPS↔HTTP redirects disabled", CloudHttp().base.followSslRedirects)
        server.shutdown(); other.shutdown()
    }

    @Test fun errorMapping() {
        assertTrue(HttpErrors.map(401, null, "", "A") is CloudException.AuthRequired)
        assertTrue(HttpErrors.map(403, null, """{"reason":"storageQuotaExceeded"}""", "A") is CloudException.QuotaExceeded)
        assertTrue(HttpErrors.map(403, null, "", "A") is CloudException.Forbidden)
        assertTrue(HttpErrors.map(404, null, "", "A") is CloudException.NotFound)
        assertTrue(HttpErrors.map(409, null, "", "A") is CloudException.Conflict)
        assertTrue(HttpErrors.map(413, null, "", "A") is CloudException.PayloadTooLarge)
        assertEquals(7000L, (HttpErrors.map(429, "7", "", "A") as CloudException.RateLimited).retryAfterMs)
        assertTrue(HttpErrors.map(503, null, "", "A") is CloudException.Server)
        assertTrue(HttpErrors.map(507, null, "", "A") is CloudException.QuotaExceeded)
        assertEquals(30_000L, HttpErrors.parseRetryAfter("Thu, 01 Jan 1970 00:00:40 GMT", now = 10_000))
        assertTrue(HttpErrors.mapIo(javax.net.ssl.SSLHandshakeException("bad cert")) is CloudException.Tls)
        assertTrue(HttpErrors.mapIo(java.net.UnknownHostException("x")) is CloudException.Network)
    }

    @Test fun retryHonoursRetryAfterAndIdempotency() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val policy = RetryPolicy(maxAttempts = 4, random = Random(1), sleep = { sleeps += it })
        var n = 0
        val v = policy.run(idempotent = false) { n++; if (n < 3) throw CloudException.RateLimited(2000) else "done" }
        assertEquals("done", v); assertEquals(listOf(2000L, 2000L), sleeps)
        // Non-idempotent request + server error: never retried (could create duplicates).
        n = 0
        assertTrue(runCatching { policy.run(idempotent = false) { n++; throw CloudException.Server(503) } }.exceptionOrNull() is CloudException.Server)
        assertEquals(1, n)
        // Idempotent + network: backoff with jitter, bounded attempts.
        n = 0; sleeps.clear()
        assertTrue(runCatching { policy.run(idempotent = true) { n++; throw java.io.IOException("reset") } }.exceptionOrNull() is CloudException.Network)
        assertEquals(4, n); assertEquals(3, sleeps.size)
        assertTrue(sleeps.zipWithNext().all { (a, b) -> b >= a / 2 })
        // Auth failures are never retried.
        n = 0
        runCatching { policy.run(idempotent = true) { n++; throw CloudException.AuthRequired("A") } }
        assertEquals(1, n)
    }

    @Test fun redaction() {
        val s = Redact.text("Authorization: Bearer abc.def.ghi refresh_token=XYZ123 {\"access_token\":\"tok\",\"password\":\"pw\"}")
        assertFalse(s.contains("abc.def.ghi") || s.contains("XYZ123") || s.contains("\"tok\"") || s.contains("pw\""))
    }
}
