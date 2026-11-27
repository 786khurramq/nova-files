package com.nova.files.cloudui

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nova.files.MainActivity
import com.nova.files.NovaApp
import com.nova.files.cloud.accounts.AccountRepository
import com.nova.files.cloud.auth.AndroidKeystoreKeyProvider
import com.nova.files.cloud.auth.CredentialStore
import com.nova.files.cloud.auth.PendingAuthStore
import com.nova.files.cloud.db.CloudDatabase
import com.nova.files.cloud.db.TransferKind
import com.nova.files.cloud.net.CloudHttp
import com.nova.files.cloud.transfer.Endpoint
import com.nova.files.cloud.transfer.LocalEndpointResolver
import com.nova.files.cloud.transfer.TransferEngine
import com.nova.files.cloud.transfer.TransferQueue
import com.nova.files.transfer.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.cloudCredentials by preferencesDataStore("nova_cloud_credentials")

/** Cloud + shared-queue components, constructed once by AppContainer (no DI framework). */
class CloudRuntime(private val app: Context, scope: CoroutineScope) {
    val db = CloudDatabase.open(app)
    val credentials = CredentialStore(app.cloudCredentials, AndroidKeystoreKeyProvider())
    val pendingAuth = PendingAuthStore(credentials)
    val http = CloudHttp()
    val providers: Map<com.nova.files.cloud.core.ProviderId, com.nova.files.cloud.core.CloudProviderAdapter> = mapOf(
        com.nova.files.cloud.core.ProviderId.GOOGLE_DRIVE to com.nova.files.cloud.providers.google.GoogleDriveProvider(),
        com.nova.files.cloud.core.ProviderId.DROPBOX to com.nova.files.cloud.providers.dropbox.DropboxProvider(),
    )
    private val messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Sign-in results and other cloud notices for the UI. */
    val notices: kotlinx.coroutines.flow.SharedFlow<String> = messages
    val auth: CloudAuth = CloudAuth(app, scope, http, pendingAuth, { accounts }, providers) { messages.tryEmit(it) }
    val accounts = AccountRepository(db, credentials, http, providers = providers.filterKeys { it in auth.configs },
        refreshers = auth.configs.keys.mapNotNull { p -> auth.refresher(p)?.let { p to it } }.toMap())
    val queue = TransferQueue(db)
    val engine = TransferEngine(queue, com.nova.files.cloud.transfer.CloudEndpointResolver(app, accounts), onWaitingForNetwork = { RecoveryWorker.schedule(app) })

    init {
        scope.launch(Dispatchers.IO) {
            queue.recoverAfterRestart()
            pendingAuth.purgeExpired()
        }
    }

    /**
     * Queues shared items (content:// or files) to be copied into [destDir] on this device. Runs through
     * the persistent queue so it survives interruptions and never overwrites existing files.
     */
    suspend fun saveToDevice(items: List<com.nova.files.transfer.protocol.SendItem>, destDir: java.io.File): List<String> {
        val group = java.util.UUID.randomUUID().toString()
        return items.filter { !it.isDir }.mapNotNull { item ->
            val src = when (item) {
                is com.nova.files.transfer.UriSendItem -> Endpoint.ContentUri(item.uri.toString(), item.relPath)
                is com.nova.files.transfer.protocol.FileSendItem -> Endpoint.Local(item.file.path)
                else -> null
            } ?: return@mapNotNull null
            queue.enqueue(TransferKind.COPY, src, Endpoint.Local(destDir.path), item.relPath.substringAfterLast('/'), item.size,
                conflict = com.nova.files.cloud.core.ConflictPolicy.KEEP_BOTH, groupId = group).id
        }
    }

    /** True if any queued job involves the network (cloud) – those use user-initiated jobs on Android 14+. */
    private suspend fun needsNetwork() = queue.runnable().any { Endpoint.decode(it.sourceJson) is Endpoint.Cloud || Endpoint.decode(it.destJson) is Endpoint.Cloud }

    /**
     * Starts executing queued jobs. Call only from a user-visible context (Android 12+ forbids starting
     * foreground services from the background; user-initiated jobs must be scheduled while visible).
     */
    suspend fun startQueued(ctx: Context) {
        if (queue.runnable().isEmpty()) return
        if (Build.VERSION.SDK_INT >= 34 && needsNetwork() && CloudTransferJobService.schedule(ctx)) return
        TransferService.drain(ctx)
    }
}

/** Android 14+ user-initiated data transfer job for cloud transfers (not subject to the dataSync FGS limit). */
@RequiresApi(34)
class CloudTransferJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        TransferService.createChannels(this)
        val n = NotificationCompat.Builder(this, "transfer_ongoing").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Cloud transfers in progress").setOngoing(true).setSilent(true).build()
        setNotification(params, NOTIFICATION_ID, n, JOB_END_NOTIFICATION_POLICY_REMOVE)
        val engine = (application as NovaApp).container.cloud.engine
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { engine.drain() } finally { jobFinished(params, false) }
        }
        return true
    }

    /** The system stopped us (quota, constraints, user): running jobs return to the queue and resume later. */
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }

    companion object {
        private const val JOB_ID = 7301
        private const val NOTIFICATION_ID = 7302
        fun schedule(ctx: Context): Boolean = runCatching {
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, CloudTransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }
}

/**
 * Runs when connectivity returns after a transfer was left waiting for the network. It doesn't start a
 * foreground service from the background (not allowed on Android 12+); it tells the user, and the queue
 * resumes as soon as Nova Files is opened.
 */
class RecoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val q = (applicationContext as NovaApp).container.cloud.queue
        val waiting = q.runnable().size
        if (waiting == 0) return Result.success()
        TransferService.createChannels(applicationContext)
        val open = PendingIntent.getActivity(applicationContext, 11,
            Intent(applicationContext, MainActivity::class.java).putExtra("open", "cloud").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, "transfer_events").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Connection is back").setContentText("$waiting transfer${if (waiting == 1) "" else "s"} ready to resume – tap to continue")
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7401, n) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("nova-transfer-recovery", ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** Helper for wireless transfers: they are executed by the existing engine but recorded in the shared queue. */
suspend fun TransferQueue.recordWireless(id: String, outgoing: Boolean, deviceId: String, deviceName: String, local: Endpoint, name: String, size: Long) {
    val existing = get(id)
    if (existing != null) { setState(id, com.nova.files.cloud.db.TransferState.RUNNING, null); return }
    val wireless = Endpoint.Wireless(deviceId, deviceName)
    enqueue(if (outgoing) TransferKind.WIRELESS_SEND else TransferKind.WIRELESS_RECEIVE,
        if (outgoing) local else wireless, if (outgoing) wireless else local, name, size,
        id = id, state = com.nova.files.cloud.db.TransferState.RUNNING, resumable = false)
}
