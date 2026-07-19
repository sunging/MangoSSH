package website.sung.mangossh.data.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AesGcmPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

/** Small AEAD wrapper used for local vault data and later portable archives. */
object AesGcmCipher {
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private val secureRandom = SecureRandom()

    @Throws(GeneralSecurityException::class)
    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): AesGcmPayload {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(associatedData)
        return AesGcmPayload(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(
        key: SecretKey,
        payload: AesGcmPayload,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        require(payload.nonce.size == NONCE_SIZE_BYTES) { "Unexpected AES-GCM nonce size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, payload.nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.ciphertext)
    }
}
