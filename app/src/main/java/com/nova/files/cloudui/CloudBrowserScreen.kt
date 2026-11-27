package com.nova.files.cloudui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nova.files.AppContainer
import com.nova.files.cloud.core.*
import com.nova.files.cloud.db.AccountEntity
import com.nova.files.cloud.db.CloudItemEntity
import com.nova.files.cloud.db.TransferKind
import com.nova.files.cloud.transfer.Endpoint
import com.nova.files.core.SortBy
import com.nova.files.core.fmtSize
import com.nova.files.thumbs.Thumb
import com.nova.files.ui.AppState
import com.nova.files.ui.components.*
import com.nova.files.ui.screens.ConfirmDialog
import com.nova.files.ui.screens.TextInputDialog
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One browser for every provider. All calls go through the account's own session. */
@Composable
fun CloudBrowserScreen(c: AppContainer, state: AppState, account: AccountEntity) {
    val pid = remember(account.providerId) { ProviderId.valueOf(account.providerId) }
    val adapter = remember(pid) { c.cloud.accounts.provider(pid) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val rootName = if (pid == ProviderId.GOOGLE_DRIVE) "My Drive" else pid.label
    val stack = remember(account.id) { mutableStateListOf<Pair<String?, String>>(null to rootName) }
    val items = remember { mutableStateListOf<CloudFile>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CloudException?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dialog by remember { mutableStateOf<String?>(null) }   // mkdir | rename | delete | export | search | sort
    var search by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SortBy.NAME) }
    var refresh by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<AccountSession?>(null) }
    val folderId = stack.last().first

    fun handle(e: Throwable) {
        val ce = e as? CloudException ?: CloudException.Network(e)
        if (ce is CloudException.AuthRequired || ce is CloudException.CredentialsUnavailable) scope.launch { c.cloud.accounts.markNeedsReconnect(account.id) }
        error = ce
    }

    suspend fun load(reset: Boolean) {
        if (adapter == null) return
        loading = true; error = null
        try {
            val s = session ?: c.cloud.accounts.session(account.id).also { session = it }
            if (reset) {
                cursor = null; items.clear()
                // Show cached metadata instantly while the fresh listing loads.
                if (search == null) c.cloud.db.items().children(account.id, folderId).map { it.toCloudFile(pid) }.let { items.addAll(it) }
            }
            val page = if (search != null) adapter.search(s, search!!, if (reset) null else cursor) else adapter.list(s, folderId, if (reset) null else cursor)
            if (reset) items.clear()
            items.addAll(page.items)
            cursor = page.nextCursor
            if (search == null) {
                if (reset) c.cloud.db.items().clearFolder(account.id, folderId)
                c.cloud.db.items().upsert(page.items.map { it.toEntity(folderId) })
            }
        } catch (e: Exception) { handle(e) } finally { loading = false }
    }

    LaunchedEffect(account.id, folderId, search, refresh) {
        selection = emptySet()
        state.cloudFolders[account.id] = folderId to stack.last().second
        load(reset = true)
    }
    BackHandler(enabled = true) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            search != null -> search = null
            stack.size > 1 -> stack.removeAt(stack.size - 1)
            else -> state.cloudAccount = null
        }
    }

    val sorted = remember(items.toList(), sort) {
        val cmp: Comparator<CloudFile> = when (sort) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.DATE -> compareByDescending { it.modified ?: 0 }
            SortBy.SIZE -> compareByDescending { it.size ?: 0 }
            SortBy.TYPE -> compareBy<CloudFile> { it.name.substringAfterLast('.', "").lowercase() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        items.filter { it.isFolder }.sortedWith(cmp) + items.filter { !it.isFolder }.sortedWith(cmp)
    }
    val selected = sorted.filter { it.id in selection }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { u ->
                val name = com.nova.files.core.ExternalContent.describe(ctx, u, null).name
                c.cloud.queue.enqueue(TransferKind.COPY, Endpoint.ContentUri(u.toString(), name),
                    Endpoint.Cloud(account.id, pid.name, parentId = folderId, parentName = stack.last().second), name, null, ConflictPolicy.KEEP_BOTH)
            }
            c.cloud.startQueued(ctx)
            c.transfers.toast("Uploading ${uris.size} file(s) to ${stack.last().second} – progress in Transfers")
        }
    }

    fun download(files: List<CloudFile>, export: ExportFormat? = null) = scope.launch {
        val dest = c.transfers.receiveRoot()
        var n = 0
        files.forEach { f ->
            val cap = adapter?.canExecute(f, Operation.DOWNLOAD)
            val fmt = if (cap is CapabilityResult.RequiresExport) export ?: cap.formats.first() else null
            if (cap is CapabilityResult.Unsupported) return@forEach
            val name = if (fmt != null) "${f.name}.${fmt.extension}" else f.name
            c.cloud.queue.enqueue(TransferKind.COPY, Endpoint.Cloud(account.id, pid.name, fileId = f.id, revision = f.revision, exportMime = fmt?.mimeType),
                Endpoint.Local(dest.path), name, if (fmt == null) f.size else null, ConflictPolicy.KEEP_BOTH, resumable = fmt == null)
            n++
        }
        c.cloud.startQueued(ctx)
        val skipped = files.size - n
        c.transfers.toast("Downloading $n file(s) to ${dest.name}" + (if (skipped > 0) " · $skipped can't be downloaded" else "") + " – progress in Transfers")
        selection = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        // Header: which provider and account this is.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NovaIconButton(Icons.Rounded.ArrowBack, "Back to accounts") { state.cloudAccount = null }
            Spacer(Modifier.width(6.dp))
            CategoryBadge(providerIcon(pid), Spectrum.Cloud, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Muted(listOfNotNull(pid.label, account.email).joinToString(" · "), maxLines = 1)
            }
        }
        if (pid == ProviderId.GOOGLE_DRIVE) {
            // Accounts connected before 0.3.3 (or where the Drive box was unticked) only have drive.file access.
            var limited by remember(account.id) { mutableStateOf(false) }
            LaunchedEffect(account.id, refresh) {
                limited = !com.nova.files.cloud.providers.google.GoogleDriveProvider.hasFullAccess(c.cloud.accounts.grantedScope(account.id))
            }
            // After a reconnect for this account, reload the listing with the new access.
            LaunchedEffect(account.id) { c.cloud.auth.connected.collect { if (it == account.id) { cursor = null; refresh++ } } }
            if (limited) Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Muted("Only files Nova Files created are shown. Reconnect and allow Drive access to see all your files.", maxLines = 3)
                Spacer(Modifier.height(6.dp))
                NovaButton("Reconnect to show all files", Icons.Rounded.Login, primary = true) {
                    scope.launch { c.cloud.auth.begin(ctx, pid, account.id)?.let { c.transfers.toast(it) } }
                }
            }
        }

        // Breadcrumbs
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (search != null) item { TextButton(onClick = { search = null }) { Text("Search: “$search”  ✕") } }
            else itemsIndexed(stack.toList()) { i, (_, name) ->
                if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = Nova.colors.muted, modifier = Modifier.size(18.dp))
                TextButton(onClick = { while (stack.size > i + 1) stack.removeAt(stack.size - 1) }) {
                    Text(name, color = if (i == stack.size - 1) Nova.colors.text else Nova.colors.muted, maxLines = 1)
                }
            }
        }

        // Toolbar or selection actions
        if (selection.isNotEmpty() && adapter != null) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { Text("${selection.size} selected", style = MaterialTheme.typography.titleSmall) }
                val files = selected.filter { !it.isFolder }
                if (files.isNotEmpty() && files.any { adapter.canExecute(it, Operation.DOWNLOAD) !is CapabilityResult.Unsupported }) item {
                    NovaButton("Download", Icons.Rounded.Download, primary = true, color = Spectrum.Cloud) {
                        if (files.any { adapter.canExecute(it, Operation.DOWNLOAD) is CapabilityResult.RequiresExport }) dialog = "export" else download(files)
                    }
                }
                if (selected.size == 1 && adapter.canExecute(selected[0], Operation.RENAME).allowed && selected[0].extra["canRename"] != "false") item {
                    NovaButton("Rename", Icons.Rounded.Edit) { dialog = "rename" }
                }
                if (selected.all { adapter.canExecute(it, Operation.DELETE).allowed && it.extra["canTrash"] != "false" }) item {
                    NovaButton("Delete", Icons.Rounded.Delete, color = Spectrum.Danger) { dialog = "delete" }
                }
                item { NovaButton("All", Icons.Rounded.SelectAll) { selection = sorted.map { it.id }.toSet() } }
                item { NovaIconButton(Icons.Rounded.Close, "Clear selection") { selection = emptySet() } }
            }
        } else if (adapter != null) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1 && search == null) item { NovaIconButton(Icons.Rounded.ArrowUpward, "Up") { stack.removeAt(stack.size - 1) } }
                if (search == null && adapter.canExecute(null, Operation.UPLOAD).allowed) item {
                    NovaButton("Upload", Icons.Rounded.Upload, primary = true, color = Spectrum.Cloud) {
                        if (c.profile.isTv) c.transfers.toast("On TV: open Files, select files, then choose Upload › ${account.displayName}")
                        else runCatching { picker.launch(arrayOf("*/*")) }.onFailure { c.transfers.toast("No file picker on this device – use Files › select › Upload") }
                    }
                }
                if (search == null && adapter.canExecute(null, Operation.CREATE_FOLDER).allowed) item { NovaIconButton(Icons.Rounded.CreateNewFolder, "New folder") { dialog = "mkdir" } }
                if (adapter.canExecute(null, Operation.SEARCH).allowed) item { NovaIconButton(Icons.Rounded.Search, "Search") { dialog = "search" } }
                item { NovaIconButton(Icons.Rounded.Sort, "Sort") { dialog = "sort" } }
                item { NovaIconButton(Icons.Rounded.Refresh, "Refresh") { refresh++ } }
            }
        }

        Box(Modifier.weight(1f)) {
            val e = error
            when {
                adapter == null -> EmptyState(Icons.Rounded.CloudOff, "Provider unavailable", "${pid.label} isn't available in this build.")
                e != null && items.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(if (e is CloudException.Network) Icons.Rounded.WifiOff else Icons.Rounded.ErrorOutline, "Couldn't load", e.userMessage)
                    if (e is CloudException.AuthRequired || e is CloudException.CredentialsUnavailable)
                        NovaButton("Reconnect ${account.displayName}", Icons.Rounded.Login, primary = true) { scope.launch { c.cloud.auth.begin(ctx, pid, account.id)?.let { c.transfers.toast(it) } } }
                    else NovaButton("Try again", Icons.Rounded.Refresh) { refresh++ }
                }
                !loading && items.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, if (search != null) "No matches" else "Empty folder",
                    if (search == null && stack.size == 1) "Upload something from this device to get started." else "")
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e != null) item { Muted("Showing saved listing – ${e.userMessage}", Modifier.padding(4.dp), 2) }
                    items(sorted, key = { it.id }) { f ->
                        CloudRow(c, adapter, session, f, f.id in selection,
                            onClick = {
                                if (selection.isNotEmpty()) selection = if (f.id in selection) selection - f.id else selection + f.id
                                else if (f.isFolder) { search = null; stack.add(f.id to f.name) }
                                else selection = setOf(f.id)
                            },
                            onLong = { selection = if (f.id in selection) selection - f.id else selection + f.id })
                    }
                    if (cursor != null) item {
                        NovaButton(if (loading) "Loading…" else "Load more", Icons.Rounded.ExpandMore, Modifier.fillMaxWidth(), enabled = !loading) { scope.launch { load(reset = false) } }
                    }
                }
            }
            if (loading && items.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Nova.colors.accent)
        }
    }

    val s = session
    when (dialog) {
        "mkdir" -> TextInputDialog("New folder", "Folder name", "", "Create", { dialog = null }) { name ->
            scope.launch {
                runCatching { adapter!!.createFolder(s ?: c.cloud.accounts.session(account.id), folderId, name, ConflictPolicy.ASK) }
                    .onSuccess { refresh++ }.onFailure { c.transfers.toast((it as? CloudException)?.userMessage ?: "Couldn't create folder") }
                dialog = null
            }
        }
        "rename" -> selected.firstOrNull()?.let { f ->
            TextInputDialog("Rename", "New name", f.name, "Rename", { dialog = null }) { name ->
                scope.launch {
                    runCatching { adapter!!.rename(s ?: c.cloud.accounts.session(account.id), f, name) }
                        .onSuccess { refresh++ }.onFailure { c.transfers.toast((it as? CloudException)?.userMessage ?: "Rename failed") }
                    dialog = null
                }
            }
        }
        "delete" -> ConfirmDialog("Delete ${selected.size} item(s) from ${pid.label}?",
            selected.take(5).joinToString("\n") { "• " + it.name } + (if (selected.size > 5) "\n…and ${selected.size - 5} more" else "") + "\n\n" +
                when (pid) {
                    ProviderId.GOOGLE_DRIVE -> "They move to Google Drive's trash, where they can be restored for 30 days."
                    ProviderId.DROPBOX -> "Dropbox keeps deleted files restorable on dropbox.com for a limited time."
                    else -> "This may be permanent."
                } + " Nothing on this device is deleted.", "Delete", { dialog = null }) {
            scope.launch {
                var failed = 0
                selected.forEach { f -> runCatching { adapter!!.delete(s ?: c.cloud.accounts.session(account.id), f) }.onFailure { failed++ } }
                if (failed > 0) c.transfers.toast("$failed item(s) couldn't be deleted")
                selection = emptySet(); refresh++; dialog = null
            }
        }
        "export" -> {
            val docs = selected.filter { it.kind == ItemKind.NATIVE_DOCUMENT }
            val formats = docs.flatMap { it.exportFormats }.distinctBy { it.label }
            Dialog(onDismissRequest = { dialog = null }) {
                Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Export Google documents as", style = MaterialTheme.typography.titleLarge)
                        Muted("Google Docs, Sheets and Slides have no file of their own; Google converts them when downloading. Exports don't support resume.", maxLines = 4)
                        formats.forEach { fmt -> NovaButton(fmt.label + " (.${fmt.extension})", null, Modifier.fillMaxWidth()) {
                            dialog = null
                            val chosen = selected.map { f -> f to f.exportFormats.firstOrNull { it.label == fmt.label } }
                            chosen.groupBy { it.second }.forEach { (exp, list) -> download(list.map { it.first }, exp) }
                        } }
                        NovaButton("Cancel", null, Modifier.fillMaxWidth()) { dialog = null }
                    }
                }
            }
        }
        "search" -> TextInputDialog("Search ${pid.label}", "File name", search ?: "", "Search", { dialog = null }) { q -> search = q.takeIf { it.isNotBlank() }; dialog = null }
        "sort" -> Dialog(onDismissRequest = { dialog = null }) {
            Surface(shape = RoundedCornerShape(28.dp), color = Nova.colors.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sort by", style = MaterialTheme.typography.titleLarge)
                    SortBy.entries.forEach { o -> NovaButton(o.label, null, Modifier.fillMaxWidth(), primary = o == sort) { sort = o; dialog = null } }
                    Muted("Sorting applies to the items loaded so far.")
                }
            }
        }
    }
}

@Composable
private fun CloudRow(c: AppContainer, adapter: CloudProviderAdapter, session: AccountSession?, f: CloudFile, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }.coerceIn(48, 256)
    val canThumb = adapter.canExecute(f, Operation.THUMBNAIL).allowed
    val key = remember(f.accountId, f.id, f.revision, px) { c.thumbs.cloudKey(f.accountId, f.id, f.revision, px) }
    val thumb by produceState<Thumb?>(c.thumbs.peekCloud(key), key, session) {
        if (canThumb && value == null && session != null) value = c.thumbs.loadCloud(key, px, fill = true) { adapter.thumbnail(session, f, px) }
    }
    NovaCard(onClick = onClick, onLongClick = onLong, selected = selected, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp), accent = Spectrum.Cloud) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileThumb(File(f.name), f.isFolder, thumb, 40.dp, selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val parts = buildList {
                    when (f.kind) {
                        ItemKind.FOLDER -> add("Folder")
                        ItemKind.NATIVE_DOCUMENT -> add("Google document · export to download")
                        ItemKind.SPECIAL -> add(f.extra["note"] ?: "Can't be downloaded")
                        ItemKind.FILE -> f.size?.let { add(fmtSize(it)) }
                    }
                    f.modified?.let { add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                    add("in the cloud")
                }
                Muted(parts.joinToString(" · "), maxLines = 1)
            }
            Icon(Icons.Rounded.Cloud, "Stored in the cloud", tint = Nova.colors.muted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun CloudFile.toEntity(folderKey: String?) = CloudItemEntity(accountId, id, folderKey, name, mimeType, kind.name, size, modified, revision,
    kotlinx.serialization.json.JsonObject(hashes.mapKeys { it.key.name }.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString(),
    thumbnailRef, kotlinx.serialization.json.JsonObject(extra.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
        + ("exports" to kotlinx.serialization.json.JsonPrimitive(exportFormats.joinToString("|") { "${it.mimeType};${it.extension};${it.label}" }))).toString(),
    System.currentTimeMillis())

private fun CloudItemEntity.toCloudFile(pid: ProviderId): CloudFile {
    val extraObj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(extraJson) as kotlinx.serialization.json.JsonObject }.getOrNull()
    val extras = extraObj?.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content } ?: emptyMap()
    val hashes = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(hashesJson) as kotlinx.serialization.json.JsonObject)
        .entries.associate { HashAlgorithm.valueOf(it.key) to (it.value as kotlinx.serialization.json.JsonPrimitive).content } }.getOrDefault(emptyMap())
    val exports = extras["exports"].orEmpty().split('|').filter { it.isNotBlank() }.mapNotNull { e -> e.split(';').takeIf { it.size == 3 }?.let { ExportFormat(it[0], it[1], it[2]) } }
    return CloudFile(accountId, pid, fileId, parentId, name, mimeType, runCatching { ItemKind.valueOf(kind) }.getOrDefault(ItemKind.FILE), size, null, modified, revision,
        hashes, thumbnailRef, exports, extras - "exports")
}
