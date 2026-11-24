package com.nova.files.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.BuildConfig
import com.nova.files.core.DarkMode
import com.nova.files.core.ThemeChoice
import com.nova.files.transfer.protocol.Crypto
import com.nova.files.ui.AppState
import com.nova.files.ui.components.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(c: AppContainer, state: AppState, activity: Activity) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val trusted by c.trust.devices.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var permDialog by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    val pm = activity.getSystemService(PowerManager::class.java)
    val batteryOk = remember(tick) { pm.isIgnoringBatteryOptimizations(activity.packageName) }
    val filesOk = remember(tick) { c.files.hasAllFilesAccess() }
    val installOk = remember(tick) { activity.packageManager.canRequestPackageInstalls() }

    fun launch(i: Intent): Boolean = runCatching { activity.startActivity(i); true }.getOrDefault(false)

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Appearance") }
        item {
            NovaCard {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice.entries.forEach { t -> NovaButton(t.label, if (t == s.theme) Icons.Rounded.Check else null, primary = t == s.theme) { c.settings.setTheme(t) } }
                }
                Spacer(Modifier.height(14.dp))
                Text("Mode", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkMode.entries.forEach { m -> NovaButton(m.label, null, primary = m == s.darkMode) { c.settings.setDarkMode(m) } }
                }
            }
        }

        item { SectionTitle("Wireless transfer") }
        item { SettingRow(Icons.Rounded.Badge, "Device name", c.transfers.deviceName) { editName = true } }
        item { ToggleRow(Icons.Rounded.Visibility, "Visible to nearby devices", "Receive files while Nova Files runs in the background", s.discoverable) { c.settings.setDiscoverable(it) } }
        item { ToggleRow(Icons.Rounded.VerifiedUser, "Auto-accept from trusted devices", "Otherwise every incoming transfer asks first", s.autoAcceptTrusted) { c.settings.setAutoAccept(it) } }
        item {
            SettingRow(Icons.Rounded.MoveToInbox, "Receive location", c.transfers.receiveRoot().path + if (s.receiveDir.isNotBlank()) "" else "  (default)") {
                state.openFolder(c.transfers.receiveRoot())
            }
        }
        if (s.receiveDir.isNotBlank()) item { NovaButton("Reset receive location to Download/NovaFiles", Icons.Rounded.RestartAlt) { c.settings.setReceiveDir("") } }
        item { Muted("To change: open any folder in Files and choose “Receive here”. Files are written to a hidden temporary folder and only appear after their SHA-256 checksum verifies. Existing files are never overwritten.", maxLines = 5) }

        item { SectionTitle("Trusted devices") }
        if (trusted.isEmpty()) item { Muted("No paired devices yet. Devices are trusted after both screens show the same 6-digit code.") }
        items(trusted, key = { it.fingerprint }) { d ->
            NovaCard(padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(if (d.type == "tv") Icons.Rounded.Tv else Icons.Rounded.PhoneAndroid, Spectrum.Transfer, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Muted(Crypto.prettyFingerprint(d.fingerprint) + if (d.lastHost.isNotBlank()) " · ${d.lastHost}" else "")
                    }
                    NovaButton("Remove", Icons.Rounded.LinkOff, color = Spectrum.Danger) { c.trust.remove(d.fingerprint) }
                }
            }
        }

        item { SectionTitle("Permissions & device") }
        item { StatusRow(Icons.Rounded.FolderShared, "All files access", filesOk, if (filesOk) "Granted" else "Needed to manage all folders") {
            if (!c.files.requestAllFilesAccess(activity)) permDialog = true } }
        item { StatusRow(Icons.Rounded.BatterySaver, "Unrestricted background", batteryOk, if (batteryOk) "Transfers won't be paused by battery optimisation" else "Recommended on Samsung for long transfers") {
            if (!launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))))
                if (!launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) c.transfers.toast("This device has no battery optimisation settings") } }
        item { StatusRow(Icons.Rounded.InstallMobile, "Install unknown apps", installOk, if (installOk) "Allowed – APKs open in the system installer" else "Needed only to install received APKs") {
            if (!launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))))
                c.transfers.toast("Enable under Settings › Device Preferences › Security & restrictions › Unknown sources") } }
        if (Build.VERSION.SDK_INT >= 26) item { SettingRow(Icons.Rounded.Notifications, "Notifications", "Transfer progress and incoming requests") {
            launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) } }

        item { SectionTitle("About") }
        item {
            NovaCard {
                Text("Nova Files ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Muted("${c.profile.manufacturer} ${c.profile.model} · Android API ${c.profile.sdk} · ${if (c.profile.isTv) "TV interface" else "Touch interface"}")
                Muted("Device ID ${Crypto.prettyFingerprint(c.identity.fingerprint)} (key stored in Android Keystore)")
                Muted("Transfers: ECDH P-256 key exchange, ECDSA device identity, AES-256-GCM encryption, SHA-256 verification.", maxLines = 3)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (editName) TextInputDialog("Device name", "Shown to other devices", c.transfers.deviceName, "Save", { editName = false }) { c.settings.setDeviceName(it); editName = false }
    if (permDialog) ConfirmDialog("All files access", "No settings screen for this permission on this device. Grant it from a computer:\n\n${c.files.adbGrantCommand}", "OK", { permDialog = false }) { permDialog = false }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    NovaCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Nova.colors.accent); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Muted(sub) }
            Icon(Icons.Rounded.ChevronRight, null, tint = Nova.colors.muted)
        }
    }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    NovaCard(onClick = { onChange(!value) }, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Nova.colors.accent); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Muted(sub) }
            Switch(value, null, colors = SwitchDefaults.colors(checkedTrackColor = Nova.colors.accent))
        }
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, ok: Boolean, sub: String, onClick: () -> Unit) {
    NovaCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (ok) Spectrum.Success else Spectrum.Downloads); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Muted(sub) }
            Pill(if (ok) "OK" else "Action", if (ok) Spectrum.Success else Spectrum.Downloads)
        }
    }
}

@Composable
fun ModulePendingScreen(tab: com.nova.files.ui.Tab, color: Color, title: String, planned: List<String>) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        NovaCard(accent = color) {
            CategoryBadge(tab.icon, color, 56.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Muted("Not included in this build. This milestone proves file management and wireless transfer across your three devices first; this module is built next, in the planned order.", maxLines = 5)
            Spacer(Modifier.height(16.dp))
            planned.forEach { Row(Modifier.padding(vertical = 4.dp)) { Text("•  ", color = color); Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}
