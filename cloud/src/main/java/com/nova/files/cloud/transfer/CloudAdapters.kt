package com.nova.files.cloud.transfer

import android.content.Context
import android.webkit.MimeTypeMap
import com.nova.files.cloud.accounts.AccountRepository
import com.nova.files.cloud.core.*
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/** A cloud file as a transfer source. Native documents are exported in the chosen format. */
class CloudSource(
    private val adapter: CloudProviderAdapter,
    private val session: AccountSession,
    private val file: CloudFile,
    private val export: ExportFormat?,
) : TransferSource {
    override val name: String = if (export != null && !file.name.endsWith(".${export.extension}", true)) "${file.name}.${export.extension}" else file.name
    override val size: Long? = if (file.kind == ItemKind.FILE) file.size else null
    override val supportsOffset = file.kind == ItemKind.FILE && Operation.RESUMABLE_DOWNLOAD in adapter.capabilities
    /** Provider checksums apply to the stored bytes, never to an export. */
    override val knownHashes: Map<HashAlgorithm, String> = if (file.kind == ItemKind.FILE) file.hashes else emptyMap()

    override fun open(offset: Long): InputStream = runBlocking {
        val d = adapter.download(session, file, offset, export)
        if (offset > 0 && d.startOffset == 0L) d.stream.also { skipFully(it, offset) } else d.stream
    }
}

/** A cloud folder as an upload destination; resumability comes from the provider's upload sessions. */
class CloudUploadDestination(
    private val adapter: CloudProviderAdapter,
    private val session: AccountSession,
    private val parentId: String?,
) : UploadDestination {
    override val supportsResume = Operation.RESUMABLE_UPLOAD in adapter.capabilities

    override suspend fun upload(source: TransferSource, name: String, conflict: ConflictPolicy, checkpoint: String?,
                                onProgress: (Long) -> Unit, onCheckpoint: (String) -> Unit): CommittedResult {
        val cp = checkpoint?.let { runCatching { EndpointJson.decodeFromString(UploadCheckpoint.serializer(), it) }.getOrNull() }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val f = adapter.upload(session, parentId, name, source.size, mime, ByteSource { source.open(it) }, conflict, cp,
            onProgress, onCheckpoint = { onCheckpoint(EndpointJson.encodeToString(UploadCheckpoint.serializer(), it)) })
        return CommittedResult("cloud:${session.accountId}:${f.id}", f.name, f.hashes[HashAlgorithm.SHA256], skipped = f.extra["skipped"] == "true")
    }
}

/** Local + cloud adapters. Every cloud endpoint resolves to a session for ITS account only. */
class CloudEndpointResolver(ctx: Context, private val accounts: AccountRepository) : LocalEndpointResolver(ctx) {
    private fun adapterFor(e: Endpoint.Cloud): CloudProviderAdapter =
        accounts.provider(ProviderId.valueOf(e.provider)) ?: throw CloudException.Unsupported(Operation.BROWSE, "${e.provider} isn't available")

    override fun source(e: Endpoint): TransferSource = if (e is Endpoint.Cloud) runBlocking {
        val adapter = adapterFor(e)
        val session = accounts.session(e.accountId)
        val file = adapter.metadata(session, e.fileId ?: throw CloudException.NotFound())
        val export = e.exportMime?.let { m -> file.exportFormats.firstOrNull { it.mimeType == m } }
        when (val c = adapter.canExecute(file, Operation.DOWNLOAD)) {
            is CapabilityResult.Unsupported -> throw CloudException.Unsupported(Operation.DOWNLOAD, c.reason)
            is CapabilityResult.RequiresExport -> if (export == null) throw CloudException.Unsupported(Operation.DOWNLOAD, c.reason)
            else -> {}
        }
        CloudSource(adapter, session, file, export)
    } else super.source(e)

    override fun destination(e: Endpoint): TransferDestination = if (e is Endpoint.Cloud) runBlocking {
        CloudUploadDestination(adapterFor(e), accounts.session(e.accountId), e.parentId)
    } else super.destination(e)
}
