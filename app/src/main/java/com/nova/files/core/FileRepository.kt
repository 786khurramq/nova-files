package com.nova.files.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.nova.files.transfer.protocol.FileReceiveSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class FileCategory { FOLDER, DOCUMENT, APK, ARCHIVE, IMAGE, AUDIO, VIDEO, OTHER }

data class FileEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val category: FileCategory,
    val hidden: Boolean,
) { val path: String get() = file.absolutePath }

data class StorageVolumeInfo(
    val label: String,
    val path: File,
    val total: Long,
    val free: Long,
    val removable: Boolean,
    val primary: Boolean,
) { val used get() = total - free }

object FileTypes {
    private val doc = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "ods", "odp", "csv", "epub", "json", "xml", "html", "htm", "log", "srt")
    private val img = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "svg", "avif")
    private val aud = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "aiff", "mka")
    private val vid = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts", "wmv", "flv", "3gp", "mpg", "mpeg", "vob")
    private val arc = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
    private val apk = setOf("apk", "apks", "xapk", "apkm")

    fun category(name: String, isDir: Boolean): FileCategory {
        if (isDir) return FileCategory.FOLDER
        return when (name.substringAfterLast('.', "").lowercase()) {
            in doc -> FileCategory.DOCUMENT; in img -> FileCategory.IMAGE; in aud -> FileCategory.AUDIO
            in vid -> FileCategory.VIDEO; in arc -> FileCategory.ARCHIVE; in apk -> FileCategory.APK
            else -> FileCategory.OTHER
        }
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}

class FileRepository(private val ctx: Context) {

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Opens the All files access screen. Returns false if this device has none (common on Android TV). */
    fun requestAllFilesAccess(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (i in tries) if (runCatching { activity.startActivity(i) }.isSuccess) return true
        return false
    }

    val adbGrantCommand get() = "adb shell appops set --uid ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow"

    fun volumes(): List<StorageVolumeInfo> {
        val out = linkedMapOf<String, StorageVolumeInfo>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        runCatching {
            for (v in sm.storageVolumes) {
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir: File = if (Build.VERSION.SDK_INT >= 30) v.directory ?: continue else {
                    runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as File }.getOrNull() ?: continue
                }
                out[dir.absolutePath] = stat(v.getDescription(ctx) ?: dir.name, dir, v.isRemovable, v.isPrimary)
            }
        }
        // Fallback for devices whose StorageManager hides USB volumes.
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { d ->
            val root = File(d.absolutePath.substringBefore("/Android/data"))
            if (root.absolutePath !in out && root.exists()) out[root.absolutePath] = stat(if (out.isEmpty()) "Internal storage" else "External storage", root, out.isNotEmpty(), out.isEmpty())
        }
        // USB mounts on some TV boxes (e.g. /mnt/media_rw, /storage/XXXX-XXXX) readable by file path.
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name !in setOf("emulated", "self") }?.forEach {
            if (it.absolutePath !in out) out[it.absolutePath] = stat(it.name, it, true, false)
        }
        return out.values.sortedByDescending { it.primary }
    }

    private fun stat(label: String, dir: File, removable: Boolean, primary: Boolean): StorageVolumeInfo {
        val (t, f) = runCatching { StatFs(dir.absolutePath).let { it.totalBytes to it.availableBytes } }.getOrDefault(dir.totalSpace to dir.usableSpace)
        return StorageVolumeInfo(label, dir, t, f, removable, primary)
    }

    suspend fun list(dir: File, showHidden: Boolean, sort: SortBy, desc: Boolean): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val files = dir.listFiles() ?: throw IOException(
                if (!dir.exists()) "Folder no longer exists" else "Android restricts access to this folder" + if (!hasAllFilesAccess()) " – grant All files access in Settings" else "")
            val entries = files.asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .map { f ->
                    val d = f.isDirectory
                    FileEntry(f, f.name, d, if (d) 0 else f.length(), f.lastModified(), FileTypes.category(f.name, d), f.name.startsWith("."))
                }.toList()
            val cmp: Comparator<FileEntry> = when (sort) {
                SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortBy.DATE -> compareBy { it.modified }
                SortBy.SIZE -> compareBy { it.size }
                SortBy.TYPE -> compareBy<FileEntry> { it.category.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
            val sorted = entries.sortedWith(if (desc) cmp.reversed() else cmp)
            sorted.filter { it.isDir } + sorted.filter { !it.isDir }
        }
    }

    suspend fun createFolder(parent: File, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            validName(name)
            val f = File(parent, name)
            if (f.exists()) throw IOException("A file or folder with that name already exists")
            if (!f.mkdir()) throw IOException("Could not create folder here (write access denied)")
            f
        }
    }

    suspend fun rename(f: File, newName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            validName(newName)
            val dest = File(f.parentFile, newName)
            if (dest.exists()) throw IOException("A file with that name already exists")
            if (!f.renameTo(dest)) throw IOException("Rename failed (write access denied)")
            dest
        }
    }

    suspend fun delete(files: List<File>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var n = 0
            files.forEach { if (it.deleteRecursively()) n++ else throw IOException("Could not delete ${it.name}") }
            n
        }
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /** Copy or move with space check; never overwrites (adds " (1)"). */
    suspend fun transfer(sources: List<File>, destDir: File, move: Boolean, onProgress: (Long, Long) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (sources.any { destDir.canonicalPath == it.canonicalPath || destDir.canonicalPath.startsWith(it.canonicalPath + File.separator) })
                throw IOException("Cannot copy a folder into itself")
            val total = sources.sumOf { sizeOf(it) }
            if (!move || !sameVolume(sources.first(), destDir)) {
                val free = StatFs(destDir.absolutePath).availableBytes
                if (total > free) throw IOException("Not enough space: needs ${fmtSize(total)}, ${fmtSize(free)} free")
            }
            var done = 0L; var count = 0
            for (src in sources) {
                val target = FileReceiveSink.uniqueName(File(destDir, src.name))
                if (move && src.renameTo(target)) { done += sizeOf(target); count++; onProgress(done, total); continue }
                copyRec(src, target) { n -> done += n; onProgress(done, total) }
                if (move && !src.deleteRecursively()) throw IOException("Copied but could not remove original ${src.name}")
                count++
            }
            count
        }
    }

    private fun sameVolume(a: File, b: File) = runCatching {
        a.canonicalPath.split('/').take(4) == b.canonicalPath.split('/').take(4)
    }.getOrDefault(false)

    private fun copyRec(src: File, dst: File, progress: (Long) -> Unit) {
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("Cannot create ${dst.name}")
            src.listFiles()?.forEach { copyRec(it, File(dst, it.name), progress) }
        } else {
            val tmp = File(dst.parentFile, ".${dst.name}.novatmp")
            src.inputStream().use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); progress(r.toLong()) }
            } }
            if (!tmp.renameTo(dst)) { tmp.delete(); throw IOException("Cannot write ${dst.name}") }
            dst.setLastModified(src.lastModified())
        }
    }

    private fun validName(n: String) {
        if (n.isBlank() || n == "." || n == ".." || n.contains('/') || n.contains('\u0000')) throw IOException("Invalid name")
        if (n.toByteArray().size > 255) throw IOException("Name too long")
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** Opens a file with a compatible app. APKs go through the system installer, never installed silently. */
    fun open(f: File): String? {
        val mime = FileTypes.mime(f.name)
        if (mime == "application/vnd.android.package-archive" && Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { ctx.startActivity(i); "Allow Nova Files to install apps, then open the APK again" }
            catch (e: ActivityNotFoundException) { "This device has no 'install unknown apps' screen. Enable it under Settings › Device Preferences › Security & restrictions › Unknown sources." }
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); null } catch (e: ActivityNotFoundException) {
            try { ctx.startActivity(Intent.createChooser(i.setDataAndType(uriFor(f), "*/*"), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            catch (e2: ActivityNotFoundException) { "No app on this device can open ${f.name}" }
        }
    }

    fun share(files: List<File>): String? {
        val uris = ArrayList(files.filter { it.isFile }.map { uriFor(it) })
        if (uris.isEmpty()) return "Select files (not folders) to share"
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(FileTypes.mime(files[0].name))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
        catch (e: ActivityNotFoundException) { "No sharing apps available on this device" }
    }
}

fun fmtSize(b: Long): String = com.nova.files.transfer.protocol.TransferEngine.fmt(b)
