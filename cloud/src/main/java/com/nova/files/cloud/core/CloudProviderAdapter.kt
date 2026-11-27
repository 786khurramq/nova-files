package com.nova.files.cloud.core

import okhttp3.OkHttpClient
import java.io.InputStream

/**
 * Everything a provider call needs, bound to exactly one account. Providers never look up a
 * "current" account: the session passed in is the only identity they act for.
 */
class AccountSession(
    val accountId: String,
    val providerId: ProviderId,
    /** HTTP client that authenticates as this account only. */
    val http: OkHttpClient,
    /** Provider-specific non-secret configuration (e.g. WebDAV base URL). */
    val config: Map<String, String> = emptyMap(),
)

/** Supplies upload bytes from any offset (for resumable sessions) without loading the file into memory. */
fun interface ByteSource { fun open(offset: Long): InputStream }

/**
 * Common provider contract. Implementations declare [capabilities]; every call they don't support
 * keeps the default, which throws [CloudException.Unsupported] instead of failing in surprising ways.
 */
interface CloudProviderAdapter {
    val providerId: ProviderId
    val capabilities: Set<Operation>

    /** Item-specific check. The UI uses this to decide which actions to show. */
    fun canExecute(item: CloudFile?, op: Operation): CapabilityResult = Capabilities.check(this, item, op)

    suspend fun profile(session: AccountSession): AccountProfile
    suspend fun list(session: AccountSession, folderId: String?, cursor: String?): Page

    suspend fun metadata(session: AccountSession, id: String): CloudFile = unsupported(Operation.METADATA)
    suspend fun search(session: AccountSession, query: String, cursor: String?): Page = unsupported(Operation.SEARCH)
    suspend fun createFolder(session: AccountSession, parentId: String?, name: String, conflict: ConflictPolicy): CloudFile = unsupported(Operation.CREATE_FOLDER)
    suspend fun rename(session: AccountSession, item: CloudFile, newName: String): CloudFile = unsupported(Operation.RENAME)
    suspend fun move(session: AccountSession, item: CloudFile, newParentId: String?): CloudFile = unsupported(Operation.MOVE)
    suspend fun copy(session: AccountSession, item: CloudFile, newParentId: String?, newName: String?): CloudFile = unsupported(Operation.SERVER_COPY)
    suspend fun delete(session: AccountSession, item: CloudFile): Unit = unsupported(Operation.DELETE)
    suspend fun quota(session: AccountSession): Quota? = null
    suspend fun download(session: AccountSession, item: CloudFile, offset: Long, export: ExportFormat?): DownloadStream = unsupported(Operation.DOWNLOAD)

    /**
     * Uploads [size] bytes (null = unknown) into [parentId]. Resumable providers report progress through
     * [onCheckpoint] so the engine can persist it, and accept [resumeFrom] to continue a valid session.
     */
    suspend fun upload(
        session: AccountSession, parentId: String?, name: String, size: Long?, mimeType: String?,
        source: ByteSource, conflict: ConflictPolicy, resumeFrom: UploadCheckpoint?,
        onProgress: (Long) -> Unit, onCheckpoint: (UploadCheckpoint) -> Unit,
    ): CloudFile = unsupported(Operation.UPLOAD)

    /** Small provider-native thumbnail bytes at roughly [px] pixels, or null if none exists. */
    suspend fun thumbnail(session: AccountSession, item: CloudFile, px: Int): ByteArray? = null
    suspend fun changes(session: AccountSession, cursor: String?): ChangeSet = unsupported(Operation.CHANGE_TRACKING)

    private fun unsupported(op: Operation): Nothing = throw CloudException.Unsupported(op, "${providerId.label} doesn't support ${op.name.lowercase().replace('_', ' ')}")
}

object Capabilities {
    fun check(p: CloudProviderAdapter, item: CloudFile?, op: Operation): CapabilityResult {
        if (op !in p.capabilities) return CapabilityResult.Unsupported("${p.providerId.label} doesn't support this")
        item ?: return CapabilityResult.Supported
        return when {
            op == Operation.DOWNLOAD && item.kind == ItemKind.NATIVE_DOCUMENT ->
                if (item.exportFormats.isNotEmpty()) CapabilityResult.RequiresExport(item.exportFormats, "${item.name} is a ${p.providerId.label} document and must be exported")
                else CapabilityResult.Unsupported("${item.name} can't be downloaded or exported")
            op == Operation.DOWNLOAD && item.kind == ItemKind.SPECIAL -> CapabilityResult.Unsupported("${item.name} is a special ${p.providerId.label} item without downloadable content")
            op in setOf(Operation.DOWNLOAD, Operation.THUMBNAIL, Operation.RESUMABLE_DOWNLOAD) && item.isFolder ->
                CapabilityResult.Unsupported("Folders are transferred file by file")
            op == Operation.UPLOAD && !item.isFolder -> CapabilityResult.Unsupported("Uploads go into a folder")
            op == Operation.THUMBNAIL && item.thumbnailRef == null && item.extra["hasThumbnail"] != "true" ->
                CapabilityResult.Unsupported("No preview available")
            else -> CapabilityResult.Supported
        }
    }
}
