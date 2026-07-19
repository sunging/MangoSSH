package website.sung.mangossh.data.vault

import java.security.GeneralSecurityException
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

    /**
     * Encrypts [plaintext] using a provider-created GCM nonce.
     *
     * Android Keystore keys keep IND-CPA/randomized encryption enabled. In that
     * mode Android correctly rejects a caller-supplied IV, even if the caller
     * generated it with [java.security.SecureRandom]. Initializing the cipher
     * without parameters delegates nonce generation to the selected provider;
     * the nonce is then stored alongside the ciphertext for decryption.
     */
    @Throws(GeneralSecurityException::class)
    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): AesGcmPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = requireNotNull(cipher.iv) { "AES-GCM provider did not return a nonce" }
        require(nonce.size == NONCE_SIZE_BYTES) { "Unexpected AES-GCM nonce size" }
        return AesGcmPayload(nonce = nonce, ciphertext = ciphertext)
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
