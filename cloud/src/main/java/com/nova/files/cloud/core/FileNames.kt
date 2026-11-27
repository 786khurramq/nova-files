package com.nova.files.cloud.core

import java.text.Normalizer

/** Destination filesystems with different naming rules. */
enum class NameTarget { ANDROID, FAT, CLOUD_GENERIC }

/**
 * Unicode NFC normalisation and destination-specific sanitising. The original name is kept by the
 * caller (transfer record) whenever this changes it.
 */
object FileNames {
    private val windowsReserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private val fatIllegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val androidIllegal = Regex("[/\\u0000]")

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    fun sanitize(name: String, target: NameTarget, maxBytes: Int = 255): String {
        var n = nfc(name)
        n = when (target) {
            NameTarget.FAT -> n.replace(fatIllegal, "_")
            NameTarget.ANDROID -> n.replace(androidIllegal, "_").replace(Regex("[\\u0001-\\u001F\\u007F]"), "_")
            NameTarget.CLOUD_GENERIC -> n.replace(fatIllegal, "_")
        }
        // Trailing dots/spaces are invalid on FAT/exFAT/Windows and confuse several providers.
        n = n.trim().trimEnd('.', ' ')
        if (n.isEmpty() || n == "." || n == "..") n = "_"
        if (target != NameTarget.ANDROID && n.substringBefore('.').uppercase() in windowsReserved) n = "_$n"
        return truncateUtf8(n, maxBytes)
    }

    /** Case-insensitive comparison for destinations like FAT/exFAT and most cloud providers. */
    fun sameName(a: String, b: String, caseSensitive: Boolean): Boolean =
        if (caseSensitive) nfc(a) == nfc(b) else nfc(a).lowercase() == nfc(b).lowercase()

    /** "name (1).ext", "name (2).ext"… until [exists] returns false. */
    fun keepBoth(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var i = 1
        while (true) { val c = "$base ($i)$ext"; if (!exists(c)) return c; i++ }
    }

    private fun truncateUtf8(s: String, max: Int): String {
        if (s.toByteArray().size <= max) return s
        val ext = s.substringAfterLast('.', "").let { if (it.length in 1..12) ".$it" else "" }
        var base = s.removeSuffix(ext)
        while ((base + ext).toByteArray().size > max && base.isNotEmpty()) base = base.dropLast(1)
        return base + ext
    }
}
