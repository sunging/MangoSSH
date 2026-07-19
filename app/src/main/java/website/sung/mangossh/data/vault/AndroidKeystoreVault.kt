package website.sung.mangossh.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Encrypts the current local vault with an AES-256 key held by Android Keystore.
 * The file is authenticated before any JSON is parsed and AtomicFile prevents a
 * partially written vault from replacing a usable one.
 */
class AndroidKeystoreVault(context: Context) {
    private val appContext = context.applicationContext
    private val vaultFile = File(appContext.filesDir, VAULT_FILE_NAME)
    private val atomicFile = AtomicFile(vaultFile)

    @Synchronized
    fun read(): VaultSnapshot? {
        if (!vaultFile.exists()) return null
        val encoded = atomicFile.readFully()
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Unrecognized vault header" }
        require(input.readUnsignedByte() == FORMAT_VERSION) { "Unsupported vault format" }
        val nonceSize = input.readUnsignedByte()
        require(nonceSize == AES_GCM_NONCE_SIZE) { "Invalid vault nonce" }
        val nonce = ByteArray(nonceSize)
        input.readFully(nonce)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in MIN_CIPHERTEXT_SIZE..MAX_CIPHERTEXT_SIZE) { "Invalid vault length" }
        require(input.available() == ciphertextSize) { "Vault has trailing or missing data" }
        val ciphertext = ByteArray(ciphertextSize)
        input.readFully(ciphertext)
        val plaintext = AesGcmCipher.decrypt(
            key = getOrCreateKey(),
            payload = AesGcmPayload(nonce = nonce, ciphertext = ciphertext),
            associatedData = ASSOCIATED_DATA,
        )
        return try {
            VaultPayloadCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun write(snapshot: VaultSnapshot) {
        val plaintext = VaultPayloadCodec.encode(snapshot)
        require(plaintext.size <= MAX_PLAINTEXT_SIZE) { "Vault payload is too large" }
        try {
            val payload = AesGcmCipher.encrypt(
                key = getOrCreateKey(),
                plaintext = plaintext,
                associatedData = ASSOCIATED_DATA,
            )
            val output = atomicFile.startWrite()
            try {
                DataOutputStream(output).apply {
                    write(MAGIC)
                    writeByte(FORMAT_VERSION)
                    writeByte(payload.nonce.size)
                    write(payload.nonce)
                    writeInt(payload.ciphertext.size)
                    write(payload.ciphertext)
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (throwable: Throwable) {
                atomicFile.failWrite(output)
                throw throwable
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "website.sung.mangossh.local-vault-v1"
        private const val VAULT_FILE_NAME = "mangossh-vault.bin"
        private const val FORMAT_VERSION = 1
        private const val AES_GCM_NONCE_SIZE = 12
        private const val MIN_CIPHERTEXT_SIZE = 16
        private const val MAX_CIPHERTEXT_SIZE = 6 * 1024 * 1024
        private const val MAX_PLAINTEXT_SIZE = 5 * 1024 * 1024
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte())
        private val ASSOCIATED_DATA = "MangoSSH local vault v1".encodeToByteArray()
    }
}
