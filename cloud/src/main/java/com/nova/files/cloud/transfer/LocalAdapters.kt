package com.nova.files.cloud.transfer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.core.FileNames
import com.nova.files.cloud.core.NameTarget
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class LocalFileSource(private val file: File) : TransferSource {
    override val name: String = file.name
    override val size: Long? = file.length()
    override val supportsOffset = true
    override fun open(offset: Long): InputStream = file.inputStream().also { if (offset > 0) skipFully(it, offset) }
}

/** content:// document. Offsets are supported by skipping, which is correct for any stream. */
class ContentUriSource(private val ctx: Context, private val uri: Uri, fallbackName: String?) : TransferSource {
    override val name: String
    override val size: Long?
    init {
        var n: String? = null; var s: Long? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { if (!c.isNull(0)) n = c.getString(0); if (!c.isNull(1)) s = c.getLong(1) }
            }
        }
        name = n ?: fallbackName ?: uri.lastPathSegment ?: "file"
        size = s
    }
    override val supportsOffset = true
    override fun open(offset: Long): InputStream =
        (ctx.contentResolver.openInputStream(uri) ?: throw CloudException.NotFound("The other app no longer allows access")).also { if (offset > 0) skipFully(it, offset) }
}

internal fun skipFully(i: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(64 * 1024)
    while (left > 0) {
        val s = i.skip(left)
        if (s > 0) { left -= s; continue }
        val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw CloudException.Integrity("Source is shorter than the saved progress – restart needed")
        left -= r
    }
}

/**
 * Writes into a folder: data goes to a hidden ".name.novapart" file that is renamed into place only
 * after the whole file arrived. Existing files are never replaced unless the policy says REPLACE.
 */
class LocalDirDestination(private val dir: File, private val target: NameTarget = NameTarget.ANDROID) : TransferDestination {
    override val supportsResume = true

    @Serializable private data class Checkpoint(val partPath: String, val finalName: String)

    override fun prepare(name: String, size: Long?, conflict: ConflictPolicy, checkpoint: String?): DestinationWriter? {
        if (!dir.exists() && !dir.mkdirs()) throw CloudException.Forbidden("Can't create folder ${dir.path}")
        val cp = checkpoint?.let { runCatching { EndpointJson.decodeFromString(Checkpoint.serializer(), it) }.getOrNull() }
        val part = cp?.partPath?.let { File(it) }?.takeIf { it.exists() && it.parentFile == dir }
        val clean = FileNames.sanitize(name, target)
        val finalName = cp?.finalName ?: run {
            val exists = { n: String -> File(dir, n).exists() }
            if (exists(clean)) when (conflict) {
                ConflictPolicy.SKIP -> return null
                ConflictPolicy.ASK -> throw CloudException.Conflict(clean)
                ConflictPolicy.REPLACE -> clean
                ConflictPolicy.KEEP_BOTH -> FileNames.keepBoth(clean, exists)
            } else clean
        }
        val partFile = part ?: File(dir, ".$finalName.novapart")
        val start = if (part != null) part.length() else { partFile.delete(); 0L }
        if (size != null) {
            val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(dir.usableSpace)
            val needed = size - start
            if (needed > 0 && needed + 16L * 1024 * 1024 > free) throw CloudException.InsufficientStorage(needed, free)
        }
        val out = FileOutputStream(partFile, start > 0)
        return object : DestinationWriter {
            override val startOffset = start
            override val stream: OutputStream = out
            override fun checkpoint() = EndpointJson.encodeToString(Checkpoint.serializer(), Checkpoint(partFile.path, finalName))
            override fun commit(): CommittedResult {
                out.flush(); out.fd.sync(); out.close()
                val dest = File(dir, finalName)
                if (dest.exists() && conflict != ConflictPolicy.REPLACE) {
                    // Something appeared meanwhile: never overwrite silently.
                    val alt = File(dir, FileNames.keepBoth(finalName) { File(dir, it).exists() })
                    if (!partFile.renameTo(alt)) throw CloudException.Forbidden("Couldn't save ${alt.name}")
                    return CommittedResult(alt.path, alt.name)
                }
                if (dest.exists()) dest.delete()
                if (!partFile.renameTo(dest)) throw CloudException.Forbidden("Couldn't save ${dest.name}")
                return CommittedResult(dest.path, dest.name)
            }
            override fun abort(keepPartial: Boolean) {
                runCatching { out.close() }
                if (!keepPartial) partFile.delete()
            }
        }
    }
}

/** Adapters for local endpoints; cloud adapters are added on top in later phases. */
open class LocalEndpointResolver(private val ctx: Context) : EndpointResolver {
    override fun source(e: Endpoint): TransferSource = when (e) {
        is Endpoint.Local -> LocalFileSource(File(e.path))
        is Endpoint.ContentUri -> ContentUriSource(ctx, Uri.parse(e.uri), e.displayName)
        else -> throw CloudException.Unsupported(com.nova.files.cloud.core.Operation.DOWNLOAD, "This source type isn't available yet")
    }
    override fun destination(e: Endpoint): TransferDestination = when (e) {
        is Endpoint.Local -> LocalDirDestination(File(e.path))
        else -> throw CloudException.Unsupported(com.nova.files.cloud.core.Operation.UPLOAD, "This destination type isn't available yet")
    }
}
