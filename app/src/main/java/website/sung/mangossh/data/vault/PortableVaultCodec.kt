package website.sung.mangossh.data.vault

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected portable vault format for manual export and WebDAV sync.
 * It intentionally uses a key derived from a user supplied passphrase instead
 * of the device-bound Android Keystore key used for local storage.
 */
internal object PortableVaultCodec {
    private const val VERSION: Byte = 2
    private const val LEGACY_VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 310_000
    private const val GCM_TAG_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 16 * 1024 * 1024
    private val magic = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'X'.code.toByte())

    fun encrypt(snapshot: VaultSnapshot, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "同步口令不能为空。" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val plaintext = VaultPayloadCodec.encode(snapshot)
        val expectedCiphertextLength = plaintext.size + GCM_TAG_BYTES
        require(expectedCiphertextLength <= MAX_CIPHERTEXT_BYTES) { "Vault is too large to export" }
        val header = header(salt, nonce, ciphertextLength = expectedCiphertextLength)
        val keyBytes = deriveKey(passphrase, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "保险库过大，无法导出。" }
            check(ciphertext.size == expectedCiphertextLength) { "Unexpected AES-GCM output length" }
            return header + ciphertext
        } finally {
            keyBytes.fill(0)
            plaintext.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): VaultSnapshot {
        require(passphrase.isNotEmpty()) { "同步口令不能为空。" }
        val parsed = parse(blob)
        val keyBytes = deriveKey(passphrase, parsed.salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, parsed.nonce))
            val authenticatedLength = if (parsed.version == VERSION) parsed.ciphertext.size else 0
            cipher.updateAAD(header(parsed.salt, parsed.nonce, authenticatedLength, parsed.version))
            val plaintext = try {
                cipher.doFinal(parsed.ciphertext)
            } catch (error: GeneralSecurityException) {
                throw IllegalArgumentException("同步口令错误或备份文件已损坏。", error)
            }
            return try {
                VaultPayloadCodec.decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun header(
        salt: ByteArray,
        nonce: ByteArray,
        ciphertextLength: Int,
        version: Byte = VERSION,
    ): ByteArray =
        ByteBuffer.allocate(magic.size + 1 + 1 + salt.size + 1 + nonce.size + Int.SIZE_BYTES)
            .put(magic)
            .put(version)
            .put(salt.size.toByte())
            .put(salt)
            .put(nonce.size.toByte())
            .put(nonce)
            .putInt(ciphertextLength)
            .array()

    private fun parse(blob: ByteArray): ParsedVault {
        val minimumSize = magic.size + 1 + 1 + SALT_BYTES + 1 + NONCE_BYTES + Int.SIZE_BYTES + 16
        require(blob.size >= minimumSize) { "不是有效的 MangoSSH 加密备份。" }
        val buffer = ByteBuffer.wrap(blob)
        val parsedMagic = ByteArray(magic.size).also(buffer::get)
        require(parsedMagic.contentEquals(magic)) { "不是有效的 MangoSSH 加密备份。" }
        val version = buffer.get()
        require(version == VERSION || version == LEGACY_VERSION) { "Unsupported backup version" }
        val saltLength = buffer.get().toInt() and 0xFF
        require(saltLength == SALT_BYTES) { "备份文件头无效。" }
        val salt = ByteArray(saltLength).also(buffer::get)
        val nonceLength = buffer.get().toInt() and 0xFF
        require(nonceLength == NONCE_BYTES) { "备份文件头无效。" }
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertextLength = buffer.int
        require(ciphertextLength in 16..MAX_CIPHERTEXT_BYTES && ciphertextLength == buffer.remaining()) {
            "备份文件长度无效。"
        }
        return ParsedVault(version, salt, nonce, ByteArray(ciphertextLength).also(buffer::get))
    }

    private data class ParsedVault(
        val version: Byte,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )
}
