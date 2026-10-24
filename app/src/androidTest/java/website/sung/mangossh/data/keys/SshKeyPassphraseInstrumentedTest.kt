package website.sung.mangossh.data.keys

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Re-encoding runs on the device's own providers: a key decoded from the vault may come back as a
 * platform key class that the JVM unit tests never see. Every key and passphrase is generated per run.
 */
class SshKeyPassphraseInstrumentedTest {
    private val manager = SshKeyManager()

    @Test fun storedKeysOfEveryTypeCanGainAndLoseAPassphrase() {
        listOf(SshKeyGenerationType.ED25519, SshKeyGenerationType.ECDSA_P256, SshKeyGenerationType.RSA_2048).forEach { type ->
            val secret = UUID.randomUUID().toString()
            val plain = manager.generateKey(type, "plain")

            val protected = manager.editKey(plain, KeyEditRequest("plain", null, KeyPassphraseChange.Set(secret), false))
            assertTrue(type.name, protected.requiresPassphrase)
            assertEquals(type.name, plain.fingerprint, protected.fingerprint)
            assertThrows(type.name, KeyPassphraseRequiredException::class.java) { manager.decodeKeyPair(protected) }
            assertNotNull(type.name, manager.decodeKeyPair(protected, secret))

            val removed = manager.editKey(protected, KeyEditRequest("plain", secret, KeyPassphraseChange.Remove, false))
            assertFalse(type.name, removed.requiresPassphrase)
            assertEquals(type.name, plain.fingerprint, removed.fingerprint)
        }
    }
}
