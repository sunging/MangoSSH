package website.sung.mangossh.data.keys

import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.vault.VaultPayloadCodec
import website.sung.mangossh.data.vault.VaultSnapshot

/** Disabled containers survive backups verbatim but cannot prompt, decrypt or authenticate. */
class DisabledKeyEncryptionTest {
    @Test fun historicalContainersRemainStoredButAreRejectedBeforePassphrasePrompt() {
        val manager = SshKeyManager()
        val generated = manager.generateEd25519("temporary")
        val bodies = listOf(
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----\n",
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: DES-CBC,00\n\nAA==\n-----END RSA PRIVATE KEY-----\n",
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: DES-EDE3-CBC,00\n\nAA==\n-----END RSA PRIVATE KEY-----\n",
        )
        for (pem in bodies) {
            val record = generated.copy(privateKeyPem = pem, requiresPassphrase = true)
            val restored = VaultPayloadCodec.decode(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(record)))).keys.single()
            assertEquals(record, restored)
            assertThrows(UnsupportedKeyEncryptionException::class.java) { manager.decodeKeyPair(restored) }
            assertThrows(UnsupportedKeyEncryptionException::class.java) { manager.importPrivateKey("temporary", pem) }
        }
        assertEquals(generated.publicKey, manager.importPrivateKey(generated.label, generated.privateKeyPem).publicKey)
    }
}
