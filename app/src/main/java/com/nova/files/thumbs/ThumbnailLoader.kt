package com.nova.files.thumbs

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

/**
 * A preview for a file. [fill] = photographic content that may be centre-cropped (aspect preserved);
 * otherwise (icons, artwork, document pages) it is fitted whole. [detail] = e.g. "WhatsApp · 2.24.1".
 */
class Thumb(val bitmap: Bitmap?, val detail: String?, val fill: Boolean)

/**
 * Universal thumbnail system.
 * Priority: embedded app icon → embedded artwork / document thumbnail → image / video frame → format icon (UI).
 * Nothing is installed, executed or extracted to user storage. All work runs off the main thread with a
 * small parallelism limit; results are cached in memory (LRU, byte-sized) and on disk, keyed by
 * path + size + modification time so a changed file is regenerated automatically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailLoader(private val ctx: Context) {
    private val tag = "NovaThumbs"
    private val io = Dispatchers.IO.limitedParallelism(3)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    private class Job(val deferred: Deferred<Thumb?>, var waiters: Int)
    private val inflight = HashMap<String, Job>()
    private val memLimitKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).coerceAtMost(48L * 1024).toInt()
    private val mem = object : LruCache<String, Thumb>(memLimitKb) {
        override fun sizeOf(key: String, value: Thumb) = ((value.bitmap?.allocationByteCount ?: 0) / 1024).coerceAtLeast(1)
    }
    /** Files known to have no extractable preview (corrupt, unsupported) – avoids retrying while scrolling. */
    private val none = LruCache<String, Boolean>(2000)

    init { scope.launch { trimDisk(150L shl 20, 100L shl 20) } }

    fun key(f: File, px: Int) = FileFormats.cacheKey(f.absolutePath, f.length(), f.lastModified(), px)

    /** Synchronous memory lookup so already-seen thumbnails appear without a placeholder flash. */
    fun peek(key: String): Thumb? = mem.get(key)
    fun knownMissing(key: String) = none.get(key) == true

    suspend fun load(f: File, px: Int): Thumb? {
        val format = FileFormats.of(f.name)
        if (format.extractor == Extractor.NONE || !f.isFile) return null
        val key = key(f, px)
        mem.get(key)?.let { return it }
        if (none.get(key) == true) return null
        // Shared, reference-counted job: items scrolled off-screen cancel work nobody needs any more.
        val job = synchronized(inflight) {
            inflight[key]?.also { it.waiters++ } ?: Job(scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { loadOrExtract(f, px, key, format.extractor) } finally { synchronized(inflight) { inflight.remove(key) } }
            }, 1).also { inflight[key] = it; it.deferred.start() }
        }
        try {
            return job.deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(inflight) { if (--job.waiters <= 0) { job.deferred.cancel(); inflight.remove(key) } }
            throw e
        }
    }

    private fun loadOrExtract(f: File, px: Int, key: String, ex: Extractor): Thumb? {
        readDisk(key)?.let { mem.put(key, it); return it }
        if (File(dir, "$key.none").exists()) { none.put(key, true); return null }
        val t = try { extract(f, px, ex) } catch (e: Throwable) {
            // Malformed files must never crash the list. OutOfMemoryError included.
            Log.w(tag, "No preview for ${f.name}: ${e.javaClass.simpleName} ${e.message}"); null
        }
        if (t?.bitmap == null && t?.detail == null) {
            none.put(key, true); runCatching { File(dir, "$key.none").createNewFile() }; return null
        }
        writeDisk(key, t); mem.put(key, t)
        return t
    }

    // ------------------------------------------------------------------ disk cache

    private fun readDisk(key: String): Thumb? {
        val meta = File(dir, "$key.meta"); if (!meta.exists()) return null
        val lines = runCatching { meta.readLines() }.getOrNull() ?: return null
        val img = File(dir, "$key.webp")
        val bmp = if (img.exists()) BitmapFactory.decodeFile(img.path) else null
        if (img.exists() && bmp == null) return null
        runCatching { meta.setLastModified(System.currentTimeMillis()) }
        return Thumb(bmp, lines.getOrNull(1)?.takeIf { it.isNotBlank() }, lines.getOrNull(0) == "fill")
    }

    private fun writeDisk(key: String, t: Thumb) = runCatching {
        t.bitmap?.let { b ->
            val tmp = File(dir, "$key.webp.tmp")
            tmp.outputStream().use { o ->
                // Photos: lossy WebP. Icons/pages: lossless so edges stay crisp.
                @Suppress("DEPRECATION")
                val fmt = when { Build.VERSION.SDK_INT < 30 -> Bitmap.CompressFormat.WEBP; t.fill -> Bitmap.CompressFormat.WEBP_LOSSY; else -> Bitmap.CompressFormat.WEBP_LOSSLESS }
                b.compress(fmt, if (t.fill) 82 else 100, o)
            }
            tmp.renameTo(File(dir, "$key.webp"))
        }
        File(dir, "$key.meta").writeText((if (t.fill) "fill" else "fit") + "\n" + (t.detail ?: "").replace('\n', ' '))
    }

    private fun trimDisk(max: Long, target: Long) {
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= max) return
        files.sortBy { it.lastModified() }
        for (f in files) { if (total <= target) break; total -= f.length(); f.delete() }
    }

    fun clear() { mem.evictAll(); none.evictAll(); dir.listFiles()?.forEach { it.delete() } }

    // ------------------------------------------------------------------ extractors

    private fun extract(f: File, px: Int, ex: Extractor): Thumb? = when (ex) {
        Extractor.APK -> apk(f, px)
        Extractor.APP_BUNDLE -> bundle(f, px)
        Extractor.IMAGE -> image(f, px)?.let { Thumb(it, null, true) }
        Extractor.RAW_IMAGE -> raw(f, px)?.let { Thumb(it, null, true) }
        Extractor.VIDEO -> video(f, px)
        Extractor.AUDIO -> audio(f, px)
        Extractor.PDF -> pdf(f, px)
        Extractor.OOXML, Extractor.ODF -> zipImage(f, px) { FileFormats.documentThumbnailEntry(it) }?.let { Thumb(it, null, false) }
        Extractor.EPUB -> epub(f, px)
        Extractor.FONT -> font(f, px)
        Extractor.NONE -> null
    }

    /** Reads the icon, label and version from an APK without installing or running it. */
    private fun apk(f: File, px: Int): Thumb? {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(0))
                   else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(f.path, 0)
        val ai = info?.applicationInfo ?: return null
        ai.sourceDir = f.path; ai.publicSourceDir = f.path   // resolve resources from the archive itself
        val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != ai.packageName }
        val bmp = icon?.let { drawableToBitmap(it, px) }
        val detail = listOfNotNull(label ?: info.packageName, info.versionName?.let { "v$it" }).joinToString(" · ")
        return Thumb(bmp, detail, false)
    }

    /** XAPK / APKS / APKM: bundled icon.png, else the base APK's icon (read from a temporary private copy). */
    private fun bundle(f: File, px: Int): Thumb? = ZipFile(f).use { z ->
        val names = z.entries().asSequence().map { it.name }.toList()
        val meta = listOf("manifest.json", "info.json").firstNotNullOfOrNull { n -> z.getEntry(n)?.takeIf { it.size in 1..(256 * 1024) }?.let { z.getInputStream(it).use { s -> String(s.readBytes()) } } }
        val metaName = meta?.let { Regex("\"(?:name|app_name)\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val metaVer = meta?.let { Regex("\"(?:version_name|versionName|release_version)\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        FileFormats.bundleIconEntry(names)?.let { n -> z.getEntry(n)?.takeIf { it.size in 1..(8L shl 20) } }?.let { e ->
            val bytes = z.getInputStream(e).use { it.readBytes() }
            decodeBytes(bytes, px)?.let { return@use Thumb(it, listOfNotNull(metaName, metaVer?.let { v -> "v$v" }).joinToString(" · ").ifBlank { null }, false) }
        }
        val sizes = names.associateWith { z.getEntry(it)?.size ?: 0L }
        val base = FileFormats.bundleBaseApk(names, sizes) ?: return@use null
        val entry = z.getEntry(base) ?: return@use null
        if (entry.size > 300L shl 20) return@use null
        val tmpDir = File(dir, "tmp").apply { mkdirs() }
        val tmp = File(tmpDir, "base-${System.nanoTime()}.apk")
        try {
            z.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            apk(tmp, px)?.let { t -> Thumb(t.bitmap, t.detail ?: listOfNotNull(metaName, metaVer).joinToString(" · ").ifBlank { null }, false) }
        } finally { tmp.delete() }
    }

    private fun image(f: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                val (w, h) = scaledSize(info.size.width, info.size.height, px); d.setTargetSize(w, h)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        return decodeFileSampled(f, px)?.let { rotate(it, exifRotation(f)) }
    }

    /** RAW photos: use the camera's embedded JPEG preview (fast, no RAW decoding). */
    private fun raw(f: File, px: Int): Bitmap? {
        val exif = runCatching { ExifInterface(f.path) }.getOrNull()
        val embedded = exif?.thumbnailBitmap ?: exif?.thumbnail?.let { decodeBytes(it, px) }
        if (embedded != null) return rotate(scaleDown(embedded, px), exifRotation(f))
        return image(f, px)
    }

    private fun video(f: File, px: Int): Thumb? {
        val bmp = if (Build.VERSION.SDK_INT >= 29) runCatching { ThumbnailUtils.createVideoThumbnail(f, Size(px * 2, px * 2), null) }.getOrNull() else null
        val frame = bmp ?: retrieve(f) { r -> (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px * 2, px * 2) else r.frameAtTime) }
        val dur = retrieve(f) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
        return if (frame == null && dur == null) null else Thumb(frame?.let { scaleDown(it, px) }, dur?.let { fmtDuration(it) }, true)
    }

    /** Embedded album art (ID3, FLAC, MP4 atoms…) plus artist / album text. */
    private fun audio(f: File, px: Int): Thumb? = retrieve(f) { r ->
        val art = r.embeddedPicture?.takeIf { it.size < 20 * 1024 * 1024 }?.let { decodeBytes(it, px) }
        val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val detail = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() }, dur?.let { fmtDuration(it) }).joinToString(" · ").ifBlank { null }
        if (art == null && detail == null) null else Thumb(art, detail, false)
    }

    /** First page of a PDF rendered at thumbnail size. */
    private fun pdf(f: File, px: Int): Thumb? = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return@use null
            val bmp = r.openPage(0).use { p ->
                val (w, h) = scaledSize(p.width, p.height, px * 2, fit = true)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
            Thumb(bmp, "${r.pageCount} page${if (r.pageCount == 1) "" else "s"}", false)
        }
    }

    private fun epub(f: File, px: Int): Thumb? = ZipFile(f).use { z ->
        fun text(name: String) = z.getEntry(name)?.takeIf { it.size in 1..(2L shl 20) }?.let { e -> z.getInputStream(e).use { String(it.readBytes()) } }
        val opfPath = text("META-INF/container.xml")?.let { FileFormats.epubOpfPath(it) } ?: return@use null
        val opf = text(opfPath) ?: return@use null
        val title = Regex("<dc:title[^>]*>([^<]+)</dc:title>").find(opf)?.groupValues?.get(1)?.trim()
        val cover = FileFormats.epubCoverPath(opfPath, opf)?.let { z.getEntry(it) }?.takeIf { it.size in 1..(15L shl 20) }
            ?.let { e -> decodeBytes(z.getInputStream(e).use { it.readBytes() }, px) }
        if (cover == null && title == null) null else Thumb(cover, title, false)
    }

    private fun zipImage(f: File, px: Int, pick: (List<String>) -> String?): Bitmap? = ZipFile(f).use { z ->
        val name = pick(z.entries().asSequence().map { it.name }.toList()) ?: return@use null
        val e = z.getEntry(name)?.takeIf { it.size in 1..(8L shl 20) } ?: return@use null
        decodeBytes(z.getInputStream(e).use { it.readBytes() }, px)
    }

    /** Font specimen ("Aa") rendered with the font itself. */
    private fun font(f: File, px: Int): Thumb? {
        val tf = Typeface.createFromFile(f)
        val size = px * 2
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; color = Color.rgb(20, 22, 30); textSize = size * 0.48f; textAlign = Paint.Align.CENTER }
        val y = size / 2f - (p.descent() + p.ascent()) / 2
        c.drawText("Aa", size / 2f, y, p)
        return Thumb(b, null, false)
    }

    // ------------------------------------------------------------------ cloud (provider-native thumbnails)

    /** Cache key scoped to account + immutable id + revision, so accounts never share thumbnails and edits refresh. */
    fun cloudKey(accountId: String, fileId: String, revision: String?, px: Int) =
        FileFormats.cacheKey("cloud|$accountId|$fileId", 0, (revision ?: "").hashCode().toLong(), px)

    fun peekCloud(key: String): Thumb? = mem.get(key)

    /**
     * Small provider thumbnail ([fetch] downloads only the provider's preview, never the full file),
     * decoded with downsampling and stored in the same memory/disk caches as local thumbnails.
     */
    suspend fun loadCloud(key: String, px: Int, fill: Boolean, fetch: suspend () -> ByteArray?): Thumb? {
        mem.get(key)?.let { return it }
        if (none.get(key) == true) return null
        val job = synchronized(inflight) {
            inflight[key]?.also { it.waiters++ } ?: Job(scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    readDisk(key)?.let { mem.put(key, it); return@async it }
                    if (File(dir, "$key.none").exists()) { none.put(key, true); return@async null }
                    val bytes = try { fetch() } catch (e: Exception) { null }    // rate limits / errors → fallback icon, retried next session
                    val bmp = bytes?.let { runCatching { decodeBytes(it, px) }.getOrNull() }
                    if (bmp == null) { if (bytes != null) { none.put(key, true); runCatching { File(dir, "$key.none").createNewFile() } }; null }
                    else Thumb(bmp, null, fill).also { writeDisk(key, it); mem.put(key, it) }
                } finally { synchronized(inflight) { inflight.remove(key) } }
            }, 1).also { inflight[key] = it; it.deferred.start() }
        }
        try { return job.deferred.await() }
        catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(inflight) { if (--job.waiters <= 0) { job.deferred.cancel(); inflight.remove(key) } }
            throw e
        }
    }

    // ------------------------------------------------------------------ external content (content://)

    /** For files from other apps: the provider's own thumbnail (MediaStore, Downloads, documents). */
    suspend fun loadExternal(uri: Uri, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        val key = "ext|$uri|$px"
        mem.get(key)?.bitmap?.let { return it }
        if (none.get(key) == true) return null
        return kotlinx.coroutines.withContext(io) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
                .also { if (it != null) mem.put(key, Thumb(it, null, true)) else none.put(key, true) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun <T> retrieve(f: File, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(f.path); block(r) } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Target size whose shorter side is [px] (for cropping) or longer side is [px] (fit), never upscaling. */
    private fun scaledSize(w: Int, h: Int, px: Int, fit: Boolean = false): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return px to px
        val ref = if (fit) maxOf(w, h) else minOf(w, h)
        val k = minOf(1f, px.toFloat() / ref)
        return (w * k).toInt().coerceAtLeast(1) to (h * k).toInt().coerceAtLeast(1)
    }

    private fun sampleFor(w: Int, h: Int, px: Int): Int { var s = 1; while (minOf(w, h) / (s * 2) >= px) s *= 2; return s }

    private fun decodeFileSampled(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight, px) })?.let { scaleDown(it, px) }
    }

    private fun decodeBytes(b: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight, px) })?.let { scaleDown(it, px) }
    }

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val (w, h) = scaledSize(b.width, b.height, px)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    private fun exifRotation(f: File) = runCatching {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(b: Bitmap, deg: Int): Bitmap =
        if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    /** Renders any drawable (legacy or adaptive icon) to a square bitmap. */
    private fun drawableToBitmap(d: Drawable, px: Int): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return scaleDown(d.bitmap, px)
        val size = px.coerceAtLeast(48)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b))
        return b
    }

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
