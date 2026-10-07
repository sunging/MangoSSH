package website.sung.mangossh.data.keys

import org.connectbot.sshlib.SshKeys
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.UUID

/** Passphrase handling for generated, imported and edited keys; every passphrase is random per run. */
class SshKeyEditTest {
    private val manager = SshKeyManager()
    private fun passphrase() = UUID.randomUUID().toString()

    @Test fun generatedKeyWithPassphraseIsEncryptedAndRemembersOnlyOnRequest() {
        val secret = passphrase()
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "encrypted", secret)
        assertTrue(key.requiresPassphrase)
        assertNull(key.savedPassphrase)
        assertTrue(manager.isPassphraseProtected(key.privateKeyPem))
        assertThrows(KeyPassphraseRequiredException::class.java) { manager.decodeKeyPair(key) }
        assertEquals("EdDSA", manager.decodeKeyPair(key, secret).public.algorithm)

        val remembered = manager.generateKey(SshKeyGenerationType.ED25519, "remembered", secret, rememberPassphrase = true)
        assertEquals(secret, remembered.savedPassphrase)
        assertEquals("EdDSA", manager.decodeKeyPair(remembered).public.algorithm)

        val plain = manager.generateKey(SshKeyGenerationType.ED25519, "plain", "", rememberPassphrase = true)
        assertFalse(plain.requiresPassphrase)
        assertNull(plain.savedPassphrase)
    }

    @Test fun importRemembersAVerifiedPassphraseOnlyForEncryptedKeys() {
        val secret = passphrase()
        val source = manager.generateKey(SshKeyGenerationType.ED25519, "source", secret)
        val imported = manager.importPrivateKey("imported", source.privateKeyPem, secret, rememberPassphrase = true)
        assertEquals(secret, imported.savedPassphrase)
        assertEquals(source.fingerprint, imported.fingerprint)

        val plain = manager.generateKey(SshKeyGenerationType.ED25519, "plain")
        assertNull(manager.importPrivateKey("plain", plain.privateKeyPem, secret, rememberPassphrase = true).savedPassphrase)
    }

    @Test fun renameRewritesOnlyTheLabelAndPublicKeyComment() {
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "old name")
        val edited = manager.editKey(key, KeyEditRequest("  new name  ", null, KeyPassphraseChange.Keep, false))
        assertEquals("new name", edited.label)
        assertEquals(key.publicKey.split(' ').take(2).joinToString(" ") + " new name", edited.publicKey)
        assertEquals(key.copy(label = edited.label, publicKey = edited.publicKey), edited)

        val blank = manager.editKey(key, KeyEditRequest(" ", null, KeyPassphraseChange.Keep, false))
        assertEquals(key, blank)
    }

    @Test fun passphraseCanBeSetChangedAndRemovedWithoutChangingTheKey() {
        val first = passphrase()
        val second = passphrase()
        val original = manager.generateKey(SshKeyGenerationType.ECDSA_P256, "key")

        val protected = manager.editKey(original, KeyEditRequest("key", null, KeyPassphraseChange.Set(first), false))
        assertTrue(protected.requiresPassphrase)
        assertNull(protected.savedPassphrase)
        assertSameIdentity(original, protected)
        manager.decodeKeyPair(protected, first)

        val changed = manager.editKey(protected, KeyEditRequest("key", first, KeyPassphraseChange.Set(second), true))
        assertEquals(second, changed.savedPassphrase)
        assertSameIdentity(original, changed)
        assertThrows(Exception::class.java) { manager.decodeKeyPair(changed, first) }
        manager.decodeKeyPair(changed)

        // The remembered passphrase authorizes removal without asking again.
        val removed = manager.editKey(changed, KeyEditRequest("key", null, KeyPassphraseChange.Remove, true))
        assertFalse(removed.requiresPassphrase)
        assertNull(removed.savedPassphrase)
        assertFalse(manager.isPassphraseProtected(removed.privateKeyPem))
        assertSameIdentity(original, removed)
        manager.decodeKeyPair(removed)
    }

    @Test fun rememberingAnExistingPassphraseVerifiesItFirst() {
        val secret = passphrase()
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "key", secret)
        assertThrows(IncorrectKeyPassphraseException::class.java) {
            manager.editKey(key, KeyEditRequest("key", passphrase(), KeyPassphraseChange.Keep, true))
        }
        assertThrows(KeyPassphraseRequiredException::class.java) {
            manager.editKey(key, KeyEditRequest("key", null, KeyPassphraseChange.Keep, true))
        }
        val remembered = manager.editKey(key, KeyEditRequest("key", secret, KeyPassphraseChange.Keep, true))
        assertEquals(key.privateKeyPem, remembered.privateKeyPem)
        assertEquals(secret, remembered.savedPassphrase)

        val forgotten = manager.editKey(remembered, KeyEditRequest("key", null, KeyPassphraseChange.Keep, false))
        assertNull(forgotten.savedPassphrase)
        assertEquals(key, forgotten)
    }

    @Test fun wrongCurrentPassphraseIsReportedAndLeavesNoChange() {
        val key = manager.generateKey(SshKeyGenerationType.ED25519, "key", passphrase())
        assertThrows(IncorrectKeyPassphraseException::class.java) {
            manager.editKey(key, KeyEditRequest("key", passphrase(), KeyPassphraseChange.Remove, false))
        }
        assertThrows(KeyPassphraseRequiredException::class.java) {
            manager.editKey(key, KeyEditRequest("key", null, KeyPassphraseChange.Set(passphrase()), false))
        }
    }

    @Test fun encryptedPemKeyIsRewrittenAsOpenSsh() {
        val secret = passphrase()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = SshKeys.encodePemPrivateKey(pair, secret)
        val imported = manager.importPrivateKey("pem", pem, secret)
        val next = passphrase()
        val edited = manager.editKey(imported, KeyEditRequest("pem", secret, KeyPassphraseChange.Set(next), false))
        assertTrue(edited.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertSameIdentity(imported, edited)
        manager.decodeKeyPair(edited, next)
    }

    @Test fun disabledFormatsAndDsaCanBeRenamedButNotReEncrypted() {
        val generated = manager.generateKey(SshKeyGenerationType.ED25519, "key")
        val disabled = generated.copy(requiresPassphrase = true,
            privateKeyPem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----\n")
        assertFalse(disabled.canChangePassphrase())
        assertEquals("renamed", manager.editKey(disabled, KeyEditRequest("renamed", null, KeyPassphraseChange.Keep, false)).label)
        assertThrows(UnsupportedKeyEncryptionException::class.java) {
            manager.editKey(disabled, KeyEditRequest("key", passphrase(), KeyPassphraseChange.Remove, false))
        }
        val dsa = generated.copy(algorithm = "ssh-dss")
        assertFalse(dsa.canChangePassphrase())
        assertThrows(UnsupportedDsaKeyException::class.java) {
            manager.editKey(dsa, KeyEditRequest("key", null, KeyPassphraseChange.Set(passphrase()), false))
        }
        assertTrue(generated.canChangePassphrase())
    }

    private fun assertSameIdentity(expected: website.sung.mangossh.data.vault.StoredSshKey, actual: website.sung.mangossh.data.vault.StoredSshKey) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.fingerprint, actual.fingerprint)
        assertEquals(expected.publicKey, actual.publicKey)
        assertEquals(expected.algorithm, actual.algorithm)
        assertEquals(expected.createdAtEpochMillis, actual.createdAtEpochMillis)
    }
}
