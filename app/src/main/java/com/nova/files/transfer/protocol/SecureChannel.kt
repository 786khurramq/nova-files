package com.nova.files.transfer.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_PLAIN_FRAME = 64 * 1024
const val DATA_CHUNK = 256 * 1024
private const val MAX_ENC_FRAME = DATA_CHUNK + 1024
private const val TYPE_MSG: Byte = 1
private const val TYPE_DATA: Byte = 2

sealed class Incoming {
    class M(val msg: Msg) : Incoming()
    class D(val bytes: ByteArray, val length: Int) : Incoming()
}

/** Information about the authenticated remote device. */
data class PeerInfo(
    val deviceId: String,
    val name: String,
    val deviceType: String,
    val fingerprint: String,
    val identityKey: String,
    val appVersion: String,
)

/**
 * Length-prefixed frames over TCP. After [handshake], every frame is AES-256-GCM encrypted with a
 * per-direction key and a monotonically increasing nonce, so frames cannot be replayed, reordered
 * or tampered with. Identity is proven by ECDSA signatures over the handshake transcript.
 */
class SecureChannel(val socket: Socket) : AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 * 1024))
    private val writeLock = Any()
    private val readLock = Any()

    private var sendCipher: Cipher? = null
    private var recvCipher: Cipher? = null
    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendCounter = 0L
    private var recvCounter = 0L

    lateinit var peer: PeerInfo; private set
    /** 6-digit short authentication string; identical on both devices only without a MITM. */
    lateinit var verificationCode: String; private set

    @Volatile var lastReceivedAt = System.currentTimeMillis(); private set

    // ---------- plaintext (handshake only) ----------
    private fun writePlain(b: ByteArray) = synchronized(writeLock) {
        output.writeInt(b.size); output.write(b); output.flush()
    }

    private fun readPlain(): ByteArray {
        val len = input.readInt()
        if (len <= 0 || len > MAX_PLAIN_FRAME) throw ProtocolException("Invalid handshake frame")
        return ByteArray(len).also { input.readFully(it) }
    }

    /**
     * Mutually authenticated key exchange.
     * @param expectedFingerprint if set (trusted device / QR code), the peer must present exactly this identity.
     */
    fun handshake(
        isClient: Boolean,
        identity: IdentityKey,
        myName: String,
        myType: String,
        appVersion: String,
        expectedFingerprint: String? = null,
    ) {
        val eph = Crypto.newEcKeyPair()
        val myHello = Hello(
            role = if (isClient) "client" else "server",
            deviceId = Crypto.deviceIdFor(identity.publicKeyEncoded),
            name = myName.take(64),
            deviceType = myType,
            identityKey = Crypto.b64(identity.publicKeyEncoded),
            ephemeralKey = Crypto.b64(eph.public.encoded),
            nonce = Crypto.b64(Crypto.randomBytes(16)),
            appVersion = appVersion,
        )
        val myBytes = ProtoJson.encodeToString(Hello.serializer(), myHello).toByteArray()
        val peerBytes: ByteArray
        if (isClient) { writePlain(myBytes); peerBytes = readPlain() } else { peerBytes = readPlain(); writePlain(myBytes) }

        val peerHello = try { ProtoJson.decodeFromString(Hello.serializer(), String(peerBytes)) }
        catch (e: Exception) { throw ProtocolException("Not a Nova Files device") }
        if (peerHello.proto != PROTOCOL_VERSION)
            throw ProtocolException("Incompatible Nova Files version on ${peerHello.name}. Update both devices.")
        if (peerHello.role == myHello.role) throw ProtocolException("Handshake role conflict")

        val peerIdentity = Crypto.unb64(peerHello.identityKey)
        val peerIdentityKey = Crypto.decodePublic(peerIdentity)
        val fp = Crypto.fingerprint(peerIdentity)
        if (peerHello.deviceId != fp.take(16)) throw ProtocolException("Device identity mismatch")
        if (expectedFingerprint != null && !expectedFingerprint.equals(fp, ignoreCase = true))
            throw ProtocolException("Security check failed: ${peerHello.name} is not the device you trusted. Re-pair if it was reset.")
        if (peerIdentity.contentEquals(identity.publicKeyEncoded)) throw ProtocolException("Cannot connect to itself")

        val clientBytes = if (isClient) myBytes else peerBytes
        val serverBytes = if (isClient) peerBytes else myBytes
        val transcript = Crypto.sha256(
            "NOVA-FILES-1".toByteArray(),
            ByteBuffer.allocate(4).putInt(clientBytes.size).array(), clientBytes,
            ByteBuffer.allocate(4).putInt(serverBytes.size).array(), serverBytes,
        )
        val shared = Crypto.ecdh(eph.private, Crypto.decodePublic(Crypto.unb64(peerHello.ephemeralKey)))
        val c2s = Crypto.hkdf(shared, transcript, "nova c2s", 32)
        val s2c = Crypto.hkdf(shared, transcript, "nova s2c", 32)
        val sas = Crypto.hkdf(shared, transcript, "nova sas", 8)
        verificationCode = "%06d".format((ByteBuffer.wrap(sas).long and Long.MAX_VALUE) % 1_000_000)
        sendKey = SecretKeySpec(if (isClient) c2s else s2c, "AES")
        recvKey = SecretKeySpec(if (isClient) s2c else c2s, "AES")
        sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
        recvCipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Prove possession of identity private key, bound to this exact session.
        val myRole = myHello.role
        send(Msg.Auth(Crypto.b64(identity.sign("auth:$myRole".toByteArray() + transcript))))
        val auth = receiveMsg() as? Msg.Auth ?: throw ProtocolException("Authentication missing")
        if (!Crypto.verify(peerIdentityKey, "auth:${peerHello.role}".toByteArray() + transcript, Crypto.unb64(auth.sig)))
            throw ProtocolException("Authentication failed")

        peer = PeerInfo(peerHello.deviceId, peerHello.name, peerHello.deviceType, fp, peerHello.identityKey, peerHello.appVersion)
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    private fun writeEncrypted(type: Byte, payload: ByteArray, off: Int, len: Int) = synchronized(writeLock) {
        val c = sendCipher ?: throw IllegalStateException("No session")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCounter++)))
        val outLen = c.getOutputSize(len + 1)
        val buf = ByteArray(outLen)
        var n = c.update(byteArrayOf(type), 0, 1, buf, 0)
        n += c.doFinal(payload, off, len, buf, n)
        output.writeInt(n); output.write(buf, 0, n)
        if (type == TYPE_MSG) output.flush()
    }

    fun send(msg: Msg) {
        val b = ProtoJson.encodeToString(Msg.serializer(), msg).toByteArray()
        writeEncrypted(TYPE_MSG, b, 0, b.size)
    }

    fun sendData(b: ByteArray, off: Int, len: Int) = writeEncrypted(TYPE_DATA, b, off, len)

    fun flush() = synchronized(writeLock) { output.flush() }

    fun receive(): Incoming = synchronized(readLock) {
        val len = try { input.readInt() } catch (e: EOFException) { throw ProtocolException("Connection closed by other device") }
        if (len <= 16 || len > MAX_ENC_FRAME) throw ProtocolException("Invalid frame")
        val buf = ByteArray(len)
        input.readFully(buf)
        val c = recvCipher ?: throw IllegalStateException("No session")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(recvCounter++)))
        val plain = try { c.doFinal(buf) } catch (e: Exception) { throw ProtocolException("Integrity check failed – connection tampered or corrupted") }
        lastReceivedAt = System.currentTimeMillis()
        when (plain[0]) {
            TYPE_MSG -> Incoming.M(try { ProtoJson.decodeFromString(Msg.serializer(), String(plain, 1, plain.size - 1)) } catch (e: Exception) { throw ProtocolException("Malformed message") })
            TYPE_DATA -> { System.arraycopy(plain, 1, plain, 0, plain.size - 1); Incoming.D(plain, plain.size - 1) }
            else -> throw ProtocolException("Unknown frame type")
        }
    }

    /** Receive the next control message, skipping pings. */
    fun receiveMsg(): Msg {
        while (true) {
            when (val f = receive()) {
                is Incoming.M -> if (f.msg !is Msg.Ping) return f.msg
                is Incoming.D -> throw ProtocolException("Unexpected data")
            }
        }
    }

    override fun close() { try { socket.close() } catch (_: Exception) {} }
}
