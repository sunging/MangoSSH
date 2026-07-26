package website.sung.mangossh.data.tsnet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import tsnetbridge.StateStore
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.data.vault.AesGcmCipher
import website.sung.mangossh.data.vault.AesGcmPayload

/**
 * Device-bound StateStore for the embedded Tailnet node.
 *
 * The encrypted file lives in no-backup storage, uses a dedicated Android
 * Keystore key, and is intentionally separate from the user vault and portable
 * archives. Corrupt authenticated state is discarded as a unit so tsnet can
 * return to explicit enrollment instead of accepting a partial identity.
 */
internal interface EmbeddedTsnetStateStore : StateStore {
    fun nodeName(): String
    fun hasEnrolledIdentity(): Boolean
    fun markEnrolled()
    fun clearIdentity()
}

internal class AndroidTsnetStateStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
    stateFileName: String = STATE_FILE_NAME,
    nodeNameFileName: String = NODE_NAME_FILE_NAME,
) : EmbeddedTsnetStateStore {
    private val appContext = context.applicationContext
    private val stateFile = File(appContext.noBackupFilesDir, stateFileName)
    private val atomicStateFile = AtomicFile(stateFile)
    private val atomicNodeNameFile = AtomicFile(File(appContext.noBackupFilesDir, nodeNameFileName))

    @Synchronized
    override fun readState(key: String): ByteArray {
        validateKey(key)
        val values = readValuesRecoveringCorruption()
        return try {
            values[key]?.copyOf() ?: ByteArray(0)
        } finally {
            values.values.forEach { it.fill(0) }
        }
    }

    @Synchronized
    override fun writeState(key: String, value: ByteArray) {
        validateKey(key)
        require(value.isNotEmpty()) { "Embedded tsnet state value is empty" }
        val values = readValuesRecoveringCorruption().toMutableMap()
        try {
            values[key]?.fill(0)
            values[key] = value.copyOf()
            writeValues(values)
        } finally {
            values.values.forEach { it.fill(0) }
        }
    }

    /** Returns the stable, non-secret node name allocated once per app install. */
    @Synchronized
    override fun nodeName(): String {
        if (atomicNodeNameFile.baseFile.isFile) {
            val existing = atomicNodeNameFile.readFully().decodeToString()
            if (NODE_NAME_PATTERN.matches(existing)) return existing
        }
        val suffix = ByteArray(4).also(SecureRandom()::nextBytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        val generated = "mangossh-android-$suffix"
        val output = atomicNodeNameFile.startWrite()
        try {
            output.write(generated.encodeToByteArray())
            output.flush()
            atomicNodeNameFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicNodeNameFile.failWrite(output)
            throw throwable
        }
        return generated
    }

    @Synchronized
    override fun hasEnrolledIdentity(): Boolean {
        val values = readValuesRecoveringCorruption()
        return try {
            values[ENROLLED_MARKER_KEY]?.contentEquals(ENROLLED_MARKER_VALUE) == true
        } finally {
            values.values.forEach { it.fill(0) }
        }
    }

    @Synchronized
    override fun markEnrolled() {
        val values = readValuesRecoveringCorruption().toMutableMap()
        try {
            values[ENROLLED_MARKER_KEY] = ENROLLED_MARKER_VALUE.copyOf()
            writeValues(values)
        } finally {
            values.values.forEach { it.fill(0) }
        }
    }

    /**
     * Deletes the node identity and its Keystore key while retaining the stable
     * installation node name for a later explicit re-enrollment.
     */
    @Synchronized
    override fun clearIdentity() {
        atomicStateFile.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun readValuesRecoveringCorruption(): Map<String, ByteArray> {
        if (!stateFile.isFile) return emptyMap()
        return try {
            readValues()
        } catch (error: GeneralSecurityException) {
            recoverCorruptState(error)
            emptyMap()
        } catch (error: IllegalArgumentException) {
            recoverCorruptState(error)
            emptyMap()
        } catch (error: java.io.EOFException) {
            recoverCorruptState(error)
            emptyMap()
        }
    }

    private fun readValues(): Map<String, ByteArray> {
        val encoded = atomicStateFile.readFully()
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(FILE_MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(FILE_MAGIC)) { "Invalid encrypted tsnet state header" }
        require(input.readUnsignedByte() == FILE_FORMAT_VERSION) { "Unsupported encrypted tsnet state format" }
        val nonceLength = input.readUnsignedByte()
        require(nonceLength == AES_GCM_NONCE_SIZE) { "Invalid encrypted tsnet nonce" }
        val nonce = ByteArray(nonceLength).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in MIN_CIPHERTEXT_SIZE..MAX_CIPHERTEXT_SIZE) {
            "Invalid encrypted tsnet state length"
        }
        require(input.available() == ciphertextLength) { "Encrypted tsnet state length mismatch" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val plaintext = AesGcmCipher.decrypt(
            key = getOrCreateKey(),
            payload = AesGcmPayload(nonce, ciphertext),
            associatedData = ASSOCIATED_DATA,
        )
        return try {
            TsnetStateCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
            encoded.fill(0)
        }
    }

    private fun writeValues(values: Map<String, ByteArray>) {
        val plaintext = TsnetStateCodec.encode(values)
        try {
            val encrypted = AesGcmCipher.encrypt(
                key = getOrCreateKey(),
                plaintext = plaintext,
                associatedData = ASSOCIATED_DATA,
            )
            val output = atomicStateFile.startWrite()
            try {
                DataOutputStream(output).apply {
                    write(FILE_MAGIC)
                    writeByte(FILE_FORMAT_VERSION)
                    writeByte(encrypted.nonce.size)
                    write(encrypted.nonce)
                    writeInt(encrypted.ciphertext.size)
                    write(encrypted.ciphertext)
                    flush()
                }
                atomicStateFile.finishWrite(output)
            } catch (throwable: Throwable) {
                atomicStateFile.failWrite(output)
                throw throwable
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun recoverCorruptState(error: Exception) {
        atomicStateFile.delete()
        MangoLog.warn(MangoLogEvent.TSNET_STATE_RECOVERED, error)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun validateKey(key: String) {
        require(key.isNotEmpty() && key.length <= MAX_STATE_KEY_CHARS) { "Invalid embedded tsnet state key" }
    }

    private companion object {
        const val KEY_ALIAS = "website.sung.mangossh.embedded-tsnet-v1"
        const val STATE_FILE_NAME = "mangossh-tsnet-state.bin"
        const val NODE_NAME_FILE_NAME = "mangossh-tsnet-node-name"
        const val FILE_FORMAT_VERSION = 1
        const val AES_GCM_NONCE_SIZE = 12
        const val MIN_CIPHERTEXT_SIZE = 16
        const val MAX_CIPHERTEXT_SIZE = 6 * 1024 * 1024
        const val MAX_STATE_KEY_CHARS = 256
        const val ENROLLED_MARKER_KEY = "__mangossh_enrolled_v1"
        val ENROLLED_MARKER_VALUE = byteArrayOf(1)
        val FILE_MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte())
        val ASSOCIATED_DATA = "MangoSSH embedded tsnet state v1".encodeToByteArray()
        val NODE_NAME_PATTERN = Regex("""mangossh-android-[0-9a-f]{8}""")
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
