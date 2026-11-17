package website.sung.mangossh.data.vault

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
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
    val remoteConflictId: String? = null,
    val exportReady: Boolean = false,
    val rememberedManual: Boolean = false,
    val rememberedRemote: Boolean = false,
    val failure: BackupFailure? = null,
    val completed: Boolean = false,
    val authorizing: Boolean = false,
) {
    val remoteConflict: Boolean get() = remoteConflictId != null
}

/** The app lock approves only the continuation for the requested backup action. */
internal fun interface BackupReauthentication {
    /** Returns a callback that cancels only the prompt opened for this request. */
    fun request(onCancelled: () -> Unit, onApproved: () -> Unit): () -> Unit
}

/** Owns I/O, plaintext lifetime and pending confirmations. A lock invalidates all pending work. */
internal class BackupCoordinator(
    context: Context,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
    private val unlocked: () -> Boolean,
    private val accessGeneration: () -> Long,
    private val reauthentication: BackupReauthentication,
    private val appVersion: () -> String = { "unknown" },
    private val remote: BackupRemoteTransport = WebDavClient(),
    private val localStoreFactory: () -> BackupLocalStore = { BackupLocalStore(context.applicationContext) },
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val resolver = context.applicationContext.contentResolver
    private val local by lazy(localStoreFactory)
    private val mutable = MutableStateFlow(BackupOperationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var cleanupPending = false
    private var awaitingApproval: PendingApproval? = null
    private var preparedExpiration: Job? = null
    private var pending: PendingImport? = null
    private var upload: PendingUpload? = null
    private var export: PreparedExport? = null
    private class PendingImport(val archive: BackupArchive, var preview: ImportPreview, val target: String?, val remoteDigest: String?)
    private class PendingUpload(
        val id: String,
        val config: WebDavConfig,
        val bytes: ByteArray,
        val previous: RemoteBackup?,
        val approval: Approval,
    )
    private class PreparedExport(val bytes: ByteArray, val approval: Approval)
    private class PendingApproval {
        var cancelPrompt: (() -> Unit)? = null
        var expiration: Job? = null
    }

    /**
     * Private to this coordinator. A PIN callback can transfer ownership into one worker only.
     * The approval then follows that worker's immutable snapshot and expires even while a picker
     * or an overwrite prompt is open. It is never a general grant to perform backup I/O.
     */
    private class Approval(
        val id: String,
        val epoch: Long,
        val accessEpoch: Long,
        val deadline: Long,
        val revision: Long,
        val snapshot: VaultSnapshot,
    )

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
    fun prepareExport(password: String?, remember: Boolean, includeConfig: Boolean) = authorize(BackupPhase.SAVING) { approval ->
        val snapshot = approval.snapshot.let { if (includeConfig) it else it.copy(webDavConfig = null) }
        val bytes = withPassword(password, "manual", remember, approval) { PortableVaultCodec.encrypt(snapshot, it, appVersion()) }
        export?.bytes?.fill(0)
        export = PreparedExport(bytes, approval)
        update { it.copy(exportReady = true) }
        armPreparedExpiration(approval)
    }

    /** A cancelled picker discards its ciphertext; only a closed output stream counts as success. */
    fun writeExport(uri: Uri?) {
        if (uri == null) { cancel(); return }
        execute(BackupPhase.SAVING) {
            val prepared = export ?: throw BackupException(BackupFailure.INVALID)
            try {
                checkApproval(prepared.approval)
                resolver.openOutputStream(uri, "wt")?.use {
                    // Opening a provider stream can block as the grant is revoked.
                    checkApproval(prepared.approval)
                    it.write(prepared.bytes)
                    it.flush()
                }
                    ?: throw BackupException(BackupFailure.STORAGE)
                update { it.copy(exportReady = false, completed = true) }
            } finally {
                prepared.bytes.fill(0)
                export = null
                preparedExpiration?.cancel()
                update { it.copy(exportReady = false) }
            }
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
    fun commit(decision: ImportDecision) {
        val frozen = decision.copy(useIncoming = decision.useIncoming.toSet())
        if (frozen.restoreWebDav && mutable.value.preview?.hasWebDavConfig == true) {
            authorize(BackupPhase.SAVING) { approval -> commitPrepared(frozen, approval) }
        } else {
            execute(BackupPhase.SAVING) { commitPrepared(frozen, null) }
        }
    }

    private suspend fun commitPrepared(decision: ImportDecision, approval: Approval?) {
        val prepared = pending ?: throw BackupException(BackupFailure.INVALID)
        if (decision.operationId != prepared.preview.operationId) throw BackupException(BackupFailure.CHANGED)
        val changesTarget = decision.restoreWebDav && prepared.archive.snapshot.webDavConfig != null
        if (changesTarget && approval == null) throw BackupException(BackupFailure.LOCKED)
        val epoch = generation
        val saved = vault.commitImport(prepared.preview.revision, prepared.archive.snapshot, decision, local) {
            unlocked() && epoch == generation && (approval == null || approvalIsLive(approval))
        }
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

    /** A destination edit is bound to the approved replacement and the previous vault version. */
    fun changeWebDavConfig(config: WebDavConfig?) = authorize(BackupPhase.SAVING) { approval ->
        vault.changeWebDavConfig(approval.revision, config) { approvalIsLive(approval) }
        update { it.copy(completed = true) }
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
    fun upload(password: String?, remember: Boolean) = authorize(BackupPhase.UPLOADING) { approval ->
        clearUpload()
        update { it.copy(remoteConflictId = null) }
        val config = approval.snapshot.webDavConfig ?: throw BackupException(BackupFailure.INVALID)
        val bytes = withPassword(password, BackupLocalStore.target(config), remember, approval) {
            PortableVaultCodec.encrypt(approval.snapshot.copy(webDavConfig = null), it, appVersion())
        }
        try {
            checkApproval(approval)
            val previous = remote.download(config)
            val prepared = PendingUpload(approval.id, config, bytes, previous, approval)
            upload = prepared
            checkApproval(approval)
            if (previous != null && local.revision(BackupLocalStore.target(config)) != BackupLocalStore.digest(previous.bytes)) {
                update { it.copy(remoteConflictId = prepared.id) }
                armPreparedExpiration(approval)
            } else publish(prepared, approval)
        } catch (error: Exception) { bytes.fill(0); clearUpload(); throw error }
    }

    /** Rechecks both sides before acting on the earlier overwrite confirmation. */
    fun confirmUpload(id: String) {
        if (mutable.value.remoteConflictId != id) return
        authorize(BackupPhase.UPLOADING, onRejected = { discardConflict(id) }) { approval ->
            val prepared = upload?.takeIf { it.id == id } ?: throw BackupException(BackupFailure.CHANGED)
            publish(prepared, approval)
        }
    }

    /** Cancellation clears mutable buffers and invalidates all subsequent confirmation callbacks. */
    @Synchronized
    fun cancel() {
        generation++
        val request = awaitingApproval
        awaitingApproval = null
        request?.expiration?.cancel()
        request?.cancelPrompt?.invoke()
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
        preparedExpiration?.cancel()
        pending = null
        clearUpload()
        export?.bytes?.fill(0)
        export = null
    }

    private fun clearUpload() {
        upload?.bytes?.fill(0)
        upload?.previous?.bytes?.fill(0)
        upload = null
    }

    @Synchronized
    private fun discardConflict(id: String) {
        if (job?.isCompleted == false || mutable.value.remoteConflictId != id) return
        clearUpload()
        preparedExpiration?.cancel()
        mutable.value = mutable.value.copy(remoteConflictId = null)
    }

    private suspend fun publish(prepared: PendingUpload, approval: Approval) {
        suspend fun checkPublication() {
            checkApproval(approval)
            if (!approvalIsLive(prepared.approval)) throw BackupException(BackupFailure.LOCKED)
            if (prepared.approval.revision != approval.revision || prepared.config != approval.snapshot.webDavConfig) {
                throw BackupException(BackupFailure.CHANGED)
            }
        }
        var current: RemoteBackup? = null
        try {
            checkPublication()
            current = remote.download(prepared.config)
            if (current?.etag != prepared.previous?.etag ||
                !(current?.bytes contentEqualsNullable prepared.previous?.bytes)) throw BackupException(BackupFailure.CHANGED)
            if (current != null && !WebDavClient.strongEtag(current.etag)) throw BackupException(BackupFailure.UNSAFE_SERVER)
            checkPublication()
            remote.verifyConditionalWrites(prepared.config)
            if (current != null) {
                checkPublication()
                remote.archive(prepared.config, current)
            }
            checkPublication()
            try {
                remote.publish(prepared.config, prepared.bytes, current?.etag)
            } catch (error: Exception) {
                if (error is CancellationException || error is BackupException) throw error
                checkPublication()
                val observed = remote.download(prepared.config)
                try { if (observed == null || !observed.bytes.contentEquals(prepared.bytes)) throw BackupException(BackupFailure.NETWORK) }
                finally { observed?.bytes?.fill(0) }
            }
            update { it.copy(remoteConflictId = null, completed = true) }
            try {
                checkPublication()
                local.revision(BackupLocalStore.target(prepared.config), BackupLocalStore.digest(prepared.bytes))
                checkPublication()
                remote.prune(prepared.config)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(failure = BackupFailure.PARTIAL) } }
        } finally {
            current?.bytes?.fill(0)
            clearUpload()
            preparedExpiration?.cancel()
            update { it.copy(remoteConflictId = null) }
        }
    }

    private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = if (this == null) other == null else other != null && contentEquals(other)

    private suspend fun preview(archive: BackupArchive, target: String? = null, digest: String? = null) {
        val (revision, snapshot) = vault.backupSnapshot()
        val summary = BackupMerger.preview(snapshot, archive, revision)
        pending = PendingImport(archive, summary, target, digest)
        update { it.copy(preview = summary, history = null) }
    }

    private suspend fun <T> withPassword(
        input: String?,
        target: String,
        remember: Boolean,
        approval: Approval? = null,
        block: (CharArray) -> T,
    ): T {
        val password = input?.toCharArray() ?: local.password(target) ?: throw BackupException(BackupFailure.AUTHENTICATION)
        try {
            if (password.isEmpty()) throw BackupException(BackupFailure.AUTHENTICATION)
            val result = block(password)
            try {
                if (approval != null) checkApproval(approval)
                coroutineContext.ensureActive()
                if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
                if (remember && input != null) local.remember(target, password)
                return result
            } catch (error: Exception) {
                if (result is ByteArray) result.fill(0)
                throw error
            }
        } finally { password.fill('\u0000') }
    }

    private fun config() = vault.snapshot.value.webDavConfig ?: throw BackupException(BackupFailure.INVALID)
    private fun remotePasswordPresent() = vault.snapshot.value.webDavConfig?.let { local.hasPassword(BackupLocalStore.target(it)) } ?: false

    @Synchronized
    private fun authorize(
        phase: BackupPhase,
        onRejected: () -> Unit = {},
        action: suspend (Approval) -> Unit,
    ) {
        if (cleanupPending || awaitingApproval != null || job?.isCompleted == false || !unlocked()) return
        val owner = PendingApproval().also { awaitingApproval = it }
        mutable.value = mutable.value.copy(authorizing = true)
        val epoch = generation
        val accessEpoch = accessGeneration()
        val deadline = elapsedMillis() + APPROVAL_LIFETIME_MILLIS
        val observed = vault.snapshot.value
        fun reject(failure: BackupFailure? = null) = synchronized(this) {
            if (awaitingApproval !== owner) return@synchronized
            awaitingApproval = null
            owner.expiration?.cancel()
            onRejected()
            mutable.value = mutable.value.copy(authorizing = false, failure = failure ?: mutable.value.failure)
        }
        owner.expiration = scope.launch {
            delay(APPROVAL_LIFETIME_MILLIS)
            synchronized(this@BackupCoordinator) {
                if (awaitingApproval !== owner) return@synchronized
                reject(BackupFailure.LOCKED)
                owner.cancelPrompt?.invoke()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val (revision, snapshot) = vault.backupSnapshot()
                if (snapshot !== observed) {
                    reject(BackupFailure.CHANGED)
                    return@launch
                }
                val approval = Approval(UUID.randomUUID().toString(), epoch, accessEpoch, deadline, revision, snapshot)
                synchronized(this@BackupCoordinator) {
                    if (awaitingApproval !== owner) return@synchronized
                    if (!approvalIsLive(approval)) {
                        reject()
                        return@synchronized
                    }
                    owner.cancelPrompt = reauthentication.request(onCancelled = { reject() }, onApproved = {
                        synchronized(this@BackupCoordinator) approved@{
                            if (awaitingApproval !== owner) return@approved
                            awaitingApproval = null
                            owner.expiration?.cancel()
                            execute(phase) {
                                try {
                                    checkApproval(approval)
                                } catch (error: BackupException) {
                                    // Only this authorization worker can own its pending upload.
                                    if (phase == BackupPhase.UPLOADING) {
                                        clearUpload()
                                        update { it.copy(remoteConflictId = null) }
                                    }
                                    throw error
                                }
                                action(approval)
                            }
                        }
                    })
                }
            } catch (cancelled: CancellationException) {
                reject()
                throw cancelled
            } catch (error: BackupException) {
                reject(error.reason)
            }
        }
    }

    private fun approvalIsLive(approval: Approval): Boolean =
        unlocked() && generation == approval.epoch && accessGeneration() == approval.accessEpoch &&
            elapsedMillis() < approval.deadline

    @Synchronized
    private fun armPreparedExpiration(approval: Approval) {
        if (generation != approval.epoch) return
        preparedExpiration?.cancel()
        preparedExpiration = scope.launch {
            delay((approval.deadline - elapsedMillis()).coerceAtLeast(0))
            // Wait for any active owner before zeroing buffers it may still be reading.
            val active = synchronized(this@BackupCoordinator) { job }
            active?.join()
            synchronized(this@BackupCoordinator) {
                if (generation != approval.epoch || job?.isCompleted == false) return@synchronized
                if (upload?.id == approval.id) {
                    clearUpload()
                    mutable.value = mutable.value.copy(remoteConflictId = null, failure = BackupFailure.LOCKED)
                }
                if (export?.approval?.id == approval.id) {
                    export?.bytes?.fill(0)
                    export = null
                    mutable.value = mutable.value.copy(exportReady = false, failure = BackupFailure.LOCKED)
                }
            }
        }
    }

    private suspend fun checkApproval(approval: Approval) {
        coroutineContext.ensureActive()
        if (!approvalIsLive(approval)) throw BackupException(BackupFailure.LOCKED)
        val (revision, snapshot) = vault.backupSnapshot()
        if (revision != approval.revision || snapshot.webDavConfig != approval.snapshot.webDavConfig) {
            throw BackupException(BackupFailure.CHANGED)
        }
        if (!approvalIsLive(approval)) throw BackupException(BackupFailure.LOCKED)
    }

    private suspend fun update(transform: (BackupOperationState) -> BackupOperationState) {
        val context = coroutineContext
        synchronized(this) {
            context.ensureActive()
            if (!unlocked()) throw BackupException(BackupFailure.LOCKED)
            mutable.value = transform(mutable.value)
        }
    }

    @Synchronized
    private fun execute(phase: BackupPhase, action: suspend () -> Unit) {
        if (cleanupPending || awaitingApproval != null || job?.isCompleted == false || !unlocked()) return
        val epoch = generation
        mutable.value = mutable.value.copy(phase = phase, failure = null, completed = false, authorizing = false)
        job = scope.launch(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                MangoLog.warn(MangoLogEvent.BACKUP_OPERATION_FAILED, error)
                synchronized(this@BackupCoordinator) {
                    if (epoch == generation) mutable.value = mutable.value.copy(failure = when (error) {
                        is BackupException -> error.reason
                        is website.sung.mangossh.data.sync.WebDavTransportException -> BackupFailure.NETWORK
                        is java.io.IOException, is SecurityException -> BackupFailure.STORAGE
                        is java.security.GeneralSecurityException -> BackupFailure.STORAGE
                        else -> BackupFailure.INVALID
                    })
                }
            } finally {
                if (epoch == generation) {
                    val manual = local.hasPassword("manual")
                    val remote = remotePasswordPresent()
                    synchronized(this@BackupCoordinator) {
                        if (epoch == generation) mutable.value = mutable.value.copy(
                            phase = BackupPhase.IDLE, rememberedManual = manual, rememberedRemote = remote,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val APPROVAL_LIFETIME_MILLIS = 5 * 60 * 1_000L
    }
}
