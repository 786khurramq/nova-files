package com.nova.files.cloud.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials per account. Never logged; [toString] is redacted. */
@Serializable
sealed class Credentials {
    @Serializable @SerialName("oauth")
    data class OAuth(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long, val scope: String? = null) : Credentials() {
        fun expiresSoon(now: Long = System.currentTimeMillis()) = now >= expiresAtMs - 60_000
        override fun toString() = "OAuth(expiresAt=$expiresAtMs, hasRefresh=${refreshToken != null})"
    }
    /** WebDAV-style username/password: the password IS the credential there, so it's stored encrypted. */
    @Serializable @SerialName("password")
    data class Password(val username: String, val password: String) : Credentials() { override fun toString() = "Password(user=$username)" }
    /** Opaque provider session (e.g. MEGA's exported session) – never the account password. */
    @Serializable @SerialName("session")
    data class Session(val session: String) : Credentials() { override fun toString() = "Session(…)" }
}

sealed class CredentialResult {
    data class Ok(val credentials: Credentials) : CredentialResult()
    data object Missing : CredentialResult()
    /** Unreadable: key invalidated, data damaged, or record tampered with. Account must reconnect. */
    data class Unreadable(val reason: String) : CredentialResult()
}

/** Source of the AES key. Production: non-exportable Android Keystore key. */
interface KeyProvider {
    fun key(): SecretKey
    /** Called when the key is permanently unusable; a fresh key is created on next [key]. */
    fun reset()
}

class AndroidKeystoreKeyProvider(private val alias: String = "nova_cloud_credentials_v1") : KeyProvider {
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun key(): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
    override fun reset() { runCatching { ks.deleteEntry(alias) } }
}

/**
 * Encrypted credential records in DataStore. AES-256-GCM with a random IV per write; the record key
 * is bound as additional authenticated data, so a record copied to another account's slot fails to
 * decrypt instead of silently authenticating as the wrong account.
 */
class CredentialStore(private val store: DataStore<Preferences>, private val keys: KeyProvider) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
    private val random = SecureRandom()

    private fun prefKey(ref: String) = stringPreferencesKey("cred:$ref")
    private fun aad(ref: String) = "nova-cred-v1|$ref".toByteArray()

    suspend fun put(ref: String, c: Credentials) = putRaw(ref, json.encodeToString(Credentials.serializer(), c))

    suspend fun get(ref: String): CredentialResult {
        val raw = when (val r = getRaw(ref)) { is RawResult.Ok -> r.value; RawResult.Missing -> return CredentialResult.Missing; is RawResult.Bad -> return CredentialResult.Unreadable(r.reason) }
        return runCatching { CredentialResult.Ok(json.decodeFromString(Credentials.serializer(), raw)) }
            .getOrElse { CredentialResult.Unreadable("format") }
    }

    suspend fun remove(ref: String) { store.edit { it.remove(prefKey(ref)) } }

    /** Temporary values such as pending OAuth state/PKCE verifiers use the same protection. */
    suspend fun putRaw(ref: String, value: String) {
        val enc = encrypt(value.toByteArray(), aad(ref))
        store.edit { it[prefKey(ref)] = enc }
    }

    sealed class RawResult { data class Ok(val value: String) : RawResult(); data object Missing : RawResult(); data class Bad(val reason: String) : RawResult() }

    suspend fun getRaw(ref: String): RawResult {
        val enc = store.data.first()[prefKey(ref)] ?: return RawResult.Missing
        return try { RawResult.Ok(String(decrypt(enc, aad(ref)))) }
        catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) { keys.reset(); RawResult.Bad("key invalidated") }
        catch (e: java.security.UnrecoverableKeyException) { keys.reset(); RawResult.Bad("key unavailable") }
        catch (e: Exception) { RawResult.Bad("damaged") }
    }

    suspend fun refs(prefix: String = ""): List<String> =
        store.data.first().asMap().keys.map { it.name }.filter { it.startsWith("cred:$prefix") }.map { it.removePrefix("cred:") }

    // Test hooks for tamper/relocation tests.
    internal suspend fun getRawEncrypted(ref: String): String = store.data.first()[prefKey(ref)]!!
    internal suspend fun putEncrypted(ref: String, enc: String) { store.edit { it[prefKey(ref)] = enc } }

    private fun encrypt(plain: ByteArray, aad: ByteArray): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(128, iv)); c.updateAAD(aad)
        return "v1:" + Base64.getEncoder().encodeToString(iv + c.doFinal(plain))
    }

    private fun decrypt(s: String, aad: ByteArray): ByteArray {
        require(s.startsWith("v1:")) { "unknown format" }
        val b = Base64.getDecoder().decode(s.removePrefix("v1:"))
        require(b.size > 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, b, 0, 12)); c.updateAAD(aad)
        return c.doFinal(b, 12, b.size - 12)
    }
}
