package com.nova.files.ui.screens

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.core.FileTypes
import com.nova.files.core.StorageVolumeInfo
import com.nova.files.core.fmtSize
import com.nova.files.ui.AppState
import com.nova.files.ui.Tab
import com.nova.files.ui.components.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(c: AppContainer, state: AppState) {
    var volumes by remember { mutableStateOf<List<StorageVolumeInfo>>(emptyList()) }
    val nearby by c.transfers.discovery.devices.collectAsStateWithLifecycle()
    val transfers by c.transfers.transfers.collectAsStateWithLifecycle()
    val history by c.history.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { volumes = withContext(Dispatchers.IO) { c.files.volumes() } }
    val cols = if (c.profile.isTv) 4 else 2
    val full = { _: Any -> GridItemSpan(cols) }

    val recent = remember(history) {
        history.filter { !it.outgoing && it.baseDir.isNotBlank() }.flatMap { h -> h.files.filter { it.ok }.map { File(h.baseDir, it.savedAs) } }.filter { it.exists() }.take(8)
    }

    LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(cols) }) { SectionTitle("Storage") }
        items(volumes, key = { it.path.absolutePath }, span = { GridItemSpan(if (c.profile.isTv) 2 else cols) }) { v ->
            NovaCard(onClick = { state.openFolder(v.path) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(if (v.primary) Icons.Rounded.Storage else Icons.Rounded.Usb, if (v.primary) Spectrum.Documents else Spectrum.Downloads)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.label, style = MaterialTheme.typography.titleMedium)
                        Muted("${fmtSize(v.used)} used · ${fmtSize(v.free)} free · ${fmtSize(v.total)} total")
                    }
                }
                Spacer(Modifier.height(12.dp))
                GradientProgress(if (v.total > 0) v.used.toFloat() / v.total else 0f, brush = Spectrum.bar, height = 10.dp)
            }
        }

        item(span = { GridItemSpan(cols) }) { SectionTitle("Quick access") }
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        quick(Icons.Rounded.Upload, "Send", "To a nearby device", Spectrum.Transfer) { state.tab = Tab.FILES; c.transfers.toast("Select files, then press Send") }
        quick(Icons.Rounded.MoveToInbox, "Receive", "${nearby.size} device${if (nearby.size == 1) "" else "s"} nearby", Spectrum.Transfer) { state.tab = Tab.TRANSFER }
        quick(Icons.Rounded.Download, "Downloads", dl.path.substringAfterLast('/'), Spectrum.Downloads) { state.openFolder(dl) }
        quick(Icons.Rounded.Inbox, "Received", "Nova transfers", Spectrum.Apps) { state.openFolder(c.transfers.receiveRoot()) }
        listOf(Environment.DIRECTORY_DCIM to (Icons.Rounded.PhotoCamera to Spectrum.Photos), Environment.DIRECTORY_MOVIES to (Icons.Rounded.Movie to Spectrum.Video),
            Environment.DIRECTORY_MUSIC to (Icons.Rounded.MusicNote to Spectrum.Audio), Environment.DIRECTORY_DOCUMENTS to (Icons.Rounded.Description to Spectrum.Documents)).forEach { (d, ic) ->
            val f = Environment.getExternalStoragePublicDirectory(d)
            if (f.exists()) quick(ic.first, d, "Folder", ic.second) { state.openFolder(f) }
        }

        val active = transfers.filter { it.active }
        if (active.isNotEmpty()) {
            item(span = { GridItemSpan(cols) }) {
                NovaCard(onClick = { state.tab = Tab.TRANSFER }, accent = Spectrum.Transfer) {
                    val a = active.first()
                    Text((if (a.outgoing) "Sending to " else "Receiving from ") + a.peerName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    GradientProgress(if (a.totalBytes > 0) (a.progress?.bytesDone ?: 0).toFloat() / a.totalBytes else 0f)
                    Spacer(Modifier.height(6.dp))
                    Muted("${active.size} active transfer${if (active.size == 1) "" else "s"}")
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(cols) }) { SectionTitle("Recently received") }
            items(recent, key = { "r" + it.absolutePath }) { f ->
                val cat = FileTypes.category(f.name, f.isDirectory)
                NovaCard(onClick = { c.files.open(f)?.let { c.transfers.toast(it) } }, accent = Spectrum.forCategory(cat), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val thumb by rememberThumb(c.thumbs, f, false, f.lastModified(), f.length(), 36.dp)
                        FileThumb(f, false, thumb, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column { Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1); Muted(fmtSize(f.length()), maxLines = 1) }
                    }
                }
            }
        }

        item(span = { GridItemSpan(cols) }) { SectionTitle("Modules") }
        module(Icons.Rounded.CleaningServices, "Cleaner", Spectrum.Cleaner) { state.tab = Tab.CLEANER }
        module(Icons.Rounded.Android, "APK manager", Spectrum.Apps) { state.tab = Tab.APKS }
        module(Icons.Rounded.Cloud, "Cloud", Spectrum.Cloud) { state.tab = Tab.CLOUD }
        item(span = { GridItemSpan(cols) }) { Spacer(Modifier.height(40.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.quick(icon: ImageVector, title: String, sub: String, color: Color, onClick: () -> Unit) =
    item(key = "q$title") {
        NovaCard(onClick = onClick, accent = color, padding = PaddingValues(14.dp)) {
            CategoryBadge(icon, color, 40.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Muted(sub, maxLines = 1)
        }
    }

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.module(icon: ImageVector, title: String, color: Color, onClick: () -> Unit) =
    item(key = "m$title") {
        NovaCard(onClick = onClick, accent = color, padding = PaddingValues(14.dp)) {
            CategoryBadge(icon, color, 40.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted("In development – next build", maxLines = 1)
        }
    }
