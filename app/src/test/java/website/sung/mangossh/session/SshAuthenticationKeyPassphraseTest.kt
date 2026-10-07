package website.sung.mangossh.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import java.util.UUID

/** The key unlock prompt offers to remember the passphrase and saves only one that decrypted the key. */
class SshAuthenticationKeyPassphraseTest {
    private val manager = SshKeyManager()
    private val secret = UUID.randomUUID().toString()

    private class Run(val answers: List<String>?) {
        val prompts = mutableListOf<List<AuthenticationField>>()
        val saved = mutableListOf<Pair<String, String?>>()
    }

    private fun unlock(key: StoredSshKey, run: Run) = runBlocking {
        val profile = ConnectionProfileDraft(label = "host", hostname = "host", port = 22, username = "user",
            protocol = ConnectionProtocol.SSH, route = ConnectionRoute.DIRECT, keyId = key.id).toProfile()
        SshAuthentication(manager, { _, _, _, fields -> run.prompts += fields; run.answers }) { stored, passphrase ->
            run.saved += stored.id to passphrase
        }.credentials("session", profile, VaultSnapshot(keys = listOf(key))).key()
    }

    @Test fun tickingRememberSavesThePassphraseThatUnlockedTheKey() {
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret)
        val run = Run(listOf(secret, AuthenticationField.TOGGLE_ON))
        assertNotNull(unlock(key, run))
        val toggle = run.prompts.single()[1]
        assertTrue(toggle.toggle)
        assertFalse(toggle.initiallyChecked)
        assertEquals(listOf(key.id to secret), run.saved)
    }

    @Test fun leavingRememberUntickedSavesNothing() {
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret)
        val run = Run(listOf(secret, ""))
        assertNotNull(unlock(key, run))
        assertTrue(run.saved.isEmpty())
    }

    @Test fun aWrongPassphraseIsNeverSaved() {
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret)
        val run = Run(listOf(UUID.randomUUID().toString(), AuthenticationField.TOGGLE_ON))
        assertThrows(Exception::class.java) { unlock(key, run) }
        assertTrue(run.saved.isEmpty())
    }

    @Test fun aStaleRememberedPassphrasePromptsPreTickedAndIsReplacedOrCleared() {
        val stale = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret)
            .copy(savedPassphrase = UUID.randomUUID().toString())
        val replace = Run(listOf(secret, AuthenticationField.TOGGLE_ON))
        assertNotNull(unlock(stale, replace))
        assertTrue(replace.prompts.single()[1].initiallyChecked)
        assertEquals(listOf(stale.id to secret), replace.saved)

        val clear = Run(listOf(secret, ""))
        assertNotNull(unlock(stale, clear))
        assertEquals(listOf<Pair<String, String?>>(stale.id to null), clear.saved)
    }

    @Test fun aWorkingRememberedPassphraseOrAnUnencryptedKeyNeverPrompts() {
        val remembered = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret, rememberPassphrase = true)
        val plain = manager.generateKey(SshKeyGenerationType.ED25519, "plain")
        for (key in listOf(remembered, plain)) {
            val run = Run(null)
            assertNotNull(unlock(key, run))
            assertTrue(run.prompts.isEmpty())
            assertTrue(run.saved.isEmpty())
        }
    }
}
