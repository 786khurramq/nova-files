package com.nova.files.transfer.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
const val DEFAULT_TRANSFER_PORT = 47821
const val DISCOVERY_PORT = 47820
const val SERVICE_TYPE = "_novafiles._tcp."

val ProtoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "t" }

/** Plain-text hello exchanged before keys exist. Bound into the transcript. */
@Serializable
data class Hello(
    val proto: Int = PROTOCOL_VERSION,
    val role: String,               // "client" | "server"
    val deviceId: String,
    val name: String,
    val deviceType: String,         // phone | tablet | tv
    val identityKey: String,        // base64 X.509 EC P-256
    val ephemeralKey: String,       // base64 X.509 EC P-256
    val nonce: String,
    val appVersion: String = "",
)

@Serializable
sealed class Msg {
    @Serializable @SerialName("auth") data class Auth(val sig: String) : Msg()
    @Serializable @SerialName("trust") data class Trust(val trustsPeer: Boolean, val purpose: String) : Msg()
    @Serializable @SerialName("pair") data class PairDecision(val accept: Boolean) : Msg()
    @Serializable @SerialName("offer") data class Offer(
        val transferId: String,
        val items: List<OfferItem>,
        val totalBytes: Long,
    ) : Msg()
    @Serializable @SerialName("accept") data class Accept(val offsets: List<Long>) : Msg()
    @Serializable @SerialName("reject") data class Reject(val reason: String) : Msg()
    @Serializable @SerialName("fstart") data class FileStart(val index: Int, val offset: Long) : Msg()
    @Serializable @SerialName("fend") data class FileEnd(val index: Int, val sha256: String) : Msg()
    @Serializable @SerialName("fack") data class FileAck(val index: Int, val ok: Boolean, val savedAs: String = "", val error: String = "") : Msg()
    @Serializable @SerialName("ctrl") data class Control(val action: String) : Msg() // pause | resume | cancel
    @Serializable @SerialName("done") data object Done : Msg()
    @Serializable @SerialName("ping") data object Ping : Msg()
    @Serializable @SerialName("bye") data object Bye : Msg()
    @Serializable @SerialName("err") data class Error(val message: String) : Msg()
}

@Serializable
data class OfferItem(
    val path: String,       // relative, '/' separated
    val size: Long,
    val mtime: Long,
    val dir: Boolean = false,
)

object Purpose { const val TRANSFER = "transfer"; const val PAIR = "pair" }
object Ctrl { const val PAUSE = "pause"; const val RESUME = "resume"; const val CANCEL = "cancel" }

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TransferCancelledException(val byPeer: Boolean) : Exception(if (byPeer) "Cancelled by the other device" else "Cancelled")
