package com.nova.files.cloud.providers.google

import com.nova.files.cloud.auth.OAuthConfig
import com.nova.files.cloud.core.*
import com.nova.files.cloud.net.HttpErrors
import com.nova.files.cloud.providers.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Google Drive API v3 with the least-privilege `drive.file` scope: Nova Files can see and manage the
 * files and folders it created or uploaded – not the rest of the user's Drive. Identity is the
 * immutable Drive fileId. Google Docs/Sheets/Slides have no binary content and are exported.
 */
class GoogleDriveProvider(
    private val api: String = "https://www.googleapis.com/drive/v3",
    private val upload: String = "https://www.googleapis.com/upload/drive/v3",
    private val chunkBytes: Int = 8 * 1024 * 1024,           // multiple of 256 KiB as Drive requires
) : CloudProviderAdapter {
    override val providerId = ProviderId.GOOGLE_DRIVE
    override val capabilities = setOf(
        Operation.BROWSE, Operation.SEARCH, Operation.UPLOAD, Operation.DOWNLOAD, Operation.CREATE_FOLDER, Operation.RENAME,
        Operation.MOVE, Operation.COPY, Operation.SERVER_COPY, Operation.DELETE, Operation.METADATA, Operation.QUOTA,
        Operation.RESUMABLE_UPLOAD, Operation.RESUMABLE_DOWNLOAD, Operation.THUMBNAIL, Operation.CHANGE_TRACKING,
    )

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val FOLDER = "application/vnd.google-apps.folder"
        fun oauthConfig(clientId: String, packageName: String) = OAuthConfig(
            providerId = ProviderId.GOOGLE_DRIVE,
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = clientId,
            redirectUri = "$packageName:/oauth2redirect",
            scopes = listOf(SCOPE),
            // offline + consent: Google only issues a refresh token when asked for offline access.
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent select_account"),
            revokeEndpoint = "https://oauth2.googleapis.com/revoke",
        )
        private const val FILE_FIELDS = "id,name,mimeType,parents,size,createdTime,modifiedTime,md5Checksum,sha256Checksum,version,hasThumbnail,thumbnailLink,capabilities(canDownload,canRename,canTrash,canAddChildren,canCopy)"

        val EXPORTS = mapOf(
            "application/vnd.google-apps.document" to listOf(ExportFormat("application/pdf", "pdf", "PDF"), ExportFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Word")),
            "application/vnd.google-apps.spreadsheet" to listOf(ExportFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", "Excel"), ExportFormat("application/pdf", "pdf", "PDF")),
            "application/vnd.google-apps.presentation" to listOf(ExportFormat("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx", "PowerPoint"), ExportFormat("application/pdf", "pdf", "PDF")),
            "application/vnd.google-apps.drawing" to listOf(ExportFormat("image/png", "png", "PNG"), ExportFormat("application/pdf", "pdf", "PDF")),
        )

        /** Escapes a value for a Drive query string literal. */
        fun q(v: String) = v.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun check(r: Response, s: AccountSession): Response {
        if (r.isSuccessful) return r
        val body = runCatching { r.peekBody(8192).string() }.getOrDefault("")
        r.close()
        throw when {
            r.code == 403 && (body.contains("storageQuotaExceeded") || body.contains("quotaExceeded\"")) -> CloudException.QuotaExceeded()
            r.code == 403 && (body.contains("userRateLimitExceeded") || body.contains("rateLimitExceeded")) -> CloudException.RateLimited(HttpErrors.parseRetryAfter(r.header("Retry-After")) ?: 2000)
            r.code == 403 && body.contains("insufficientFilePermissions") -> CloudException.Forbidden("Nova Files can only access files it created or that you opened with it")
            r.code == 403 && body.contains("exportSizeLimitExceeded") -> CloudException.PayloadTooLarge("This document is too large for Google to export")
            r.code == 404 -> CloudException.NotFound("Not found – it may have been deleted, or it isn't a file Nova Files can access")
            else -> HttpErrors.map(r.code, r.header("Retry-After"), body, s.accountId)
        }
    }

    private suspend fun get(s: AccountSession, url: okhttp3.HttpUrl): JsonObject = check(s.http.exec(Request.Builder().url(url).build()), s).json()

    private fun url(path: String, vararg params: Pair<String, String?>) = "$api/$path".toHttpUrl().newBuilder().apply {
        params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
    }.build()

    private fun parse(s: AccountSession, f: JsonObject): CloudFile {
        val mime = f.str("mimeType")
        val caps = f.obj("capabilities")
        val native = mime?.startsWith("application/vnd.google-apps.") == true && mime != FOLDER
        val exports = EXPORTS[mime] ?: emptyList()
        return CloudFile(
            accountId = s.accountId, providerId = providerId,
            id = f.str("id") ?: "?", parentId = f["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            name = f.str("name") ?: "?", mimeType = mime,
            kind = when { mime == FOLDER -> ItemKind.FOLDER; native && exports.isNotEmpty() -> ItemKind.NATIVE_DOCUMENT; native -> ItemKind.SPECIAL; else -> ItemKind.FILE },
            size = f.long("size"), created = iso(f.str("createdTime")), modified = iso(f.str("modifiedTime")),
            revision = f.str("version"),
            hashes = buildMap {
                f.str("md5Checksum")?.let { put(HashAlgorithm.MD5, it) }
                f.str("sha256Checksum")?.let { put(HashAlgorithm.SHA256, it) }
            },
            thumbnailRef = f.str("thumbnailLink")?.takeIf { f.bool("hasThumbnail") != false },
            exportFormats = exports,
            extra = buildMap {
                caps?.bool("canRename")?.let { put("canRename", it.toString()) }
                caps?.bool("canTrash")?.let { put("canTrash", it.toString()) }
                caps?.bool("canDownload")?.let { put("canDownload", it.toString()) }
                if (native && exports.isEmpty()) put("note", "This Google item type can't be downloaded or exported")
            },
        )
    }

    // ------------------------------------------------------------------ account

    override suspend fun profile(session: AccountSession): AccountProfile {
        val u = get(session, url("about", "fields" to "user(displayName,emailAddress,permissionId,photoLink)")).obj("user")
            ?: throw CloudException.Http(200, "Google didn't return the account")
        return AccountProfile(u.str("permissionId") ?: throw CloudException.Http(200, "Google didn't return an account id"),
            u.str("displayName") ?: u.str("emailAddress") ?: "Google Drive", u.str("emailAddress"), u.str("photoLink"))
    }

    override suspend fun quota(session: AccountSession): Quota {
        val q = get(session, url("about", "fields" to "storageQuota(limit,usage,usageInDriveTrash)")).obj("storageQuota")
        return Quota(q?.long("usage"), q?.long("limit"), q?.long("usageInDriveTrash"))
    }

    override suspend fun revoke(session: AccountSession, refreshToken: String?) {
        refreshToken ?: return
        runCatching {
            session.http.exec(Request.Builder().url("https://oauth2.googleapis.com/revoke".toHttpUrl().newBuilder().addQueryParameter("token", refreshToken).build())
                .post("".toRequestBody(null)).build()).close()
        }
    }

    // ------------------------------------------------------------------ browse

    override suspend fun list(session: AccountSession, folderId: String?, cursor: String?): Page {
        val o = get(session, url("files",
            "q" to "'${q(folderId ?: "root")}' in parents and trashed = false",
            "fields" to "nextPageToken,files($FILE_FIELDS)", "pageSize" to "200", "orderBy" to "folder,name_natural",
            "pageToken" to cursor, "spaces" to "drive"))
        return Page(o["files"]?.jsonArray?.mapNotNull { it.asObj() }?.map { parse(session, it) } ?: emptyList(), o.str("nextPageToken"))
    }

    override suspend fun metadata(session: AccountSession, id: String): CloudFile = parse(session, get(session, url("files/$id", "fields" to FILE_FIELDS)))

    override suspend fun search(session: AccountSession, query: String, cursor: String?): Page {
        val o = get(session, url("files", "q" to "name contains '${q(query)}' and trashed = false",
            "fields" to "nextPageToken,files($FILE_FIELDS)", "pageSize" to "100", "pageToken" to cursor))
        return Page(o["files"]?.jsonArray?.mapNotNull { it.asObj() }?.map { parse(session, it) } ?: emptyList(), o.str("nextPageToken"))
    }

    override suspend fun changes(session: AccountSession, cursor: String?): ChangeSet {
        if (cursor == null) return ChangeSet(emptyList(), emptyList(), get(session, url("changes/startPageToken")).str("startPageToken")!!, false)
        val o = get(session, url("changes", "pageToken" to cursor, "fields" to "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS,trashed))"))
        val ch = o["changes"]?.jsonArray?.mapNotNull { it.asObj() } ?: emptyList()
        val removed = ch.filter { it.bool("removed") == true || it.obj("file")?.bool("trashed") == true }.mapNotNull { it.str("fileId") }
        val changed = ch.filter { it.bool("removed") != true && it.obj("file")?.bool("trashed") != true }.mapNotNull { it.obj("file") }.map { parse(session, it) }
        return ChangeSet(changed, removed, o.str("nextPageToken") ?: o.str("newStartPageToken") ?: cursor, o.str("nextPageToken") != null)
    }

    /** Drive allows duplicate names, so conflicts are detected explicitly. */
    private suspend fun existing(s: AccountSession, parentId: String?, name: String): List<CloudFile> =
        get(s, url("files", "q" to "name = '${q(name)}' and '${q(parentId ?: "root")}' in parents and trashed = false", "fields" to "files($FILE_FIELDS)"))["files"]
            ?.jsonArray?.mapNotNull { it.asObj() }?.map { parse(s, it) } ?: emptyList()

    private suspend fun resolveName(s: AccountSession, parentId: String?, name: String, conflict: ConflictPolicy): Pair<String, CloudFile?> {
        val clash = existing(s, parentId, name).firstOrNull() ?: return name to null
        return when (conflict) {
            ConflictPolicy.ASK -> throw CloudException.Conflict(name)
            ConflictPolicy.SKIP, ConflictPolicy.REPLACE -> name to clash
            ConflictPolicy.KEEP_BOTH -> {
                val siblings = list(s, parentId, null).items.map { it.name.lowercase() }.toSet()
                FileNames.keepBoth(name) { it.lowercase() in siblings } to null
            }
        }
    }

    // ------------------------------------------------------------------ modify

    override suspend fun createFolder(session: AccountSession, parentId: String?, name: String, conflict: ConflictPolicy): CloudFile {
        val (finalName, clash) = resolveName(session, parentId, FileNames.sanitize(name, NameTarget.CLOUD_GENERIC), conflict)
        if (clash != null) return clash
        val body = buildJsonObject { put("name", finalName); put("mimeType", FOLDER); putJsonArray("parents") { add(parentId ?: "root") } }
        val r = session.http.exec(Request.Builder().url(url("files", "fields" to FILE_FIELDS)).post(body.toString().toRequestBody(JsonType)).build())
        return parse(session, check(r, session).json())
    }

    private suspend fun patch(s: AccountSession, id: String, body: JsonObject, vararg params: Pair<String, String?>): CloudFile {
        val r = s.http.exec(Request.Builder().url(url("files/$id", "fields" to FILE_FIELDS, *params)).patch(body.toString().toRequestBody(JsonType)).build())
        return parse(s, check(r, s).json())
    }

    override suspend fun rename(session: AccountSession, item: CloudFile, newName: String): CloudFile =
        patch(session, item.id, buildJsonObject { put("name", FileNames.sanitize(newName, NameTarget.CLOUD_GENERIC)) })

    override suspend fun move(session: AccountSession, item: CloudFile, newParentId: String?): CloudFile =
        patch(session, item.id, buildJsonObject {}, "addParents" to (newParentId ?: "root"), "removeParents" to item.parentId)

    /** Server-side copy (files only; Drive can't copy folders). */
    override suspend fun copy(session: AccountSession, item: CloudFile, newParentId: String?, newName: String?): CloudFile {
        if (item.isFolder) throw CloudException.Unsupported(Operation.SERVER_COPY, "Google Drive can't copy folders directly")
        val body = buildJsonObject { putJsonArray("parents") { add(newParentId ?: "root") }; newName?.let { put("name", it) } }
        val r = session.http.exec(Request.Builder().url(url("files/${item.id}/copy", "fields" to FILE_FIELDS)).post(body.toString().toRequestBody(JsonType)).build())
        return parse(session, check(r, session).json())
    }

    /** Moves to Drive's trash (recoverable for 30 days) rather than deleting permanently. */
    override suspend fun delete(session: AccountSession, item: CloudFile) { patch(session, item.id, buildJsonObject { put("trashed", true) }) }

    // ------------------------------------------------------------------ content

    override suspend fun download(session: AccountSession, item: CloudFile, offset: Long, export: ExportFormat?): DownloadStream {
        val req = when (item.kind) {
            ItemKind.FILE -> Request.Builder().url(url("files/${item.id}", "alt" to "media")).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            ItemKind.NATIVE_DOCUMENT -> {
                val fmt = export ?: item.exportFormats.firstOrNull() ?: throw CloudException.Unsupported(Operation.DOWNLOAD, "No export format")
                Request.Builder().url(url("files/${item.id}/export", "mimeType" to fmt.mimeType)).build()
            }
            else -> throw CloudException.Unsupported(Operation.DOWNLOAD, item.extra["note"] ?: "This item can't be downloaded")
        }
        val r = check(session.http.exec(req), session)
        val body = r.body ?: throw CloudException.Http(r.code, "Empty download")
        val start = if (offset > 0 && r.code == 206) offset else 0L
        val len = body.contentLength().takeIf { it >= 0 }?.let { it + start } ?: item.size.takeIf { item.kind == ItemKind.FILE }
        return DownloadStream(body.byteStream(), len, start)
    }

    /** thumbnailLink is a short-lived signed URL; size is chosen with the "=s<px>" suffix. Fetched without credentials. */
    override suspend fun thumbnail(session: AccountSession, item: CloudFile, px: Int): ByteArray? {
        val link = item.thumbnailRef ?: return null
        val sized = if (Regex("=s\\d+$").containsMatchIn(link)) link.replace(Regex("=s\\d+$"), "=s$px") else link
        val plain = session.http.newBuilder().apply { interceptors().clear(); authenticator(okhttp3.Authenticator.NONE) }.build()
        return plain.exec(Request.Builder().url(sized).build()).use { if (it.isSuccessful) it.body?.bytes() else null }
    }

    override suspend fun upload(
        session: AccountSession, parentId: String?, name: String, size: Long?, mimeType: String?,
        source: ByteSource, conflict: ConflictPolicy, resumeFrom: UploadCheckpoint?,
        onProgress: (Long) -> Unit, onCheckpoint: (UploadCheckpoint) -> Unit,
    ): CloudFile {
        val clean = FileNames.sanitize(name, NameTarget.CLOUD_GENERIC)
        val type = mimeType ?: "application/octet-stream"
        var sessionUri = resumeFrom?.sessionId?.takeIf { (resumeFrom.expiresAt ?: Long.MAX_VALUE) > System.currentTimeMillis() }
        var confirmed = 0L
        if (sessionUri != null) {
            when (val p = queryProgress(session, sessionUri!!, size)) {
                is Progress.Done -> return p.file
                is Progress.At -> confirmed = p.bytes
                Progress.Gone -> { sessionUri = null; if (resumeFrom!!.confirmedBytes > 0) onProgress(0) }
            }
        }
        if (sessionUri == null) {
            val (finalName, clash) = resolveName(session, parentId, clean, conflict)
            if (clash != null && conflict == ConflictPolicy.SKIP) return clash.copy(extra = clash.extra + ("skipped" to "true"))
            val init = if (clash != null) {
                // REPLACE: new revision of the existing file (keeps its id, sharing and history).
                Request.Builder().url("$upload/files/${clash.id}".toHttpUrl().newBuilder().addQueryParameter("uploadType", "resumable").addQueryParameter("fields", FILE_FIELDS).build())
                    .patch("{}".toRequestBody(JsonType))
            } else {
                val meta = buildJsonObject { put("name", finalName); putJsonArray("parents") { add(parentId ?: "root") } }
                Request.Builder().url("$upload/files".toHttpUrl().newBuilder().addQueryParameter("uploadType", "resumable").addQueryParameter("fields", FILE_FIELDS).build())
                    .post(meta.toString().toRequestBody(JsonType))
            }
            init.header("X-Upload-Content-Type", type)
            if (size != null) init.header("X-Upload-Content-Length", size.toString())
            val r = check(session.http.exec(init.build()), session)
            sessionUri = r.header("Location") ?: throw CloudException.Http(r.code, "Google didn't start an upload session")
            r.close()
            onCheckpoint(UploadCheckpoint(sessionUri!!, 0, System.currentTimeMillis() + 6L * 24 * 3600 * 1000))
        }

        val hasher = MultiHasher(listOf(HashAlgorithm.MD5))
        source.open(0).use { input ->
            if (confirmed > 0) { hasher.consume(input, confirmed); onProgress(confirmed) }
            var offset = confirmed
            while (true) {
                val chunk = readChunk(input, chunkBytes)
                val last = chunk.size < chunkBytes || (size != null && offset + chunk.size >= size)
                val total = when { size != null -> size.toString(); last -> (offset + chunk.size).toString(); else -> "*" }
                val range = if (chunk.isEmpty()) "bytes */$total" else "bytes $offset-${offset + chunk.size - 1}/$total"
                val r = session.http.exec(Request.Builder().url(sessionUri!!).put(chunk.toRequestBody(OctetType)).header("Content-Range", range).build())
                when {
                    r.code == 308 -> {
                        val acked = r.header("Range")?.substringAfter("bytes=")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: offset
                        r.close()
                        if (acked != offset + chunk.size) {
                            // Drive persisted less than we sent: restart reading from the acknowledged byte.
                            throw CloudException.Network(null, "Upload interrupted – resuming")
                        }
                        hasher.update(chunk, 0, chunk.size)
                        offset = acked; onProgress(chunk.size.toLong())
                        onCheckpoint(UploadCheckpoint(sessionUri!!, offset, System.currentTimeMillis() + 6L * 24 * 3600 * 1000))
                    }
                    r.isSuccessful -> {
                        hasher.update(chunk, 0, chunk.size); onProgress(chunk.size.toLong())
                        val f = parse(session, r.json())
                        MultiHasher.mismatch(f.hashes, hasher.results())?.let { throw CloudException.Integrity("Google Drive checksum doesn't match the uploaded data – the upload is corrupt") }
                        return f
                    }
                    r.code == 404 || r.code == 410 -> { r.close(); throw CloudException.Network(null, "Upload session expired – restarting") }
                    else -> check(r, session)
                }
                if (last && chunk.isEmpty()) throw CloudException.Integrity("Upload ended without confirmation")
            }
        }
    }

    private sealed class Progress { class At(val bytes: Long) : Progress(); class Done(val file: CloudFile) : Progress(); data object Gone : Progress() }

    /** Asks Drive how much of an existing resumable session it already has. */
    private suspend fun queryProgress(s: AccountSession, uri: String, size: Long?): Progress {
        val r = s.http.exec(Request.Builder().url(uri).put(ByteArray(0).toRequestBody(null)).header("Content-Range", "bytes */${size ?: "*"}").build())
        return when {
            r.code == 308 -> r.use { Progress.At(it.header("Range")?.substringAfter("bytes=")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L) }
            r.isSuccessful -> Progress.Done(parse(s, r.json()))
            else -> { r.close(); Progress.Gone }
        }
    }
}
