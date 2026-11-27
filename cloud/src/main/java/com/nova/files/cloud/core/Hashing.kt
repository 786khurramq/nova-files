package com.nova.files.cloud.core

import java.io.InputStream
import java.security.MessageDigest

/**
 * Dropbox content_hash: SHA-256 over the concatenated SHA-256 digests of each 4 MiB block.
 * https://www.dropbox.com/developers/reference/content-hash
 */
class DropboxContentHasher {
    private val overall = MessageDigest.getInstance("SHA-256")
    private var block = MessageDigest.getInstance("SHA-256")
    private var blockPos = 0

    fun update(b: ByteArray, off: Int, len: Int) {
        var o = off; var left = len
        while (left > 0) {
            val n = minOf(left, BLOCK - blockPos)
            block.update(b, o, n); blockPos += n; o += n; left -= n
            if (blockPos == BLOCK) { overall.update(block.digest()); block = MessageDigest.getInstance("SHA-256"); blockPos = 0 }
        }
    }

    fun digestHex(): String {
        if (blockPos > 0) overall.update(block.digest())
        return overall.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val BLOCK = 4 * 1024 * 1024 }
}

/** Computes several provider-compatible hashes in one pass. Unknown algorithms are ignored. */
class MultiHasher(algorithms: Collection<HashAlgorithm>) {
    private val digests = algorithms.mapNotNull { a ->
        when (a) {
            HashAlgorithm.MD5 -> a to MessageDigest.getInstance("MD5")
            HashAlgorithm.SHA1 -> a to MessageDigest.getInstance("SHA-1")
            HashAlgorithm.SHA256 -> a to MessageDigest.getInstance("SHA-256")
            else -> null
        }
    }.toMap()
    private val dropbox = if (HashAlgorithm.DROPBOX_CONTENT_HASH in algorithms) DropboxContentHasher() else null

    fun update(b: ByteArray, off: Int, len: Int) {
        digests.values.forEach { it.update(b, off, len) }
        dropbox?.update(b, off, len)
    }

    fun results(): Map<HashAlgorithm, String> =
        digests.mapValues { (_, d) -> d.digest().joinToString("") { "%02x".format(it) } } +
            (dropbox?.let { mapOf(HashAlgorithm.DROPBOX_CONTENT_HASH to it.digestHex()) } ?: emptyMap())

    /** Hashes the first [length] bytes of [input] (used to cover an already-uploaded prefix when resuming). */
    fun consume(input: InputStream, length: Long) {
        val buf = ByteArray(1 shl 20); var left = length
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw CloudException.Integrity("Source changed while resuming – restart needed")
            update(buf, 0, r); left -= r
        }
    }

    companion object {
        /** Compares only algorithms both sides have; different algorithms are never treated as equivalent. */
        fun mismatch(expected: Map<HashAlgorithm, String>, actual: Map<HashAlgorithm, String>): HashAlgorithm? =
            expected.entries.firstOrNull { (alg, v) -> actual[alg]?.let { !it.equals(v, ignoreCase = true) } == true }?.key
    }
}

/** InputStream wrapper that feeds a [MultiHasher] and counts bytes. */
class HashingInputStream(private val inner: InputStream, private val hasher: MultiHasher?, private val onBytes: (Int) -> Unit = {}) : InputStream() {
    override fun read(): Int { val b = ByteArray(1); val r = read(b, 0, 1); return if (r < 0) -1 else b[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = inner.read(b, off, len)
        if (r > 0) { hasher?.update(b, off, r); onBytes(r) }
        return r
    }
    override fun close() = inner.close()
}
