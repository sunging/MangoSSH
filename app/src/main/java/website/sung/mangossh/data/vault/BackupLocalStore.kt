package website.sung.mangossh.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** History handles contain no decrypted vault data. */
data class BackupHistoryEntry(val id: String, val createdAt: Long, val remote: Boolean = false)

/** Separate Keystore domains keep remembered passwords out of every vault and recovery point. */
internal class BackupLocalStore(context: Context, private val keyNamespace: String = "website.sung.mangossh.backup") {
    private val directory = File(context.noBackupFilesDir, "backup-state")
    private val history = File(directory, "history")
    private val secrets = File(directory, "passwords")
    private val revisions = File(directory, "revisions")

    fun checkpoint(snapshot: VaultSnapshot): BackupHistoryEntry {
        val now = maxOf(System.currentTimeMillis(), (list().firstOrNull()?.createdAt ?: 0) + 1)
        val entry = BackupHistoryEntry("$now-${UUID.randomUUID()}", now)
        val bytes = VaultPayloadCodec.encode(snapshot)
        try { write(File(history, entry.id), bytes, "history") } finally { bytes.fill(0) }
        return entry
    }

    fun list(): List<BackupHistoryEntry> = history.listFiles().orEmpty()
        .filter { it.isFile && it.name.removeSuffix(".bak").matches(Regex("[0-9]+-[0-9a-f-]{36}")) }
        .map { it.name.removeSuffix(".bak") }
        .distinct()
        .map { BackupHistoryEntry(it, it.substringBefore('-').toLong()) }
        .sortedByDescending { it.createdAt }

    fun prune() {
        list().drop(10).forEach {
            val file = File(history, it.id)
            AtomicFile(file).delete()
            if (exists(file)) throw BackupException(BackupFailure.STORAGE)
        }
    }

    fun readHistory(id: String): BackupArchive {
        val entry = list().firstOrNull { it.id == id } ?: throw BackupException(BackupFailure.INVALID)
        val bytes = read(File(history, id), "history") ?: throw BackupException(BackupFailure.STORAGE)
        return try { BackupArchive(VaultPayloadCodec.decode(bytes), BackupMetadata(id.substringAfter('-'), entry.createdAt, "unknown")) } finally { bytes.fill(0) }
    }

    fun remember(target: String, password: CharArray) {
        val bytes = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val content = ByteArray(bytes.remaining()).also(bytes::get)
        try { write(File(secrets, target), content, "passwords") } finally {
            content.fill(0)
            if (bytes.hasArray()) bytes.array().fill(0)
        }
    }

    fun password(target: String): CharArray? {
        val bytes = read(File(secrets, target), "passwords") ?: return null
        return try {
            val chars = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
            CharArray(chars.remaining()).also { chars.get(it); if (chars.hasArray()) chars.array().fill('\u0000') }
        } finally { bytes.fill(0) }
    }

    fun hasPassword(target: String) = exists(File(secrets, target))
    fun forget(target: String) {
        AtomicFile(File(secrets, target)).delete()
        if (hasPassword(target)) throw BackupException(BackupFailure.STORAGE)
    }
    fun revision(target: String): String? = read(File(revisions, target), "revisions")?.let { bytes ->
        try { bytes.decodeToString() } finally { bytes.fill(0) }
    }
    fun revision(target: String, value: String) = write(File(revisions, target), value.encodeToByteArray(), "revisions")

    private fun key(domain: String): SecretKey {
        val alias = "$keyNamespace.$domain.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun write(file: File, bytes: ByteArray, domain: String) {
        requireNotNull(file.parentFile).let { if (!it.isDirectory && !it.mkdirs()) throw BackupException(BackupFailure.STORAGE) }
        val encrypted = AesGcmCipher.encrypt(key(domain), bytes, "$domain:${file.name}".encodeToByteArray())
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(encrypted.nonce); stream.write(encrypted.ciphertext); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun read(file: File, domain: String): ByteArray? {
        if (!exists(file)) return null
        val bytes = AtomicFile(file).openRead().use { BackupLimits.readLimited(it) }
        if (bytes.size < 28) throw BackupException(BackupFailure.STORAGE)
        return AesGcmCipher.decrypt(key(domain), AesGcmPayload(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size)), "$domain:${file.name}".encodeToByteArray())
    }

    private fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()

    companion object {
        fun target(config: WebDavConfig?): String = if (config == null) "manual" else digest(
            listOf(config.endpoint.trimEnd('/'), config.username, config.remoteFileName).joinToString("\u0000").encodeToByteArray(),
        )
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
