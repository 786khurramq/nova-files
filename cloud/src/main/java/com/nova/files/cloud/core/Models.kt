package com.nova.files.cloud.core

import kotlinx.serialization.Serializable

/** Supported providers. Adding one means adding an entry and a [CloudProviderAdapter]. */
enum class ProviderId(val label: String) {
    GOOGLE_DRIVE("Google Drive"), DROPBOX("Dropbox"), ONEDRIVE("OneDrive"), MEGA("MEGA"), WEBDAV("WebDAV");
}

/** Every operation the UI may offer. Upload and download resumability are separate on purpose. */
enum class Operation {
    BROWSE, SEARCH, UPLOAD, DOWNLOAD, CREATE_FOLDER, RENAME, MOVE, COPY, DELETE, METADATA, QUOTA,
    RESUMABLE_UPLOAD, RESUMABLE_DOWNLOAD, THUMBNAIL, SERVER_COPY, CHANGE_TRACKING,
}

/** What an item is, as far as available operations are concerned. */
enum class ItemKind {
    FILE, FOLDER,
    /** Provider-native document without a binary body (Google Docs/Sheets/Slides). Needs export. */
    NATIVE_DOCUMENT,
    /** Bundles such as OneDrive packages / Dropbox Paper whose content is not an ordinary file. */
    SPECIAL,
}

/** Hash algorithms providers report. Different algorithms are never compared with each other. */
enum class HashAlgorithm { MD5, SHA1, SHA256, DROPBOX_CONTENT_HASH, QUICK_XOR, CRC32 }

@Serializable
data class ExportFormat(val mimeType: String, val extension: String, val label: String)

/** Provider-neutral metadata. Identity is (accountId, id); never the display name or path. */
data class CloudFile(
    val accountId: String,
    val providerId: ProviderId,
    /** Immutable provider id: Drive fileId, Dropbox id, "driveId!itemId" for OneDrive, MEGA handle, normalised WebDAV path. */
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String?,
    val kind: ItemKind,
    val size: Long?,
    val created: Long?,
    val modified: Long?,
    /** ETag / revision used for caching and conditional requests. */
    val revision: String?,
    val hashes: Map<HashAlgorithm, String> = emptyMap(),
    val thumbnailRef: String? = null,
    val exportFormats: List<ExportFormat> = emptyList(),
    /** Provider-specific values preserved without forcing them into the common model. */
    val extra: Map<String, String> = emptyMap(),
) {
    val isFolder get() = kind == ItemKind.FOLDER
}

data class Page(val items: List<CloudFile>, val nextCursor: String?)

data class Quota(val usedBytes: Long?, val totalBytes: Long?, val trashBytes: Long? = null)

data class AccountProfile(
    val providerAccountId: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String? = null,
)

data class ChangeSet(val changed: List<CloudFile>, val removedIds: List<String>, val nextCursor: String, val hasMore: Boolean)

/** A download body. [length] is null when the provider doesn't report it. */
class DownloadStream(val stream: java.io.InputStream, val length: Long?, val startOffset: Long)

/** Provider resume point for an upload: session id/URL plus confirmed byte offset. */
@Serializable
data class UploadCheckpoint(val sessionId: String, val confirmedBytes: Long, val expiresAt: Long? = null, val extra: Map<String, String> = emptyMap())

/** How to treat an existing destination item. ASK is resolved by the UI before a job runs. */
enum class ConflictPolicy { ASK, KEEP_BOTH, REPLACE, SKIP }

/** Typed result of an item-specific capability check. */
sealed class CapabilityResult {
    data object Supported : CapabilityResult()
    data class Unsupported(val reason: String) : CapabilityResult()
    data class RequiresExport(val formats: List<ExportFormat>, val reason: String) : CapabilityResult()
    val allowed get() = this is Supported
}
