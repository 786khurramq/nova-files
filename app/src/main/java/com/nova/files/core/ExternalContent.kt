package com.nova.files.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.nova.files.transfer.protocol.FileReceiveSink
import java.io.File
import java.io.IOException

/** What the built-in viewer can do with a file. */
enum class ViewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ZIP, OFFICE, OTHER }

/** A file handed to Nova Files by another app (content:// or, rarely, file://). */
data class ExternalFile(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val kind: ViewKind get() = ExternalContent.classify(mime, name)
}

object ExternalContent {

    // ---------------------------------------------------------------- pure logic (JVM-tested)

    private val textMimes = setOf("application/json", "application/xml", "application/javascript", "application/x-sh", "application/x-yaml", "application/yaml")
    private val zipMimes = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")
    private val officeMimes = setOf(
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/rtf", "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    )
    private val textExt = setOf("txt", "csv", "tsv", "html", "htm", "json", "xml", "md", "log", "srt", "ini", "conf", "yaml", "yml", "properties", "js", "css", "kt", "java", "py", "sh")
    private val officeExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp")

    /** Decides the viewer from MIME first, then the file extension (covers application/octet-stream). */
    fun classify(mime: String, name: String): ViewKind {
        val m = mime.lowercase().substringBefore(';').trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") -> ViewKind.IMAGE
            m.startsWith("video/") -> ViewKind.VIDEO
            m.startsWith("audio/") -> ViewKind.AUDIO
            m == "application/pdf" -> ViewKind.PDF
            m in zipMimes -> ViewKind.ZIP
            m in officeMimes -> ViewKind.OFFICE
            m.startsWith("text/") || m in textMimes -> ViewKind.TEXT
            else -> when (FileTypes.category(name, false)) {
                FileCategory.IMAGE -> ViewKind.IMAGE
                FileCategory.VIDEO -> ViewKind.VIDEO
                FileCategory.AUDIO -> ViewKind.AUDIO
                else -> when {
                    ext == "pdf" -> ViewKind.PDF
                    ext == "zip" -> ViewKind.ZIP
                    ext in officeExt -> ViewKind.OFFICE
                    ext in textExt -> ViewKind.TEXT
                    else -> ViewKind.OTHER
                }
            }
        }
    }

    /**
     * Blocks the "confused deputy" case: another app handing us a URI that points at Nova's own private
     * data (our FileProvider, or file:// paths in our data directory), hoping we'll save/send it onward.
     */
    fun isPathAllowed(canonicalPath: String, privateRoots: List<String>): Boolean =
        privateRoots.none { root -> canonicalPath == root || canonicalPath.startsWith("$root/") } &&
            !canonicalPath.startsWith("/data/data/") && !canonicalPath.startsWith("/data/user/") && !canonicalPath.startsWith("/proc/")

    fun isAuthorityAllowed(authority: String?, ownPackage: String): Boolean =
        authority != null && authority != "$ownPackage.files" && !authority.startsWith("$ownPackage.")

    /** Strips path separators and control characters from a sender-supplied display name. */
    fun safeName(raw: String?): String {
        val n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F:*?\"<>|]"), "_").trim().trimStart('.')
        return n.ifBlank { "file" }.take(200)
    }

    // ---------------------------------------------------------------- Android

    fun isSafe(ctx: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> isAuthorityAllowed(uri.authority, ctx.packageName)
        "file" -> {
            val p = uri.path ?: return false
            val canonical = runCatching { File(p).canonicalPath }.getOrNull() ?: return false
            val roots = listOfNotNull(ctx.applicationInfo.dataDir, ctx.filesDir.parent, ctx.cacheDir.parent,
                ctx.getExternalFilesDir(null)?.parentFile?.let { runCatching { it.canonicalPath }.getOrNull() })
                .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
            isPathAllowed(canonical, roots)
        }
        else -> false
    }

    /** All file URIs carried by a SEND / SEND_MULTIPLE / VIEW intent: data, EXTRA_STREAM and ClipData. */
    fun urisFrom(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.data?.let { if (it.scheme == "content" || it.scheme == "file") out += it }
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        intent.clipData?.let { cd: ClipData -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out += it } }
        return out.toList()
    }

    /** Name, MIME and size without copying. Size is -1 when the provider doesn't report it. */
    fun describe(ctx: Context, uri: Uri, intentType: String?): ExternalFile {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            name = f.name; size = if (f.isFile) f.length() else -1
        } else runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment
        val safe = safeName(name)
        val resolverType = if (uri.scheme == "content") runCatching { ctx.contentResolver.getType(uri) }.getOrNull() else null
        // Prefer the most specific type: provider's type, then sender's, then extension.
        val generic = setOf(null, "", "*/*", "application/octet-stream")
        val mime = listOf(resolverType, intentType).firstOrNull { it !in generic }
            ?: FileTypes.mime(safe).takeIf { it != "*/*" }
            ?: resolverType ?: intentType ?: "application/octet-stream"
        return ExternalFile(uri, safe, mime, size)
    }

    /** Streams the content into [destDir] without overwriting anything. Returns the saved file. */
    fun saveCopy(ctx: Context, f: ExternalFile, destDir: File, onProgress: (Long) -> Unit = {}): File {
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Cannot create ${destDir.path}")
        val target = FileReceiveSink.uniqueName(File(destDir, f.name))
        val tmp = File(destDir, ".${target.name}.novatmp")
        try {
            (ctx.contentResolver.openInputStream(f.uri) ?: throw IOException("The other app no longer allows access to this file")).use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 20); var done = 0L
                    while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); done += r; onProgress(done) }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Could not save ${target.name}")
            return target
        } finally { if (tmp.exists()) tmp.delete() }
    }
}
