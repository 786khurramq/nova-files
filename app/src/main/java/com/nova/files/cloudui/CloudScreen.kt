package com.nova.files.cloudui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.db.AccountEntity
import com.nova.files.cloud.db.AuthStatus
import com.nova.files.cloud.db.TransferEntity
import com.nova.files.cloud.db.TransferKind
import com.nova.files.cloud.db.TransferState
import com.nova.files.cloud.transfer.Endpoint
import com.nova.files.core.fmtSize
import com.nova.files.ui.AppState
import com.nova.files.ui.components.*
import com.nova.files.ui.screens.ConfirmDialog
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Cloud section: the single account centre plus the shared transfer queue (cloud, local and wireless).
 * Phase 1 ships no providers, so "add account" is honestly marked as coming rather than faked.
 */
@Composable
fun CloudScreen(c: AppContainer, state: AppState) {
    val accounts by c.cloud.accounts.observe().collectAsStateWithLifecycle(emptyList())
    val transfers by c.cloud.queue.observe().collectAsStateWithLifecycle(emptyList())
    val live by c.cloud.engine.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var removing by remember { mutableStateOf<AccountEntity?>(null) }
    val available = c.cloud.accounts.availableProviders

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Cloud accounts") }
        if (accounts.isEmpty()) item {
            NovaCard(accent = Spectrum.Cloud) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(Icons.Rounded.CloudQueue, Spectrum.Cloud, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("No cloud accounts connected", style = MaterialTheme.typography.titleMedium)
                        Muted(if (available.isEmpty()) "Account sign-in arrives with the first providers in the next update. Nothing on this screen is simulated."
                              else "Add an account to browse and transfer cloud files.", maxLines = 3)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Planned providers", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                ProviderId.entries.forEach { p ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(providerIcon(p), null, tint = Nova.colors.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Pill(if (p in available) "Available" else plannedPhase(p), if (p in available) Spectrum.Success else Nova.colors.muted)
                    }
                }
            }
        }
        items(accounts, key = { it.id }) { a -> AccountCard(a) { removing = a } }

        item {
            SectionTitle("Transfers", trailing = {
                if (transfers.any { it.state.finished }) NovaButton("Clear finished", null) { scope.launch { c.cloud.queue.clearFinished() } }
            })
        }
        if (transfers.isEmpty()) item { Muted("Transfers to and from this device appear here and survive app restarts.") }
        items(transfers, key = { it.id }) { t ->
            TransferRow(t, live[t.id]?.bytes ?: t.bytesDone, live[t.id]?.bytesPerSecond ?: 0L, accounts,
                onPause = { c.cloud.engine.pause(t.id) },
                onResume = { scope.launch { c.cloud.queue.setState(t.id, TransferState.QUEUED, null); c.cloud.startQueued(ctx) } },
                onCancel = { scope.launch { c.cloud.engine.cancel(t.id); if (t.state != TransferState.RUNNING) c.cloud.queue.setState(t.id, TransferState.CANCELLED, "Cancelled") } },
                onResolve = { policy -> scope.launch { c.cloud.queue.update(t.copy(conflictPolicy = policy.name, state = TransferState.QUEUED, error = null)); c.cloud.startQueued(ctx) } },
                onOpen = { path -> state.openFolder(File(path)) })
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    removing?.let { a ->
        ConfirmDialog("Disconnect ${a.displayName}?",
            "Nova Files will forget this ${providerLabel(a)} account on this device: its stored sign-in and cached folder listings are deleted, and its unfinished transfers are cancelled.\n\nFiles you already downloaded stay on this device. Files in the cloud are not touched. Other accounts are not affected.",
            "Disconnect", { removing = null }) {
            scope.launch {
                val r = c.cloud.accounts.remove(a.id)
                c.transfers.toast("Disconnected ${a.displayName}" + if (r.cancelledTransfers > 0) " · ${r.cancelledTransfers} transfer(s) cancelled" else "")
                removing = null
            }
        }
    }
}

private fun plannedPhase(p: ProviderId) = when (p) {
    ProviderId.GOOGLE_DRIVE, ProviderId.DROPBOX -> "Next update"
    ProviderId.ONEDRIVE -> "Planned"
    ProviderId.WEBDAV -> "Planned"
    ProviderId.MEGA -> "Under review"
}

private fun providerIcon(p: ProviderId): ImageVector = when (p) {
    ProviderId.GOOGLE_DRIVE -> Icons.Rounded.AddToDrive
    ProviderId.DROPBOX -> Icons.Rounded.Inventory2
    ProviderId.ONEDRIVE -> Icons.Rounded.Cloud
    ProviderId.MEGA -> Icons.Rounded.Lock
    ProviderId.WEBDAV -> Icons.Rounded.Dns
}

private fun providerLabel(a: AccountEntity) = runCatching { ProviderId.valueOf(a.providerId).label }.getOrDefault(a.providerId)

@Composable
private fun AccountCard(a: AccountEntity, onRemove: () -> Unit) {
    val p = runCatching { ProviderId.valueOf(a.providerId) }.getOrNull()
    NovaCard(accent = Spectrum.Cloud) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(p?.let { providerIcon(it) } ?: Icons.Rounded.Cloud, Spectrum.Cloud, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Muted(listOfNotNull(providerLabel(a), a.email).joinToString(" · "), maxLines = 1)
            }
            if (a.authStatus != AuthStatus.OK) Pill("Reconnect needed", Spectrum.Downloads) else Pill("Connected", Spectrum.Success)
        }
        val total = a.quotaTotal
        if (total != null && total > 0) {
            Spacer(Modifier.height(10.dp))
            GradientProgress((a.quotaUsed ?: 0).toFloat() / total)
            Spacer(Modifier.height(4.dp))
            Muted("${fmtSize(a.quotaUsed ?: 0)} of ${fmtSize(total)} used")
        }
        a.lastSyncAt?.let { Muted("Last synced " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
        Spacer(Modifier.height(10.dp))
        NovaButton("Disconnect", Icons.Rounded.LinkOff, color = Spectrum.Danger, onClick = onRemove)
    }
}

private fun describe(e: Endpoint, accounts: List<AccountEntity>): String = when (e) {
    is Endpoint.Local -> if (e.path.isBlank()) "This device" else "This device · ${File(e.path).name.ifBlank { e.path }}"
    is Endpoint.ContentUri -> "Shared from another app"
    is Endpoint.SafTree -> "Selected folder"
    is Endpoint.Cloud -> accounts.firstOrNull { it.id == e.accountId }?.let { "${providerLabel(it)} · ${it.displayName}" } ?: "Disconnected account"
    is Endpoint.Wireless -> e.deviceName
}

@Composable
private fun TransferRow(
    t: TransferEntity, bytes: Long, rate: Long, accounts: List<AccountEntity>,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onResolve: (ConflictPolicy) -> Unit, onOpen: (String) -> Unit,
) {
    val src = runCatching { Endpoint.decode(t.sourceJson) }.getOrNull()
    val dst = runCatching { Endpoint.decode(t.destJson) }.getOrNull()
    val color = when (t.state) {
        TransferState.COMPLETED -> Spectrum.Success
        TransferState.FAILED -> Spectrum.Danger
        TransferState.CANCELLED -> Nova.colors.muted
        TransferState.WAITING_AUTH, TransferState.WAITING_NETWORK, TransferState.PAUSED -> Spectrum.Downloads
        else -> Spectrum.Transfer
    }
    val icon = when (t.kind) {
        TransferKind.WIRELESS_SEND -> Icons.Rounded.Upload
        TransferKind.WIRELESS_RECEIVE -> Icons.Rounded.Download
        else -> if (dst is Endpoint.Cloud) Icons.Rounded.CloudUpload else if (src is Endpoint.Cloud) Icons.Rounded.CloudDownload else Icons.Rounded.SaveAlt
    }
    NovaCard(accent = color, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(icon, color, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Muted("${src?.let { describe(it, accounts) } ?: "?"}  →  ${dst?.let { describe(it, accounts) } ?: "?"}", maxLines = 1)
            }
            Pill(t.state.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, color)
        }
        val total = t.expectedSize
        if (!t.state.finished && total != null && total > 0) {
            Spacer(Modifier.height(10.dp))
            GradientProgress(bytes.toFloat() / total)
            Spacer(Modifier.height(4.dp))
            Muted("${fmtSize(bytes)} of ${fmtSize(total)}" + if (rate > 0 && t.state == TransferState.RUNNING) " · ${fmtSize(rate)}/s" else "")
        } else if (!t.state.finished && t.state == TransferState.RUNNING) {
            Spacer(Modifier.height(6.dp)); Muted("${fmtSize(bytes)} transferred (total size unknown)")
        }
        t.error?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Muted(it, maxLines = 3) }
        val copy = t.kind == TransferKind.COPY || t.kind == TransferKind.MOVE
        val conflict = t.state == TransferState.FAILED && t.error?.contains("already exists") == true
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                conflict -> {
                    NovaButton("Keep both", null) { onResolve(ConflictPolicy.KEEP_BOTH) }
                    NovaButton("Replace", null, color = Spectrum.Danger) { onResolve(ConflictPolicy.REPLACE) }
                    NovaButton("Skip", null) { onResolve(ConflictPolicy.SKIP) }
                }
                copy && t.state == TransferState.RUNNING && t.resumable -> {
                    NovaButton("Pause", Icons.Rounded.Pause, onClick = onPause)
                    NovaButton("Cancel", Icons.Rounded.Close, color = Spectrum.Danger, onClick = onCancel)
                }
                copy && t.state in setOf(TransferState.PAUSED, TransferState.WAITING_NETWORK, TransferState.WAITING_AUTH, TransferState.QUEUED) -> {
                    if (t.state != TransferState.QUEUED) NovaButton("Resume", Icons.Rounded.PlayArrow, onClick = onResume)
                    NovaButton("Cancel", Icons.Rounded.Close, color = Spectrum.Danger, onClick = onCancel)
                }
                copy && t.state == TransferState.FAILED -> NovaButton("Retry", Icons.Rounded.Replay, onClick = onResume)
                t.state == TransferState.COMPLETED && dst is Endpoint.Local && dst.path.isNotBlank() -> NovaButton("Open folder", Icons.Rounded.FolderOpen) { onOpen(dst.path) }
                else -> {}
            }
        }
    }
}
