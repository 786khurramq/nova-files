package com.nova.files.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.nova.files.MainActivity
import com.nova.files.NovaApp
import com.nova.files.core.ExternalContent
import com.nova.files.core.ExternalFile
import com.nova.files.ui.theme.NovaTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Entry point for Android's "Open with" (ACTION_VIEW). Shows the file another app handed us,
 * reading it through the granted content:// URI. Nothing is copied unless the user taps Save,
 * and nothing is executed or installed.
 */
class FileViewerActivity : ComponentActivity() {
    private val file = mutableStateOf<ExternalFile?>(null)
    private val error = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        load(intent)
        val c = (application as NovaApp).container
        setContent {
            val s by c.settings.settings.collectAsStateWithLifecycle()
            NovaTheme(s.theme, s.darkMode, c.profile.isTv, c.profile.hasTouch) {
                ViewerScreen(this, c, file.value, error.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent?) {
        file.value = null; error.value = null
        val uri = intent?.let { ExternalContent.urisFrom(it).firstOrNull() }
        when {
            uri == null -> error.value = "No file was provided by the other app."
            !ExternalContent.isSafe(this, uri) -> error.value = "This file location isn't allowed for security reasons."
            else -> runCatching { ExternalContent.describe(this, uri, intent.type) }
                .onSuccess { file.value = it }
                .onFailure { error.value = "Couldn't read the file: ${it.message}" }
        }
    }

    /** Hand the file to the existing "Send to nearby device" flow in MainActivity. */
    fun sendToDevice(f: ExternalFile) {
        val i = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, f.uri as Parcelable)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, f.uri)
        startActivity(i)
    }

    fun openFolder(path: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("open_dir", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the file in another app, excluding Nova Files so the chooser can't loop back here. */
    fun openElsewhere(f: ExternalFile): String? {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(f.name, f.uri)
        val chooser = Intent.createChooser(view, "Open with")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, FileViewerActivity::class.java)))
        return try { startActivity(chooser); null } catch (e: ActivityNotFoundException) { "No other app on this device can open this file" }
    }

    fun share(f: ExternalFile): String? {
        val i = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, f.uri as Parcelable).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, f.uri)
        return try { startActivity(Intent.createChooser(i, "Share")); null } catch (e: ActivityNotFoundException) { "No sharing apps available" }
    }
}
