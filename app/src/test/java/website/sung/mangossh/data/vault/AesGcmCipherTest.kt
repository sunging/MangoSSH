package website.sung.mangossh.data.vault

import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AesGcmCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 3).toByte() }, "AES")
    private val associatedData = "profile:123".encodeToByteArray()

    @Test
    fun roundTripPreservesPlaintext() {
        val plaintext = "MangoSSH vault payload".encodeToByteArray()
        val encrypted = AesGcmCipher.encrypt(key, plaintext, associatedData)

        val decrypted = AesGcmCipher.decrypt(key, encrypted, associatedData)

        assertEquals(12, encrypted.nonce.size)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = AesGcmCipher.encrypt(key, "secret".encodeToByteArray(), associatedData)
        val modified = encrypted.ciphertext.copyOf().also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
        }

        try {
            AesGcmCipher.decrypt(
                key = key,
                payload = encrypted.copy(ciphertext = modified),
                associatedData = associatedData,
            )
            fail("Modified ciphertext must not decrypt")
        } catch (_: GeneralSecurityException) {
            // Expected: AES-GCM authenticates every ciphertext byte.
        }
    }
}
