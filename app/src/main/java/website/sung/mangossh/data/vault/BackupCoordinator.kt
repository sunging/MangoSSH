package website.sung.mangossh.data.vault

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import website.sung.mangossh.data.sync.RemoteBackup
import website.sung.mangossh.data.sync.WebDavClient
import website.sung.mangossh.data.sync.BackupRemoteTransport
import kotlin.coroutines.coroutineContext

/** User-visible operation stages contain no sensitive contents. */
enum class BackupPhase { IDLE, READING, DECRYPTING, SAVING, UPLOADING }

/** Safe UI state; decrypted archives and export bytes stay owned by the coordinator. */
data class BackupOperationState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val preview: ImportPreview? = null,
    val history: List<BackupHistoryEntry>? = null,
    val remoteConflict: Boolean = false,
    val exportReady: Boolean = false,
    val rememberedManual: Boolean = false,
    val rememberedRemote: Boolean = false,
    val failure: BackupFailure? = null,
    val completed: Boolean = false,
)

/** Owns I/O, plaintext lifetime and pending confirmations. A lock invalidates all pending work. */
internal class BackupCoordinator(
    context: Context,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
    private val unlocked: () -> Boolean,
    private val appVersion: () -> String = { "unknown" },
    private val remote: BackupRemoteTransport = WebDavClient(),
    private val localStoreFactory: () -> BackupLocalStore = { BackupLocalStore(context.applicationContext) },
) {
    private val resolver = context.applicationContext.contentResolver
    private val local by lazy(localStoreFactory)
    private val mutable = MutableStateFlow(BackupOperationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var cleanupPending = false
    private var pending: PendingImport? = null
    private var upload: PendingUpload? = null
    private var export: ByteArray? = null
    private class PendingImport(val archive: BackupArchive, var preview: ImportPreview, val target: String?, val remoteDigest: String?)
    private class PendingUpload(val config: WebDavConfig, val bytes: ByteArray, val previous: RemoteBackup?, val revision: Long)

    /** Exposes only saved-password presence, never the saved value. */
    fun refreshPasswords() = execute(BackupPhase.READING) {
        update { it.copy(rememberedManual = local.hasPassword("manual"), rememberedRemote = remotePasswordPresent()) }
    }

    /** Removes a target's saved password without modifying any archive. */
    fun forget(remotePassword: Boolean) = execute(BackupPhase.SAVING) {
        local.forget(if (remotePassword) BackupLocalStore.target(config()) else "manual")
        update { it.copy(rememberedManual = local.hasPassword("manual"), rememberedRemote = remotePasswordPresent()) }
    }

    /** Produces coordinator-owned ciphertext for the subsequent document picker result. */
    fun prepareExport(password: String?, remember: Boolean, includeConfig: Boolean) = execute(BackupPhase.SAVING) {
        val snapshot = vault.backupSnapshot().second.let { if (includeConfig) it else it.copy(webDavConfig = null) }
        export = withPassword(password, "manual", remember) { PortableVaultCodec.encrypt(snapshot, it, appVersion()) }
        update { it.copy(exportReady = true) }
    }

    /** A cancelled picker discards its ciphertext; only a closed output stream counts as success. */
    fun writeExport(uri: Uri?) {
        if (uri == null) { cancel(); return }
        execute(BackupPhase.SAVING) {
            val bytes = export ?: throw BackupException(BackupFailure.INVALID)
            try {
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                    ?: throw BackupException(BackupFailure.STORAGE)
                update { it.copy(exportReady = false, completed = true) }
            } finally { bytes.fill(0); export = null; update { it.copy(exportReady = false) } }
        }
    }

    /** Reads a bounded document on I/O and prepares, but does not apply, its import. */
    fun importFile(uri: Uri, password: String?, remember: Boolean) = execute(BackupPhase.READING) {
        val bytes = resolver.openInputStream(uri)?.use { WebDavClient.readLimited(it) }
            ?: throw BackupException(BackupFailure.STORAGE)
        try {
            update { it.copy(phase = BackupPhase.DECRYPTING) }
            val archive = withPassword(password, "manual", remember) { PortableVaultCodec.decryptArchive(bytes, it) }
            preview(archive)
        } finally { bytes.fill(0) }
    }

    /** Defers acknowledging the downloaded remote revision until local import commits. */
    fun download(password: String?, remember: Boolean) = execute(BackupPhase.READING) {
        val config = config()
        val result = remote.download(config) ?: throw BackupException(BackupFailure.NETWORK)
        try {
            update { it.copy(phase = BackupPhase.DECRYPTING) }
            val archive = withPassword(password, BackupLocalStore.target(config), remember) { PortableVaultCodec.decryptArchive(result.bytes, it) }
            preview(archive, BackupLocalStore.target(config), BackupLocalStore.digest(result.bytes))
        } finally { result.bytes.fill(0) }
    }

    /** Requires a current preview and persists recovery before changing the live vault. */
    fun commit(decision: ImportDecision) = execute(BackupPhase.SAVING) {
        val prepared = pending ?: throw BackupException(BackupFailure.INVALID)
        if (decision.operationId != prepared.preview.operationId) throw BackupException(BackupFailure.CHANGED)
        val epoch = generation
        val saved = vault.commitImport(prepared.preview.revision, prepared.archive.snapshot, decision, local) { unlocked() && epoch == generation }
        val result = if (!saved) {
            preview(prepared.archive, prepared.target, prepared.remoteDigest)
            ImportResult.Changed(requireNotNull(pending).preview)
        } else ImportResult.Saved
        when (result) {
            is ImportResult.Changed -> update { it.copy(preview = result.preview, failure = BackupFailure.CHANGED) }
            ImportResult.Saved -> {
                pending = null
                update { it.copy(preview = null, completed = true) }
                try {
                    if (prepared.target != null && prepared.remoteDigest != null) local.revision(prepared.target, prepared.remoteDigest)
                    local.prune()
                } catch (_: Exception) { update { it.copy(failure = BackupFailure.PARTIAL) } }
            }
        }
    }

    /** Loads only history handles; decryption waits for an explicit restore selection. */
    fun listHistory(onRemote: Boolean) = execute(BackupPhase.READING) {
        update { it.copy(history = if (onRemote) remote.history(config()) else local.list()) }
    }

    /** Historical passwords are temporary and cannot replace the current saved password. */
    fun restore(entry: BackupHistoryEntry, password: String?) = execute(BackupPhase.READING) {
        if (!entry.remote) preview(local.readHistory(entry.id))
        else {
            val config = config()
            val result = remote.readHistory(config, entry.id)
            try {
                val archive = withPassword(password, BackupLocalStore.target(config), false) { PortableVaultCodec.decryptArchive(result.bytes, it) }
                // A historical version is not an acknowledgement of the remote head.
                preview(archive)
            } finally { result.bytes.fill(0) }
        }
    }

    /** Unknown remote contents pause publication until the user explicitly decides. */
    fun upload(password: String?, remember: Boolean) = execute(BackupPhase.UPLOADING) {
        val config = config()
        val (revision, snapshot) = vault.backupSnapshot()
        val bytes = withPassword(password, BackupLocalStore.target(config), remember) {
            PortableVaultCodec.encrypt(snapshot.copy(webDavConfig = null), it, appVersion())
        }
        try {
            val previous = remote.download(config)
            val prepared = PendingUpload(config, bytes, previous, revision)
            upload = prepared
            if (previous != null && local.revision(BackupLocalStore.target(config)) != BackupLocalStore.digest(previous.bytes)) {
                update { it.copy(remoteConflict = true) }
            } else publish(prepared)
        } catch (error: Exception) { bytes.fill(0); upload = null; throw error }
    }

    /** Rechecks both sides before acting on the earlier overwrite confirmation. */
    fun confirmUpload() = execute(BackupPhase.UPLOADING) {
        val prepared = upload ?: throw BackupException(BackupFailure.INVALID)
        publish(prepared)
    }

    /** Cancellation clears mutable buffers and invalidates all subsequent confirmation callbacks. */
    @Synchronized
    fun cancel() {
        generation++
        val epoch = generation
        job?.cancel()
        if (job?.isCompleted == false) {
            cleanupPending = true
            job?.invokeOnCompletion {
                synchronized(this) {
                    if (epoch == generation) { clearPending(); cleanupPending = false }
                }
            }
        } else { clearPending(); cleanupPending = false }
        mutable.value = BackupOperationState(rememberedManual = mutable.value.rememberedManual, rememberedRemote = mutable.value.rememberedRemote)
    }

    private fun clearPending() {
        pending = null
        upload?.bytes?.fill(0)
        upload?.previous?.bytes?.fill(0)
        upload = null
        export?.fill(0)
        export = null
    }

    private suspend fun publish(prepared: PendingUpload) {
        if (vault.backupSnapshot().first != prepared.revision) throw BackupException(BackupFailure.CHANGED)
        val current = remote.download(prepared.config)
        try {
            if (!(current?.bytes contentEqualsNullable prepared.previous?.bytes)) throw BackupException(BackupFailure.CHANGED)
            if (current != null && !WebDavClient.strongEtag(current.etag)) throw BackupException(BackupFailure.UNSAFE_SERVER)
            remote.verifyConditionalWrites(prepared.config)
            coroutineContext.ensureActive()
            if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
            if (current != null) {
                remote.archive(prepared.config, current)
            }
            coroutineContext.ensureActive()
            if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
            try {
                remote.publish(prepared.config, prepared.bytes, current?.etag)
            } catch (error: Exception) {
                if (error is CancellationException || error is BackupException) throw error
                val observed = remote.download(prepared.config)
                try { if (observed == null || !observed.bytes.contentEquals(prepared.bytes)) throw BackupException(BackupFailure.NETWORK) }
                finally { observed?.bytes?.fill(0) }
            }
            update { it.copy(remoteConflict = false, completed = true) }
            try {
                local.revision(BackupLocalStore.target(prepared.config), BackupLocalStore.digest(prepared.bytes))
                remote.prune(prepared.config)
            } catch (_: Exception) { update { it.copy(failure = BackupFailure.PARTIAL) } }
        } finally {
            current?.bytes?.fill(0)
            prepared.bytes.fill(0)
            prepared.previous?.bytes?.fill(0)
            upload = null
            update { it.copy(remoteConflict = false) }
        }
    }

    private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = if (this == null) other == null else other != null && contentEquals(other)

    private suspend fun preview(archive: BackupArchive, target: String? = null, digest: String? = null) {
        val (revision, snapshot) = vault.backupSnapshot()
        val summary = BackupMerger.preview(snapshot, archive, revision)
        pending = PendingImport(archive, summary, target, digest)
        update { it.copy(preview = summary, history = null) }
    }

    private suspend fun <T> withPassword(input: String?, target: String, remember: Boolean, block: (CharArray) -> T): T {
        val password = input?.toCharArray() ?: local.password(target) ?: throw BackupException(BackupFailure.AUTHENTICATION)
        try {
            if (password.isEmpty()) throw BackupException(BackupFailure.AUTHENTICATION)
            val result = block(password)
            coroutineContext.ensureActive()
            if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
            if (remember && input != null) local.remember(target, password)
            return result
        } finally { password.fill('\u0000') }
    }

    private fun config() = vault.snapshot.value.webDavConfig ?: throw BackupException(BackupFailure.INVALID)
    private fun remotePasswordPresent() = vault.snapshot.value.webDavConfig?.let { local.hasPassword(BackupLocalStore.target(it)) } ?: false

    private suspend fun update(transform: (BackupOperationState) -> BackupOperationState) {
        coroutineContext.ensureActive()
        if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
        mutable.value = transform(mutable.value)
    }

    @Synchronized
    private fun execute(phase: BackupPhase, action: suspend () -> Unit) {
        if (cleanupPending || job?.isCompleted == false || !unlocked()) return
        val epoch = generation
        mutable.value = mutable.value.copy(phase = phase, failure = null, completed = false)
        job = scope.launch(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (epoch == generation) mutable.value = mutable.value.copy(failure = when (error) {
                    is BackupException -> error.reason
                    is website.sung.mangossh.data.sync.WebDavTransportException -> BackupFailure.NETWORK
                    is java.io.IOException, is SecurityException -> BackupFailure.STORAGE
                    is java.security.GeneralSecurityException -> BackupFailure.STORAGE
                    else -> BackupFailure.INVALID
                })
            } finally {
                if (epoch == generation) mutable.value = mutable.value.copy(phase = BackupPhase.IDLE,
                    rememberedManual = local.hasPassword("manual"), rememberedRemote = remotePasswordPresent())
            }
        }
    }
}
