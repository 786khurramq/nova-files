package com.nova.files.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nova.files.MainActivity
import com.nova.files.NovaApp
import com.nova.files.transfer.protocol.TransferEngine
import com.nova.files.transfer.protocol.TransferResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the receive server and running transfers alive while
 * the app is in the background or the screen is off. Holds Wi-Fi + CPU locks only during transfers.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        startFg(buildNotification("Ready to receive", null, 0, 0))
        val app = application as NovaApp
        scope.launch {
            combine(app.container.transfers.transfers, app.container.settings.settings) { t, s -> t to s }.collect { (list, s) ->
                val active = list.filter { it.active }
                setLocks(active.isNotEmpty())
                if (active.isEmpty() && !s.discoverable) { stopSelf(); return@collect }
                val n = if (active.isEmpty()) buildNotification("Visible as ${app.container.transfers.deviceName}", "Ready to receive files", 0, 0)
                else {
                    val a = active.first()
                    val p = a.progress
                    val pct = if (a.totalBytes > 0 && p != null) (p.bytesDone * 100 / a.totalBytes).toInt() else 0
                    val title = (if (a.outgoing) "Sending to " else "Receiving from ") + a.peerName
                    val text = if (p != null) "${p.fileIndex + 1}/${p.fileCount} · ${TransferEngine.fmt(p.bytesPerSecond)}/s · ${p.currentFile}" else a.status.name.lowercase().replace('_', ' ')
                    buildNotification(title, text, pct, 100)
                }
                lastNotification = n
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ONGOING, n)
            }
        }
    }

    private var lastNotification: Notification? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be followed by startForeground().
        startFg(lastNotification ?: buildNotification("Ready to receive", null, 0, 0))
        return START_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ daily dataSync limit reached: stop cleanly instead of crashing.
        stopSelf()
    }

    private fun startFg(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID_ONGOING, n, type)
    }

    @SuppressLint("WakelockTimeout")
    private fun setLocks(on: Boolean) {
        if (on) {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nova:transfer").apply { setReferenceCounted(false); acquire() }
            }
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:transfer").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
        } else {
            runCatching { wifiLock?.release() }; wifiLock = null
            runCatching { wakeLock?.release() }; wakeLock = null
        }
    }

    private fun buildNotification(title: String, text: String?, progress: Int, max: Int): Notification =
        NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(max, progress, false)
            .setContentIntent(openApp(this))
            .build()

    override fun onDestroy() {
        setLocks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CH_ONGOING = "transfer_ongoing"
        private const val CH_EVENTS = "transfer_events"
        private const val ID_ONGOING = 1001

        fun ensureRunning(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TransferService::class.java)) }

        private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open", "transfer"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Transfers in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Transfer requests and results", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun notifyIncoming(ctx: Context, from: String, files: Int) {
            if (!canNotify(ctx)) return
            createChannels(ctx)
            val n = NotificationCompat.Builder(ctx, CH_EVENTS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("$from wants to send $files file${if (files == 1) "" else "s"}")
                .setContentText("Open Nova Files to accept or decline")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2001, n)
        }

        fun notifyFinished(ctx: Context, outgoing: Boolean, peer: String, r: TransferResult) {
            if (!canNotify(ctx)) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(2001)
            val ok = r.files.count { it.ok }
            val title = when {
                r.success -> (if (outgoing) "Sent $ok file${if (ok == 1) "" else "s"} to " else "Received $ok file${if (ok == 1) "" else "s"} from ") + peer
                r.cancelled -> "Transfer with $peer cancelled"
                else -> "Transfer with $peer failed"
            }
            val n = NotificationCompat.Builder(ctx, CH_EVENTS)
                .setSmallIcon(if (r.success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(r.error ?: TransferEngine.fmt(r.bytes) + " verified")
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            nm.notify(3000 + (r.transferId.hashCode() and 0xfff), n)
        }
    }
}
