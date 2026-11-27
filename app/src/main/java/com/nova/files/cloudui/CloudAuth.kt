package com.nova.files.cloudui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.nova.files.BuildConfig
import com.nova.files.MainActivity
import com.nova.files.NovaApp
import com.nova.files.cloud.accounts.AccountRepository
import com.nova.files.cloud.auth.Credentials
import com.nova.files.cloud.auth.OAuthClient
import com.nova.files.cloud.auth.OAuthConfig
import com.nova.files.cloud.auth.PendingAuthStore
import com.nova.files.cloud.core.AccountSession
import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.CloudProviderAdapter
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.net.CloudHttp
import com.nova.files.cloud.providers.dropbox.DropboxProvider
import com.nova.files.cloud.providers.google.GoogleDriveProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Interceptor

/**
 * Browser-based OAuth (authorization code + PKCE) for Google Drive and Dropbox.
 * The pending state/verifier is stored encrypted, so sign-in survives the app being killed while the
 * user is in the browser. Results are reported through [onMessage].
 */
class CloudAuth(
    private val app: Context,
    private val scope: CoroutineScope,
    private val http: CloudHttp,
    private val pending: PendingAuthStore,
    private val accounts: () -> AccountRepository,
    private val providers: Map<ProviderId, CloudProviderAdapter>,
    private val onMessage: (String) -> Unit,
) {
    val oauth = OAuthClient(http.base)

    val configs: Map<ProviderId, OAuthConfig> = buildMap {
        if (BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()) put(ProviderId.GOOGLE_DRIVE, GoogleDriveProvider.oauthConfig(BuildConfig.GOOGLE_CLIENT_ID, app.packageName))
        if (BuildConfig.DROPBOX_APP_KEY.isNotBlank()) put(ProviderId.DROPBOX, DropboxProvider.oauthConfig(BuildConfig.DROPBOX_APP_KEY))
    }

    /** Opens the provider's sign-in page in the browser. Returns an error message if that's impossible here. */
    suspend fun begin(ctx: Context, provider: ProviderId, reconnectAccountId: String? = null): String? = try {
        beginInternal(ctx, provider, reconnectAccountId)
    } catch (e: Exception) {
        android.util.Log.e("NovaAuth", "Sign-in could not start: ${e.javaClass.simpleName}")
        "Couldn't start sign-in (${e.javaClass.simpleName}). Please try again."
    }

    private suspend fun beginInternal(ctx: Context, provider: ProviderId, reconnectAccountId: String?): String? {
        val cfg = configs[provider] ?: return "${provider.label} isn't configured in this build"
        val p = pending.begin(provider, cfg.redirectUri, reconnectAccountId)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizationUrl(cfg, p))).addCategory(Intent.CATEGORY_BROWSABLE)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); null } catch (e: ActivityNotFoundException) {
            pending.consume(p.state)
            "This device has no web browser for signing in. Install a browser, or connect the account on your phone first."
        }
    }

    /** Handles the redirect: validates state (single use), exchanges the code, identifies and stores the account. */
    fun complete(uri: Uri) = scope.launch {
        val error = uri.getQueryParameter("error")
        val state = uri.getQueryParameter("state")
        val p = pending.consume(state)
        if (p == null) { onMessage("Sign-in link expired or wasn't started by Nova Files – please try again"); return@launch }
        if (error != null) { onMessage(if (error == "access_denied") "Sign-in cancelled" else "Sign-in failed: $error"); return@launch }
        val code = uri.getQueryParameter("code") ?: run { onMessage("Sign-in failed: no authorisation code"); return@launch }
        val cfg = configs[p.providerId] ?: return@launch
        val adapter = providers[p.providerId] ?: return@launch
        try {
            val creds = oauth.exchangeCode(cfg, code, p)
            // Identify the account with a client bound to these brand-new credentials only.
            val temp = http.base.newBuilder().addInterceptor(Interceptor { c -> c.proceed(c.request().newBuilder().header("Authorization", "Bearer ${creds.accessToken}").build()) }).build()
            val tmpSession = AccountSession("pending", p.providerId, temp)
            val profile = adapter.profile(tmpSession)
            val acct = accounts().connect(p.providerId, profile, creds, expectAccountId = p.reconnectAccountId)
            runCatching { adapter.quota(tmpSession)?.let { accounts().updateQuota(acct.id, it.usedBytes, it.totalBytes) } }
            onMessage(if (p.reconnectAccountId != null) "${acct.displayName} reconnected" else "Connected ${p.providerId.label}: ${acct.displayName}")
        } catch (e: CloudException) {
            onMessage(e.userMessage)
        } catch (e: Exception) {
            onMessage("Sign-in failed – please try again")
        }
    }

    /** Provider-specific refresh used by each account's token provider. */
    fun refresher(provider: ProviderId): (suspend (String, Credentials.OAuth) -> Credentials.OAuth)? {
        val cfg = configs[provider] ?: return null
        return { accountId, old -> oauth.refresh(cfg, old, accountId) }
    }
}

/** Receives the OAuth redirect from the browser, hands it to [CloudAuth] and returns to the app. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as NovaApp).container.cloud.auth.complete(it) }
        startActivity(Intent(this, MainActivity::class.java).putExtra("open", "cloud")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
