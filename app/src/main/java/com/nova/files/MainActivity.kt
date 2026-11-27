package com.nova.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.nova.files.ui.AppRoot
import com.nova.files.ui.AppState
import com.nova.files.ui.PendingSend
import com.nova.files.ui.Tab
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private val state = AppState()
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = (application as NovaApp).container
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 29) { add(Manifest.permission.READ_EXTERNAL_STORAGE); add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            else if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (perms.isNotEmpty()) askPermissions.launch(perms.toTypedArray())
        // Only handle the launch intent once; after process recreation it would be re-delivered.
        if (savedInstanceState == null) handleIntent(intent)
        if (c.settings.settings.value.discoverable) com.nova.files.transfer.TransferService.ensureRunning(this)
        setContent { AppRoot(c, state, this) }
    }

    override fun onResume() {
        super.onResume()
        // The app is visible, so it may start the foreground service / user-initiated job for queued work
        // left over from an interruption, process death or reboot.
        val c = (application as NovaApp).container
        lifecycleScope.launch { c.cloud.startQueued(this@MainActivity) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val c = (application as NovaApp).container
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val all = com.nova.files.core.ExternalContent.urisFrom(intent)
                val safe = all.filter { com.nova.files.core.ExternalContent.isSafe(this, it) }
                when {
                    safe.isNotEmpty() -> share(safe)
                    all.isNotEmpty() -> c.transfers.toast("Those items can't be shared from that location")
                    else -> c.transfers.toast("Only files can be sent with Nova Files – nothing to send")
                }
            }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "nova" }?.let { uri ->
                c.transfers.parseTarget(uri.toString())?.let { c.transfers.pair(it); state.tab = Tab.TRANSFER }
            }
        }
        if (intent.getStringExtra("open") == "transfer") state.tab = Tab.TRANSFER
        if (intent.getStringExtra("open") == "cloud") state.tab = Tab.CLOUD
        intent.getStringExtra("open_dir")?.let { state.openFolder(java.io.File(it)) }
    }

    private fun share(uris: List<Uri>) {
        val c = (application as NovaApp).container
        Thread {
            val items = c.transfers.itemsFromUris(uris)
            runOnUiThread {
                if (items.isEmpty()) c.transfers.toast("Nothing to send – the shared items could not be read")
                else { state.tab = Tab.TRANSFER; state.pendingSend = PendingSend(items, emptyList(), "${items.size} shared item${if (items.size == 1) "" else "s"}") }
            }
        }.start()
    }
}
