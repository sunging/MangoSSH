package website.sung.mangossh.data.vault

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import java.util.UUID

/**
 * Password-protected portable vault format for manual export and WebDAV sync.
 * It intentionally uses a key derived from a user supplied passphrase instead
 * of the device-bound Android Keystore key used for local storage.
 */
internal object PortableVaultCodec {
    private const val VERSION: Byte = 3
    private const val LEGACY_VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 310_000
    private const val GCM_TAG_BYTES = 16
    const val MAX_FILE_BYTES = BackupLimits.MAX_FILE_BYTES
    private const val MAX_CIPHERTEXT_BYTES = MAX_FILE_BYTES - 40
    private val magic = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'X'.code.toByte())

    fun encrypt(snapshot: VaultSnapshot, passphrase: CharArray, appVersion: String = "unknown"): ByteArray {
        require(passphrase.isNotEmpty()) { "The sync passphrase is empty." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        BackupValidator.validate(snapshot)
        val payload = VaultPayloadCodec.encode(snapshot)
        val plaintext = try {
            JSONObject().put("id", UUID.randomUUID().toString())
                .put("createdAt", System.currentTimeMillis()).put("appVersion", appVersion)
                .put("payload", JSONObject(payload.decodeToString())).toString().encodeToByteArray()
        } finally { payload.fill(0) }
        val expectedCiphertextLength = plaintext.size + GCM_TAG_BYTES
        if (expectedCiphertextLength > MAX_CIPHERTEXT_BYTES) {
            plaintext.fill(0)
            throw BackupException(BackupFailure.TOO_LARGE)
        }
        val header = header(salt, nonce, ciphertextLength = expectedCiphertextLength)
        val keyBytes = try { deriveKey(passphrase, salt) } catch (error: Exception) { plaintext.fill(0); throw error }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "The encrypted vault exceeds the export limit." }
            check(ciphertext.size == expectedCiphertextLength) { "Unexpected AES-GCM output length" }
            return header + ciphertext
        } finally {
            keyBytes.fill(0)
            plaintext.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): VaultSnapshot = decryptArchive(blob, passphrase).snapshot

    fun decryptArchive(blob: ByteArray, passphrase: CharArray): BackupArchive {
        require(passphrase.isNotEmpty()) { "The sync passphrase is empty." }
        val parsed = parse(blob)
        val keyBytes = deriveKey(passphrase, parsed.salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, parsed.nonce))
            val authenticatedLength = if (parsed.version >= 2) parsed.ciphertext.size else 0
            cipher.updateAAD(header(parsed.salt, parsed.nonce, authenticatedLength, parsed.version))
            val plaintext = try {
                cipher.doFinal(parsed.ciphertext)
            } catch (error: GeneralSecurityException) {
                throw BackupException(BackupFailure.AUTHENTICATION)
            }
            return try {
                if (parsed.version < 3) BackupArchive(VaultPayloadCodec.decode(plaintext), null)
                else {
                    val root = JSONObject(plaintext.decodeToString())
                    require(root.get("id") is String && root.get("appVersion") is String)
                    require(root.get("createdAt") is Long || root.get("createdAt") is Int)
                    val metadata = BackupMetadata(root.getString("id"), root.getLong("createdAt"), root.getString("appVersion"))
                    UUID.fromString(metadata.id)
                    require(metadata.createdAt >= 0)
                    val payload = root.getJSONObject("payload").toString().encodeToByteArray()
                    try { BackupArchive(VaultPayloadCodec.decode(payload), metadata) } finally { payload.fill(0) }
                }
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
        if (blob.size > MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        val minimumSize = magic.size + 1 + 1 + SALT_BYTES + 1 + NONCE_BYTES + Int.SIZE_BYTES + 16
        require(blob.size >= minimumSize) { "The blob is not a valid MangoSSH encrypted backup." }
        val buffer = ByteBuffer.wrap(blob)
        val parsedMagic = ByteArray(magic.size).also(buffer::get)
        require(parsedMagic.contentEquals(magic)) { "The blob is not a valid MangoSSH encrypted backup." }
        val version = buffer.get()
        if (version != VERSION && version != 2.toByte() && version != LEGACY_VERSION) throw BackupException(BackupFailure.VERSION)
        val saltLength = buffer.get().toInt() and 0xFF
        require(saltLength == SALT_BYTES) { "The backup header is invalid." }
        val salt = ByteArray(saltLength).also(buffer::get)
        val nonceLength = buffer.get().toInt() and 0xFF
        require(nonceLength == NONCE_BYTES) { "The backup header is invalid." }
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertextLength = buffer.int
        require(ciphertextLength in 16..MAX_CIPHERTEXT_BYTES && ciphertextLength == buffer.remaining()) {
            "The backup length is invalid."
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
