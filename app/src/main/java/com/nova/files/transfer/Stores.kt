package com.nova.files.transfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nova.files.transfer.protocol.Crypto
import com.nova.files.transfer.protocol.IdentityKey
import com.nova.files.transfer.protocol.ProtoJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Device identity: EC P-256 signing key that never leaves AndroidKeyStore. */
class AndroidIdentityKey : IdentityKey {
    private val alias = "nova_device_identity_v1"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }.generateKeyPair()
        }
    }

    override val publicKeyEncoded: ByteArray = ks.getCertificate(alias).publicKey.encoded
    override fun sign(data: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
    }
    val fingerprint: String get() = Crypto.fingerprint(publicKeyEncoded)
    val deviceId: String get() = Crypto.deviceIdFor(publicKeyEncoded)
}

@Serializable
data class TrustedDevice(
    val deviceId: String,
    val fingerprint: String,
    val name: String,
    val type: String,
    val pairedAt: Long,
    val lastHost: String = "",
    val lastPort: Int = 0,
    val lastSeen: Long = 0,
)

@Serializable
data class HistoryEntry(
    val id: String,
    val outgoing: Boolean,
    val peerName: String,
    val peerId: String,
    val peerType: String,
    val time: Long,
    val fileCount: Int,
    val bytes: Long,
    val success: Boolean,
    val cancelled: Boolean = false,
    val error: String? = null,
    val files: List<HistoryFile> = emptyList(),
    /** For outgoing transfers: source paths so a failed transfer can be retried/resumed. */
    val sources: List<String> = emptyList(),
    val baseDir: String = "",
)

@Serializable
data class HistoryFile(val path: String, val size: Long, val ok: Boolean, val savedAs: String = "", val error: String = "")

/** Small JSON-file backed list store; atomic writes. */
private class JsonListStore<T>(private val file: File, private val ser: kotlinx.serialization.KSerializer<T>) {
    fun read(): List<T> = runCatching { ProtoJson.decodeFromString(ListSerializer(ser), file.readText()) }.getOrDefault(emptyList())
    fun write(items: List<T>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ProtoJson.encodeToString(ListSerializer(ser), items))
        tmp.renameTo(file)
    }
}

class TrustStore(ctx: Context) {
    private val store = JsonListStore(File(ctx.filesDir, "trusted_devices.json"), TrustedDevice.serializer())
    private val _devices = MutableStateFlow(store.read())
    val devices: StateFlow<List<TrustedDevice>> = _devices

    @Synchronized fun byFingerprint(fp: String) = _devices.value.firstOrNull { it.fingerprint.equals(fp, true) }
    @Synchronized fun byId(id: String) = _devices.value.firstOrNull { it.deviceId == id }

    @Synchronized fun upsert(d: TrustedDevice) {
        _devices.value = _devices.value.filterNot { it.fingerprint == d.fingerprint } + d
        store.write(_devices.value)
    }

    @Synchronized fun touch(fp: String, host: String, port: Int, name: String) {
        val d = byFingerprint(fp) ?: return
        upsert(d.copy(lastHost = host.ifEmpty { d.lastHost }, lastPort = if (port > 0) port else d.lastPort, lastSeen = System.currentTimeMillis(), name = name))
    }

    @Synchronized fun remove(fp: String) {
        _devices.value = _devices.value.filterNot { it.fingerprint == fp }
        store.write(_devices.value)
    }
}

class HistoryStore(ctx: Context) {
    private val store = JsonListStore(File(ctx.filesDir, "transfer_history.json"), HistoryEntry.serializer())
    private val _entries = MutableStateFlow(store.read())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Synchronized fun add(e: HistoryEntry) {
        _entries.value = (listOf(e) + _entries.value.filterNot { it.id == e.id && it.outgoing == e.outgoing }).take(300)
        store.write(_entries.value)
    }

    @Synchronized fun clear() { _entries.value = emptyList(); store.write(emptyList()) }
}
