package website.sung.mangossh.session

import android.content.Context
import android.net.Uri
import com.trilead.ssh2.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
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
 * Resuming and retrying reuse the connection the transfer was started on and
 * never open a new one: authentication and host-key prompts are only rendered
 * by the remote file browser, so a transfer list that could reconnect would
 * have nowhere to ask. [onSessionEnded] marks those transfers uncontrollable
 * instead.
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
    private val connectionOf: (String) -> Connection,
    private val onSessionIdle: (String) -> Unit,
) {
    private val _transfers = MutableStateFlow<List<ScpTransferState>>(emptyList())
    val transfers: StateFlow<List<ScpTransferState>> = _transfers.asStateFlow()

    private val handles = ConcurrentHashMap<String, TransferHandle>()

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
    }

    /** Continues a paused transfer from the offset it stopped at. */
    fun resume(transferId: String) {
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canResume) return
        start(transferId, current.transferredBytes, current.completedItems)
    }

    /** Stops a queued, running, or paused transfer for good. */
    fun cancel(transferId: String) {
        val handle = handles[transferId] ?: return
        val current = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (!current.canCancel) return
        handle.stop = StopReason.CANCEL
        if (current.phase == ScpTransferPhase.PAUSED) {
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
        removed.forEach { handles.remove(it.id) }
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

    private fun start(transferId: String, startOffset: Long, completedItems: Int) {
        val handle = handles[transferId] ?: return
        handle.stop = null
        update(transferId) { state ->
            state.copy(
                phase = ScpTransferPhase.QUEUED,
                detail = null,
                transferredBytes = startOffset,
                completedItems = completedItems,
                currentItem = null,
            )
        }
        handle.job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (val request = handle.request) {
                        is TransferRequest.FileDownload ->
                            runFileDownload(transferId, handle, request, startOffset)

                        is TransferRequest.DirectoryDownload ->
                            runDirectoryDownload(transferId, handle, request, startOffset, completedItems)

                        is TransferRequest.FileUpload ->
                            runFileUpload(transferId, handle, request, startOffset)

                        is TransferRequest.DirectoryUpload ->
                            runDirectoryUpload(transferId, handle, request, startOffset, completedItems)
                    }
                }
            } catch (cancelled: CancellationException) {
                val current = _transfers.value.firstOrNull { it.id == transferId }
                settle(
                    transferId = transferId,
                    phase = ScpTransferPhase.CANCELLED,
                    transferredBytes = current?.transferredBytes ?: startOffset,
                    completedItems = current?.completedItems ?: completedItems,
                )
                throw cancelled
            } catch (error: Exception) {
                val current = _transfers.value.firstOrNull { it.id == transferId }
                settle(
                    transferId = transferId,
                    phase = ScpTransferPhase.FAILED,
                    transferredBytes = current?.transferredBytes ?: startOffset,
                    completedItems = current?.completedItems ?: completedItems,
                    detail = error.toRemoteFileMessage(),
                )
            }
        }
    }

    private fun runFileDownload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.FileDownload,
        startOffset: Long,
    ) {
        val connection = connectionOf(request.sessionId)
        markRunning(transferId)
        val destination = openDownloadTarget(transferId, request.destination, startOffset)
        val throttle = ProgressThrottle()
        val reached = destination.stream.use { output ->
            remoteFiles.download(
                connection = connection,
                remotePath = request.remotePath,
                output = output,
                startOffset = destination.offset,
                control = handle.control(),
            ) { transferred, total ->
                if (throttle.shouldEmit(transferred, total)) {
                    updateProgress(transferId, transferred, total)
                }
            }
        }
        settleAfterRun(transferId, handle, reached, completedItems = 0)
    }

    private fun runFileUpload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.FileUpload,
        startOffset: Long,
    ) {
        val connection = connectionOf(request.sessionId)
        markRunning(transferId)
        val totalBytes = documentLength(request.source)
        val throttle = ProgressThrottle()
        val result = openSource(request.source).use { input ->
            remoteFiles.upload(
                connection = connection,
                input = input,
                remoteDirectory = request.remoteDirectory,
                fileName = request.fileName,
                startOffset = startOffset,
                totalBytes = totalBytes,
                maxBytes = MAX_UPLOAD_BYTES,
                control = handle.control(),
            ) { transferred, total ->
                if (throttle.shouldEmit(transferred, total)) {
                    updateProgress(transferId, transferred, total)
                }
            }
        }
        settleAfterRun(transferId, handle, result.transferredBytes, completedItems = 0)
    }

    /**
     * Downloads a remote tree, one file at a time.
     *
     * Resuming is file-grained: files already finished are skipped and the file
     * that was interrupted is transferred again from its start, which avoids
     * tracking a separate offset for every entry of the tree.
     */
    private fun runDirectoryDownload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.DirectoryDownload,
        startOffset: Long,
        completedItems: Int,
    ) {
        val connection = connectionOf(request.sessionId)
        markRunning(transferId)
        val walk = remoteFiles.walk(
            connection = connection,
            root = request.remotePath,
            maxEntries = MAX_TRANSFER_TREE_ENTRIES,
            maxDepth = MAX_TRANSFER_TREE_DEPTH,
        )
        if (walk.truncated) throw TransferTreeTooLargeException()
        update(transferId) { state ->
            state.copy(
                totalItems = walk.files.size,
                totalBytes = walk.totalBytes,
                detail = skippedDetail(walk.skippedEntries),
            )
        }

        val resolver = context.contentResolver
        val treeUri = request.destinationTree
        val rootName = RemoteFilePaths.nameOf(request.remotePath).takeIf { it != RemoteFilePaths.ROOT }
            ?: FALLBACK_DIRECTORY_NAME
        val localRoot = LocalDocumentTree.createDirectory(
            resolver = resolver,
            treeUri = treeUri,
            parentDocumentUri = LocalDocumentTree.rootOf(treeUri),
            name = rootName,
        )
        val directories = mutableMapOf("" to localRoot)
        walk.directories.forEach { relative ->
            val parent = directories.getValue(parentRelativePath(relative))
            directories[relative] = LocalDocumentTree.createDirectory(
                resolver = resolver,
                treeUri = treeUri,
                parentDocumentUri = parent,
                name = relative.substringAfterLast('/'),
            )
        }

        var transferred = startOffset
        var completed = completedItems
        walk.files.drop(completedItems).forEach { entry ->
            if (handle.stop != null) {
                settleAfterRun(transferId, handle, transferred, completed)
                return
            }
            update(transferId) { state -> state.copy(currentItem = entry.relativePath) }
            val parent = directories[parentRelativePath(entry.relativePath)] ?: localRoot
            val document = LocalDocumentTree.createFile(
                resolver = resolver,
                treeUri = treeUri,
                parentDocumentUri = parent,
                mimeType = DEFAULT_MIME_TYPE,
                name = entry.relativePath.substringAfterLast('/'),
            )
            val throttle = ProgressThrottle()
            val base = transferred
            val fileBytes = openDownloadTarget(transferId, document, startOffset = 0L).stream.use { output ->
                remoteFiles.download(
                    connection = connection,
                    remotePath = entry.absolutePath,
                    output = output,
                    startOffset = 0L,
                    control = handle.control(),
                ) { fileTransferred, _ ->
                    if (throttle.shouldEmit(base + fileTransferred, walk.totalBytes)) {
                        updateProgress(transferId, base + fileTransferred, walk.totalBytes)
                    }
                }
            }
            if (handle.stop != null) {
                // The interrupted file restarts on resume, so only whole files count.
                settleAfterRun(transferId, handle, base, completed)
                return
            }
            transferred = base + fileBytes
            completed += 1
            updateProgress(transferId, transferred, walk.totalBytes)
            update(transferId) { state -> state.copy(completedItems = completed) }
        }
        settleAfterRun(transferId, handle, transferred, completed)
    }

    /** Uploads a picked local folder, mirroring [runDirectoryDownload]. */
    private fun runDirectoryUpload(
        transferId: String,
        handle: TransferHandle,
        request: TransferRequest.DirectoryUpload,
        startOffset: Long,
        completedItems: Int,
    ) {
        val connection = connectionOf(request.sessionId)
        markRunning(transferId)
        val resolver = context.contentResolver
        val walk = LocalDocumentTree.walk(
            resolver = resolver,
            treeUri = request.sourceTree,
            maxEntries = MAX_TRANSFER_TREE_ENTRIES,
            maxDepth = MAX_TRANSFER_TREE_DEPTH,
        )
        if (walk.truncated) throw TransferTreeTooLargeException()
        update(transferId) { state ->
            state.copy(totalItems = walk.files.size, totalBytes = walk.totalBytes)
        }

        val rootName = sanitizeRemoteFileName(walk.name.ifBlank { FALLBACK_DIRECTORY_NAME })
        val remoteRoot = RemoteFilePaths.join(request.remoteDirectory, rootName)
        remoteFiles.mkdirIfMissing(connection, remoteRoot)
        walk.directories.forEach { relative ->
            remoteFiles.mkdirIfMissing(connection, RemoteFilePaths.resolve(remoteRoot, relative))
        }

        var transferred = startOffset
        var completed = completedItems
        walk.files.drop(completedItems).forEach { entry ->
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
            val result = openSource(entry.documentUri).use { input ->
                remoteFiles.upload(
                    connection = connection,
                    input = input,
                    remoteDirectory = remoteDirectory,
                    fileName = entry.relativePath.substringAfterLast('/'),
                    startOffset = 0L,
                    totalBytes = entry.sizeBytes,
                    maxBytes = MAX_UPLOAD_BYTES,
                    control = handle.control(),
                ) { fileTransferred, _ ->
                    if (throttle.shouldEmit(base + fileTransferred, walk.totalBytes)) {
                        updateProgress(transferId, base + fileTransferred, walk.totalBytes)
                    }
                }
            }
            if (handle.stop != null) {
                settleAfterRun(transferId, handle, base, completed)
                return
            }
            transferred = base + result.transferredBytes
            completed += 1
            updateProgress(transferId, transferred, walk.totalBytes)
            update(transferId) { state -> state.copy(completedItems = completed) }
        }
        settleAfterRun(transferId, handle, transferred, completed)
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
        var sessionId: String? = null
        _transfers.update { current ->
            current.map { state ->
                if (state.id == transferId) {
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

    private fun update(transferId: String, transform: (ScpTransferState) -> ScpTransferState) {
        _transfers.update { current ->
            current.map { state -> if (state.id == transferId) transform(state) else state }
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
        if (startOffset > 0L && documentLength(destination) == startOffset) {
            val appended = runCatching { resolver.openOutputStream(destination, "wa") }.getOrNull()
            if (appended != null) return DownloadTarget(appended, startOffset)
            update(transferId) { state ->
                state.copy(detail = RemoteFileMessage.ResumeRestarted)
            }
        } else if (startOffset > 0L) {
            update(transferId) { state ->
                state.copy(detail = RemoteFileMessage.ResumeRestarted)
            }
        }
        val stream = try {
            resolver.openOutputStream(destination, "w")
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

        fun control(): TransferControl = TransferControl { stop == null }
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
            val now = System.currentTimeMillis()
            val finished = total != null && transferred >= total
            val steppedBytes = transferred - lastEmittedBytes >= PROGRESS_MIN_BYTES
            val steppedTime = now - lastEmittedAtMillis >= PROGRESS_MIN_INTERVAL_MILLIS
            if (!finished && !steppedBytes && !steppedTime) return false
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
