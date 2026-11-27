package com.nova.files.cloud

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nova.files.cloud.auth.CredentialStore
import com.nova.files.cloud.auth.KeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software AES key standing in for the Android Keystore key in JVM tests. */
class SoftwareKeyProvider : KeyProvider {
    var current: SecretKey = gen()
    var resets = 0
    private fun gen() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun key() = current
    override fun reset() { resets++; current = gen() }
}

fun tempDir(prefix: String = "nova"): File = Files.createTempDirectory(prefix).toFile()

fun credentialStore(keys: KeyProvider = SoftwareKeyProvider(), dir: File = tempDir()): CredentialStore =
    CredentialStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(dir, "creds.preferences_pb") }, keys)
