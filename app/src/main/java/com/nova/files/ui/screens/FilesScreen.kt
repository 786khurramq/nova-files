package com.nova.files.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.files.AppContainer
import com.nova.files.core.FileCategory
import com.nova.files.core.FileEntry
import com.nova.files.core.SortBy
import com.nova.files.core.StorageVolumeInfo
import com.nova.files.core.fmtSize
import com.nova.files.transfer.protocol.FileSendItem
import com.nova.files.ui.AppState
import com.nova.files.ui.Clipboard
import com.nova.files.ui.PendingSend
import com.nova.files.ui.components.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

fun iconFor(c: FileCategory): ImageVector = when (c) {
    FileCategory.FOLDER -> Icons.Rounded.Folder; FileCategory.DOCUMENT -> Icons.Rounded.Description
    FileCategory.APK -> Icons.Rounded.Android; FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
    FileCategory.IMAGE -> Icons.Rounded.Image; FileCategory.AUDIO -> Icons.Rounded.MusicNote
    FileCategory.VIDEO -> Icons.Rounded.Movie; FileCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
fun FilesScreen(c: AppContainer, state: AppState, activity: Activity) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<List<StorageVolumeInfo>>(emptyList()) }
    var dir by rememberSaveableFile()
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) } // "mkdir" | "rename" | "delete" | "sort" | "perm"
    var opProgress by remember { mutableStateOf<String?>(null) }
    var hasAccess by remember { mutableStateOf(c.files.hasAllFilesAccess()) }

    LaunchedEffect(Unit) {
        volumes = c.files.volumes()
        if (dir == null) dir = volumes.firstOrNull()?.path
    }
    LaunchedEffect(state.openDir) { state.openDir?.let { dir = it; state.openDir = null; selection = emptySet() } }
    LaunchedEffect(dir, refresh, settings.showHidden, settings.sortBy, settings.sortDesc) {
        hasAccess = c.files.hasAllFilesAccess()
        val d = dir ?: return@LaunchedEffect
        loading = true
        c.files.list(d, settings.showHidden, settings.sortBy, settings.sortDesc)
            .onSuccess { entries = it; error = null }
            .onFailure { entries = emptyList(); error = it.message }
        loading = false
    }
    val root = volumes.firstOrNull { dir?.absolutePath?.startsWith(it.path.absolutePath) == true }
    val canGoUp = dir != null && root != null && dir!!.absolutePath != root.path.absolutePath
    BackHandler(enabled = selection.isNotEmpty() || selectMode) { selection = emptySet(); selectMode = false }
    BackHandler(enabled = selection.isEmpty() && !selectMode && canGoUp) { dir = dir!!.parentFile }

    val selectedFiles = entries.filter { it.path in selection }
    fun toggle(e: FileEntry) { selection = if (e.path in selection) selection - e.path else selection + e.path }
    fun click(e: FileEntry) {
        if (selectMode || selection.isNotEmpty()) toggle(e)
        else if (e.isDir) { dir = e.file }
        else c.files.open(e.file)?.let { c.transfers.toast(it) }
    }

    Column(Modifier.fillMaxSize()) {
        if (!hasAccess) PermissionBanner {
            if (!c.files.requestAllFilesAccess(activity)) dialog = "perm"
        }
        // Volumes
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "cloud") {
                NovaCard(onClick = { state.tab = com.nova.files.ui.Tab.CLOUD }, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp), accent = Spectrum.Cloud) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cloud, null, tint = Spectrum.Cloud)
                        Spacer(Modifier.width(8.dp))
                        Column { Text("Cloud", style = MaterialTheme.typography.labelLarge, maxLines = 1); Muted("Accounts & transfers") }
                    }
                }
            }
            items(volumes, key = { it.path.absolutePath }) { v ->
                val sel = root?.path == v.path
                NovaCard(onClick = { dir = v.path; selection = emptySet() }, selected = sel, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (v.primary) Icons.Rounded.PhoneAndroid else if (v.label.contains("USB", true)) Icons.Rounded.Usb else Icons.Rounded.SdStorage, null, tint = if (sel) Nova.colors.accent else Nova.colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Column { Text(v.label, style = MaterialTheme.typography.labelLarge, maxLines = 1); Muted("${fmtSize(v.free)} free") }
                    }
                }
            }
        }
        // Breadcrumbs
        val crumbs = remember(dir, root) { crumbsFor(dir, root) }
        val crumbState = rememberLazyListState()
        LaunchedEffect(crumbs.size) { if (crumbs.isNotEmpty()) crumbState.scrollToItem(crumbs.size - 1) }
        LazyRow(state = crumbState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(crumbs) { i, (label, f) ->
                if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = Nova.colors.muted, modifier = Modifier.size(18.dp))
                TextButton(onClick = { dir = f; selection = emptySet() }) { Text(label, color = if (i == crumbs.size - 1) Nova.colors.text else Nova.colors.muted, maxLines = 1) }
            }
        }
        // Toolbar / selection bar
        val clip = state.clipboard
        if (selection.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { Text("${selection.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 6.dp)) }
                item { NovaButton("Send", Icons.Rounded.Send, primary = true, color = Spectrum.Transfer) {
                    val files = selectedFiles.map { it.file }
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val items = FileSendItem.expand(files)
                        state.pendingSend = PendingSend(items, files.map { it.absolutePath }, if (files.size == 1) files[0].name else "${files.size} items")
                    }
                    selection = emptySet(); selectMode = false
                } }
                item { NovaButton("Copy", Icons.Rounded.ContentCopy) { state.clipboard = Clipboard(selectedFiles.map { it.file }, false); selection = emptySet(); selectMode = false } }
                item { NovaButton("Move", Icons.Rounded.ContentCut) { state.clipboard = Clipboard(selectedFiles.map { it.file }, true); selection = emptySet(); selectMode = false } }
                if (selection.size == 1) item { NovaButton("Rename", Icons.Rounded.Edit) { dialog = "rename" } }
                if (!c.profile.isTv) item { NovaButton("Share", Icons.Rounded.Share) { c.files.share(selectedFiles.map { it.file })?.let { c.transfers.toast(it) } } }
                item { NovaButton("Delete", Icons.Rounded.Delete, color = Spectrum.Danger) { dialog = "delete" } }
                item { NovaButton("All", Icons.Rounded.SelectAll) { selection = entries.map { it.path }.toSet() } }
                item { NovaIconButton(Icons.Rounded.Close, "Clear selection") { selection = emptySet(); selectMode = false } }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canGoUp) item { NovaIconButton(Icons.Rounded.ArrowUpward, "Up") { dir = dir?.parentFile } }
                item { NovaButton(if (selectMode) "Selecting…" else "Select", Icons.Rounded.Checklist) { selectMode = !selectMode } }
                item { NovaIconButton(Icons.Rounded.CreateNewFolder, "New folder") { dialog = "mkdir" } }
                item { NovaIconButton(Icons.AutoMirrored.Rounded.Sort, "Sort") { dialog = "sort" } }
                item { NovaIconButton(if (settings.gridView) Icons.Rounded.ViewList else Icons.Rounded.GridView, "View") { c.settings.setGrid(!settings.gridView) } }
                item { NovaIconButton(if (settings.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Hidden files") { c.settings.setShowHidden(!settings.showHidden) } }
                item { NovaIconButton(Icons.Rounded.Refresh, "Refresh") { refresh++; scope.launch { volumes = c.files.volumes() } } }
                if (dir != null) item { NovaButton("Receive here", Icons.Rounded.MoveToInbox) { c.settings.setReceiveDir(dir!!.absolutePath); c.transfers.toast("Received files will be saved to ${dir!!.name}") } }
            }
        }
        if (clip != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("${clip.files.size} item(s) to ${if (clip.move) "move" else "copy"}", Modifier.weight(1f))
                NovaButton("Paste here", Icons.Rounded.ContentPaste, primary = true) {
                    val d = dir ?: return@NovaButton
                    scope.launch {
                        opProgress = "Starting…"
                        c.files.transfer(clip.files, d, clip.move) { done, total -> opProgress = "${fmtSize(done)} / ${fmtSize(total)}" }
                            .onSuccess { c.transfers.toast("${if (clip.move) "Moved" else "Copied"} $it item(s)"); state.clipboard = null }
                            .onFailure { c.transfers.toast(it.message ?: "Failed") }
                        opProgress = null; refresh++
                    }
                }
                NovaButton("Cancel", null) { state.clipboard = null }
            }
        }
        opProgress?.let { Muted("Working: $it", Modifier.padding(horizontal = 20.dp)) }

        Box(Modifier.weight(1f)) {
            when {
                error != null -> EmptyState(Icons.Rounded.Lock, "Can't open this folder", error!!)
                !loading && entries.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, "Empty folder", if (!settings.showHidden) "Hidden files are not shown" else "")
                settings.gridView -> LazyVerticalGrid(GridCells.Adaptive(if (c.profile.isTv) 170.dp else 110.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries, key = { it.path }) { e -> GridItem(c.thumbs, e, e.path in selection, { click(e) }, { toggle(e) }) }
                }
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.path }) { e -> ListItemRow(c.thumbs, e, e.path in selection, { click(e) }, { toggle(e) }) }
                }
            }
        }
    }

    when (dialog) {
        "mkdir" -> TextInputDialog("New folder", "Folder name", "", "Create", { dialog = null }) { name ->
            scope.launch { c.files.createFolder(dir!!, name).onFailure { c.transfers.toast(it.message ?: "Failed") }; refresh++; dialog = null }
        }
        "rename" -> selectedFiles.firstOrNull()?.let { e ->
            TextInputDialog("Rename", "New name", e.name, "Rename", { dialog = null }) { name ->
                scope.launch { c.files.rename(e.file, name).onFailure { c.transfers.toast(it.message ?: "Failed") }; selection = emptySet(); refresh++; dialog = null }
            }
        }
        "delete" -> ConfirmDialog("Delete ${selectedFiles.size} item(s)?",
            "This permanently deletes:\n" + selectedFiles.take(5).joinToString("\n") { "• " + it.name } + (if (selectedFiles.size > 5) "\n…and ${selectedFiles.size - 5} more" else "") +
                "\n\nFolders are deleted with everything inside. This cannot be undone.", "Delete", { dialog = null }) {
            scope.launch { c.files.delete(selectedFiles.map { it.file }).onSuccess { c.transfers.toast("Deleted $it item(s)") }.onFailure { c.transfers.toast(it.message ?: "Failed") }; selection = emptySet(); refresh++; dialog = null }
        }
        "sort" -> SortDialog(settings.sortBy, settings.sortDesc, { dialog = null }) { s, d -> c.settings.setSort(s, d); dialog = null }
        "perm" -> ConfirmDialog("All files access", "This device has no settings screen for All files access (common on Android TV). Grant it once from a computer with ADB:\n\n${c.files.adbGrantCommand}\n\nWithout it Nova Files can still browse media folders and Downloads, and receive transfers into Download/NovaFiles.", "OK", { dialog = null }) { dialog = null }
    }
}

@Composable
private fun rememberSaveableFile(): MutableState<File?> {
    val s = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    return remember {
        object : MutableState<File?> {
            override var value: File? get() = s.value?.let { File(it) }; set(v) { s.value = v?.absolutePath }
            override fun component1() = value
            override fun component2(): (File?) -> Unit = { value = it }
        }
    }
}

private fun crumbsFor(dir: File?, root: StorageVolumeInfo?): List<Pair<String, File>> {
    dir ?: return emptyList()
    val out = ArrayList<Pair<String, File>>()
    var f: File? = dir
    while (f != null) {
        val isRoot = root != null && f.absolutePath == root.path.absolutePath
        out += (if (isRoot) root!!.label else f.name.ifEmpty { "/" }) to f
        if (isRoot) break
        f = f.parentFile
    }
    return out.reversed()
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    NovaCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), accent = Spectrum.Downloads, onClick = onGrant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = Spectrum.Downloads)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Limited storage access", style = MaterialTheme.typography.titleSmall)
                Muted("Grant All files access to manage every folder. Restricted folders are never shown as accessible.")
            }
            Text("Grant", color = Spectrum.Downloads, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ListItemRow(loader: com.nova.files.thumbs.ThumbnailLoader, e: FileEntry, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val thumb by rememberThumb(loader, e.file, e.isDir, e.modified, e.size, 40.dp)
    NovaCard(onClick = onClick, onLongClick = onLong, selected = selected, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp), accent = Spectrum.forCategory(e.category)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileThumb(e.file, e.isDir, thumb, 40.dp, selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Muted(listOfNotNull(thumb?.detail, if (e.isDir) "Folder" else fmtSize(e.size), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.modified))).joinToString(" · "), maxLines = 1)
            }
        }
    }
}

@Composable
private fun GridItem(loader: com.nova.files.thumbs.ThumbnailLoader, e: FileEntry, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val thumb by rememberThumb(loader, e.file, e.isDir, e.modified, e.size, 160.dp)
    NovaCard(onClick = onClick, onLongClick = onLong, selected = selected, padding = PaddingValues(10.dp), accent = Spectrum.forCategory(e.category)) {
        // Fixed square preview area: photos/videos are centre-cropped, icons and pages fitted whole.
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Nova.colors.surfaceHigh), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t?.bitmap != null && t.fill) FileThumb(e.file, e.isDir, t, maxWidth, selected)
            else FileThumb(e.file, e.isDir, t, if (t?.bitmap != null) maxWidth * 0.82f else maxWidth * 0.5f, selected)
        }
        Spacer(Modifier.height(8.dp))
        Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Muted(thumb?.detail ?: if (e.isDir) "Folder" else fmtSize(e.size), maxLines = 1)
    }
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovaButton(confirm, null, Modifier.weight(1f), primary = true, enabled = text.isNotBlank()) { onConfirm(text.trim()) }
                    NovaButton("Cancel", null, Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Nova.colors.muted)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovaButton(confirm, null, Modifier.weight(1f), primary = true, color = if (confirm == "Delete") Spectrum.Danger else null, onClick = onConfirm)
                    if (confirm != "OK") NovaButton("Cancel", null, Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SortDialog(current: SortBy, desc: Boolean, onDismiss: () -> Unit, onPick: (SortBy, Boolean) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleLarge)
                SortBy.entries.forEach { s ->
                    NovaButton(s.label + if (s == current) (if (desc) "  ↓" else "  ↑") else "", null, Modifier.fillMaxWidth(), primary = s == current) {
                        onPick(s, if (s == current) !desc else s != SortBy.NAME)
                    }
                }
            }
        }
    }
}
