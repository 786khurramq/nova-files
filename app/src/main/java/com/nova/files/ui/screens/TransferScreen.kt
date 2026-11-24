package com.nova.files.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nova.files.AppContainer
import com.nova.files.core.fmtSize
import com.nova.files.transfer.ActiveTransfer
import com.nova.files.transfer.HistoryEntry
import com.nova.files.transfer.TransferManager
import com.nova.files.transfer.TransferStatus
import com.nova.files.transfer.protocol.Crypto
import com.nova.files.transfer.protocol.FileSendItem
import com.nova.files.ui.AppState
import com.nova.files.ui.PendingSend
import com.nova.files.ui.Tab
import com.nova.files.ui.components.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun TransferScreen(c: AppContainer, state: AppState) {
    val nearby by c.transfers.discovery.devices.collectAsStateWithLifecycle()
    val trusted by c.trust.devices.collectAsStateWithLifecycle()
    val transfers by c.transfers.transfers.collectAsStateWithLifecycle()
    val history by c.history.entries.collectAsStateWithLifecycle()
    val server by c.transfers.server.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) Thread {
            val items = c.transfers.itemsFromUris(uris)
            if (items.isNotEmpty()) state.pendingSend = PendingSend(items, emptyList(), "${items.size} file${if (items.size == 1) "" else "s"}")
        }.start()
    }
    val hasSystemPicker = remember { !c.profile.isTv }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(settings.discoverable) { c.transfers.discovery.probe(); if (settings.discoverable) com.nova.files.transfer.TransferService.ensureRunning(ctx) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            NovaCard(accent = Spectrum.Transfer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(if (c.profile.isTv) Icons.Rounded.Tv else Icons.Rounded.PhoneAndroid, Spectrum.Transfer, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.transfers.deviceName, style = MaterialTheme.typography.titleLarge)
                        Muted(when {
                            server.error != null -> server.error!!
                            server.addresses.isEmpty() -> "Not connected to a network"
                            else -> server.addresses.joinToString("  ·  ") { "$it:${server.port}" }
                        })
                        Muted("Device ID ${Crypto.prettyFingerprint(c.identity.fingerprint)}")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (settings.discoverable) "Visible to nearby devices" else "Hidden", if (settings.discoverable) Spectrum.Success else Nova.colors.muted)
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                FlowRowActions {
                    NovaButton(if (settings.discoverable) "Hide" else "Become visible", if (settings.discoverable) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility) {
                        c.settings.setDiscoverable(!settings.discoverable)
                    }
                    NovaButton("Show QR / IP", Icons.Rounded.QrCode2) { showQr = true }
                    NovaButton("Connect by IP", Icons.Rounded.Lan) { showManual = true }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaButton("Send from Files", Icons.Rounded.FolderOpen, Modifier.weight(1f), primary = true, color = Spectrum.Transfer) {
                    state.tab = Tab.FILES; c.transfers.toast("Select files or folders, then press Send")
                }
                if (hasSystemPicker) NovaButton("Pick files", Icons.Rounded.AttachFile, Modifier.weight(1f)) { runCatching { picker.launch(arrayOf("*/*")) } }
            }
        }

        val active = transfers
        if (active.isNotEmpty()) {
            item { SectionTitle("Transfers", trailing = { if (active.any { !it.active }) NovaButton("Clear finished", null) { c.transfers.clearFinished() } }) }
            items(active, key = { (if (it.outgoing) "o" else "i") + it.id }) { t -> TransferCard(c, state, t) }
        }

        item { SectionTitle("Nearby devices", trailing = { NovaIconButton(Icons.Rounded.Refresh, "Scan") { c.transfers.discovery.probe() } }) }
        if (nearby.isEmpty()) item { ScanningRow() }
        items(nearby, key = { it.deviceId }) { d ->
            val isTrusted = trusted.any { it.deviceId == d.deviceId }
            DeviceRow(d.name, d.type, "${d.host}:${d.port} · ${d.via}", isTrusted, true,
                actionLabel = if (isTrusted) null else "Pair", onAction = { c.transfers.pair(c.transfers.targetFor(d)) }) {
                state.tab = Tab.FILES; c.transfers.toast("Select files in Files, then press Send and choose ${d.name}")
            }
        }

        val offline = trusted.filter { t -> nearby.none { it.deviceId == t.deviceId } }
        if (offline.isNotEmpty()) {
            item { SectionTitle("Trusted devices") }
            items(offline, key = { "t" + it.fingerprint }) { t ->
                DeviceRow(t.name, t.type, if (t.lastHost.isNotBlank()) "Last seen at ${t.lastHost}" else "Not seen yet", true, false,
                    actionLabel = if (t.lastHost.isNotBlank()) "Check" else null,
                    onAction = { c.transfers.pair(TransferManager.Target(t.name, t.type, t.lastHost, t.lastPort.takeIf { it > 0 } ?: 47821, t.deviceId, t.fingerprint)) }) {}
            }
        }

        if (history.isNotEmpty()) {
            item { SectionTitle("History", trailing = { NovaButton("Clear", null) { c.history.clear() } }) }
            items(history.take(25), key = { "h" + it.id + it.outgoing + it.time }) { h -> HistoryRow(c, state, h) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (showQr) QrDialog(c) { showQr = false }
    if (showManual) ManualConnectDialog(c, state) { showManual = false }
}

@Composable
private fun FlowRowActions(content: @Composable () -> Unit) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
fun ScanningRow() {
    val c = Nova.colors
    val t = rememberInfiniteTransition(label = "scan")
    val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "r")
    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(56.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                for (k in 0..2) {
                    val f = (r + k / 3f) % 1f
                    drawCircle(Spectrum.Transfer.copy(alpha = (1f - f) * 0.7f), radius = size.minDimension / 2 * f, center = center, style = Stroke(3f))
                }
                drawCircle(Spectrum.Transfer, radius = 7f, center = center)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Looking for devices…", style = MaterialTheme.typography.titleMedium)
                Muted("Open Nova Files on the other device and keep it visible. Both must be on the same Wi-Fi or hotspot. If nothing appears, use Connect by IP.", maxLines = 4)
            }
        }
    }
}

@Composable
fun DeviceRow(name: String, type: String, detail: String, trusted: Boolean, online: Boolean,
              actionLabel: String? = null, onAction: (() -> Unit)? = null, onClick: () -> Unit) {
    NovaCard(onClick = onClick, accent = Spectrum.Transfer, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(if (type == "tv") Icons.Rounded.Tv else Icons.Rounded.PhoneAndroid, if (online) Spectrum.Transfer else Nova.colors.muted)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (trusted) { Spacer(Modifier.width(8.dp)); Pill("Trusted", Spectrum.Success) }
                }
                Muted(detail, maxLines = 1)
            }
            if (actionLabel != null && onAction != null) NovaButton(actionLabel, null, onClick = onAction)
        }
    }
}

@Composable
private fun TransferCard(c: AppContainer, state: AppState, t: ActiveTransfer) {
    val colors = Nova.colors
    val p = t.progress
    val frac = if (t.totalBytes > 0 && p != null) p.bytesDone.toFloat() / t.totalBytes else if (t.status == TransferStatus.DONE) 1f else 0f
    val statusColor = when (t.status) { TransferStatus.DONE -> Spectrum.Success; TransferStatus.FAILED -> Spectrum.Danger; TransferStatus.CANCELLED -> colors.muted; else -> Spectrum.Transfer }
    NovaCard(accent = statusColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(if (t.outgoing) Icons.Rounded.Upload else Icons.Rounded.Download, statusColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (t.outgoing) "To " else "From ") + t.peerName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Muted(statusText(t))
            }
            Pill(t.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, statusColor)
        }
        Spacer(Modifier.height(12.dp))
        GradientProgress(frac)
        if (p != null && t.active) {
            Spacer(Modifier.height(8.dp))
            Row { Muted(p.currentFile, Modifier.weight(1f), 1); Muted("${(frac * 100).toInt()}%") }
            if (p.fileSize > 0) { Spacer(Modifier.height(6.dp)); GradientProgress(p.fileBytes.toFloat() / p.fileSize, height = 4.dp) }
            Spacer(Modifier.height(6.dp))
            val eta = if (p.bytesPerSecond > 0) (t.totalBytes - p.bytesDone) / p.bytesPerSecond else -1
            Muted("${fmtSize(p.bytesDone)} of ${fmtSize(t.totalBytes)} · ${fmtSize(p.bytesPerSecond)}/s" + if (eta >= 0) " · ${fmtEta(eta)} left" else "")
        }
        if (t.active && t.status == TransferStatus.TRANSFERRING) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p?.paused == true && p.pausedByPeer.not()) NovaButton("Resume", Icons.Rounded.PlayArrow) { t.resume() }
                else NovaButton("Pause", Icons.Rounded.Pause) { t.pause() }
                NovaButton("Cancel", Icons.Rounded.Close, color = Spectrum.Danger) { t.cancel() }
            }
        } else if (t.active) {
            Spacer(Modifier.height(12.dp))
            NovaButton("Cancel", Icons.Rounded.Close, color = Spectrum.Danger) { t.cancel() }
        }
        if (!t.active) {
            t.message?.let { Spacer(Modifier.height(8.dp)); Muted(it, maxLines = 4) }
            if (!t.outgoing && (t.result?.files?.any { it.ok } == true)) {
                Spacer(Modifier.height(10.dp))
                NovaButton("Open received folder", Icons.Rounded.FolderOpen) { state.openFolder(File(t.receiveRoot)) }
            }
        }
    }
}

private fun statusText(t: ActiveTransfer): String = when (t.status) {
    TransferStatus.CONNECTING -> "Connecting securely…"
    TransferStatus.PAIRING -> "Verifying device…"
    TransferStatus.WAITING_APPROVAL -> "Waiting for ${t.peerName} to accept"
    TransferStatus.RETRYING -> t.message ?: "Reconnecting…"
    TransferStatus.TRANSFERRING -> if (t.progress?.paused == true) (if (t.progress.pausedByPeer) "Paused by ${t.peerName}" else "Paused") else "${(t.progress?.fileIndex ?: 0) + 1} of ${t.fileCount} files"
    TransferStatus.DONE -> "${t.result?.files?.count { it.ok } ?: t.fileCount} files · ${fmtSize(t.result?.bytes ?: t.totalBytes)} · SHA-256 verified"
    TransferStatus.FAILED -> "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
}

private fun fmtEta(s: Long) = when { s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"; s >= 60 -> "${s / 60}m ${s % 60}s"; else -> "${s}s" }

@Composable
private fun HistoryRow(c: AppContainer, state: AppState, h: HistoryEntry) {
    val color = if (h.success) Spectrum.Success else if (h.cancelled) Nova.colors.muted else Spectrum.Danger
    val canRetry = h.outgoing && !h.success && h.sources.isNotEmpty() && h.sources.all { File(it).exists() }
    NovaCard(padding = PaddingValues(14.dp), onClick = if (!h.outgoing && h.success && h.baseDir.isNotBlank()) ({ state.openFolder(File(h.baseDir)) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (h.outgoing) Icons.Rounded.NorthEast else Icons.Rounded.SouthWest, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (h.outgoing) "Sent to " else "Received from ") + h.peerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Muted("${h.fileCount} files · ${fmtSize(h.bytes)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.time))}" + (h.error?.let { " · $it" } ?: ""), maxLines = 2)
            }
            if (canRetry) NovaButton("Retry", Icons.Rounded.Replay) {
                val dev = c.trust.byId(h.peerId)
                val near = c.transfers.discovery.devices.value.firstOrNull { it.deviceId == h.peerId }
                val target = near?.let { c.transfers.targetFor(it) }
                    ?: dev?.takeIf { it.lastHost.isNotBlank() }?.let { TransferManager.Target(it.name, it.type, it.lastHost, it.lastPort.takeIf { p -> p > 0 } ?: 47821, it.deviceId, it.fingerprint) }
                if (target == null) state.pendingSend = PendingSend(FileSendItem.expand(h.sources.map { File(it) }), h.sources, "${h.fileCount} files (retry)")
                else c.transfers.send(target, FileSendItem.expand(h.sources.map { File(it) }), h.sources, h.id) // same id → receiver resumes
            }
        }
    }
}

@Composable
private fun QrDialog(c: AppContainer, onDismiss: () -> Unit) {
    val link = c.transfers.pairingLink()
    val server by c.transfers.server.collectAsStateWithLifecycle()
    val bmp = remember(link) { link?.let { qr(it, 640) } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Connect to ${c.transfers.deviceName}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                if (bmp != null) Image(bmp.asImageBitmap(), "Pairing QR code", Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                else Muted("No network address – connect to Wi-Fi or a hotspot.")
                Spacer(Modifier.height(16.dp))
                server.addresses.forEach { Text("$it : ${server.port}", style = MaterialTheme.typography.headlineSmall, color = Nova.colors.accent) }
                Spacer(Modifier.height(8.dp))
                Muted("On the other device choose Connect by IP and enter the address above. The QR code contains the address and this device's security fingerprint.", maxLines = 5)
                Spacer(Modifier.height(16.dp))
                NovaButton("Close", null, Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}

private fun qr(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun ManualConnectDialog(c: AppContainer, state: AppState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp)) {
                Text("Connect by IP address", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Muted("Find the address under Transfer › Show QR / IP on the other device.", maxLines = 3)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(text, { text = it }, label = { Text("e.g. 192.168.1.20 or 192.168.1.20:47821") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovaButton("Pair", Icons.Rounded.Link, Modifier.weight(1f), primary = true) {
                        val t = c.transfers.parseTarget(text)
                        if (t == null) c.transfers.toast("Invalid address") else { c.transfers.pair(t); onDismiss() }
                    }
                    NovaButton("Cancel", null, Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}
