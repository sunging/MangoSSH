package website.sung.mangossh.session

import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshFileFailure
import website.sung.mangossh.session.ssh.SshFileHandle
import website.sung.mangossh.session.ssh.SshFiles
import website.sung.mangossh.session.ssh.SshFileAttributes

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Sanitized reason why a remote file operation could not be completed. */
enum class RemoteFileFailure {
    /** The server refused or does not offer the SFTP subsystem. */
    SUBSYSTEM_UNAVAILABLE,
    NOT_FOUND,
    ACCESS_DENIED,
    NOT_A_FILE,
    TOO_LARGE,
    IO_FAILURE,
}

/**
 * Remote file failure carrying only an application-owned category.
 *
 * Server-supplied error text is deliberately dropped here: the controller maps
 * [failure] to a fixed string resource so remote output never reaches the UI.
 */
class RemoteFileException(
    val failure: RemoteFileFailure,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Cooperative stop signal checked at every SFTP chunk boundary.
 *
 * SFTP has no pause or abort verb, so a transfer stops by leaving its read or
 * write loop between chunks; the caller decides whether that was a pause or a
 * cancellation from the offset the loop returns.
 */
internal fun interface TransferControl {
    fun shouldContinue(): Boolean
    fun own(session: java.io.Closeable): Boolean = shouldContinue()
    fun release(session: java.io.Closeable) { session.close() }
    fun progressed() = Unit
    fun ownLocal(resource: java.io.Closeable) = Unit
    fun releaseLocal(resource: java.io.Closeable) { resource.close() }
}

/** Where an upload stopped, and the path it was writing to. */
internal data class RemoteUploadResult(
    val remotePath: String,
    val transferredBytes: Long,
)

/**
 * Blocking SFTP operations for the remote file browser.
 *
 * Every call opens its own [SshFiles] on the already-authenticated SSH
 * connection and closes it before returning, so no channel state is shared
 * between coroutines. Paths travel as SFTP protocol fields rather than through
 * a remote shell, which is why they need no shell-quoting restrictions.
 *
 * All functions block and must be called off the main dispatcher.
 */
internal class RemoteFileClient {

    /** Returns the absolute path the session's account starts in. */
    suspend fun resolveHome(connection: SshConnection): String = withClient(connection) { client ->
        RemoteFilePaths.normalize(client.canonicalPath("."))
    }

    /** Resolves [path] through the server, following symlinks. */
    suspend fun canonicalize(connection: SshConnection, path: String): String = withClient(connection) { client ->
        RemoteFilePaths.normalize(client.canonicalPath(path))
    }

    /** Returns the kind of [path] after following symlinks. */
    suspend fun identity(connection: SshConnection, path: String, control: TransferControl? = null): SourceIdentity =
        withClient(connection, control) { client ->
            val attributes = client.lstat(path)
            if (attributes.toKind() != RemoteFileKind.FILE) throw SourceChangedException()
            SourceIdentity(path, attributes.size, attributes.mtime?.times(1_000L))
        }

    suspend fun statKind(connection: SshConnection, path: String): RemoteFileKind = withClient(connection) { client ->
        client.stat(path).toKind()
    }

    /**
     * Lists [path], dropping `.`/`..` and capping the result at [maxEntries].
     *
     * The listing is sorted for display here so the UI never re-sorts remote
     * user data on every recomposition.
     */
    suspend fun list(
        connection: SshConnection,
        sessionId: String,
        path: String,
        maxEntries: Int,
    ): RemoteDirectoryListing = withClient(connection) { client ->
        val directory = RemoteFilePaths.normalize(path)
        val raw = client.list(directory, maxEntries + 1)
        val entries = raw.asSequence()
            .filter { it.filename != "." && it.filename != ".." }
            .mapNotNull { entry ->
                val name = entry.filename ?: return@mapNotNull null
                if (runCatching { RemoteFilePaths.requireSafeRemoteName(name) }.isFailure) return@mapNotNull null
                val attributes = entry.attributes
                RemoteFileEntry(
                    name = name,
                    path = RemoteFilePaths.join(directory, name),
                    kind = attributes.toKind(),
                    sizeBytes = attributes?.size,
                    modifiedEpochSeconds = attributes.mtime?.toLong(),
                    permissions = runCatching { attributes.permissions?.let { Integer.toOctalString(it and 4095) } }.getOrNull(),
                )
            }
            .take(maxEntries)
            .toList()
        RemoteDirectoryListing(
            sessionId = sessionId,
            path = directory,
            entries = RemoteFilePaths.sortForDisplay(entries),
            truncated = raw.size > maxEntries,
        )
    }

    /**
     * Reads at most [maxBytes] leading bytes of [path] for a read-only preview.
     *
     * Returns null when the bytes are not valid UTF-8 text, which the UI reports
     * as "cannot be previewed" instead of rendering binary noise.
     */
    suspend fun readTextPreview(
        connection: SshConnection,
        path: String,
        maxBytes: Int,
    ): RemoteTextPreview? = withClient(connection) { client ->
        val attributes = client.stat(path)
        if (attributes.isDirectory) throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        val totalSize = attributes.size
        val handle = client.open(path)
        val buffer = ByteArray(SFTP_CHUNK_BYTES)
        val collected = ByteArrayOutputStream()
        try {
            var offset = 0L
            while (collected.size() < maxBytes + 1) {
                val request = minOf(SFTP_CHUNK_BYTES, maxBytes + 1 - collected.size())
                val read = client.read(handle, offset, buffer, 0, request)
                if (read <= 0) break
                collected.write(buffer, 0, read)
                offset += read
            }
        } finally {
            client.close(handle)
        }
        val collectedBytes = collected.toByteArray()
        val bytes = collectedBytes.copyOf(minOf(collectedBytes.size, maxBytes))
        val truncated = collectedBytes.size > maxBytes || (totalSize != null && totalSize > bytes.size.toLong())
        RemoteTextDecoder.decode(
            path = path,
            bytes = bytes,
            truncated = truncated,
            totalSizeBytes = totalSize,
        )
    }

    /**
     * Streams [remotePath] into [output] from [startOffset], reporting
     * transferred and total bytes, and returns the absolute offset it reached.
     *
     * SFTP has no transfer callback, so progress comes from this read loop; the
     * SCP client the legacy dialog uses cannot report progress at all. A read
     * resumes at an arbitrary offset, so [output] must already be positioned at
     * [startOffset] by the caller.
     */
    suspend fun download(
        connection: SshConnection,
        remotePath: String,
        output: OutputStream,
        startOffset: Long,
        expected: SourceIdentity? = null,
        control: TransferControl,
        onProgress: (Long, Long?) -> Unit,
    ): Long = withClient(connection, control) { client ->
        val attributes = client.lstat(remotePath)
        expected?.requireMatches(SourceIdentity(remotePath, attributes.size, attributes.mtime?.times(1_000L)))
        if (attributes.isDirectory) throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        val total = attributes.size
        val handle = client.open(remotePath)
        var offset = startOffset
        try {
            onProgress(offset, total)
            offset = SftpPipeline.read(SFTP_PIPELINE_DEPTH, SFTP_CHUNK_BYTES, startOffset,
                readAt = { at, count -> client.read(handle, at, count) },
                shouldContinue = control::shouldContinue,
            ) { data, reached ->
                output.write(data, 0, data.size)
                offset = reached
                control.progressed()
                onProgress(reached, total)
            }
            output.flush()
            if (control.shouldContinue()) {
                val finalAttributes = client.fstat(handle)
                expected?.requireMatches(SourceIdentity(remotePath, finalAttributes.size, finalAttributes.mtime?.times(1_000L)))
                if (total != null && offset != total) throw SourceChangedException()
            }
        } finally {
            if (control.shouldContinue()) client.close(handle) else runCatching { client.close(handle) }
        }
        offset
    }

    /**
     * Streams [input] into `remoteDirectory/fileName` and enforces [maxBytes] so
     * a huge local selection cannot run away.
     *
     * A [startOffset] of zero truncates any existing remote file; a resume opens
     * the partial file read/write instead and keeps the bytes already there.
     * [input] starts at position zero; the first [startOffset] bytes are skipped.
     * A staged file longer than [startOffset] has its unacknowledged tail cut off;
     * any other size mismatch fails with [SourceChangedException] rather than
     * leaving a hole or stale bytes in the middle.
     */
    suspend fun upload(
        connection: SshConnection,
        input: InputStream,
        remoteDirectory: String,
        fileName: String,
        startOffset: Long,
        totalBytes: Long?,
        maxBytes: Long,
        control: TransferControl,
        onProgress: (Long, Long?) -> Unit,
    ): RemoteUploadResult = withClient(connection, control) { client ->
        val remotePath = RemoteFilePaths.join(remoteDirectory, fileName)
        val staging = isStagingName(fileName)
        // A staged file is inspected without following links, so a name swapped for a
        // symbolic link is refused instead of truncating whatever the link points at.
        val existing = runCatching { if (staging) client.lstat(remotePath) else client.stat(remotePath) }.getOrNull()
        // Pipelined writes can land past the acknowledged offset before a pause closes the
        // channel. A staged file is ours, so its unacknowledged tail is cut off on resume;
        // any other file must match the offset exactly.
        val size = existing?.size
        val resumable = startOffset > 0L && size != null && (size == startOffset || staging && size > startOffset)
        if (startOffset > 0L && !resumable) throw SourceChangedException()
        val handle = when {
            staging -> openStaging(client, remotePath, existing, truncate = !resumable)
            resumable -> client.open(remotePath, write = true)
            else -> client.open(remotePath, write = true, create = true, truncate = true)
        }
        val buffer = ByteArray(SFTP_CHUNK_BYTES)
        var offset = if (resumable) startOffset else 0L
        try {
            if (resumable && size != startOffset) {
                // Some servers only implement the path form; the handle was verified above.
                try { client.fsetstat(handle, SshFileAttributes(size = startOffset)) }
                catch (_: SshFileFailure) { client.setstat(remotePath, SshFileAttributes(size = startOffset)) }
                if (client.fstat(handle).size != startOffset) throw SourceChangedException()
            }
            // The stream arrives at position zero, so a resume has to consume
            // the bytes the remote file already holds before writing again.
            if (offset > 0L) skipFully(input, offset, buffer)
            onProgress(offset, totalBytes)
            var issued = offset
            offset = SftpPipeline.write(SFTP_PIPELINE_DEPTH, offset,
                nextChunk = {
                    var read: Int
                    do { read = input.read(buffer) } while (read == 0)
                    if (read < 0) null else {
                        if (issued + read > maxBytes) throw RemoteFileException(RemoteFileFailure.TOO_LARGE)
                        issued += read
                        buffer.copyOf(read)
                    }
                },
                writeAt = { at, data -> client.write(handle, at, data) },
                shouldContinue = control::shouldContinue,
            ) { acknowledged ->
                offset = acknowledged
                control.progressed()
                onProgress(acknowledged, totalBytes)
            }
        } finally {
            if (control.shouldContinue()) client.close(handle) else runCatching { client.close(handle) }
        }
        if (control.shouldContinue() && totalBytes != null && totalBytes != offset) throw SourceChangedException()
        RemoteUploadResult(remotePath = remotePath, transferredBytes = offset)
    }

    /** Reads the whole editable UTF-8 file with metadata and hash validation on both sides of the read. */
    suspend fun readEditable(connection: SshConnection, path: String, control: TransferControl): EditableRemoteText {
        val target = inspectTarget(connection, path, control)
        val identity = target.identity ?: throw SourceChangedException()
        if (identity.size != null && identity.size > MAX_REMOTE_PREVIEW_BYTES) throw RemoteFileException(RemoteFileFailure.TOO_LARGE)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (size() + count > MAX_REMOTE_PREVIEW_BYTES) throw RemoteFileException(RemoteFileFailure.TOO_LARGE)
                super.write(bytes, offset, count)
            }
        }
        download(connection, path, output, 0, identity, control) { _, _ -> }
        if (!control.shouldContinue()) throw java.io.InterruptedIOException()
        val bytes = output.toByteArray()
        val decoded = RemoteTextDecoder.decode(path, bytes, false, bytes.size.toLong()) ?: throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        val hasBom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        val text = decoded.text.removePrefix("\uFEFF")
        return EditableRemoteText(path, text, hasBom, if (text.contains("\r\n")) "\r\n" else "\n", identity, target,
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    /** Shares the transfer staging and replacement protocol; conflicts preserve the caller's draft. */
    suspend fun saveEditable(connection: SshConnection, source: EditableRemoteText, draft: String, alternateName: String?,
        allowDirectOverwrite: Boolean, control: TransferControl) {
        val destination = alternateName?.let { RemoteFilePaths.join(RemoteFilePaths.parentOf(source.path), it) } ?: source.path
        val target = if (destination == source.path) {
            val current = readEditable(connection, source.path, control)
            source.requireUnchanged(current)
            current.target
        } else inspectTarget(connection, destination, control).also { if (it.identity != null) throw SourceChangedException() }
        if (target.identity != null && !target.atomicReplace && !allowDirectOverwrite) throw AtomicReplaceUnavailableException()
        val bytes = source.encode(draft)
        val token = java.util.UUID.randomUUID().toString()
        val name = stagingName(token)
        val directory = RemoteFilePaths.parentOf(destination)
        val temporary = reserveTemporary(connection, directory, token, control)
        try {
            upload(connection, bytes.inputStream(), directory, name, 0, bytes.size.toLong(), MAX_REMOTE_PREVIEW_BYTES.toLong(), control) { _, _ -> }
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            if (!java.security.MessageDigest.isEqual(expected, sha256(connection, temporary, control))) throw SourceChangedException()
            if (destination == source.path) source.requireUnchanged(readEditable(connection, source.path, control))
            commitTemporary(connection, temporary, destination, target, allowDirectOverwrite, control)
        } finally {
            bytes.fill(0)
            runCatching { removeTemporary(connection, temporary, token) }
        }
    }

    /** Captures target metadata without following symbolic links or swallowing permission errors. */
    suspend fun inspectTarget(connection: SshConnection, path: String, control: TransferControl? = null): RemoteTarget = withClient(connection, control) { client ->
        val attributes = try { client.lstat(path) } catch (error: SshFileFailure) {
            if (error.status == 2 || error.status == 10) null else throw error
        }
        if (attributes != null && attributes.toKind() != RemoteFileKind.FILE) throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        RemoteTarget(attributes?.let { SourceIdentity(path, it.size, it.mtime?.times(1_000L)) }, attributes?.permissions, client.atomicReplaceSupported, attributes?.uid, attributes?.gid)
    }

    /** Calculates SHA-256 through SFTP reads; no remote shell command or external utility is invoked. */
    suspend fun sha256(connection: SshConnection, path: String, control: TransferControl): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { digest.update(bytes, offset, count) }
        }
        val identity = identity(connection, path, control)
        download(connection, path, sink, 0, identity, control) { _, _ -> }
        if (!control.shouldContinue()) throw java.io.InterruptedIOException()
        return digest.digest()
    }

    /** Commits one verified sibling temp; normal rename is used only when the target does not exist. */
    suspend fun commitTemporary(connection: SshConnection, temporary: String, destination: String, target: RemoteTarget,
        allowDirectOverwrite: Boolean, control: TransferControl, expectedTargetDigest: ByteArray? = null) = withClient(connection, control) { client ->
        require(RemoteFilePaths.parentOf(temporary) == RemoteFilePaths.parentOf(destination))
        if (expectedTargetDigest != null && !java.security.MessageDigest.isEqual(expectedTargetDigest,
                sha256(connection, destination, control))) throw SourceChangedException()
        val current = try { client.lstat(destination) } catch (error: SshFileFailure) {
            if (error.status == 2 || error.status == 10) null else throw error
        }
        if (target.identity == null) {
            if (current != null) throw SourceChangedException()
        } else {
            if (current == null || current.toKind() != RemoteFileKind.FILE) throw SourceChangedException()
            target.identity.requireMatches(SourceIdentity(destination, current.size, current.mtime?.times(1_000L)))
            if (current.permissions != target.permissions || current.uid != target.uid || current.gid != target.gid) throw SourceChangedException()
        }
        if (target.identity != null) {
            val uid = target.uid ?: throw MetadataPreservationException()
            val gid = target.gid ?: throw MetadataPreservationException()
            val mode = target.permissions?.and(0xfff) ?: throw MetadataPreservationException()
            if (!client.atomicReplaceSupported && mode and 0xe00 != 0) throw MetadataPreservationException()
            if (client.atomicReplaceSupported) {
                try {
                    val before = client.lstat(temporary)
                    if (before.uid != uid || before.gid != gid) client.setstat(temporary,
                        SshFileAttributes(uid = uid, gid = gid))
                    client.setstat(temporary, SshFileAttributes(permissions = mode))
                    val verified = client.lstat(temporary)
                    if (verified.uid != uid || verified.gid != gid || verified.permissions?.and(0xfff) != mode)
                        throw MetadataPreservationException()
                } catch (_: SshFileFailure) { throw MetadataPreservationException() }
            }
        }
        if (target.identity == null) {
            // The staging file was created private; a brand-new destination gets the mode an
            // OpenSSH server would give it under the common umask. A server that refuses the
            // change keeps the file private rather than failing the upload.
            runCatching { client.setstat(temporary, SshFileAttributes(permissions = NEW_FILE_PERMISSIONS)) }
            client.rename(temporary, destination)
        } else if (client.atomicReplaceSupported) client.replaceAtomically(temporary, destination)
        else {
            if (!allowDirectOverwrite) throw AtomicReplaceUnavailableException()
            val source = client.open(temporary)
            try {
                val output = client.open(destination, write = true, create = true, truncate = true)
                try {
                    var offset = 0L
                    val buffer = ByteArray(SFTP_CHUNK_BYTES)
                    while (control.shouldContinue()) {
                        val count = client.read(source, offset, buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        client.write(output, offset, buffer, 0, count)
                        offset += count
                        control.progressed()
                    }
                    if (!control.shouldContinue()) throw java.io.InterruptedIOException()
                    val written = client.fstat(output)
                    if (client.fstat(source).size != written.size) throw java.io.IOException()
                    if (written.uid != target.uid || written.gid != target.gid ||
                        written.permissions?.and(0xfff) != target.permissions?.and(0xfff)) throw java.io.IOException()
                } finally { client.close(output) }
            } finally { client.close(source) }
            client.remove(temporary)
        }
    }

    /**
     * Claims a sibling temporary with exclusive-create before any upload or cleanup can own it.
     *
     * The file is created owner-only: it holds the full new content for as long as the
     * transfer is paused or failed, and its directory may be readable by other accounts.
     * A server that ignores the creation mode is asked once more with SETSTAT.
     */
    suspend fun reserveTemporary(connection: SshConnection, directory: String, token: String, control: TransferControl): String = withClient(connection, control) { client ->
        val path = RemoteFilePaths.join(directory, stagingName(token))
        val handle = client.open(path, write = true, create = true, exclusive = true, permissions = STAGING_PERMISSIONS)
        try {
            val created = client.fstat(handle)
            if (created.permissions?.and(0x3f) != 0) runCatching { client.setstat(path, SshFileAttributes(permissions = STAGING_PERMISSIONS)) }
            client.close(handle)
        } catch (error: Exception) {
            runCatching { client.close(handle) }
            runCatching { client.remove(path) }
            throw error
        }
        path
    }

    /**
     * Opens a reserved staging file for writing after proving it is still the regular file
     * that was reserved. SFTP v3 cannot refuse to follow links on open, so the name is
     * checked with LSTAT first and the opened handle is compared with that result.
     */
    private suspend fun openStaging(client: SshFiles, path: String, before: SshFileAttributes?, truncate: Boolean): SshFileHandle {
        if (before == null || before.permissions != null && !before.isRegularFile) throw SourceChangedException()
        val handle = client.open(path, write = true, truncate = truncate)
        try {
            val opened = client.fstat(handle)
            if (opened.permissions != null && !opened.isRegularFile) throw SourceChangedException()
            if (opened.uid != before.uid || opened.permissions != before.permissions) throw SourceChangedException()
            if (!truncate && opened.size != before.size) throw SourceChangedException()
            return handle
        } catch (error: Exception) {
            runCatching { client.close(handle) }
            throw error
        }
    }

    /** Deletes only a task-generated sibling name whose ownership token still matches. */
    suspend fun removeTemporary(connection: SshConnection, path: String, token: String) {
        require(RemoteFilePaths.nameOf(path) == stagingName(token))
        withClient(connection) { client ->
            try { client.remove(path) } catch (error: SshFileFailure) {
                if (error.status != 2) throw error
            }
        }
    }

    /**
     * Advances [input] by exactly [count] bytes.
     *
     * A short skip would silently upload the wrong bytes over the resumed
     * offset, so a stream that cannot reach it fails instead; retrying the
     * transfer restarts from the beginning.
     */
    private fun skipFully(input: InputStream, count: Long, buffer: ByteArray) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw RemoteFileException(RemoteFileFailure.IO_FAILURE)
            remaining -= read
        }
    }

    /**
     * Creates [path] unless a directory is already there.
     *
     * Servers report an existing name as a generic failure, so an error is only
     * swallowed when a follow-up stat proves a directory now exists.
     */
    suspend fun mkdirIfMissing(connection: SshConnection, path: String) = withClient(connection) { client ->
        try {
            client.mkdir(path, DIRECTORY_PERMISSIONS)
        } catch (error: IOException) {
            val existing = runCatching { client.stat(path) }.getOrNull()
            if (existing?.isDirectory != true) throw error
        }
    }

    /**
     * Enumerates the tree under [root] breadth-first for a directory transfer.
     *
     * Symbolic links are counted but never followed: the server is untrusted and
     * a link can point back up the tree, which would make the walk unbounded.
     * The walk also stops at [maxEntries] and [maxDepth] and reports that as
     * truncation rather than transferring a partial tree silently.
     */
    suspend fun walk(
        connection: SshConnection,
        root: String,
        maxEntries: Int,
        maxDepth: Int,
        control: TransferControl? = null,
    ): RemoteTreeWalk = withClient(connection, control, timeoutMillis = 60_000L) { client ->
        val base = RemoteFilePaths.normalize(root)
        val directories = mutableListOf<String>()
        val files = mutableListOf<RemoteTreeEntry>()
        var totalBytes = 0L
        var truncated = false
        var skipped = 0
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(base to 0)
        var visited = 0
        traversal@ while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            val listing = client.list(directory, maxEntries - visited + 1)
            for (entry in listing) {
                if (control?.shouldContinue() == false) throw java.io.InterruptedIOException()
                if (++visited > maxEntries) { truncated = true; break@traversal }
                val name = entry.filename ?: continue
                if (name == "." || name == "..") continue
                if (runCatching { RemoteFilePaths.requireSafeRemoteName(name) }.isFailure) {
                    skipped += 1
                    continue
                }
                val absolute = RemoteFilePaths.join(directory, name)
                val relative = RemoteFilePaths.relativize(base, absolute)
                when (entry.attributes.toKind()) {
                    RemoteFileKind.DIRECTORY -> {
                        if (depth + 1 > maxDepth) {
                            truncated = true
                            break@traversal
                        }
                        directories += relative
                        pending.addLast(absolute to depth + 1)
                    }

                    RemoteFileKind.FILE -> {
                        if (files.size >= maxEntries) {
                            truncated = true
                            continue
                        }
                        val size = entry.attributes?.size
                        files += RemoteTreeEntry(
                            relativePath = relative,
                            absolutePath = absolute,
                            sizeBytes = size,
                            modifiedEpochMillis = entry.attributes?.mtime?.times(1_000L),
                        )
                        totalBytes += size ?: 0L
                    }

                    else -> skipped += 1
                }
            }
        }
        RemoteTreeWalk(
            root = base,
            directories = directories,
            files = files,
            totalBytes = totalBytes,
            truncated = truncated,
            skippedEntries = skipped,
        )
    }

    /**
     * Runs [block] against a freshly opened SFTP channel.
     *
     * Failures are normalized into [RemoteFileException] so no server-provided
     * text escapes this class.
     */
    private suspend fun <T> withClient(
        connection: SshConnection,
        control: TransferControl? = null,
        timeoutMillis: Long = 15_000L,
        block: suspend (SshFiles) -> T,
    ): T {
        val owned = control ?: BlockingOperation(timeoutMillis)
        var session: java.io.Closeable? = null
        try {
            if (!owned.shouldContinue()) throw java.io.InterruptedIOException()
            val client = kotlinx.coroutines.coroutineScope {
                val opening = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                val armed = java.util.concurrent.atomic.AtomicBoolean(true)
                val cancellation = java.io.Closeable { if (armed.get()) opening.cancel() }
                if (!owned.own(cancellation)) throw java.io.InterruptedIOException()
                try {
                    connection.openFiles().also {
                        session = it
                        if (!owned.own(it)) throw java.io.InterruptedIOException()
                    }
                } finally { armed.set(false); owned.release(cancellation) }
            }

            return block(client)
        } catch (error: kotlinx.coroutines.CancellationException) {
            owned.shouldContinue()
            throw error
        } catch (error: RemoteFileException) { throw error
        } catch (error: SourceChangedException) {
            throw error
        } catch (error: MetadataPreservationException) { throw error
        } catch (error: AtomicReplaceUnavailableException) {
            throw error
        } catch (error: StagingSpaceException) {
            // A download writes through the staging budget inside this block, so
            // running out of room mid-transfer has to keep its own byte count
            // instead of being normalized into a generic I/O failure.
            throw error
        } catch (error: SshFileFailure) { throw RemoteFileException(error.toFailure(), error)
        } catch (error: IOException) {
            owned.shouldContinue() // Preserve an operation timeout instead of disguising it as an I/O error.
            throw RemoteFileException(RemoteFileFailure.IO_FAILURE, error)
        } finally {
            session?.let(owned::release)
            if (control == null) (owned as BlockingOperation).close()
        }
    }

    private fun SshFileFailure.toFailure(): RemoteFileFailure = when (status) {
        2, 10 -> RemoteFileFailure.NOT_FOUND
        3 -> RemoteFileFailure.ACCESS_DENIED
        8 -> RemoteFileFailure.SUBSYSTEM_UNAVAILABLE
        else -> RemoteFileFailure.IO_FAILURE
    }

    private fun SshFileAttributes?.toKind(): RemoteFileKind = when {
        this == null -> RemoteFileKind.OTHER
        isDirectory -> RemoteFileKind.DIRECTORY
        isSymlink -> RemoteFileKind.SYMLINK
        isRegularFile -> RemoteFileKind.FILE
        else -> RemoteFileKind.OTHER
    }

    internal companion object {
        /** SFTP v3 caps a single read or write request at 32 KiB. */
        const val SFTP_CHUNK_BYTES = 32 * 1024

        /** Requests kept in flight per transfer: at most 256 KiB outstanding. */
        const val SFTP_PIPELINE_DEPTH = 8

        /** `rwxr-xr-x`, matching what a shell `mkdir` produces under a default umask. */
        const val DIRECTORY_PERMISSIONS = 493

        /** `rw-------`: staged content is private until it is committed. */
        const val STAGING_PERMISSIONS = 0x180

        /** `rw-r--r--`, what an OpenSSH server creates under the common `022` umask. */
        const val NEW_FILE_PERMISSIONS = 0x1a4

        private val STAGING_NAME = Regex("""\.mangossh-[0-9a-fA-F-]{36}\.part""")

        fun stagingName(token: String) = ".mangossh-$token.part"

        /** True only for names this app generates in [reserveTemporary]. */
        fun isStagingName(name: String) = STAGING_NAME.matches(name)

        /**
         * SFTP v3 does not declare a filename encoding. Modern servers use
         * UTF-8, which is also what the terminal side of this app assumes.
         */
        const val FILENAME_CHARSET = "UTF-8"
    }
}
