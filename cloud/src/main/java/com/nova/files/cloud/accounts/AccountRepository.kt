package com.nova.files.cloud.accounts

import com.nova.files.cloud.auth.AccountTokenProvider
import com.nova.files.cloud.auth.CredentialResult
import com.nova.files.cloud.auth.CredentialStore
import com.nova.files.cloud.auth.Credentials
import com.nova.files.cloud.core.AccountProfile
import com.nova.files.cloud.core.AccountSession
import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.CloudProviderAdapter
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.db.AccountEntity
import com.nova.files.cloud.db.AuthStatus
import com.nova.files.cloud.db.CloudDatabase
import com.nova.files.cloud.db.ConnectionStatus
import com.nova.files.cloud.db.TransferState
import com.nova.files.cloud.net.CloudHttp
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Result of disconnecting, so the UI can tell the user exactly what happened. */
data class RemovalReport(val cancelledTransfers: Int, val cachedItemsRemoved: Int)

/**
 * Accounts, credentials and per-account sessions. Every operation takes an explicit account id;
 * there is no "current account".
 */
class AccountRepository(
    private val db: CloudDatabase,
    private val credentials: CredentialStore,
    private val http: CloudHttp,
    private val providers: Map<ProviderId, CloudProviderAdapter>,
    /** Provider-specific refresh, supplied by each OAuth provider. */
    private val refreshers: Map<ProviderId, suspend (accountId: String, Credentials.OAuth) -> Credentials.OAuth> = emptyMap(),
) {
    private val tokenProviders = HashMap<String, AccountTokenProvider>()

    fun observe(): Flow<List<AccountEntity>> = db.accounts().observeAll()
    suspend fun all() = db.accounts().all()
    suspend fun get(id: String) = db.accounts().byId(id)
    fun provider(id: ProviderId): CloudProviderAdapter? = providers[id]
    val availableProviders: List<ProviderId> get() = providers.keys.toList()

    /**
     * Adds a new account or reconnects an existing one. Identity is (provider, providerAccountId);
     * the same provider may have any number of different accounts.
     * @param expectAccountId when reconnecting, the signed-in identity must match this account.
     */
    suspend fun connect(providerId: ProviderId, profile: AccountProfile, creds: Credentials, config: String = "{}", expectAccountId: String? = null): AccountEntity {
        val existing = db.accounts().byIdentity(providerId.name, profile.providerAccountId)
        if (expectAccountId != null && existing?.id != expectAccountId)
            throw CloudException.Forbidden("You signed in with a different ${providerId.label} account than the one being reconnected")
        val now = System.currentTimeMillis()
        val entity = if (existing != null) {
            existing.copy(displayName = profile.displayName, email = profile.email, avatarUrl = profile.avatarUrl,
                authStatus = AuthStatus.OK, connectionStatus = ConnectionStatus.ONLINE, configJson = config).also { db.accounts().update(it) }
        } else {
            val id = UUID.randomUUID().toString()
            AccountEntity(id, providerId.name, profile.providerAccountId, profile.displayName, profile.email, profile.avatarUrl,
                AuthStatus.OK, ConnectionStatus.ONLINE, null, null, null, now, config, credentialRef = "acct:$id").also { db.accounts().insert(it) }
        }
        // Some providers (e.g. Google on reconnect) don't re-issue a refresh token: keep the existing one.
        val toStore = if (creds is Credentials.OAuth && creds.refreshToken == null && existing != null)
            (credentials.get(existing.credentialRef) as? CredentialResult.Ok)?.credentials?.let { old -> (old as? Credentials.OAuth)?.refreshToken }?.let { creds.copy(refreshToken = it) } ?: creds
        else creds
        credentials.put(entity.credentialRef, toStore)
        synchronized(tokenProviders) { tokenProviders.remove(entity.id)?.forget() }
        return entity
    }

    suspend fun markNeedsReconnect(accountId: String) = db.accounts().setAuth(accountId, AuthStatus.NEEDS_RECONNECT)

    /** Updates display name/email/avatar after a refresh, keeping identity unchanged. */
    suspend fun updateProfile(accountId: String, p: AccountProfile) {
        val a = db.accounts().byId(accountId) ?: return
        if (a.providerAccountId != p.providerAccountId) return
        db.accounts().update(a.copy(displayName = p.displayName, email = p.email, avatarUrl = p.avatarUrl))
    }

    suspend fun updateQuota(accountId: String, used: Long?, total: Long?) = db.accounts().setQuota(accountId, used, total, System.currentTimeMillis())

    /**
     * Disconnects one account: cancels ITS pending transfers, deletes ITS cached metadata (cascade)
     * and ITS credentials. Local files and other accounts are untouched.
     */
    suspend fun remove(accountId: String, revoke: Boolean = true): RemovalReport {
        val acct = db.accounts().byId(accountId) ?: return RemovalReport(0, 0)
        if (revoke) runCatching {
            val p = providers[ProviderId.valueOf(acct.providerId)]
            val rt = ((credentials.get(acct.credentialRef) as? CredentialResult.Ok)?.credentials as? Credentials.OAuth)?.refreshToken
            if (p != null) kotlinx.coroutines.withTimeoutOrNull(8000) { p.revoke(session(accountId), rt) }
        }
        val pending = db.transfers().pendingForAccount(accountId)
        val now = System.currentTimeMillis()
        pending.forEach { db.transfers().setState(it.id, TransferState.CANCELLED, "Account disconnected", now) }
        val cached = db.items().count(accountId)
        db.accounts().delete(accountId)
        credentials.remove(acct.credentialRef)
        synchronized(tokenProviders) { tokenProviders.remove(accountId)?.forget() }
        return RemovalReport(pending.size, cached)
    }

    /** Builds a session that can only act as [accountId]. */
    suspend fun session(accountId: String): AccountSession {
        val acct = db.accounts().byId(accountId) ?: throw CloudException.NotFound("Account no longer connected")
        val pid = ProviderId.valueOf(acct.providerId)
        if (acct.authStatus != AuthStatus.OK) throw CloudException.AuthRequired(accountId)
        val client = when (val c = credentials.get(acct.credentialRef)) {
            is CredentialResult.Ok -> when (val cr = c.credentials) {
                is Credentials.OAuth -> http.forOAuthAccount(tokenProvider(acct, pid))
                is Credentials.Password -> http.forBasicAccount(cr.username, cr.password, configValue(acct.configJson, "host") ?: "")
                is Credentials.Session -> http.base
            }
            CredentialResult.Missing -> { markNeedsReconnect(accountId); throw CloudException.AuthRequired(accountId) }
            is CredentialResult.Unreadable -> { markNeedsReconnect(accountId); throw CloudException.CredentialsUnavailable(accountId) }
        }
        return AccountSession(accountId, pid, client, parseConfig(acct.configJson))
    }

    private fun tokenProvider(acct: AccountEntity, pid: ProviderId): AccountTokenProvider = synchronized(tokenProviders) {
        tokenProviders.getOrPut(acct.id) {
            val refresh = refreshers[pid]
            AccountTokenProvider(acct.id, credentials, acct.credentialRef,
                refresher = { old -> refresh?.invoke(acct.id, old) ?: throw CloudException.AuthRequired(acct.id) },
                onAuthLost = { markNeedsReconnect(it) })
        }
    }

    private fun parseConfig(json: String): Map<String, String> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json).let { el ->
            (el as kotlinx.serialization.json.JsonObject).mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }
        }
    }.getOrDefault(emptyMap())

    private fun configValue(json: String, key: String) = parseConfig(json)[key]
}
