package com.nova.files.cloud.providers.dropbox

import com.nova.files.cloud.auth.OAuthConfig
import com.nova.files.cloud.core.*
import com.nova.files.cloud.net.HttpErrors
import com.nova.files.cloud.providers.*
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Dropbox API v2 ("Full Dropbox" access). Canonical identity is the Dropbox file id ("id:…"), never
 * the path. Paths are only used where the API requires one (creating items under a parent).
 */
class DropboxProvider(
    private val api: String = "https://api.dropboxapi.com",
    private val content: String = "https://content.dropboxapi.com",
    private val chunkBytes: Int = 8 * 1024 * 1024,          // multiple of 4 MiB as Dropbox requires
) : CloudProviderAdapter {
    override val providerId = ProviderId.DROPBOX
    override val capabilities = setOf(
        Operation.BROWSE, Operation.SEARCH, Operation.UPLOAD, Operation.DOWNLOAD, Operation.CREATE_FOLDER, Operation.RENAME,
        Operation.MOVE, Operation.COPY, Operation.SERVER_COPY, Operation.DELETE, Operation.METADATA, Operation.QUOTA,
        Operation.RESUMABLE_UPLOAD, Operation.RESUMABLE_DOWNLOAD, Operation.THUMBNAIL, Operation.CHANGE_TRACKING,
    )

    companion object {
        fun oauthConfig(appKey: String) = OAuthConfig(
            providerId = ProviderId.DROPBOX,
            authorizationEndpoint = "https://www.dropbox.com/oauth2/authorize",
            tokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
            clientId = appKey,
            redirectUri = "db-$appKey://1/connect",
            scopes = listOf("account_info.read", "files.metadata.read", "files.metadata.write", "files.content.read", "files.content.write"),
            extraAuthParams = mapOf("token_access_type" to "offline"),
        )
        private val thumbExt = setOf("jpg", "jpeg", "png", "tiff", "tif", "gif", "webp", "ppm", "bmp", "heic")
        private const val FIELDS_LIMIT = 500

        /** Dropbox-API-Arg must be ASCII: escape everything else as \\uXXXX. */
        fun headerArg(o: JsonObject): String = buildString {
            o.toString().forEach { c -> if (c.code < 0x80) append(c) else append("\\u%04x".format(c.code)) }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun rpc(s: AccountSession, path: String, body: JsonObject?): JsonObject {
        val req = Request.Builder().url("$api/2/$path")
            .post((body?.toString() ?: "null").toRequestBody(JsonType)).build()
        return check(s.http.exec(req), s).json()
    }

    /** Dropbox reports most API errors as HTTP 409 with an error_summary path such as "path/not_found/". */
    private fun check(r: Response, s: AccountSession): Response {
        if (r.isSuccessful) return r
        val body = runCatching { r.peekBody(8192).string() }.getOrDefault("")
        r.close()
        val summary = runCatching { Lenient.parseToJsonElement(body).jsonObject.str("error_summary") }.getOrNull().orEmpty()
        throw when {
            r.code == 409 && summary.contains("lookup_failed/not_found") -> CloudException.UploadSessionExpired()
            r.code == 409 && summary.contains("not_found") -> CloudException.NotFound()
            r.code == 409 && summary.contains("conflict") -> CloudException.Conflict(null)
            r.code == 409 && summary.contains("insufficient_space") -> CloudException.QuotaExceeded()
            r.code == 409 && summary.contains("too_many_write_operations") -> CloudException.RateLimited(1000)
            r.code == 409 && summary.contains("disallowed_name") -> CloudException.Http(409, "Dropbox doesn't allow that name")
            r.code == 409 && summary.contains("incorrect_offset") -> CloudException.OffsetMismatch(runCatching {
                Lenient.parseToJsonElement(body).jsonObject.obj("error")?.long("correct_offset")
                    ?: Lenient.parseToJsonElement(body).jsonObject.obj("error")?.obj("lookup_failed")?.long("correct_offset")
            }.getOrNull())
            r.code == 409 -> CloudException.Http(409, "Dropbox: ${summary.substringBefore("/.").trimEnd('/').ifBlank { "request rejected" }}")
            r.code == 400 && body.contains("expired_access_token") -> CloudException.AuthRequired(s.accountId)
            else -> HttpErrors.map(r.code, r.header("Retry-After"), body, s.accountId)
        }
    }


    private fun parse(s: AccountSession, e: JsonObject, parentId: String?): CloudFile {
        val tag = e.str(".tag")
        val name = e.str("name") ?: "?"
        val folder = tag == "folder"
        val downloadable = e.bool("is_downloadable") ?: true
        val ext = name.substringAfterLast('.', "").lowercase()
        return CloudFile(
            accountId = s.accountId, providerId = providerId,
            id = e.str("id") ?: e.str("path_lower") ?: name,
            parentId = parentId, name = name, mimeType = null,
            kind = when { folder -> ItemKind.FOLDER; !downloadable -> ItemKind.SPECIAL; else -> ItemKind.FILE },
            size = e.long("size"), created = null,
            modified = iso(e.str("server_modified")) ?: iso(e.str("client_modified")),
            revision = e.str("rev"),
            hashes = e.str("content_hash")?.let { mapOf(HashAlgorithm.DROPBOX_CONTENT_HASH to it) } ?: emptyMap(),
            thumbnailRef = if (!folder && downloadable && ext in thumbExt && (e.long("size") ?: 0) < 20L shl 20) e.str("id") else null,
            extra = buildMap {
                e.str("path_lower")?.let { put("path", it) }
                e.str("path_display")?.let { put("pathDisplay", it) }
                if (!downloadable) put("note", "Dropbox Paper or other cloud-only document – open it on dropbox.com")
            },
        )
    }

    private fun pathArg(id: String?) = id ?: ""          // "" is the Dropbox root

    private suspend fun parentPath(s: AccountSession, parentId: String?): String {
        if (parentId == null) return ""
        if (parentId.startsWith("/")) return parentId
        return rpc(s, "files/get_metadata", buildJsonObject { put("path", parentId) }).str("path_lower") ?: throw CloudException.NotFound()
    }

    // ------------------------------------------------------------------ account

    override suspend fun profile(session: AccountSession): AccountProfile {
        val o = rpc(session, "users/get_current_account", null)
        return AccountProfile(
            providerAccountId = o.str("account_id") ?: throw CloudException.Http(200, "Dropbox didn't return an account id"),
            displayName = o.obj("name")?.str("display_name") ?: o.str("email") ?: "Dropbox",
            email = o.str("email"), avatarUrl = o.str("profile_photo_url"),
        )
    }

    override suspend fun quota(session: AccountSession): Quota {
        val o = rpc(session, "users/get_space_usage", null)
        val alloc = o.obj("allocation")
        val total = alloc?.long("allocated") ?: alloc?.long("team_allocated")
        return Quota(o.long("used"), total)
    }

    override suspend fun revoke(session: AccountSession, refreshToken: String?) {
        runCatching { session.http.exec(Request.Builder().url("$api/2/auth/token/revoke").post("null".toRequestBody(JsonType)).build()).close() }
    }

    // ------------------------------------------------------------------ browse

    override suspend fun list(session: AccountSession, folderId: String?, cursor: String?): Page {
        val o = if (cursor == null) rpc(session, "files/list_folder", buildJsonObject {
            put("path", pathArg(folderId)); put("limit", FIELDS_LIMIT); put("include_deleted", false)
        }) else rpc(session, "files/list_folder/continue", buildJsonObject { put("cursor", cursor) })
        val items = o["entries"]?.jsonArray?.mapNotNull { it.asObj() }?.filter { it.str(".tag") != "deleted" }?.map { parse(session, it, folderId) } ?: emptyList()
        return Page(items, if (o.bool("has_more") == true) o.str("cursor") else null)
    }

    override suspend fun metadata(session: AccountSession, id: String): CloudFile =
        parse(session, rpc(session, "files/get_metadata", buildJsonObject { put("path", id) }), null)

    override suspend fun search(session: AccountSession, query: String, cursor: String?): Page {
        val o = if (cursor == null) rpc(session, "files/search_v2", buildJsonObject {
            put("query", query); putJsonObject("options") { put("max_results", 100); put("file_status", "active") }
        }) else rpc(session, "files/search/continue_v2", buildJsonObject { put("cursor", cursor) })
        val items = o["matches"]?.jsonArray?.mapNotNull { it.asObj()?.obj("metadata")?.obj("metadata") }?.map { parse(session, it, null) } ?: emptyList()
        return Page(items, if (o.bool("has_more") == true) o.str("cursor") else null)
    }

    override suspend fun changes(session: AccountSession, cursor: String?): ChangeSet {
        val c = cursor ?: rpc(session, "files/list_folder/get_latest_cursor", buildJsonObject { put("path", ""); put("recursive", true) }).str("cursor")!!
        if (cursor == null) return ChangeSet(emptyList(), emptyList(), c, false)
        val o = rpc(session, "files/list_folder/continue", buildJsonObject { put("cursor", c) })
        val entries = o["entries"]?.jsonArray?.mapNotNull { it.asObj() } ?: emptyList()
        return ChangeSet(entries.filter { it.str(".tag") != "deleted" }.map { parse(session, it, null) },
            entries.filter { it.str(".tag") == "deleted" }.mapNotNull { it.str("path_lower") }, o.str("cursor") ?: c, o.bool("has_more") == true)
    }

    // ------------------------------------------------------------------ modify

    override suspend fun createFolder(session: AccountSession, parentId: String?, name: String, conflict: ConflictPolicy): CloudFile {
        val clean = FileNames.sanitize(name, NameTarget.CLOUD_GENERIC)
        val o = rpc(session, "files/create_folder_v2", buildJsonObject {
            put("path", "${parentPath(session, parentId)}/$clean"); put("autorename", conflict == ConflictPolicy.KEEP_BOTH)
        })
        return parse(session, o.obj("metadata")!!, parentId)
    }

    override suspend fun rename(session: AccountSession, item: CloudFile, newName: String): CloudFile {
        val path = item.extra["path"] ?: metadata(session, item.id).extra["path"] ?: throw CloudException.NotFound()
        val clean = FileNames.sanitize(newName, NameTarget.CLOUD_GENERIC)
        val o = rpc(session, "files/move_v2", buildJsonObject {
            put("from_path", item.id); put("to_path", path.substringBeforeLast('/') + "/" + clean); put("autorename", false)
        })
        return parse(session, o.obj("metadata")!!, item.parentId)
    }

    override suspend fun move(session: AccountSession, item: CloudFile, newParentId: String?): CloudFile {
        val o = rpc(session, "files/move_v2", buildJsonObject {
            put("from_path", item.id); put("to_path", "${parentPath(session, newParentId)}/${item.name}"); put("autorename", true)
        })
        return parse(session, o.obj("metadata")!!, newParentId)
    }

    /** Genuine server-side copy within the same Dropbox account. */
    override suspend fun copy(session: AccountSession, item: CloudFile, newParentId: String?, newName: String?): CloudFile {
        val o = rpc(session, "files/copy_v2", buildJsonObject {
            put("from_path", item.id); put("to_path", "${parentPath(session, newParentId)}/${newName ?: item.name}"); put("autorename", true)
        })
        return parse(session, o.obj("metadata")!!, newParentId)
    }

    /** Dropbox keeps deleted files restorable from dropbox.com for its retention period. */
    override suspend fun delete(session: AccountSession, item: CloudFile) {
        rpc(session, "files/delete_v2", buildJsonObject { put("path", item.id) })
    }

    // ------------------------------------------------------------------ content

    override suspend fun download(session: AccountSession, item: CloudFile, offset: Long, export: ExportFormat?): DownloadStream {
        if (item.kind != ItemKind.FILE) throw CloudException.Unsupported(Operation.DOWNLOAD, item.extra["note"] ?: "This item can't be downloaded")
        val req = Request.Builder().url("$content/2/files/download")
            .header("Dropbox-API-Arg", headerArg(buildJsonObject { put("path", item.id) }))
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .post("".toRequestBody(null)).build()
        val r = check(session.http.exec(req), session)
        val body = r.body ?: throw CloudException.Http(r.code, "Empty download")
        val start = if (offset > 0 && r.code == 206) offset else 0L
        val len = body.contentLength().takeIf { it >= 0 }?.let { it + start } ?: item.size
        return DownloadStream(body.byteStream(), len, start)
    }

    override suspend fun thumbnail(session: AccountSession, item: CloudFile, px: Int): ByteArray? {
        if (item.thumbnailRef == null) return null
        val size = when { px <= 64 -> "w64h64"; px <= 128 -> "w128h128"; px <= 256 -> "w256h256"; px <= 480 -> "w480h320"; else -> "w640h480" }
        val req = Request.Builder().url("$content/2/files/get_thumbnail_v2")
            .header("Dropbox-API-Arg", headerArg(buildJsonObject {
                putJsonObject("resource") { put(".tag", "path"); put("path", item.id) }
                put("format", "jpeg"); put("size", size); put("mode", "fitone_bestfit")
            })).post("".toRequestBody(null)).build()
        val r = session.http.exec(req)
        return r.use { if (it.isSuccessful) it.body?.bytes() else null }
    }

    override suspend fun upload(
        session: AccountSession, parentId: String?, name: String, size: Long?, mimeType: String?,
        source: ByteSource, conflict: ConflictPolicy, resumeFrom: UploadCheckpoint?,
        onProgress: (Long) -> Unit, onCheckpoint: (UploadCheckpoint) -> Unit,
    ): CloudFile {
        val clean = FileNames.sanitize(name, NameTarget.CLOUD_GENERIC)
        val path = resumeFrom?.extra?.get("path") ?: "${parentPath(session, parentId)}/$clean"
        val commit = buildJsonObject {
            put("path", path)
            put("mode", if (conflict == ConflictPolicy.REPLACE) "overwrite" else "add")
            put("autorename", conflict == ConflictPolicy.KEEP_BOTH)
            put("mute", true)
        }
        var hasher = MultiHasher(listOf(HashAlgorithm.DROPBOX_CONTENT_HASH))
        val result = try {
            if (resumeFrom == null && size != null && size <= chunkBytes) {
                source.open(0).use { input ->
                    val req = Request.Builder().url("$content/2/files/upload")
                        .header("Dropbox-API-Arg", headerArg(commit))
                        .post(StreamBody(HashingInputStream(input, hasher), size) { onProgress(it.toLong()) }).build()
                    parse(session, check(session.http.exec(req), session).json(), parentId)
                }
            } else try {
                sessionUpload(session, path, size, source, commit, resumeFrom, hasher, onProgress, onCheckpoint, parentId)
            } catch (e: CloudException.UploadSessionExpired) {
                // Saved session expired on Dropbox's side: start a fresh session from the beginning.
                hasher = MultiHasher(listOf(HashAlgorithm.DROPBOX_CONTENT_HASH))
                onProgress(-(resumeFrom?.confirmedBytes ?: 0))
                sessionUpload(session, path, size, source, commit, null, hasher, onProgress, onCheckpoint, parentId)
            }
        } catch (e: CloudException.Conflict) {
            if (conflict == ConflictPolicy.SKIP) return metadata(session, path).copy(extra = mapOf("skipped" to "true"))
            throw CloudException.Conflict(clean)
        }
        val local = hasher.results()
        MultiHasher.mismatch(result.hashes, local)?.let { throw CloudException.Integrity("Dropbox checksum doesn't match the uploaded data – the upload is corrupt") }
        return result
    }

    private suspend fun sessionUpload(
        s: AccountSession, path: String, size: Long?, source: ByteSource, commit: JsonObject, resumeFrom: UploadCheckpoint?,
        hasher: MultiHasher, onProgress: (Long) -> Unit, onCheckpoint: (UploadCheckpoint) -> Unit, parentId: String?,
    ): CloudFile {
        var sessionId = resumeFrom?.sessionId?.takeIf { (resumeFrom.expiresAt ?: Long.MAX_VALUE) > System.currentTimeMillis() }
        var offset = if (sessionId != null) resumeFrom!!.confirmedBytes else 0L
        source.open(0).use { input ->
            if (offset > 0) { hasher.consume(input, offset); onProgress(offset) }
            if (sessionId == null) {
                val first = readChunk(input, chunkBytes); hasher.update(first, 0, first.size)
                val req = Request.Builder().url("$content/2/files/upload_session/start")
                    .header("Dropbox-API-Arg", headerArg(buildJsonObject { put("close", false) }))
                    .post(first.toRequestBody(OctetType)).build()
                sessionId = check(s.http.exec(req), s).json().str("session_id") ?: throw CloudException.Http(200, "No upload session")
                offset = first.size.toLong(); onProgress(first.size.toLong())
                onCheckpoint(UploadCheckpoint(sessionId!!, offset, System.currentTimeMillis() + 6L * 24 * 3600 * 1000, mapOf("path" to path)))
                if (first.size < chunkBytes) return finish(s, sessionId!!, offset, commit, parentId)
            }
            while (true) {
                val chunk = readChunk(input, chunkBytes)
                if (chunk.isEmpty()) break
                val req = Request.Builder().url("$content/2/files/upload_session/append_v2")
                    .header("Dropbox-API-Arg", headerArg(buildJsonObject {
                        putJsonObject("cursor") { put("session_id", sessionId); put("offset", offset) }; put("close", false)
                    })).post(chunk.toRequestBody(OctetType)).build()
                try { check(s.http.exec(req), s).close() }
                catch (e: CloudException.OffsetMismatch) { throw CloudException.Integrity("Dropbox lost track of the upload position – restarting is needed") }
                hasher.update(chunk, 0, chunk.size)
                offset += chunk.size; onProgress(chunk.size.toLong())
                onCheckpoint(UploadCheckpoint(sessionId!!, offset, System.currentTimeMillis() + 6L * 24 * 3600 * 1000, mapOf("path" to path)))
                if (chunk.size < chunkBytes) break
            }
        }
        if (size != null && offset != size) throw CloudException.Integrity("Uploaded $offset of $size bytes")
        return finish(s, sessionId!!, offset, commit, parentId)
    }

    private suspend fun finish(s: AccountSession, sessionId: String, offset: Long, commit: JsonObject, parentId: String?): CloudFile {
        val req = Request.Builder().url("$content/2/files/upload_session/finish")
            .header("Dropbox-API-Arg", headerArg(buildJsonObject {
                putJsonObject("cursor") { put("session_id", sessionId); put("offset", offset) }; put("commit", commit)
            })).post(ByteArray(0).toRequestBody(OctetType)).build()
        return parse(s, check(s.http.exec(req), s).json(), parentId)
    }
}
