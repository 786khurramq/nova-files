package com.nova.files.thumbs

/**
 * Fine-grained file formats for format-specific icons (fallback level 4), and the pure helper logic
 * used by thumbnail extraction. No Android dependencies, so all of this is JVM-tested.
 */
enum class FormatKind {
    APK, APP_BUNDLE, IMAGE, RAW_IMAGE, VECTOR, VIDEO, AUDIO, PDF, WORD, EXCEL, POWERPOINT, TEXT, CSV, WEB, DATA,
    EBOOK, ARCHIVE, FONT, DESIGN, DATABASE, CONFIG, CODE, DISK_IMAGE, CERTIFICATE, SUBTITLE, EXECUTABLE, TORRENT, FOLDER, UNKNOWN
}

/** Which extractor can produce a real preview for this file (levels 1–3). */
enum class Extractor { APK, APP_BUNDLE, IMAGE, RAW_IMAGE, VIDEO, AUDIO, PDF, OOXML, ODF, EPUB, FONT, NONE }

data class FileFormat(val kind: FormatKind, val label: String, val extractor: Extractor)

object FileFormats {
    private val map = HashMap<String, Pair<FormatKind, Extractor>>()
    private fun reg(kind: FormatKind, ex: Extractor, vararg exts: String) = exts.forEach { map[it] = kind to ex }

    init {
        reg(FormatKind.APK, Extractor.APK, "apk")
        reg(FormatKind.APP_BUNDLE, Extractor.APP_BUNDLE, "xapk", "apks", "apkm")
        reg(FormatKind.IMAGE, Extractor.IMAGE, "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "ico", "wbmp")
        reg(FormatKind.IMAGE, Extractor.NONE, "tif", "tiff")
        reg(FormatKind.RAW_IMAGE, Extractor.RAW_IMAGE, "dng", "cr2", "cr3", "nef", "nrw", "arw", "srw", "orf", "rw2", "raf", "pef")
        reg(FormatKind.VECTOR, Extractor.NONE, "svg", "svgz", "eps")
        reg(FormatKind.VIDEO, Extractor.VIDEO, "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "vob", "ogv")
        reg(FormatKind.AUDIO, Extractor.AUDIO, "mp3", "flac", "wav", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma", "alac", "aiff", "aif", "amr", "mka", "mid", "midi")
        reg(FormatKind.PDF, Extractor.PDF, "pdf")
        reg(FormatKind.WORD, Extractor.OOXML, "docx", "docm", "dotx")
        reg(FormatKind.WORD, Extractor.NONE, "doc", "dot", "rtf", "wpd", "pages")
        reg(FormatKind.WORD, Extractor.ODF, "odt", "ott")
        reg(FormatKind.EXCEL, Extractor.OOXML, "xlsx", "xlsm", "xltx")
        reg(FormatKind.EXCEL, Extractor.NONE, "xls", "numbers")
        reg(FormatKind.EXCEL, Extractor.ODF, "ods", "ots")
        reg(FormatKind.POWERPOINT, Extractor.OOXML, "pptx", "pptm", "ppsx", "potx")
        reg(FormatKind.POWERPOINT, Extractor.NONE, "ppt", "pps", "key")
        reg(FormatKind.POWERPOINT, Extractor.ODF, "odp", "otp")
        reg(FormatKind.TEXT, Extractor.NONE, "txt", "md", "markdown", "log", "nfo", "readme")
        reg(FormatKind.CSV, Extractor.NONE, "csv", "tsv")
        reg(FormatKind.WEB, Extractor.NONE, "html", "htm", "xhtml", "mht", "mhtml", "css")
        reg(FormatKind.DATA, Extractor.NONE, "json", "xml", "jsonl", "ndjson", "geojson", "gpx", "kml", "plist")
        reg(FormatKind.EBOOK, Extractor.EPUB, "epub")
        reg(FormatKind.EBOOK, Extractor.NONE, "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
        reg(FormatKind.ARCHIVE, Extractor.NONE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz", "lzma", "cab", "jar", "war")
        reg(FormatKind.FONT, Extractor.FONT, "ttf", "otf", "ttc")
        reg(FormatKind.FONT, Extractor.NONE, "woff", "woff2", "fon")
        reg(FormatKind.DESIGN, Extractor.NONE, "psd", "ai", "sketch", "fig", "xd", "xcf", "afdesign", "afphoto", "indd", "cdr", "blend", "obj", "fbx", "stl", "dwg", "dxf")
        reg(FormatKind.DATABASE, Extractor.NONE, "db", "sqlite", "sqlite3", "db3", "realm", "mdb", "accdb", "sql", "dbf")
        reg(FormatKind.CONFIG, Extractor.NONE, "ini", "conf", "cfg", "config", "yaml", "yml", "toml", "properties", "env", "reg", "prefs")
        reg(FormatKind.CODE, Extractor.NONE, "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift",
            "rb", "php", "pl", "lua", "dart", "scala", "r", "m", "sh", "bash", "zsh", "ps1", "bat", "cmd", "gradle", "vue", "svelte", "smali")
        reg(FormatKind.DISK_IMAGE, Extractor.NONE, "iso", "img", "dmg", "vhd", "vhdx", "vmdk", "qcow2", "bin", "cue", "wim")
        reg(FormatKind.CERTIFICATE, Extractor.NONE, "pem", "crt", "cer", "der", "p12", "pfx", "key", "csr", "jks", "keystore", "bks", "p7b", "gpg", "asc", "pub")
        reg(FormatKind.SUBTITLE, Extractor.NONE, "srt", "ass", "ssa", "vtt", "sub", "sbv")
        reg(FormatKind.EXECUTABLE, Extractor.NONE, "exe", "msi", "deb", "rpm", "appimage", "run", "elf", "so", "dll", "dex", "aab")
        reg(FormatKind.TORRENT, Extractor.NONE, "torrent", "magnet")
    }

    fun of(name: String, isDir: Boolean = false): FileFormat {
        if (isDir) return FileFormat(FormatKind.FOLDER, "", Extractor.NONE)
        val lower = name.lowercase()
        // Compound extensions first (archive.tar.gz).
        val ext = when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.zst") -> "tar"
            else -> lower.substringAfterLast('.', "")
        }
        val e = map[ext] ?: return FileFormat(FormatKind.UNKNOWN, ext.uppercase().take(5), Extractor.NONE)
        return FileFormat(e.first, ext.uppercase().take(5), e.second)
    }

    // ------------------------------------------------------------------ cache identity

    /** Cache key changes whenever the file's path, size or modification time changes. */
    fun cacheKey(path: String, size: Long, modified: Long, px: Int): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update("v1|$path|$size|$modified|$px".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ container parsing helpers

    /** Embedded preview inside OOXML (docProps/thumbnail.*) or ODF (Thumbnails/thumbnail.png). */
    fun documentThumbnailEntry(entries: Collection<String>): String? {
        val lower = entries.associateBy { it.lowercase() }
        return listOf("thumbnails/thumbnail.png", "docprops/thumbnail.jpeg", "docprops/thumbnail.jpg", "docprops/thumbnail.png", "docprops/thumbnail.emf.png")
            .firstNotNullOfOrNull { lower[it] }
            ?: entries.firstOrNull { val l = it.lowercase(); l.startsWith("docprops/thumbnail.") && (l.endsWith(".jpeg") || l.endsWith(".jpg") || l.endsWith(".png")) }
    }

    /** For XAPK/APKS/APKM: prefer a bundled icon.png, else the base (non-split, non-config) APK. */
    fun bundleIconEntry(entries: Collection<String>): String? =
        entries.firstOrNull { it.equals("icon.png", true) } ?: entries.firstOrNull { it.lowercase().endsWith("/icon.png") && it.count { c -> c == '/' } == 1 }

    fun bundleBaseApk(entries: Collection<String>, sizes: Map<String, Long> = emptyMap()): String? {
        val apks = entries.filter { it.lowercase().endsWith(".apk") && !it.contains("..") }
        apks.firstOrNull { it.substringAfterLast('/').equals("base.apk", true) }?.let { return it }
        val nonSplit = apks.filterNot { val n = it.substringAfterLast('/').lowercase(); n.startsWith("split_") || n.startsWith("config.") || n.contains(".config.") }
        return (nonSplit.ifEmpty { apks }).maxByOrNull { sizes[it] ?: 0L }
    }

    /** Path of the OPF package document from META-INF/container.xml. */
    fun epubOpfPath(containerXml: String): String? =
        Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)

    /** Cover image path (relative to the archive root) from an OPF document, EPUB 3 or EPUB 2 style. */
    fun epubCoverPath(opfPath: String, opf: String): String? {
        val items = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()
        fun attr(tag: String, a: String) = Regex("""\b$a\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        val href = items.firstOrNull { attr(it, "properties")?.split(' ')?.contains("cover-image") == true }?.let { attr(it, "href") }
            ?: run {
                val metaId = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }
                    .firstOrNull { attr(it, "name") == "cover" }?.let { attr(it, "content") }
                metaId?.let { id -> items.firstOrNull { attr(it, "id") == id }?.let { attr(it, "href") } }
            }
            ?: items.firstOrNull { val id = attr(it, "id")?.lowercase() ?: ""; val mt = attr(it, "media-type") ?: ""; mt.startsWith("image/") && id.contains("cover") }?.let { attr(it, "href") }
            ?: return null
        val base = opfPath.substringBeforeLast('/', "")
        return normalize(if (base.isEmpty()) href else "$base/$href")
    }

    private fun normalize(p: String): String? {
        val out = ArrayList<String>()
        for (seg in java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8").split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
            else -> out += seg
        }
        return out.joinToString("/")
    }
}
