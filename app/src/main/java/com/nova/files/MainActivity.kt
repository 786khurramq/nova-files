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
        handleIntent(intent)
        if (c.settings.settings.value.discoverable) com.nova.files.transfer.TransferService.ensureRunning(this)
        setContent { AppRoot(c, state, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val c = (application as NovaApp).container
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { share(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { share(it) }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "nova" }?.let { uri ->
                c.transfers.parseTarget(uri.toString())?.let { c.transfers.pair(it); state.tab = Tab.TRANSFER }
            }
        }
        if (intent.getStringExtra("open") == "transfer") state.tab = Tab.TRANSFER
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
