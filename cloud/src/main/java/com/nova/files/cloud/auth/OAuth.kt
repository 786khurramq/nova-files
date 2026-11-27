package com.nova.files.cloud.auth

import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public-client OAuth configuration. No client secret: PKCE protects the code exchange. */
data class OAuthConfig(
    val providerId: ProviderId,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val extraAuthParams: Map<String, String> = emptyMap(),
    val revokeEndpoint: String? = null,
)

object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    fun verifier(): String = b64.encodeToString(ByteArray(48).also { random.nextBytes(it) })   // 64 chars, RFC 7636 §4.1
    fun challenge(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun state(): String = b64.encodeToString(ByteArray(24).also { random.nextBytes(it) })
}

/** Survives process death while the user is in the browser. Stored encrypted; single use; expires. */
@Serializable
data class PendingAuth(
    val state: String,
    val verifier: String,
    val providerId: ProviderId,
    val redirectUri: String,
    val createdAt: Long,
    /** Set when re-authenticating an existing account, so the result must match that identity. */
    val reconnectAccountId: String? = null,
)

class PendingAuthStore(private val store: CredentialStore, private val ttlMs: Long = 15 * 60_000) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun begin(providerId: ProviderId, redirectUri: String, reconnectAccountId: String? = null, now: Long = System.currentTimeMillis()): PendingAuth {
        purgeExpired(now)
        val p = PendingAuth(Pkce.state(), Pkce.verifier(), providerId, redirectUri, now, reconnectAccountId)
        store.putRaw("pending:${p.state}", json.encodeToString(PendingAuth.serializer(), p))
        return p
    }

    /** Validates and consumes [state]. Unknown, reused or expired states are rejected (CSRF protection). */
    suspend fun consume(state: String?, now: Long = System.currentTimeMillis()): PendingAuth? {
        if (state.isNullOrBlank()) return null
        val ref = "pending:$state"
        val raw = (store.getRaw(ref) as? CredentialStore.RawResult.Ok)?.value
        store.remove(ref)
        val p = raw?.let { runCatching { json.decodeFromString(PendingAuth.serializer(), it) }.getOrNull() } ?: return null
        if (p.state != state || now - p.createdAt > ttlMs) return null
        return p
    }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) {
        for (ref in store.refs("pending:")) {
            val raw = (store.getRaw(ref) as? CredentialStore.RawResult.Ok)?.value
            val p = raw?.let { runCatching { json.decodeFromString(PendingAuth.serializer(), it) }.getOrNull() }
            if (p == null || now - p.createdAt > ttlMs) store.remove(ref)
        }
    }
}

/** Authorization-code + PKCE flow against any standards-compliant token endpoint. */
class OAuthClient(private val http: OkHttpClient) {

    fun authorizationUrl(cfg: OAuthConfig, pending: PendingAuth): String =
        cfg.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", cfg.clientId)
            .addQueryParameter("redirect_uri", cfg.redirectUri)
            .addQueryParameter("scope", cfg.scopes.joinToString(" "))
            .addQueryParameter("state", pending.state)
            .addQueryParameter("code_challenge", Pkce.challenge(pending.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .apply { cfg.extraAuthParams.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build().toString()

    suspend fun exchangeCode(cfg: OAuthConfig, code: String, pending: PendingAuth): Credentials.OAuth =
        token(cfg, FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", pending.redirectUri).add("client_id", cfg.clientId).add("code_verifier", pending.verifier).build(), null)

    /** Refresh. Providers that rotate refresh tokens return a new one; otherwise the old one is kept. */
    suspend fun refresh(cfg: OAuthConfig, current: Credentials.OAuth, accountId: String): Credentials.OAuth {
        val rt = current.refreshToken ?: throw CloudException.AuthRequired(accountId, "Sign-in expired – reconnect this account")
        return token(cfg, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).add("client_id", cfg.clientId).build(), current, accountId)
    }

    private suspend fun token(cfg: OAuthConfig, body: FormBody, previous: Credentials.OAuth?, accountId: String? = null): Credentials.OAuth = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(cfg.tokenEndpoint).post(body).header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (!r.isSuccessful) {
                    val err = obj?.get("error")?.jsonPrimitive?.content
                    val desc = obj?.get("error_description")?.jsonPrimitive?.content.orEmpty()
                    when {
                        err == "invalid_grant" || r.code == 401 -> throw CloudException.AuthRequired(accountId, "Access was revoked or expired – reconnect this account")
                        desc.contains("AADSTS65001") || err == "consent_required" -> throw CloudException.ConsentRequired("the app hasn't been granted the required permission")
                        r.code == 429 -> throw CloudException.RateLimited(r.header("Retry-After")?.toLongOrNull()?.times(1000))
                        r.code >= 500 -> throw CloudException.Server(r.code)
                        else -> throw CloudException.Http(r.code, "Sign-in failed (${err ?: r.code})")
                    }
                }
                obj ?: throw CloudException.Http(r.code, "Unexpected sign-in response")
                val access = obj["access_token"]?.jsonPrimitive?.content ?: throw CloudException.Http(r.code, "No access token in response")
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
                Credentials.OAuth(
                    accessToken = access,
                    refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: previous?.refreshToken,
                    expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                    scope = obj["scope"]?.jsonPrimitive?.content ?: previous?.scope,
                )
            }
        } catch (e: java.io.IOException) { throw CloudException.Network(e) }
    }
}
