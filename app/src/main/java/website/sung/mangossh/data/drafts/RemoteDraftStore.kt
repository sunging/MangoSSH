package website.sung.mangossh.data.drafts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import website.sung.mangossh.data.vault.AesGcmCipher
import website.sung.mangossh.data.vault.AesGcmPayload

/**
 * An unsaved edit of a remote file.
 *
 * [baseDigest] is the SHA-256 of the remote bytes the edit started from, so a later
 * reopen can tell whether the file changed underneath it. Path and text are user data:
 * they are never logged and never leave this device (not in vault backups either).
 */
internal class RemoteDraft(
    val profileId: String,
    val path: String,
    val text: String,
    val baseDigest: ByteArray,
    val savedAtEpochMillis: Long,
)

/**
 * Encrypted, device-only storage for editor drafts that could not be saved.
 *
 * Each draft is AES-GCM encrypted under its own Keystore key (separate from the vault
 * and backup keys) and bound to its file name as associated data, so a file renamed
 * onto another draft's slot fails to decrypt. File names are digests of profile and
 * path, never the path itself. Files live in `noBackupFilesDir`, which Android backup
 * and device transfer skip. At most [MAX_DRAFTS] are kept; the oldest go first.
 */
internal class RemoteDraftStore(
    private val directory: File,
    private val key: () -> SecretKey,
) {
    fun save(draft: RemoteDraft) {
        val plain = RemoteDraftCodec.encode(draft)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Draft directory unavailable")
            val file = fileFor(draft.profileId, draft.path)
            val sealed = AesGcmCipher.encrypt(key(), plain, associatedData(file))
            val staging = File(directory, file.name + ".tmp")
            staging.outputStream().use { it.write(sealed.nonce); it.write(sealed.ciphertext) }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            plain.fill(0)
        }
        prune()
    }

    /** Null when there is no draft, or when it cannot be read (it is then removed). */
    fun load(profileId: String, path: String): RemoteDraft? {
        val file = fileFor(profileId, path)
        if (!file.isFile) return null
        return read(file)?.takeIf { it.profileId == profileId && it.path == path }
    }

    fun delete(profileId: String, path: String) {
        fileFor(profileId, path).delete()
    }

    /**
     * Removes every draft of a deleted host. File names do not reveal the profile, so each
     * draft is decrypted to check; unreadable ones are removed by [load] on the way.
     */
    fun deleteProfile(profileId: String) {
        drafts().forEach { file -> if (read(file)?.profileId == profileId) file.delete() }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    fun count(): Int = drafts().size

    private fun drafts(): List<File> = directory.listFiles()?.filter { it.isFile && NAME.matches(it.name) }.orEmpty()

    /** Decrypts one draft file; a file that fails authentication or parsing is deleted. */
    private fun read(file: File): RemoteDraft? {
        val plain = try {
            val bytes = file.readBytes()
            if (bytes.size < NONCE_BYTES + 16 || bytes.size > MAX_FILE_BYTES) throw java.io.IOException("Bad draft")
            AesGcmCipher.decrypt(key(), AesGcmPayload(bytes.copyOfRange(0, NONCE_BYTES), bytes.copyOfRange(NONCE_BYTES, bytes.size)),
                associatedData(file))
        } catch (_: Exception) {
            file.delete()
            return null
        }
        return try {
            RemoteDraftCodec.decode(plain)
        } catch (_: Exception) {
            file.delete()
            null
        } finally {
            plain.fill(0)
        }
    }

    private fun prune() {
        drafts().sortedByDescending { it.lastModified() }.drop(MAX_DRAFTS).forEach { it.delete() }
    }

    private fun fileFor(profileId: String, path: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((profileId + "\u0000" + path).encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.draft")
    }

    private fun associatedData(file: File) = "remote-draft:${file.name}".encodeToByteArray()

    companion object {
        const val MAX_DRAFTS = 20
        private const val NONCE_BYTES = 12
        private const val MAX_FILE_BYTES = 1 shl 20
        private val NAME = Regex("[0-9a-f]{64}\\.draft")
        private const val KEY_ALIAS = "website.sung.mangossh.remote-draft.v1"

        /** The production store: app-private, excluded from backup, keyed in Android Keystore. */
        fun create(context: Context) = RemoteDraftStore(File(context.noBackupFilesDir, "remote-drafts")) { keystoreKey() }

        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}

/** Versioned binary layout of a draft before encryption. */
internal object RemoteDraftCodec {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 256 * 1024

    fun encode(draft: RemoteDraft): ByteArray {
        val text = draft.text.encodeToByteArray()
        require(text.size <= MAX_TEXT_BYTES)
        val bytes = ByteArrayOutputStream()
        try {
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(draft.profileId)
                out.writeUTF(draft.path)
                out.writeLong(draft.savedAtEpochMillis)
                out.writeInt(draft.baseDigest.size)
                out.write(draft.baseDigest)
                out.writeInt(text.size)
                out.write(text)
            }
            return bytes.toByteArray()
        } finally {
            text.fill(0)
        }
    }

    fun decode(bytes: ByteArray): RemoteDraft = DataInputStream(bytes.inputStream()).use { input ->
        require(input.readInt() == VERSION)
        val profileId = input.readUTF()
        val path = input.readUTF()
        val savedAt = input.readLong()
        val digest = ByteArray(input.readInt().also { require(it in 0..64) }).also(input::readFully)
        val text = ByteArray(input.readInt().also { require(it in 0..MAX_TEXT_BYTES) }).also(input::readFully)
        require(input.read() == -1)
        try { RemoteDraft(profileId, path, text.decodeToString(), digest, savedAt) } finally { text.fill(0) }
    }
}
