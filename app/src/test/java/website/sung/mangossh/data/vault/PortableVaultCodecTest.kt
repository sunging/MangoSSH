package website.sung.mangossh.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PortableVaultCodecTest {
    @Test
    fun roundTripPreservesVault() {
        val source = VaultSnapshot(
            snippets = listOf(CommandSnippet(id = "bootstrap", label = "Bootstrap", script = "uname -a")),
        )

        val encrypted = PortableVaultCodec.encrypt(source, "sync-passphrase".toCharArray())
        val restored = PortableVaultCodec.decrypt(encrypted, "sync-passphrase".toCharArray())

        assertEquals(source, restored)
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = PortableVaultCodec.encrypt(VaultSnapshot(), "sync-passphrase".toCharArray())
        val modified = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        try {
            PortableVaultCodec.decrypt(modified, "sync-passphrase".toCharArray())
            fail("Modified portable vault must not decrypt")
        } catch (_: IllegalArgumentException) {
            // Expected: AES-GCM authenticates the encrypted backup.
        }
    }
}
