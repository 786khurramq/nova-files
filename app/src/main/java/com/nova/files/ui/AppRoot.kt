package com.nova.files.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.core.fmtSize
import com.nova.files.transfer.Prompt
import com.nova.files.transfer.protocol.Crypto
import com.nova.files.ui.components.*
import com.nova.files.ui.screens.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.NovaTheme
import com.nova.files.ui.theme.Spectrum

@Composable
fun AppRoot(c: AppContainer, state: AppState, activity: Activity) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    NovaTheme(settings.theme, settings.darkMode, c.profile.isTv, c.profile.hasTouch) {
        val cfg = LocalConfiguration.current
        val wide = c.profile.isTv || cfg.screenWidthDp >= 720
        val snackbar = remember { SnackbarHostState() }
        val msg by c.transfers.messages.collectAsStateWithLifecycle()
        LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); c.transfers.consumeMessage() } }

        BackHandler(enabled = state.showSettings) { state.showSettings = false }
        BackHandler(enabled = !state.showSettings && state.tab != Tab.HOME && state.tab != Tab.FILES) { state.tab = Tab.HOME }

        Box(Modifier.fillMaxSize().background(Nova.colors.bg)) {
            if (wide) {
                Row(Modifier.fillMaxSize().systemBarsPadding()) {
                    SideRail(state, c.profile.isTv)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(state)
                        Box(Modifier.weight(1f)) { Content(c, state, activity) }
                    }
                }
            } else {
                Scaffold(
                    containerColor = Nova.colors.bg,
                    topBar = { Box(Modifier.statusBarsPadding()) { TopBar(state) } },
                    bottomBar = { BottomBar(state) },
                ) { pad -> Box(Modifier.padding(pad)) { Content(c, state, activity) } }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 24.dp else 96.dp))
            Prompts(c)
            state.pendingSend?.let { DevicePickerDialog(c, state, it) }
        }
    }
}

@Composable
private fun Content(c: AppContainer, state: AppState, activity: Activity) {
    if (state.showSettings) { SettingsScreen(c, state, activity); return }
    when (state.tab) {
        Tab.HOME -> HomeScreen(c, state)
        Tab.FILES -> FilesScreen(c, state, activity)
        Tab.TRANSFER -> TransferScreen(c, state)
        Tab.CLEANER -> ModulePendingScreen(Tab.CLEANER, Spectrum.Cleaner, "Storage cleaner & analyser",
            listOf("Junk & cache scan of accessible storage", "Large files, old downloads, empty folders", "Exact duplicates (SHA-256) and similar photos", "Preview + confirmation before any deletion, trash/undo"))
        Tab.APKS -> ModulePendingScreen(Tab.APKS, Spectrum.Apps, "APK backup & installer",
            listOf("Extract installed apps incl. split APK sets", "Backup to internal, USB, SD or cloud", "Install via Android PackageInstaller with your approval", "Send backups straight to MINIX or Shield"))
        Tab.CLOUD -> com.nova.files.cloudui.CloudScreen(c, state)
    }
}

@Composable
private fun TopBar(state: AppState) {
    val c = Nova.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Brush.linearGradient(listOf(Spectrum.Documents, Spectrum.Apps, Spectrum.Transfer)), RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        Text(if (state.showSettings) "Settings" else state.tab.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        NovaIconButton(if (state.showSettings) Icons.Rounded.Close else Icons.Rounded.Settings, "Settings") { state.showSettings = !state.showSettings }
    }
}

@Composable
private fun BottomBar(state: AppState) {
    val c = Nova.colors
    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = state.tab == t && !state.showSettings,
                onClick = { state.tab = t; state.showSettings = false },
                icon = { Icon(t.icon, t.label) },
                label = { Text(t.label, fontSize = 10.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = c.onAccent, indicatorColor = c.accent, unselectedIconColor = c.muted, unselectedTextColor = c.muted, selectedTextColor = c.text),
            )
        }
    }
}

@Composable
private fun SideRail(state: AppState, isTv: Boolean) {
    val c = Nova.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxHeight().width(if (isTv) 220.dp else 96.dp).background(c.surface).padding(vertical = 20.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Nova", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp), color = c.text)
        Tab.entries.forEachIndexed { i, t ->
            val src = remember { MutableInteractionSource() }
            val focused by src.collectIsFocusedAsState()
            val selected = state.tab == t && !state.showSettings
            Surface(
                onClick = { state.tab = t; state.showSettings = false },
                interactionSource = src,
                shape = RoundedCornerShape(16.dp),
                color = when { selected -> c.accent.copy(alpha = 0.18f); focused -> c.surfaceHigh; else -> Color.Transparent },
                border = if (focused) BorderStroke(3.dp, c.accent) else null,
                modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            ) {
                if (isTv) Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, null, tint = if (selected) c.accent else c.muted)
                    Spacer(Modifier.width(14.dp))
                    Text(t.label, style = MaterialTheme.typography.titleMedium, color = if (selected || focused) c.text else c.muted)
                } else Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, t.label, tint = if (selected) c.accent else c.muted)
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.text else c.muted)
                }
            }
        }
    }
}

/** Global approval dialogs: pairing codes and incoming transfers, shown over any screen. */
@Composable
private fun Prompts(c: AppContainer) {
    val prompts by c.transfers.prompts.collectAsStateWithLifecycle()
    val p = prompts.firstOrNull() ?: return
    val colors = Nova.colors
    val acceptFocus = remember(p.id) { FocusRequester() }
    Dialog(onDismissRequest = { c.transfers.answer(p, false) }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        LaunchedEffect(p.id) { runCatching { acceptFocus.requestFocus() } }
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                when (p) {
                    is Prompt.Pairing -> {
                        CategoryBadge(Icons.Rounded.Shield, Spectrum.Transfer)
                        Spacer(Modifier.height(16.dp))
                        Text("Pair with ${p.peer.name}?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Muted("Check that this code is shown on ${p.peer.name}. Only trust it if both codes match.", maxLines = 4)
                        Spacer(Modifier.height(20.dp))
                        Text(p.code.chunked(3).joinToString(" "), fontSize = if (Nova.isTv) 56.sp else 46.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                            color = colors.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Muted("Device ID ${Crypto.prettyFingerprint(p.peer.fingerprint)} · ${p.peer.deviceType}", Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NovaButton("Codes match – Trust", Icons.Rounded.Check, Modifier.weight(1f).focusRequester(acceptFocus), primary = true) { c.transfers.answer(p, true) }
                            NovaButton("Cancel", null, Modifier.weight(0.6f)) { c.transfers.answer(p, false) }
                        }
                    }
                    is Prompt.IncomingOffer -> {
                        val files = p.offer.items.filter { !it.dir }
                        CategoryBadge(Icons.Rounded.Download, Spectrum.Transfer)
                        Spacer(Modifier.height(16.dp))
                        Text("${p.peer.name} wants to send ${files.size} file${if (files.size == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Muted("${fmtSize(p.offer.totalBytes)} · ${fmtSize(p.freeBytes)} free on this device" + if (p.trusted) " · trusted device" else "")
                        Spacer(Modifier.height(14.dp))
                        files.take(6).forEach { f ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(f.path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                Muted(fmtSize(f.size))
                            }
                        }
                        if (files.size > 6) Muted("+ ${files.size - 6} more")
                        if (files.any { it.path.endsWith(".apk", true) })
                            Muted("APK files are saved only. Nothing is installed without your separate approval.", Modifier.padding(top = 10.dp))
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NovaButton("Accept", Icons.Rounded.Check, Modifier.weight(1f).focusRequester(acceptFocus), primary = true) { c.transfers.answer(p, true) }
                            NovaButton("Decline", null, Modifier.weight(0.7f)) { c.transfers.answer(p, false) }
                        }
                    }
                }
            }
        }
    }
}

/** Choose where to send pending items: nearby devices, trusted devices, or manual IP. */
@Composable
fun DevicePickerDialog(c: AppContainer, state: AppState, pending: PendingSend) {
    val nearby by c.transfers.discovery.devices.collectAsStateWithLifecycle()
    val trusted by c.trust.devices.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf("") }
    val colors = Nova.colors
    LaunchedEffect(Unit) { c.transfers.discovery.probe() }
    fun go(t: com.nova.files.transfer.TransferManager.Target) {
        c.transfers.send(t, pending.items, pending.sources)
        state.pendingSend = null
        state.tab = Tab.TRANSFER; state.showSettings = false
    }
    Dialog(onDismissRequest = { state.pendingSend = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, modifier = Modifier.widthIn(max = 560.dp).fillMaxHeight(0.85f).padding(20.dp)) {
            Column(Modifier.padding(22.dp)) {
                Text("Send ${pending.label}", style = MaterialTheme.typography.headlineSmall)
                Muted("${pending.items.count { !it.isDir }} files · ${fmtSize(pending.items.filter { !it.isDir }.sumOf { it.size })}")
                Spacer(Modifier.height(14.dp))
                val offline = trusted.filter { t -> nearby.none { it.deviceId == t.deviceId } && t.lastHost.isNotBlank() }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (nearby.isEmpty()) item { ScanningRow() }
                    items(nearby, key = { it.deviceId }) { d ->
                        val isTrusted = trusted.any { it.deviceId == d.deviceId }
                        DeviceRow(d.name, d.type, "${d.host}:${d.port}", isTrusted, true) { go(c.transfers.targetFor(d)) }
                    }
                    if (offline.isNotEmpty()) item { Muted("Trusted devices – last known address", Modifier.padding(top = 6.dp)) }
                    items(offline, key = { "t" + it.fingerprint }) { t ->
                        DeviceRow(t.name, t.type, "${t.lastHost}:${t.lastPort.takeIf { it > 0 } ?: 47821}", true, false) {
                            go(com.nova.files.transfer.TransferManager.Target(t.name, t.type, t.lastHost, t.lastPort.takeIf { it > 0 } ?: 47821, t.deviceId, t.fingerprint))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manual, { manual = it }, label = { Text("IP address (e.g. 192.168.1.20)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    NovaButton("Send", Icons.Rounded.Send, primary = true, enabled = manual.isNotBlank()) {
                        val t = c.transfers.parseTarget(manual)
                        if (t == null) c.transfers.toast("Enter an IP address like 192.168.1.20 or 192.168.1.20:47821") else go(t)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val scope = rememberCoroutineScope()
                if (pending.sources.isEmpty()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    NovaButton("Save to this device instead", Icons.Rounded.Download, Modifier.fillMaxWidth()) {
                        val items = pending.items
                        state.pendingSend = null
                        scope.launch {
                            val dest = c.transfers.receiveRoot()
                            val ids = c.cloud.saveToDevice(items, dest)
                            c.cloud.startQueued(ctx)
                            c.transfers.toast("Saving ${ids.size} file${if (ids.size == 1) "" else "s"} to ${dest.name} – progress in Cloud › Transfers")
                            state.tab = Tab.CLOUD
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                NovaButton("Cancel", null, Modifier.fillMaxWidth()) { state.pendingSend = null }
            }
        }
    }
}
