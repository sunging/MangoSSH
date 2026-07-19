package website.sung.mangossh.data.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises a real Android Keystore AES-GCM key rather than a JVM
 * [javax.crypto.spec.SecretKeySpec]. Android Keystore rejects caller-provided
 * IVs when randomized encryption is required, so this test prevents the local
 * vault from regressing to the write failure seen when adding a generated key.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreVaultInstrumentedTest {
    @Test
    fun writesAndReadsAKeyUsingKeystoreGeneratedNonce() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(baseContext.cacheDir, "vault-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = directory
        }
        val expected = VaultSnapshot(
            keys = listOf(
                StoredSshKey(
                    id = "test-key",
                    label = "Test key",
                    algorithm = "ssh-ed25519",
                    publicKey = "ssh-ed25519 AAAATEST test-key",
                    fingerprint = "SHA256:test",
                    privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\\ntest\\n-----END OPENSSH PRIVATE KEY-----\\n",
                ),
            ),
        )

        try {
            val vault = AndroidKeystoreVault(context)
            vault.write(expected)

            assertEquals(expected, vault.read())
        } finally {
            directory.deleteRecursively()
        }
    }
}
