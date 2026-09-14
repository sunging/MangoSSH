package website.sung.mangossh.session

import android.content.Context
import android.net.Uri
import com.trilead.ssh2.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs and supervises SFTP file and directory transfers.
 *
 * The engine is deliberately separate from the session controller: a transfer
 * outlives the browser screen that started it, can be paused and resumed, and
 * owns bookkeeping (resume offsets, tree walks, local document creation) that
 * has nothing to do with terminal session lifetime.
 *
 * SFTP has no pause or abort verb, so both stop cleanly at a chunk boundary
 * through [TransferControl]. A pause keeps the byte offset and closes the SFTP
 * channel; resuming reopens it and continues from that offset, so no IO thread
 * or channel is held while a transfer sits paused.
 *
 * Resuming and retrying go back to the session the transfer was started on
 * rather than opening one of their own. That session decides whether its
 * carrier is still usable, so a Mosh companion lost underneath a paused
 * transfer is re-authenticated on resume, and any prompt it raises is rendered
 * over whichever screen is showing. A session that has ended has nothing to go
 * back to; [onSessionEnded] marks those transfers uncontrollable instead.
 *
 * Local documents are addressed through Storage Access Framework grants held by
 * the activity task. They are not taken persistably: a transfer does not
 * survive process death, so a grant that outlives the process would have
 * nothing to serve.
 *
 * Remote paths, local names, and selected documents are user data. They are
 * rendered verbatim and never logged.
 */
internal class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val remoteFiles: RemoteFileClient,
    private val connectionOf: suspend (String) -> Connection,
    private val onSessionIdle: (String) -> Unit,
) {
    private val _transfers = MutableStateFlow<List<ScpTransferState>>(emptyList())
    val transfers: StateFlow<List<ScpTransferState>> = _transfers.asStateFlow()

    private val _conflicts = MutableStateFlow<List<TransferConflict>>(emptyList())
    val conflicts = _conflicts.asStateFlow()
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<TransferConflictDecision>>()

    fun resolveConflict(id: String, decision: TransferConflictDecision) { decisions[id]?.complete(decision) }

    private suspend fun decision(transferId: String, handle: TransferHandle, name: String, direction: ScpTransferDirection,
        exists: Boolean, atomic: Boolean): TransferConflictDecision {
        if (exists) handle.taskDecision?.let { saved ->
            if (saved.action != TransferConflictAction.REPLACE || atomic) return saved
        }
        if (!exists && handle.optionsChosen) return TransferConflictDecision(TransferConflictAction.REPLACE, verifySha256 = handle.verifySha256)
        val id = UUID.randomUUID().toString()
        val pending = CompletableDeferred<TransferConflictDecision>()
        decisions[id] = pending
        handle.operation?.awaitDecision(true)
        _conflicts.update { it + TransferConflict(id, name, direction, exists, atomic) }
        return try {
            val result = pending.await()
            check(resolveRun(transferId) != null)
            require(result.action != TransferConflictAction.REPLACE || !exists || atomic)
            handle.optionsChosen = true
            handle.verifySha256 = result.verifySha256
            if (result.applyToTask && result.action != TransferConflictAction.SAVE_AS) handle.taskDecision = result
            result
        } finally {
            decisions.remove(id)
            _conflicts.update { it.filterNot { conflict -> conflict.id == id } }
            handle.operation?.awaitDecision(false)
        }
    }

    private val handles = ConcurrentHashMap<String, TransferHandle>()
    private val runs = ConcurrentHashMap<String, Pair<String, TransferHandle>>()
    private val supervisor = TransferSupervisor()

    /** Downloads one remote file into a document the user selected. */
    fun downloadFile(sessionId: String, remotePath: String, destination: Uri) {
        enqueue(
            request = TransferRequest.FileDownload(sessionId, remotePath, destination),
            direction = ScpTransferDirection.DOWNLOAD,
            kind = ScpTransferKind.FILE,
            displayName = RemoteFilePaths.nameOf(remotePath),
            remotePath = remotePath,
            localUri = destination.toString(),
        )
    }

    /** Downloads a remote directory tree into a folder the user selected. */
    fun downloadDirectory(sessionId: String, remotePath: String, destinationTree: Uri) {
        enqueue(
            request = TransferRequest.DirectoryDownload(sessionId, remotePath, destinationTree),
            direction = ScpTransferDirection.DOWNLOAD,
            kind = ScpTransferKind.DIRECTORY,
            displayName = RemoteFilePaths.nameOf(remotePath),
            remotePath = remotePath,
            localUri = destinationTree.toString(),
        )
    }

    /** Uploads one selected document into a remote directory. */
    fun uploadFile(sessionId: String, source: Uri, displayName: String, remoteDirectory: String) {
        val fileName = sanitizeRemoteFileName(displayName)
        enqueue(
            request = TransferRequest.FileUpload(sessionId, source, fileName, remoteDirectory),
            direction = ScpTransferDirection.UPLOAD,
            kind = ScpTransferKind.FILE,
            displayName = fileName,
            remotePath = remoteDirectory,
            localUri = source.toString(),
        )
    }

    /** Uploads a selected local folder into a remote directory. */
    fun uploadDirectory(sessionId: String, sourceTree: Uri, remoteDirectory: String, displayName: String) {
        enqueue(
            request = TransferRequest.DirectoryUpload(sessionId, sourceTree, remoteDirectory),
            direction = ScpTransferDirection.UPLOAD,
            kind = ScpTransferKind.DIRECTORY,
            displayName = displayName,
            remotePath = remoteDirectory,
            localUri = sourceTree.toString(),
        )
    }

    /** Stops a running transfer at the next chunk boundary, keeping its offset. */
    fun pause(transferId: String) {
        val handle = handles[transferId] ?: return
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canPause) return
        handle.stop = StopReason.PAUSE
        handle.operation?.close()
        handle.job?.cancel()
    }

    /** Continues a paused transfer from the offset it stopped at. */
    fun resume(transferId: String) {
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canResume) return
        start(transferId, current.transferredBytes, current.completedItems, resume = true)
    }

    /** Stops a queued, running, or paused transfer for good. */
    fun cancel(transferId: String) {
        val handle = handles[transferId] ?: return
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canCancel) return
        handle.stop = StopReason.CANCEL
        handle.operation?.close()
        if (current.phase == ScpTransferPhase.PAUSED) {
            cleanupTemporary(handle)
            settle(transferId, ScpTransferPhase.CANCELLED, current.transferredBytes, current.completedItems)
        } else {
            // A queued transfer has not reached a chunk boundary yet, so the
            // job has to be cancelled for the stop to take effect promptly.
            handle.job?.cancel()
        }
    }

    /** Re-runs a failed or cancelled transfer from the beginning. */
    fun retry(transferId: String) {
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canRetry) return
        start(transferId, startOffset = 0L, completedItems = 0)
    }

    /** Drops finished records; queued, running, and paused transfers are kept. */
    fun clearFinished() {
        val removed = _transfers.value.filter { it.isFinished }
        if (removed.isEmpty()) return
        removed.forEach { handles.remove(it.id)?.let(::cleanupTemporary) }
        _transfers.update { current -> current.filterNot { it.isFinished } }
    }

    /**
     * True while [sessionId] still carries transfers that need its connection.
     *
     * Paused transfers count: closing the browser must not throw away the
     * connection a user explicitly intends to resume on.
     */
    fun hasBusyTransfers(sessionId: String): Boolean = _transfers.value.any { transfer ->
        transfer.sessionId == sessionId && (transfer.isActive || transfer.phase == ScpTransferPhase.PAUSED)
    }

    /**
     * Reacts to the connection behind [sessionId] going away.
     *
     * Transfers that were moving bytes end as failures, and everything else the
     * session owned becomes uncontrollable: resuming and retrying need that
     * connection, and the transfer list is not allowed to open a new one.
     */
    fun onSessionEnded(sessionId: String) {
        handles.values.filter { it.request.sessionId == sessionId }.forEach {
            it.operation?.close()
            it.job?.cancel()
        }
        val closedMessage = RemoteFileMessage.TransferSessionClosed
        _transfers.update { current ->
            current.map { transfer ->
                when {
                    transfer.sessionId != sessionId -> transfer
                    transfer.isActive -> transfer.copy(
                        phase = ScpTransferPhase.FAILED,
                        detail = closedMessage,
                        currentItem = null,
                        controllable = false,
                    )

                    transfer.controllable -> transfer.copy(controllable = false)
                    else -> transfer
                }
            }
        }
    }

    private fun enqueue(
        request: TransferRequest,
        direction: ScpTransferDirection,
        kind: ScpTransferKind,
        displayName: String,
        remotePath: String,
        localUri: String,
    ) {
        val transferId = UUID.randomUUID().toString()
        handles[transferId] = TransferHandle(request)
        _transfers.update { current ->
            current + ScpTransferState(
                id = transferId,
                sessionId = request.sessionId,
                direction = direction,
                displayName = displayName,
                remotePath = remotePath,
                phase = ScpTransferPhase.QUEUED,
                kind = kind,
                localUri = localUri,
            )
        }
        start(transferId, startOffset = 0L, completedItems = 0)
    }

    private fun start(transferId: String, startOffset: Long, completedItems: Int, resume: Boolean = false) {
        val previous = handles[transferId] ?: return
        val handle = TransferHandle(previous.request)
        handle.exactBytes = startOffset
        if (!resume) cleanupTemporary(previous)
        if (resume) {
            handle.remoteWalk = previous.remoteWalk
            handle.localWalk = previous.localWalk
            handle.sourceIdentity = previous.sourceIdentity
            handle.completed.addAll(previous.completed)
            handle.localTemps.putAll(previous.localTemps)
            handle.remoteTemps.putAll(previous.remoteTemps)
            handle.optionsChosen = previous.optionsChosen
            handle.verifySha256 = previous.verifySha256
            handle.taskDecision = previous.taskDecision
        }
        handles[transferId] = handle
        val runId = UUID.randomUUID().toString()
        runs[runId] = transferId to handle
        update(runId) { it.copy(phase = ScpTransferPhase.QUEUED, detail = null,
            transferredBytes = startOffset, completedItems = completedItems, currentItem = null) }
        var executionFailure: Exception? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
          try {
            supervisor.run(handle.request.sessionId) {
                    withContext(Dispatchers.IO) {
                        handle.operation = BlockingOperation()
                        if (handle.stop != null) {
                            handle.operation?.close()
                            throw CancellationException()
                        }
                        try {
                            when (val request = handle.request) {
                                is TransferRequest.FileDownload -> runFileDownload(runId, handle, request, startOffset)
                                is TransferRequest.DirectoryDownload -> runDirectoryDownload(runId, handle, request, startOffset, completedItems)
                                is TransferRequest.FileUpload -> runFileUpload(runId, handle, request, startOffset)
                                is TransferRequest.DirectoryUpload -> runDirectoryUpload(runId, handle, request, startOffset, completedItems)
                            }
                        } finally {
                            handle.operation?.close()
                        }
                    }
            }
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (failure: Exception) {
              // Transfer failures are task state, never unhandled application coroutine failures.
              executionFailure = failure
          }
        }
        handle.job = job
        job.invokeOnCompletion { completionFailure ->
            val error = completionFailure ?: executionFailure
            if (error != null) {
                val current = _transfers.value.firstOrNull { it.id == transferId }
                val phase = when (handle.stop) {
                    StopReason.PAUSE -> ScpTransferPhase.PAUSED
                    StopReason.CANCEL -> ScpTransferPhase.CANCELLED
                    null -> ScpTransferPhase.FAILED
                }
                settle(runId, phase, handle.exactBytes,
                    current?.completedItems ?: completedItems,
                    if (handle.stop == null) error.toRemoteFileMessage() else null)
            }
            if (handle.stop == StopReason.CANCEL) cleanupTemporary(handle)
            runs.remove(runId)
        }
        job.start()
    }

    /** Scans have a total sixty-second budget; byte transfers resume the thirty-second idle budget. */
    private fun <T> scan(handle: TransferHandle, action: () -> T): T {
        handle.operation?.close()
        val scan = BlockingOperation(60_000)
        handle.operation = scan
        if (handle.stop != null) scan.close()
        try {
            if (!scan.shouldContinue()) throw CancellationException()
            return action()
        } finally {
            scan.close()
            handle.operation = BlockingOperation().also { if (handle.stop != null) it.close() }
        }
    }

    private suspend fun runFileDownload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.FileDownload,
        startOffset: Long,
    ) {
        val connection = connectionOf(request.sessionId).also { handle.connection = it }
        markRunning(transferId)
        val observed = remoteFiles.identity(connection, request.remotePath, handle.control())
        val identity = handle.sourceIdentity ?: observed.also { handle.sourceIdentity = it }
        identity.requireMatches(observed, requireComplete = startOffset > 0)
        val throttle = ProgressThrottle()
        val reached = stagedDownload(transferId, handle, connection, request.remotePath, identity,
            request.destination, startOffset) { transferred, total ->
            handle.exactBytes = transferred
            if (throttle.shouldEmit(transferred, total)) updateProgress(transferId, transferred, total)
        }
        settleAfterRun(transferId, handle, reached, completedItems = 0)
    }

    /** SAF has no portable conditional replacement; the chosen document is untouched until confirmation. */
    private suspend fun stagedDownload(runId: String, handle: TransferHandle, connection: Connection,
        path: String, identity: SourceIdentity, destination: Uri, offset: Long,
        progress: (Long, Long?) -> Unit): Long {
        val choice = decision(runId, handle, RemoteFilePaths.nameOf(path), ScpTransferDirection.DOWNLOAD, true, false)
        if (choice.action == TransferConflictAction.SKIP) return 0L
        val finalUri = if (choice.action == TransferConflictAction.SAVE_AS) Uri.parse(requireNotNull(choice.localUri)) else destination
        if (handle.request is TransferRequest.FileDownload) update(runId) { it.copy(localUri = finalUri.toString()) }
        val previousTarget = documentIdentity(finalUri, handle.control())
        val temporary = handle.localTemps[path] ?: File.createTempFile("mangossh-transfer-", ".part", context.cacheDir)
            .also { handle.localTemps[path] = it }
        if (offset > 0 && temporary.length() != offset) throw SourceChangedException()
        identity.requireMatches(remoteFiles.identity(connection, path, handle.control()), offset > 0)
        val output = java.io.FileOutputStream(temporary, offset > 0)
        handle.operation?.ownLocal(output)
        val bytes = try {
            remoteFiles.download(connection, path, output, offset, identity, handle.control(), progress)
        } finally { handle.operation?.releaseLocal(output) }
        if (!handle.control().shouldContinue()) return bytes
        if (temporary.length() != bytes) throw SourceChangedException()
        if (choice.verifySha256 && !java.security.MessageDigest.isEqual(
                temporary.inputStream().use { streamDigest(it, handle) }, remoteFiles.sha256(connection, path, handle.control())))
            throw SourceChangedException()
        previousTarget.requireMatches(documentIdentity(finalUri, handle.control()))
        val target = openDownloadTarget(runId, finalUri, 0).stream
        handle.operation?.ownLocal(target)
        try {
            temporary.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (!handle.control().shouldContinue()) throw java.io.InterruptedIOException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    handle.control().progressed()
                }
            }
        } finally { handle.operation?.releaseLocal(target) }
        // Read back even when the provider cannot report its length; close and commit are part of completion.
        val expected = temporary.inputStream().use { streamDigest(it, handle) }
        val actual = openSource(finalUri).use { streamDigest(it, handle) }
        if (!java.security.MessageDigest.isEqual(expected, actual)) throw SourceChangedException()
        temporary.delete()
        handle.localTemps.remove(path)
        return bytes
    }

    private fun streamDigest(input: InputStream, handle: TransferHandle): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        handle.control().ownLocal(input)
        try {
            while (true) {
                if (!handle.control().shouldContinue()) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                handle.control().progressed()
            }
            return digest.digest()
        } finally { handle.control().releaseLocal(input) }
    }

    private suspend fun runFileUpload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.FileUpload,
        startOffset: Long,
    ) {
        val connection = connectionOf(request.sessionId).also { handle.connection = it }
        markRunning(transferId)
        val observed = documentIdentity(request.source, handle.control())
        val identity = handle.sourceIdentity ?: observed.also { handle.sourceIdentity = it }
        identity.requireMatches(observed, requireComplete = startOffset > 0)
        val throttle = ProgressThrottle()
        val transferred = stagedUpload(transferId, handle, connection, request.source, identity,
            request.remoteDirectory, request.fileName, startOffset) { bytes, total ->
            handle.exactBytes = bytes
            if (throttle.shouldEmit(bytes, total)) updateProgress(transferId, bytes, total)
        }
        settleAfterRun(transferId, handle, transferred, completedItems = 0)
    }

    /** A resumed upload owns only its exclusive sibling temporary until a verified commit. */
    private suspend fun stagedUpload(runId: String, handle: TransferHandle, connection: Connection,
        source: Uri, identity: SourceIdentity, directory: String, name: String, offset: Long,
        progress: (Long, Long?) -> Unit): Long {
        val original = RemoteFilePaths.join(directory, name)
        var target = remoteFiles.inspectTarget(connection, original, handle.control())
        val choice = decision(runId, handle, name, ScpTransferDirection.UPLOAD, target.identity != null, target.atomicReplace)
        if (choice.action == TransferConflictAction.SKIP) return 0L
        val destination = if (choice.action == TransferConflictAction.SAVE_AS) {
            RemoteFilePaths.join(directory, sanitizeRemoteFileName(requireNotNull(choice.alternateName)))
        } else original
        if (destination != original) {
            target = remoteFiles.inspectTarget(connection, destination, handle.control())
            if (target.identity != null) throw SourceChangedException()
        }
        identity.requireMatches(documentIdentity(source, handle.control()), offset > 0)
        val owned = handle.remoteTemps[original] ?: UUID.randomUUID().toString().let { token ->
            (remoteFiles.reserveTemporary(connection, directory, token, handle.control()) to token)
                .also { handle.remoteTemps[original] = it }
        }
        val input = openSource(source)
        handle.operation?.ownLocal(input)
        val result = try {
            remoteFiles.upload(connection, input, directory, RemoteFilePaths.nameOf(owned.first), offset,
                identity.size, MAX_UPLOAD_BYTES, handle.control(), progress)
        } finally { handle.operation?.releaseLocal(input) }
        if (!handle.control().shouldContinue()) return result.transferredBytes
        identity.requireMatches(documentIdentity(source, handle.control()))
        if (choice.verifySha256) {
            val digest = openSource(source).use { streamDigest(it, handle) }
            identity.requireMatches(documentIdentity(source, handle.control()))
            if (!java.security.MessageDigest.isEqual(digest, remoteFiles.sha256(connection, owned.first, handle.control())))
                throw SourceChangedException()
        }
        remoteFiles.commitTemporary(connection, owned.first, destination, target,
            choice.action == TransferConflictAction.DIRECT_OVERWRITE, handle.control())
        handle.remoteTemps.remove(original)
        return result.transferredBytes
    }

    /**
     * Downloads a remote tree, one file at a time.
     *
     * Resuming is file-grained: files already finished are skipped and the file
     * that was interrupted is transferred again from its start, which avoids
     * tracking a separate offset for every entry of the tree.
     */
    private suspend fun runDirectoryDownload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.DirectoryDownload,
        startOffset: Long,
        completedItems: Int,
    ) {
        val connection = connectionOf(request.sessionId).also { handle.connection = it }
        markRunning(transferId)
        val resuming = handle.remoteWalk != null
        val walk = handle.remoteWalk ?: scan(handle) { remoteFiles.walk(
            connection = connection,
            root = request.remotePath,
            maxEntries = MAX_TRANSFER_TREE_ENTRIES,
            maxDepth = MAX_TRANSFER_TREE_DEPTH,
            control = handle.control(),
        ) }.also { handle.remoteWalk = it }
        if (walk.truncated) throw TransferTreeTooLargeException()
        update(transferId) { state ->
            state.copy(
                totalItems = walk.files.size,
                totalBytes = walk.totalBytes,
                detail = skippedDetail(walk.skippedEntries),
            )
        }

        if (resuming) walk.files.forEach { entry ->
            SourceIdentity(entry.absolutePath, entry.sizeBytes, entry.modifiedEpochMillis)
                .requireMatches(remoteFiles.identity(connection, entry.absolutePath, handle.control()), true)
        }
        val resolver = context.contentResolver
        val treeUri = request.destinationTree
        val rootName = RemoteFilePaths.nameOf(request.remotePath).takeIf { it != RemoteFilePaths.ROOT }
            ?: FALLBACK_DIRECTORY_NAME
        val targets = LocalDocumentTree.Targets(resolver, treeUri)
        val localRoot = targets.directory(LocalDocumentTree.rootOf(treeUri), rootName)
        val directories = mutableMapOf("" to localRoot)
        walk.directories.forEach { relative ->
            val parent = directories.getValue(parentRelativePath(relative))
            directories[relative] = targets.directory(parent, relative.substringAfterLast('/'))
        }

        var transferred = startOffset
        var completed = completedItems
        walk.files.filterNot { it.relativePath in handle.completed }.forEach { entry ->
            if (handle.stop != null) {
                settleAfterRun(transferId, handle, transferred, completed)
                return
            }
            update(transferId) { state -> state.copy(currentItem = entry.relativePath) }
            val parent = directories[parentRelativePath(entry.relativePath)] ?: localRoot
            val document = targets.file(parent, entry.relativePath.substringAfterLast('/'), DEFAULT_MIME_TYPE)
            val throttle = ProgressThrottle()
            val base = transferred
            val fileBytes = stagedDownload(transferId, handle, connection, entry.absolutePath,
                SourceIdentity(entry.absolutePath, entry.sizeBytes, entry.modifiedEpochMillis), document, 0L) { fileTransferred, _ ->
                if (throttle.shouldEmit(base + fileTransferred, walk.totalBytes))
                    updateProgress(transferId, base + fileTransferred, walk.totalBytes)
            }
            if (handle.stop != null) {
                // The interrupted file restarts on resume, so only whole files count.
                settleAfterRun(transferId, handle, base, completed)
                return
            }
            transferred = base + fileBytes
            handle.exactBytes = transferred
            handle.completed += entry.relativePath
            completed += 1
            updateProgress(transferId, transferred, walk.totalBytes)
            update(transferId) { state -> state.copy(completedItems = completed) }
        }
        settleAfterRun(transferId, handle, transferred, completed)
    }

    /** Uploads a picked local folder, mirroring [runDirectoryDownload]. */
    private suspend fun runDirectoryUpload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.DirectoryUpload,
        startOffset: Long,
        completedItems: Int,
    ) {
        val connection = connectionOf(request.sessionId).also { handle.connection = it }
        markRunning(transferId)
        val resolver = context.contentResolver
        val resuming = handle.localWalk != null
        val walk = handle.localWalk ?: scan(handle) { LocalDocumentTree.walk(
            resolver = resolver,
            treeUri = request.sourceTree,
            maxEntries = MAX_TRANSFER_TREE_ENTRIES,
            maxDepth = MAX_TRANSFER_TREE_DEPTH,
            parentControl = handle.control(),
        ) }.also { handle.localWalk = it }
        if (walk.truncated) throw TransferTreeTooLargeException()
        update(transferId) { state ->
            state.copy(totalItems = walk.files.size, totalBytes = walk.totalBytes)
        }

        if (resuming) walk.files.forEach { entry ->
            SourceIdentity(entry.documentUri.toString() + "#" + entry.relativePath.substringAfterLast('/'), entry.sizeBytes, entry.modifiedEpochMillis)
                .requireMatches(documentIdentity(entry.documentUri, handle.control()), true)
        }
        val rootName = sanitizeRemoteFileName(walk.name.ifBlank { FALLBACK_DIRECTORY_NAME })
        val remoteRoot = RemoteFilePaths.join(request.remoteDirectory, rootName)
        remoteFiles.mkdirIfMissing(connection, remoteRoot)
        walk.directories.forEach { relative ->
            remoteFiles.mkdirIfMissing(connection, RemoteFilePaths.resolve(remoteRoot, relative))
        }

        var transferred = startOffset
        var completed = completedItems
        walk.files.filterNot { it.relativePath in handle.completed }.forEach { entry ->
            if (handle.stop != null) {
                settleAfterRun(transferId, handle, transferred, completed)
                return
            }
            update(transferId) { state -> state.copy(currentItem = entry.relativePath) }
            val remoteDirectory = RemoteFilePaths.resolve(
                remoteRoot,
                parentRelativePath(entry.relativePath),
            )
            val throttle = ProgressThrottle()
            val base = transferred
            val identity = SourceIdentity(entry.documentUri.toString() + "#" + entry.relativePath.substringAfterLast('/'), entry.sizeBytes, entry.modifiedEpochMillis)
            identity.requireMatches(documentIdentity(entry.documentUri, handle.control()))
            val fileBytes = stagedUpload(transferId, handle, connection, entry.documentUri, identity,
                remoteDirectory, entry.relativePath.substringAfterLast('/'), 0L) { fileTransferred, _ ->
                if (throttle.shouldEmit(base + fileTransferred, walk.totalBytes))
                    updateProgress(transferId, base + fileTransferred, walk.totalBytes)
            }
            if (handle.stop != null) {
                settleAfterRun(transferId, handle, base, completed)
                return
            }
            identity.requireMatches(documentIdentity(entry.documentUri, handle.control()))
            transferred = base + fileBytes
            handle.exactBytes = transferred
            handle.completed += entry.relativePath
            completed += 1
            updateProgress(transferId, transferred, walk.totalBytes)
            update(transferId) { state -> state.copy(completedItems = completed) }
        }
        settleAfterRun(transferId, handle, transferred, completed)
    }

    /** Cleanup never resolves a new connection or deletes the destination chosen by the user. */
    private fun cleanupTemporary(handle: TransferHandle) {
        scope.launch(Dispatchers.IO) {
            handle.job?.join()
            handle.localTemps.values.forEach { it.delete() }
            handle.localTemps.clear()
            handle.connection?.let { connection ->
                handle.remoteTemps.values.forEach { (path, token) ->
                    runCatching { remoteFiles.removeTemporary(connection, path, token) }
                }
            }
            handle.remoteTemps.clear()
        }
    }

    private fun settleAfterRun(
        transferId: String,
        handle: TransferHandle,
        transferredBytes: Long,
        completedItems: Int,
    ) {
        val phase = when (handle.stop) {
            StopReason.PAUSE -> ScpTransferPhase.PAUSED
            StopReason.CANCEL -> ScpTransferPhase.CANCELLED
            null -> ScpTransferPhase.COMPLETED
        }
        settle(transferId, phase, transferredBytes, completedItems)
    }

    private fun settle(
        transferId: String,
        phase: ScpTransferPhase,
        transferredBytes: Long,
        completedItems: Int,
        detail: RemoteFileMessage? = null,
    ) {
        val resolved = resolveRun(transferId) ?: return
        var sessionId: String? = null
        _transfers.update { current ->
            current.map { state ->
                if (state.id == resolved && state.controllable && resolveRun(transferId) != null) {
                    sessionId = state.sessionId
                    state.copy(
                        phase = phase,
                        transferredBytes = transferredBytes,
                        completedItems = completedItems,
                        currentItem = null,
                        detail = detail ?: state.detail,
                    )
                } else {
                    state
                }
            }
        }
        // A transfer-only connection stays open until the queue that kept it
        // alive after the browser closed has drained.
        sessionId?.let(onSessionIdle)
    }

    private fun markRunning(transferId: String) {
        update(transferId) { state -> state.copy(phase = ScpTransferPhase.RUNNING) }
    }

    private fun resolveRun(id: String): String? {
        val run = runs[id] ?: return id.takeIf { handles.containsKey(it) }
        return run.first.takeIf { handles[it] === run.second }
    }

    private fun update(transferId: String, transform: (ScpTransferState) -> ScpTransferState) {
        val resolved = resolveRun(transferId) ?: return
        _transfers.update { current ->
            current.map { state -> if (state.id == resolved && state.controllable && resolveRun(transferId) != null) transform(state) else state }
        }
    }

    private fun updateProgress(transferId: String, transferred: Long, total: Long?) {
        update(transferId) { state ->
            state.copy(transferredBytes = transferred, totalBytes = total ?: state.totalBytes)
        }
    }

    /**
     * Opens a download destination, appending when the transfer is resuming.
     *
     * Not every document provider supports append mode, and the partial file may
     * have been touched between the pause and the resume, so the local length is
     * verified first and a mismatch restarts the transfer from the beginning.
     */
    private fun openDownloadTarget(
        transferId: String,
        destination: Uri,
        startOffset: Long,
    ): DownloadTarget {
        val resolver = context.contentResolver
        if (startOffset > 0L) {
            if (documentLength(destination) != startOffset) throw SourceChangedException()
            val appended = resolver.openOutputStream(destination, "wa") ?: throw LocalDocumentException()
            return DownloadTarget(appended, startOffset)
        }
        val stream = try {
            resolver.openOutputStream(destination, "wt")
        } catch (error: Exception) {
            throw LocalDocumentException(error)
        } ?: throw LocalDocumentException()
        return DownloadTarget(stream, 0L)
    }

    private fun openSource(source: Uri): InputStream = try {
        context.contentResolver.openInputStream(source) ?: throw LocalDocumentException()
    } catch (error: LocalDocumentException) {
        throw error
    } catch (error: Exception) {
        throw LocalDocumentException(error)
    }

    private fun documentIdentity(uri: Uri, control: TransferControl): SourceIdentity {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return LocalDocumentTree.queryDocument(context.contentResolver, uri, columns, control) { cursor ->
            if (!cursor.moveToFirst()) throw SourceChangedException()
            SourceIdentity(uri.toString() + "#" + cursor.getString(2), if (cursor.isNull(0)) null else cursor.getLong(0).takeIf { it >= 0 },
                if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it > 0 })
        }
    }

    /** Reads the byte length of a selected document, or null when unavailable. */
    private fun documentLength(uri: Uri): Long? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    }.getOrNull()

    private fun skippedDetail(skippedEntries: Int): RemoteFileMessage? = skippedEntries
        .takeIf { it > 0 }
        ?.let(RemoteFileMessage::LinksSkipped)

    /** Returns the `/`-separated parent of a relative path, or `""` at the top. */
    private fun parentRelativePath(relativePath: String): String =
        if (relativePath.contains('/')) relativePath.substringBeforeLast('/') else ""

    /**
     * Reduces a picked document name to a single safe remote path component.
     *
     * The name comes from a content provider, so it is treated as untrusted:
     * a directory prefix, a separator, or a control character would otherwise
     * let it address a path the user never chose.
     */
    private fun sanitizeRemoteFileName(value: String): String {
        val normalized = value.substringAfterLast('/').trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_REMOTE_FILENAME_CHARS) {
            "Remote file name is invalid"
        }
        RemoteFilePaths.requireSafeRemoteName(normalized)
        return normalized
    }

    private class DownloadTarget(val stream: OutputStream, val offset: Long)

    private enum class StopReason { PAUSE, CANCEL }

    private class TransferHandle(val request: TransferRequest) {
        var job: Job? = null

        @Volatile
        var stop: StopReason? = null

        @Volatile var exactBytes = 0L
        @Volatile var operation: BlockingOperation? = null
        var connection: Connection? = null
        var sourceIdentity: SourceIdentity? = null
        var remoteWalk: RemoteTreeWalk? = null
        var localWalk: LocalTreeWalk? = null
        val completed = mutableSetOf<String>()
        val localTemps = ConcurrentHashMap<String, File>()
        val remoteTemps = ConcurrentHashMap<String, Pair<String, String>>()
        var optionsChosen = false
        var verifySha256 = false
        var taskDecision: TransferConflictDecision? = null

        fun control(): TransferControl = checkNotNull(operation)
    }

    /** Everything needed to run one transfer again from the start. */
    private sealed interface TransferRequest {
        val sessionId: String

        data class FileDownload(
            override val sessionId: String,
            val remotePath: String,
            val destination: Uri,
        ) : TransferRequest

        data class DirectoryDownload(
            override val sessionId: String,
            val remotePath: String,
            val destinationTree: Uri,
        ) : TransferRequest

        data class FileUpload(
            override val sessionId: String,
            val source: Uri,
            val fileName: String,
            val remoteDirectory: String,
        ) : TransferRequest

        data class DirectoryUpload(
            override val sessionId: String,
            val sourceTree: Uri,
            val remoteDirectory: String,
        ) : TransferRequest
    }

    /**
     * Rate limiter for transfer progress.
     *
     * A 32 KiB SFTP chunk arrives many times per second on a fast link, and
     * every update recomposes the transfer list, so intermediate progress is
     * only published on a byte or time step. Completion always passes through.
     */
    private class ProgressThrottle {
        private var lastEmittedBytes = -1L
        private var lastEmittedAtMillis = 0L

        fun shouldEmit(transferred: Long, total: Long?): Boolean {
            val now = System.nanoTime() / 1_000_000L
            val finished = total != null && transferred >= total
            val steppedTime = now - lastEmittedAtMillis >= PROGRESS_MIN_INTERVAL_MILLIS
            if (!finished && !steppedTime) return false
            lastEmittedBytes = transferred
            lastEmittedAtMillis = now
            return true
        }
    }

    private companion object {
        const val MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L
        const val MAX_REMOTE_FILENAME_CHARS = 255
        const val PROGRESS_MIN_BYTES = 256L * 1024L
        const val PROGRESS_MIN_INTERVAL_MILLIS = 200L
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        /** Used when a picked folder reports no name, or the remote root is transferred. */
        const val FALLBACK_DIRECTORY_NAME = "download"
    }
}
