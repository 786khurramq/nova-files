package com.nova.files.cloud.providers

import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.net.HttpErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.time.Instant

internal val JsonType = "application/json; charset=utf-8".toMediaType()
internal val OctetType = "application/octet-stream".toMediaType()
internal val Lenient = Json { ignoreUnknownKeys = true }

/** Executes on the IO dispatcher; cancels the HTTP call if the coroutine is cancelled. */
internal suspend fun OkHttpClient.exec(req: Request): Response = withContext(Dispatchers.IO) {
    try { newCall(req).execute() } catch (e: IOException) { throw HttpErrors.mapIo(e) }
}

internal fun Response.json(): JsonObject = use { r ->
    runCatching { Lenient.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrElse { throw CloudException.Http(r.code, "Unexpected response from provider") }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun iso(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Streams exactly [length] bytes from [input] into the request without buffering the file. */
internal class StreamBody(private val input: InputStream, private val length: Long, private val onBytes: (Int) -> Unit = {}) : RequestBody() {
    override fun contentType() = OctetType
    override fun contentLength() = length
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        val buf = ByteArray(256 * 1024); var left = length
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("Source ended early")
            sink.write(buf, 0, r); left -= r; onBytes(r)
        }
    }
}

/** Reads up to [max] bytes into a buffer (used for one upload chunk, bounded memory). */
internal fun readChunk(input: InputStream, max: Int): ByteArray {
    val out = ByteArray(max); var n = 0
    while (n < max) { val r = input.read(out, n, max - n); if (r < 0) break; n += r }
    return if (n == max) out else out.copyOf(n)
}

