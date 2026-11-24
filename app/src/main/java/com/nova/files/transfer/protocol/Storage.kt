package com.nova.files.transfer.protocol

import java.io.File
import java.io.InputStream

object PathSafety {
    private val illegal = Regex("[\\\\:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** Validates a peer-supplied relative path. Rejects traversal, absolute paths and hostile names. */
    fun sanitize(relPath: String): List<String> {
        if (relPath.isEmpty() || relPath.length > 4096) throw ProtocolException("Invalid file name")
        if (relPath.startsWith("/") || relPath.contains('\\')) throw ProtocolException("Rejected unsafe path: $relPath")
        val parts = relPath.split('/')
        if (parts.size > 64) throw ProtocolException("Folder structure too deep")
        return parts.map { raw ->
            if (raw.isEmpty() || raw == "." || raw == "..") throw ProtocolException("Rejected unsafe path: $relPath")
            var c = raw.replace(illegal, "_").trim().trimEnd('.')
            if (c.isEmpty()) c = "_"
            if (c.substringBefore('.').uppercase() in reserved) c = "_$c"
            if (c == ".nova-partial") c = "_nova-partial"
            truncateUtf8(c, 240)
        }
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray().size <= maxBytes) return s
        val ext = s.substringAfterLast('.', "").let { if (it.length in 1..10) ".$it" else "" }
        var base = s.removeSuffix(ext)
        while ((base + ext).toByteArray().size > maxBytes && base.isNotEmpty()) base = base.dropLast(1)
        return base + ext
    }
}

/** Where received files land. Partial data lives in a hidden folder until verified. */
class FileReceiveSink(val root: File) {
    val partialDir = File(root, ".nova-partial")

    fun prepare() {
        if (!root.exists() && !root.mkdirs()) throw ProtocolException("Cannot create receive folder ${root.path}")
        if (!partialDir.exists()) partialDir.mkdirs()
        File(partialDir, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
    }

    fun freeBytes(): Long = root.usableSpace

    fun partialFile(key: String): File = File(partialDir, "$key.part")

    private fun resolve(parts: List<String>): File {
        var f = root
        parts.forEach { f = File(f, it) }
        val canonicalRoot = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(canonicalRoot)) throw ProtocolException("Rejected path outside receive folder")
        return f
    }

    fun ensureDir(relPath: String): File = resolve(PathSafety.sanitize(relPath)).also { it.mkdirs() }

    /** Moves a verified partial into place, never overwriting an existing file. Returns the final file. */
    fun finalize(part: File, relPath: String): File {
        val target = resolve(PathSafety.sanitize(relPath))
        target.parentFile?.mkdirs()
        val dest = uniqueName(target)
        if (!part.renameTo(dest)) {
            part.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            part.delete()
        }
        return dest
    }

    companion object {
        fun uniqueName(target: File): File {
            if (!target.exists()) return target
            val name = target.name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot); val ext = name.substring(dot)
            var i = 1
            while (true) {
                val c = File(target.parentFile, "$base ($i)$ext")
                if (!c.exists()) return c
                i++
            }
        }
    }
}

/** Something that can be sent: a local file, a content:// document, a folder entry. */
interface SendItem {
    val relPath: String
    val size: Long
    val mtime: Long
    val isDir: Boolean
    fun open(): InputStream
}

class FileSendItem(val file: File, override val relPath: String) : SendItem {
    override val size: Long = if (file.isDirectory) 0 else file.length()
    override val mtime: Long = file.lastModified()
    override val isDir: Boolean = file.isDirectory
    override fun open(): InputStream = file.inputStream()

    companion object {
        /** Expands files and folders into send items, preserving folder structure (including empty folders). */
        fun expand(files: List<File>): List<SendItem> {
            val out = ArrayList<SendItem>()
            fun walk(f: File, rel: String) {
                if (f.isDirectory) {
                    out += FileSendItem(f, rel)
                    f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, "$rel/${it.name}") }
                } else if (f.isFile) out += FileSendItem(f, rel)
            }
            files.forEach { walk(it, it.name) }
            return out
        }
    }
}
