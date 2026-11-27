package com.nova.files.cloud.net

import com.nova.files.cloud.auth.AccountTokenProvider
import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.ProviderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials as OkCredentials
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * One shared base client (connection pool + bounded dispatcher). Every account gets a client
 * derived with [OkHttpClient.newBuilder] carrying ITS OWN credentials – there is no global
 * interceptor that could attach a different account's token.
 */
class CloudHttp(val base: OkHttpClient = defaultBase()) {

    /** Bearer-token client for one OAuth account. 401 → one single-flight refresh → retry once. */
    fun forOAuthAccount(tokens: AccountTokenProvider): OkHttpClient = base.newBuilder()
        .addInterceptor(BearerInterceptor(tokens))
        .authenticator(RefreshAuthenticator(tokens))
        .build()

    /** HTTP Basic client for one WebDAV account. Never sent to a different host (see [SameHostAuth]). */
    fun forBasicAccount(username: String, password: String, host: String): OkHttpClient = base.newBuilder()
        .addInterceptor(SameHostAuth(host, OkCredentials.basic(username, password, Charsets.UTF_8)))
        .build()

    companion object {
        fun defaultBase(): OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 4 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Never follow HTTPS↔HTTP redirects: blocks protocol downgrades. OkHttp also drops the
            // Authorization header itself whenever a redirect changes host.
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private class BearerInterceptor(private val tokens: AccountTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokens.accessToken() }   // OkHttp worker thread, never main
        return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
    }
}

private class RefreshAuthenticator(private val tokens: AccountTokenProvider) : Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: Response): okhttp3.Request? {
        if (response.priorResponse != null) return null      // already retried once: give up, surface 401
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = try { runBlocking { tokens.refreshAfterRejection(failed) } } catch (e: CloudException) { return null }
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}

/** Adds Basic auth only for requests to the configured host. */
private class SameHostAuth(private val host: String, private val header: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        return chain.proceed(if (r.url.host.equals(host, true)) r.newBuilder().header("Authorization", header).build() else r)
    }
}

/** Maps an unsuccessful response to a typed error. Body text is truncated and never contains our credentials. */
object HttpErrors {
    fun map(code: Int, retryAfter: String?, body: String, accountId: String?): CloudException = when (code) {
        401 -> CloudException.AuthRequired(accountId)
        403 -> if (body.contains("quota", true) && body.contains("exceed", true)) CloudException.QuotaExceeded()
               else if (body.contains("rateLimit", true)) CloudException.RateLimited(parseRetryAfter(retryAfter))
               else CloudException.Forbidden()
        404 -> CloudException.NotFound()
        409, 412 -> CloudException.Conflict(null)
        413 -> CloudException.PayloadTooLarge()
        429 -> CloudException.RateLimited(parseRetryAfter(retryAfter))
        507 -> CloudException.QuotaExceeded()
        500, 502, 503, 504 -> if (code == 503 && retryAfter != null) CloudException.RateLimited(parseRetryAfter(retryAfter)) else CloudException.Server(code)
        else -> CloudException.Http(code, "Request failed ($code)")
    }

    fun check(r: Response, accountId: String?): Response {
        if (r.isSuccessful) return r
        val body = runCatching { r.peekBody(4096).string() }.getOrDefault("")
        val e = map(r.code, r.header("Retry-After"), body, accountId)
        r.close()
        throw e
    }

    /** Retry-After is either delay-seconds or an HTTP date. */
    fun parseRetryAfter(v: String?, now: Long = System.currentTimeMillis()): Long? {
        v ?: return null
        v.trim().toLongOrNull()?.let { return (it * 1000).coerceIn(0, 3_600_000) }
        return runCatching { (java.time.ZonedDateTime.parse(v.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 3_600_000) }.getOrNull()
    }

    fun mapIo(e: IOException): CloudException = when (e) {
        is SSLException -> CloudException.Tls(e)
        is UnknownHostException -> CloudException.Network(e, "Server not found – check the address or your connection")
        else -> CloudException.Network(e)
    }
}

/** Exponential backoff with full jitter, honouring Retry-After; non-idempotent work is only retried when safe. */
class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 800,
    val maxDelayMs: Long = 30_000,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun delayFor(attempt: Int, e: CloudException): Long {
        if (e is CloudException.RateLimited && e.retryAfterMs != null) return e.retryAfterMs
        val cap = minOf(maxDelayMs, baseDelayMs shl (attempt - 1).coerceAtMost(10))
        return random.nextLong(cap / 2, cap + 1)
    }

    /**
     * @param idempotent true for GET/PUT-to-same-target/DELETE etc. A 429 is always safe to retry
     * (the request was not processed); 5xx/network errors are retried only when [idempotent].
     */
    suspend fun <T> run(idempotent: Boolean, block: suspend (attempt: Int) -> T): T {
        var attempt = 1
        while (true) {
            try { return block(attempt) }
            catch (e: IOException) { val c = HttpErrors.mapIo(e); if (!shouldRetry(c, idempotent, attempt)) throw c; sleep(delayFor(attempt, c)) }
            catch (e: CloudException) { if (!shouldRetry(e, idempotent, attempt)) throw e; sleep(delayFor(attempt, e)) }
            attempt++
        }
    }

    private fun shouldRetry(e: CloudException, idempotent: Boolean, attempt: Int): Boolean =
        attempt < maxAttempts && e.retryable && (idempotent || e is CloudException.RateLimited)
}

/** Bounded concurrency per provider; no unlimited global parallelism. */
class ProviderLimiter(private val limits: Map<ProviderId, Int> = emptyMap(), private val default: Int = 2) {
    private val semaphores = HashMap<ProviderId, Semaphore>()
    @Synchronized fun of(p: ProviderId): Semaphore = semaphores.getOrPut(p) { Semaphore(limits[p] ?: default) }
}

/** Removes secrets from any text that might reach logs or error reports. */
object Redact {
    private val patterns = listOf(
        Regex("(?i)(authorization:\\s*)(bearer|basic)\\s+[^\\s,;]+") to "$1$2 ███",
        Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 ███",
        Regex("(?i)(\"?(access_token|refresh_token|id_token|code_verifier|password|client_secret)\"?\\s*[:=]\\s*\"?)[^\"&\\s,}]+") to "$1███",
    )
    fun text(s: String): String = patterns.fold(s) { acc, (r, rep) -> r.replace(acc, rep) }
}
