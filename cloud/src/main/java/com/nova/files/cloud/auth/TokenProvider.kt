package com.nova.files.cloud.auth

import com.nova.files.cloud.core.CloudException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Access tokens for exactly one account. Refresh is single-flight: concurrent callers wait on one
 * refresh, and a caller reporting a token that has already been replaced just gets the new one.
 * Each account has its own instance, so one account's refresh never blocks or affects another.
 */
class AccountTokenProvider(
    val accountId: String,
    private val store: CredentialStore,
    private val credentialRef: String,
    private val refresher: suspend (Credentials.OAuth) -> Credentials.OAuth,
    private val onAuthLost: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var cached: Credentials.OAuth? = null

    suspend fun accessToken(): String = mutex.withLock {
        val c = current()
        if (c.expiresSoon()) refreshLocked(c).accessToken else c.accessToken
    }

    /** Called after a 401 with the token that failed. Refreshes at most once per stale token. */
    suspend fun refreshAfterRejection(failedToken: String?): String = mutex.withLock {
        val c = current()
        if (failedToken != null && c.accessToken != failedToken) return@withLock c.accessToken
        refreshLocked(c).accessToken
    }

    private suspend fun current(): Credentials.OAuth {
        cached?.let { return it }
        return when (val r = store.get(credentialRef)) {
            is CredentialResult.Ok -> (r.credentials as? Credentials.OAuth ?: throw CloudException.CredentialsUnavailable(accountId)).also { cached = it }
            CredentialResult.Missing -> { onAuthLost(accountId); throw CloudException.AuthRequired(accountId) }
            is CredentialResult.Unreadable -> { onAuthLost(accountId); throw CloudException.CredentialsUnavailable(accountId) }
        }
    }

    private suspend fun refreshLocked(c: Credentials.OAuth): Credentials.OAuth {
        val fresh = try { refresher(c) } catch (e: CloudException.AuthRequired) { cached = null; onAuthLost(accountId); throw e }
        store.put(credentialRef, fresh)
        cached = fresh
        return fresh
    }

    fun forget() { cached = null }
}
