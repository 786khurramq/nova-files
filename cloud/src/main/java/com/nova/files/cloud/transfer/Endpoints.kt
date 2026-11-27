package com.nova.files.cloud.transfer

import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.core.HashAlgorithm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Where data comes from or goes to. Cloud endpoints always carry their account id explicitly. */
@Serializable
sealed class Endpoint {
    /** A file or a destination folder on a filesystem path the app can access. */
    @Serializable @SerialName("local") data class Local(val path: String) : Endpoint()
    /** A document from another app or the system picker (content://). */
    @Serializable @SerialName("content") data class ContentUri(val uri: String, val displayName: String? = null) : Endpoint()
    /** A Storage Access Framework tree (destination folder) chosen by the user. */
    @Serializable @SerialName("saf") data class SafTree(val treeUri: String) : Endpoint()
    /** A cloud object (source: fileId) or cloud folder (destination: parentId). */
    @Serializable @SerialName("cloud") data class Cloud(val accountId: String, val provider: String, val fileId: String? = null, val parentId: String? = null, val revision: String? = null) : Endpoint()
    /** A Nova Files device reached over the existing encrypted wireless link. */
    @Serializable @SerialName("wireless") data class Wireless(val deviceId: String, val deviceName: String) : Endpoint()

    val cloudAccountId: String? get() = (this as? Cloud)?.accountId
    fun encode(): String = EndpointJson.encodeToString(serializer(), this)
    companion object { fun decode(s: String): Endpoint = EndpointJson.decodeFromString(serializer(), s) }
}

internal val EndpointJson = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }

/** Readable side of a transfer. Streams only – nothing requires a whole file in memory. */
interface TransferSource {
    val name: String
    /** Null when the source can't tell (some content providers, exported cloud documents). */
    val size: Long?
    /** True if [open] can start at an offset (enables resuming downloads/copies). */
    val supportsOffset: Boolean
    /** Hashes the source already knows (e.g. provider checksums), for verification after transfer. */
    val knownHashes: Map<HashAlgorithm, String> get() = emptyMap()
    fun open(offset: Long): InputStream
}

/** A destination write in progress. [commit] makes it visible; [abort] cleans up. */
interface DestinationWriter {
    /** Bytes already present from an earlier attempt (0 if starting fresh). */
    val startOffset: Long
    val stream: OutputStream
    /** Serialised resume information persisted with the job (e.g. partial-file path or upload session). */
    fun checkpoint(): String?
    fun commit(): CommittedResult
    fun abort(keepPartial: Boolean)
}

@Serializable
data class CommittedResult(val location: String, val name: String, val sha256: String? = null, val skipped: Boolean = false)

interface TransferDestination {
    /** True if an interrupted write can continue from [DestinationWriter.startOffset]. */
    val supportsResume: Boolean
    /** Returns null if [conflict] is SKIP and the target exists. Throws Conflict if ASK and it exists. */
    fun prepare(name: String, size: Long?, conflict: ConflictPolicy, checkpoint: String?): DestinationWriter?
}

/** Maps endpoints to adapters. Cloud providers register here in later phases. */
fun interface EndpointResolver {
    fun source(e: Endpoint): TransferSource
    fun destination(e: Endpoint): TransferDestination = throw UnsupportedOperationException("No destination adapter for ${e::class.simpleName}")
}
