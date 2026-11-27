package com.nova.files.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nova.files.AppContainer
import com.nova.files.core.ExternalContent
import com.nova.files.core.ExternalFile
import com.nova.files.core.ViewKind
import com.nova.files.core.fmtSize
import com.nova.files.ui.components.*
import com.nova.files.ui.theme.Nova
import com.nova.files.ui.theme.Spectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

private fun kindIcon(k: ViewKind) = when (k) {
    ViewKind.IMAGE -> Icons.Rounded.Image; ViewKind.VIDEO -> Icons.Rounded.Movie; ViewKind.AUDIO -> Icons.Rounded.MusicNote
    ViewKind.PDF -> Icons.Rounded.PictureAsPdf; ViewKind.TEXT -> Icons.Rounded.Description; ViewKind.ZIP -> Icons.Rounded.FolderZip
    ViewKind.OFFICE -> Icons.Rounded.Article; ViewKind.OTHER -> Icons.Rounded.InsertDriveFile
}

private fun kindColor(k: ViewKind) = when (k) {
    ViewKind.IMAGE -> Spectrum.Photos; ViewKind.VIDEO -> Spectrum.Video; ViewKind.AUDIO -> Spectrum.Audio
    ViewKind.PDF, ViewKind.TEXT, ViewKind.OFFICE -> Spectrum.Documents; ViewKind.ZIP -> Spectrum.Archive; ViewKind.OTHER -> Color(0xFF8E95AB)
}

@Composable
fun ViewerScreen(act: FileViewerActivity, c: AppContainer, f: ExternalFile?, error: String?) {
    val colors = Nova.colors
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<String?>(null) }
    fun toast(m: String) { scope.launch { snackbar.showSnackbar(m) } }

    Scaffold(containerColor = colors.bg, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NovaIconButton(Icons.Rounded.Close, "Close") { act.finish() }
                Spacer(Modifier.width(8.dp))
                if (f != null) {
                    CategoryBadge(kindIcon(f.kind), kindColor(f.kind), 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Muted((if (f.size >= 0) fmtSize(f.size) + " · " else "") + f.mime, maxLines = 1)
                    }
                } else Text("Nova Files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (f != null) {
                // Actions – all reachable by D-pad as well as touch
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        NovaButton(if (saving != null) "Saving… $saving" else "Save to device", Icons.Rounded.Download, primary = true, enabled = saving == null) {
                            saving = ""
                            scope.launch {
                                val dest = c.transfers.receiveRoot()
                                runCatching { withContext(Dispatchers.IO) { ExternalContent.saveCopy(act, f, dest) { n -> saving = fmtSize(n) } } }
                                    .onSuccess { out ->
                                        saving = null
                                        val r = snackbar.showSnackbar("Saved as ${out.name} in ${dest.name}", actionLabel = "Show", duration = SnackbarDuration.Long)
                                        if (r == SnackbarResult.ActionPerformed) act.openFolder(dest.absolutePath)
                                    }
                                    .onFailure { saving = null; toast(it.message ?: "Save failed") }
                            }
                        }
                    }
                    item { NovaButton("Send to device", Icons.AutoMirrored.Rounded.Send, color = Spectrum.Transfer) { act.sendToDevice(f) } }
                    item { NovaButton("Open with…", Icons.AutoMirrored.Rounded.OpenInNew) { act.openElsewhere(f)?.let(::toast) } }
                    if (!c.profile.isTv) item { NovaButton("Share", Icons.Rounded.Share) { act.share(f)?.let(::toast) } }
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null -> EmptyState(Icons.Rounded.ErrorOutline, "Can't open file", error)
                    f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                    else -> key(f.uri) {
                        when (f.kind) {
                            ViewKind.IMAGE -> ImageView(f)
                            ViewKind.VIDEO, ViewKind.AUDIO -> MediaView(f)
                            ViewKind.PDF -> PdfView(f)
                            ViewKind.TEXT -> TextView(f)
                            ViewKind.ZIP -> ZipView(f)
                            ViewKind.OFFICE, ViewKind.OTHER -> InfoView(f)
                        }
                    }
                }
            }
        }
    }
}

/** Loading / failure wrapper so no viewer ever shows a blank screen. */
@Composable
private fun <T> Loaded(load: suspend () -> T, content: @Composable (T) -> Unit) {
    var result by remember { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(Unit) { result = runCatching { withContext(Dispatchers.IO) { load() } } }
    when (val r = result) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Nova.colors.accent) }
        else -> {
            val v = r.getOrNull()
            if (r.isSuccess) @Suppress("UNCHECKED_CAST") content(v as T)
            else { val e = r.exceptionOrNull(); EmptyState(Icons.Rounded.ErrorOutline, "Can't display this file", (e?.message ?: "Unknown error") + "\nUse Open with… or Save to device instead.") }
        }
    }
}

@Composable
private fun ImageView(f: ExternalFile) {
    val ctx = LocalContext.current
    val maxPx = 4096
    Loaded({
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28) runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, f.uri)) { d, info, _ ->
                val s = maxOf(info.size.width, info.size.height)
                if (s > maxPx) { val k = maxPx.toFloat() / s; d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1)) }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() else null
        bmp ?: run {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
            ctx.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } ?: throw IllegalStateException("Unsupported or damaged image")
    }) { bmp ->
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val st = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 8f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
        Image(bmp.asImageBitmap(), f.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().transformable(st).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable
private fun MediaView(f: ExternalFile) {
    val audio = f.kind == ViewKind.AUDIO
    Column(Modifier.fillMaxSize()) {
        if (audio) NovaCard(Modifier.padding(16.dp).fillMaxWidth(), accent = Spectrum.Audio) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(Icons.Rounded.MusicNote, Spectrum.Audio, 56.dp); Spacer(Modifier.width(14.dp))
                Column { Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 2); Muted("Playing · use the controls below") }
            }
        }
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val mc = MediaController(ctx); mc.setAnchorView(this); setMediaController(mc)
                    setOnPreparedListener { start(); mc.show(0) }
                    setOnErrorListener { _, _, _ -> mc.hide(); true }
                    setVideoURI(f.uri)
                    isFocusable = true
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxWidth().then(if (audio) Modifier.height(120.dp) else Modifier.weight(1f)).background(Color.Black),
        )
    }
}

@Composable
private fun PdfView(f: ExternalFile) {
    val ctx = LocalContext.current
    val renderLock = remember { Mutex() }
    var tempCopy by remember { mutableStateOf<File?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { renderer?.close() }; tempCopy?.delete() } }
    Loaded({
        // PdfRenderer needs a seekable descriptor. Most providers give one; for streamed ones (some email
        // apps) we fall back to a private temporary copy that is deleted when the viewer closes.
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(f.uri, "r") }.getOrNull()
        val r = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() } ?: run {
            runCatching { pfd?.close() }
            val dir = File(ctx.cacheDir, "viewer").apply { mkdirs() }
            val t = File(dir, "doc-${System.nanoTime()}.pdf")
            (ctx.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("No access to file")).use { i -> t.outputStream().use { i.copyTo(it, 1 shl 20) } }
            tempCopy = t
            PdfRenderer(ParcelFileDescriptor.open(t, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        renderer = r
        r.pageCount
    }) { pages ->
        val r = renderer ?: return@Loaded
        val width = (LocalContext.current.resources.displayMetrics.widthPixels).coerceAtMost(1600)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Muted("$pages page${if (pages == 1) "" else "s"}", Modifier.padding(horizontal = 4.dp)) }
            items(pages) { idx ->
                var bmp by remember(idx) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(idx) {
                    bmp = withContext(Dispatchers.IO) {
                        renderLock.withLock {
                            runCatching {
                                r.openPage(idx).use { p ->
                                    val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                                        b.eraseColor(android.graphics.Color.WHITE)
                                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "Page ${idx + 1}", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).clip(RoundedCornerShape(8.dp)).background(Nova.colors.surfaceHigh), contentAlignment = Alignment.Center) { Muted("Page ${idx + 1}") }
            }
        }
    }
}

private const val TEXT_LIMIT = 2 * 1024 * 1024

@Composable
private fun TextView(f: ExternalFile) {
    val ctx = LocalContext.current
    Loaded({
        val sb = StringBuilder(); var truncated = false
        (ctx.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("No access to file")).use { i ->
            InputStreamReader(i, Charsets.UTF_8).use { rd ->
                val buf = CharArray(64 * 1024)
                while (true) { val n = rd.read(buf); if (n < 0) break; sb.append(buf, 0, n); if (sb.length >= TEXT_LIMIT) { truncated = true; break } }
            }
        }
        sb.toString().split('\n') to truncated
    }) { (lines, truncated) ->
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (truncated) item { Muted("Showing the first 2 MB. Save or open with another app for the full file.", Modifier.padding(8.dp)) }
                itemsIndexed(lines) { _, line -> Text(line.trimEnd('\r'), fontFamily = FontFamily.Monospace, fontSize = if (Nova.isTv) 16.sp else 13.sp, color = Nova.colors.text) }
            }
        }
    }
}

private data class ZipEntryInfo(val name: String, val size: Long, val dir: Boolean)

@Composable
private fun ZipView(f: ExternalFile) {
    val ctx = LocalContext.current
    Loaded({
        val list = ArrayList<ZipEntryInfo>(); var more = false
        (ctx.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("No access to file")).use { i ->
            ZipInputStream(i.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (list.size >= 5000) { more = true; break }
                    list += ZipEntryInfo(e.name, e.size, e.isDirectory)
                }
            }
        }
        if (list.isEmpty()) throw IllegalStateException("Empty or not a valid ZIP archive")
        list to more
    }) { (entries, more) ->
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Muted("${entries.count { !it.dir }} files, ${entries.count { it.dir }} folders" + (if (more) " (first 5000 shown)" else "") + " · contents listed only, nothing is extracted or run", Modifier.padding(4.dp), 3) }
            items(entries) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.dir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, null, tint = if (e.dir) Spectrum.Folder else Nova.colors.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                    if (!e.dir && e.size >= 0) Muted(fmtSize(e.size))
                }
            }
        }
    }
}

@Composable
private fun InfoView(f: ExternalFile) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CategoryBadge(kindIcon(f.kind), kindColor(f.kind), 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(f.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Muted((if (f.size >= 0) fmtSize(f.size) + " · " else "") + f.mime)
        Spacer(Modifier.height(14.dp))
        Muted(if (f.kind == ViewKind.OFFICE) "Nova Files doesn't render Office documents. Use Open with… for Word, Excel or PowerPoint, or save or send the file."
              else "Nova Files can't preview this type of file. You can save it, send it to another device, or open it with another app.", maxLines = 4)
    }
}
