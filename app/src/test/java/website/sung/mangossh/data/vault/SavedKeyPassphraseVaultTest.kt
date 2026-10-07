package website.sung.mangossh.data.vault

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** A remembered key passphrase persists only inside encrypted payloads and only for encrypted keys. */
class SavedKeyPassphraseVaultTest {
    private val secret = UUID.randomUUID().toString()
    private fun key(requiresPassphrase: Boolean = true, savedPassphrase: String? = secret) = StoredSshKey(
        id = "key", label = "Key", algorithm = "ssh-ed25519", publicKey = "ssh-ed25519 AAAA Key",
        fingerprint = "SHA256:placeholder", privateKeyPem = "placeholder", requiresPassphrase = requiresPassphrase,
        createdAtEpochMillis = 1L, savedPassphrase = savedPassphrase,
    )

    @Test fun savedPassphraseRoundTripsThroughVaultAndPortableBackup() {
        val snapshot = VaultSnapshot(keys = listOf(key()))
        assertEquals(snapshot, VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)))
        val archivePassphrase = UUID.randomUUID().toString()
        val restored = PortableVaultCodec.decrypt(PortableVaultCodec.encrypt(snapshot, archivePassphrase.toCharArray()),
            archivePassphrase.toCharArray())
        assertEquals(secret, restored.keys.single().savedPassphrase)
    }

    @Test fun keysWithoutASavedPassphraseOmitTheFieldAndDecodeAsNull() {
        val payload = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(key(savedPassphrase = null)))).decodeToString())
        assertFalse(payload.getJSONArray("keys").getJSONObject(0).has("savedPassphrase"))
        assertNull(VaultPayloadCodec.decode(payload.toString().encodeToByteArray()).keys.single().savedPassphrase)
    }

    @Test fun rejectsNonStringPassphraseAndPassphraseForUnencryptedKey() {
        val payload = JSONObject(VaultPayloadCodec.encode(VaultSnapshot(keys = listOf(key()))).decodeToString())
        payload.getJSONArray("keys").getJSONObject(0).put("savedPassphrase", 7)
        assertThrows(Exception::class.java) { VaultPayloadCodec.decode(payload.toString().encodeToByteArray()) }
        assertThrows(BackupException::class.java) {
            BackupValidator.validate(VaultSnapshot(keys = listOf(key(requiresPassphrase = false))))
        }
        assertToStringRedacts(key())
    }

    private fun assertToStringRedacts(key: StoredSshKey) = assertFalse(key.toString().contains(secret))
}
