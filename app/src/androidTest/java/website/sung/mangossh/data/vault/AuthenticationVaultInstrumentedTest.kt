package website.sung.mangossh.data.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile

/** Uses only UUID-scoped cache files and Keystore aliases; the target application's vault is untouched. */
@RunWith(AndroidJUnit4::class)
class AuthenticationVaultInstrumentedTest {
    private fun isolated(block: suspend (VaultRepository, AndroidKeystoreVault) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "ssh-password-test-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = "ssh-password-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val storage = AndroidKeystoreVault(context, alias)
            val repository = VaultRepository(context, storage)
            repository.open()
            block(repository, storage)
        } finally {
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun passwordChangesAndReferenceRemovalCommitTogether() = isolated { repository, storage ->
        val password = StoredAuthentication(UUID.randomUUID().toString(), "Ephemeral", UUID.randomUUID().toString())
        assertEquals(VaultMutationResult.Success, repository.addAuthentication(password))
        val host = ConnectionProfile(id = UUID.randomUUID().toString(), label = "Example", hostname = "synthetic.invalid",
            username = "test", authentication = AuthenticationMethod.AUTOMATIC, authenticationId = password.id)
        assertEquals(VaultMutationResult.Success, repository.upsertProfile(host))
        assertEquals(VaultMutationResult.Success, repository.editAuthentication(password.id, "Renamed", null, false, null))
        assertEquals(password.password, storage.read()!!.authentications.single().password)
        assertEquals(VaultMutationResult.Success, repository.removeAuthentication(password.id))
        val persisted = storage.read()!!
        assertTrue(persisted.authentications.isEmpty())
        assertNull(persisted.profiles.single().authenticationId)
        assertEquals(AuthenticationMethod.AUTOMATIC, persisted.profiles.single().authentication)
        assertEquals(VaultMutationResult.Invalid, repository.editAuthentication(password.id, "Deleted", null, false, null))
    }

    @Test fun failedSaveAndRevokedAuthorizationLeaveTheCommittedSnapshotIntact() = isolated { repository, storage ->
        val existing = StoredAuthentication(UUID.randomUUID().toString(), "Ephemeral", UUID.randomUUID().toString())
        assertEquals(VaultMutationResult.Success, repository.addAuthentication(existing))
        val host = ConnectionProfile(id = UUID.randomUUID().toString(), label = "Example", hostname = "synthetic.invalid",
            username = "test", authentication = AuthenticationMethod.AUTOMATIC, authenticationId = existing.id)
        assertEquals(VaultMutationResult.Success, repository.upsertProfile(host))
        val before = repository.snapshot.value
        val oversized = StoredAuthentication(UUID.randomUUID().toString(), "Ephemeral", UUID.randomUUID().toString().repeat(160_000))
        assertEquals(VaultMutationResult.StorageFailure, repository.rememberAuthentication(host, host, existing, oversized))
        assertEquals(before, repository.snapshot.value)
        assertEquals(before, storage.read())
        assertEquals(VaultMutationResult.Invalid, repository.removeAuthentication(existing.id) { false })
        assertEquals(VaultMutationResult.Invalid, repository.addAuthentication(existing.copy(id = UUID.randomUUID().toString())) { false })
        assertEquals(VaultMutationResult.Invalid, repository.editAuthentication(existing.id, "Changed", null, false, null) { false })
        val key = website.sung.mangossh.data.keys.SshKeyManager().generateEd25519("Ephemeral")
        assertEquals(VaultMutationResult.StorageFailure, repository.addAuthentication(oversized.copy(keyId = key.id), StagedKeyChange(key)))
        assertEquals(before, repository.snapshot.value)
        assertEquals(before, storage.read())
    }

    @Test fun acceptedPasswordCopyAndHostBindingPersistAtomicallyAndStaleResultsAreRejected() = isolated { repository, storage ->
        val key = website.sung.mangossh.data.keys.SshKeyManager().generateEd25519("Ephemeral")
        val original = StoredAuthentication(UUID.randomUUID().toString(), "Original", keyId = key.id)
        assertTrue(repository.addAuthentication(original, StagedKeyChange(key)).isSuccess)
        val host = ConnectionProfile(id = UUID.randomUUID().toString(), label = "Example", hostname = "synthetic.invalid", username = "test", authenticationId = original.id)
        assertTrue(repository.upsertProfile(host).isSuccess)
        val copy = original.copy(id = UUID.randomUUID().toString(), password = UUID.randomUUID().toString())
        assertTrue(repository.rememberAuthentication(host, host, original, copy, key).isSuccess)
        val persisted = storage.read()!!
        assertEquals(copy.id, persisted.profiles.single().authenticationId)
        assertEquals(listOf(original, copy), persisted.authentications)
        assertEquals(listOf(key), persisted.keys)
        assertEquals(VaultMutationResult.Invalid, repository.rememberAuthentication(host, host, original,
            copy.copy(id = UUID.randomUUID().toString()), key))
        assertEquals(persisted, storage.read())
    }
}
