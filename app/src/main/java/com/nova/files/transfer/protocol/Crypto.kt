package com.nova.files.transfer.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Long-term device identity. Android implementation keeps the private key in AndroidKeyStore. */
interface IdentityKey {
    val publicKeyEncoded: ByteArray
    fun sign(data: ByteArray): ByteArray
}

class SoftwareIdentityKey(private val pair: KeyPair = Crypto.newEcKeyPair()) : IdentityKey {
    override val publicKeyEncoded: ByteArray get() = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Crypto.sign(pair.private, data)
}

object Crypto {
    val random = SecureRandom()

    fun newEcKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun decodePublic(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(sig) }
    } catch (e: Exception) { false }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }; digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Full SHA-256 fingerprint of an identity key; deviceId is its first 16 hex chars. */
    fun fingerprint(identityPub: ByteArray): String = hex(sha256(identityPub))
    fun deviceIdFor(identityPub: ByteArray): String = fingerprint(identityPub).take(16)

    /** Human-friendly fingerprint e.g. "3F2A-91BC-…" for display. */
    fun prettyFingerprint(fp: String): String = fp.take(16).uppercase().chunked(4).joinToString("-")
}
