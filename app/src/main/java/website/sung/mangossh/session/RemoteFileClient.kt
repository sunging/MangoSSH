package website.sung.mangossh.session

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPException
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3FileAttributes
import com.trilead.ssh2.sftp.ErrorCodes
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
}

/** Where an upload stopped, and the path it was writing to. */
internal data class RemoteUploadResult(
    val remotePath: String,
    val transferredBytes: Long,
)

/**
 * Blocking SFTP operations for the remote file browser.
 *
 * Every call opens its own [SFTPv3Client] on the already-authenticated SSH
 * connection and closes it before returning, so no channel state is shared
 * between coroutines. Paths travel as SFTP protocol fields rather than through
 * a remote shell, which is why they need no shell-quoting restrictions.
 *
 * All functions block and must be called off the main dispatcher.
 */
internal class RemoteFileClient {

    /** Returns the absolute path the session's account starts in. */
    fun resolveHome(connection: Connection): String = withClient(connection) { client ->
        RemoteFilePaths.normalize(client.canonicalPath("."))
    }

    /** Resolves [path] through the server, following symlinks. */
    fun canonicalize(connection: Connection, path: String): String = withClient(connection) { client ->
        RemoteFilePaths.normalize(client.canonicalPath(path))
    }

    /** Returns the kind of [path] after following symlinks. */
    fun statKind(connection: Connection, path: String): RemoteFileKind = withClient(connection) { client ->
        client.stat(path).toKind()
    }

    /**
     * Lists [path], dropping `.`/`..` and capping the result at [maxEntries].
     *
     * The listing is sorted for display here so the UI never re-sorts remote
     * user data on every recomposition.
     */
    fun list(
        connection: Connection,
        sessionId: String,
        path: String,
        maxEntries: Int,
    ): RemoteDirectoryListing = withClient(connection) { client ->
        val directory = RemoteFilePaths.normalize(path)
        val raw = client.ls(directory)
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
                    modifiedEpochSeconds = attributes?.mtime,
                    permissions = runCatching { attributes?.octalPermissions }.getOrNull(),
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
    fun readTextPreview(
        connection: Connection,
        path: String,
        maxBytes: Int,
    ): RemoteTextPreview? = withClient(connection) { client ->
        val attributes = client.stat(path)
        if (attributes.isDirectory) throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        val totalSize = attributes.size
        val handle = client.openFileRO(path)
        val buffer = ByteArray(SFTP_CHUNK_BYTES)
        val collected = ByteArrayOutputStream()
        try {
            var offset = 0L
            while (collected.size() < maxBytes) {
                val request = minOf(SFTP_CHUNK_BYTES, maxBytes - collected.size())
                val read = client.read(handle, offset, buffer, 0, request)
                if (read <= 0) break
                collected.write(buffer, 0, read)
                offset += read
            }
        } finally {
            runCatching { client.closeFile(handle) }
        }
        val bytes = collected.toByteArray()
        val truncated = totalSize != null && totalSize > bytes.size.toLong()
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
    fun download(
        connection: Connection,
        remotePath: String,
        output: OutputStream,
        startOffset: Long,
        control: TransferControl,
        onProgress: (Long, Long?) -> Unit,
    ): Long = withClient(connection) { client ->
        val attributes = client.stat(remotePath)
        if (attributes.isDirectory) throw RemoteFileException(RemoteFileFailure.NOT_A_FILE)
        val total = attributes.size
        val handle = client.openFileRO(remotePath)
        val buffer = ByteArray(SFTP_CHUNK_BYTES)
        var offset = startOffset
        try {
            onProgress(offset, total)
            while (control.shouldContinue()) {
                val read = client.read(handle, offset, buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                offset += read
                onProgress(offset, total)
            }
            output.flush()
        } finally {
            runCatching { client.closeFile(handle) }
        }
        offset
    }

    /**
     * Streams [input] into `remoteDirectory/fileName` and enforces [maxBytes] so
     * a huge local selection cannot run away.
     *
     * A [startOffset] of zero truncates any existing remote file; a resume opens
     * the partial file read/write instead and keeps the bytes already there, so
     * [input] must already be positioned at [startOffset]. A remote file that no
     * longer matches that offset is rewritten from the start rather than left
     * with a hole in the middle.
     */
    fun upload(
        connection: Connection,
        input: InputStream,
        remoteDirectory: String,
        fileName: String,
        startOffset: Long,
        totalBytes: Long?,
        maxBytes: Long,
        control: TransferControl,
        onProgress: (Long, Long?) -> Unit,
    ): RemoteUploadResult = withClient(connection) { client ->
        val remotePath = RemoteFilePaths.join(remoteDirectory, fileName)
        val resumable = startOffset > 0L &&
            runCatching { client.stat(remotePath).size }.getOrNull() == startOffset
        val handle = if (resumable) {
            client.openFileRW(remotePath)
        } else {
            client.createFileTruncate(remotePath)
        }
        val buffer = ByteArray(SFTP_CHUNK_BYTES)
        var offset = if (resumable) startOffset else 0L
        try {
            // The stream arrives at position zero, so a resume has to consume
            // the bytes the remote file already holds before writing again.
            if (offset > 0L) skipFully(input, offset, buffer)
            onProgress(offset, totalBytes)
            while (control.shouldContinue()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (offset + read > maxBytes) throw RemoteFileException(RemoteFileFailure.TOO_LARGE)
                client.write(handle, offset, buffer, 0, read)
                offset += read
                onProgress(offset, totalBytes)
            }
        } finally {
            runCatching { client.closeFile(handle) }
        }
        RemoteUploadResult(remotePath = remotePath, transferredBytes = offset)
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
    fun mkdirIfMissing(connection: Connection, path: String) = withClient(connection) { client ->
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
    fun walk(
        connection: Connection,
        root: String,
        maxEntries: Int,
        maxDepth: Int,
    ): RemoteTreeWalk = withClient(connection) { client ->
        val base = RemoteFilePaths.normalize(root)
        val directories = mutableListOf<String>()
        val files = mutableListOf<RemoteTreeEntry>()
        var totalBytes = 0L
        var truncated = false
        var skipped = 0
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(base to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            val listing = client.ls(directory)
            for (entry in listing) {
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
                            continue
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
    private fun <T> withClient(connection: Connection, block: (SFTPv3Client) -> T): T {
        val client = try {
            SFTPv3Client(connection).apply { setCharset(FILENAME_CHARSET) }
        } catch (error: IOException) {
            throw RemoteFileException(RemoteFileFailure.SUBSYSTEM_UNAVAILABLE, error)
        }
        try {
            return block(client)
        } catch (error: RemoteFileException) {
            throw error
        } catch (error: SFTPException) {
            throw RemoteFileException(error.toFailure(), error)
        } catch (error: IOException) {
            throw RemoteFileException(RemoteFileFailure.IO_FAILURE, error)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun SFTPException.toFailure(): RemoteFileFailure = when (serverErrorCode) {
        ErrorCodes.SSH_FX_NO_SUCH_FILE, ErrorCodes.SSH_FX_NO_SUCH_PATH -> RemoteFileFailure.NOT_FOUND
        ErrorCodes.SSH_FX_PERMISSION_DENIED -> RemoteFileFailure.ACCESS_DENIED
        ErrorCodes.SSH_FX_OP_UNSUPPORTED -> RemoteFileFailure.SUBSYSTEM_UNAVAILABLE
        else -> RemoteFileFailure.IO_FAILURE
    }

    private fun SFTPv3FileAttributes?.toKind(): RemoteFileKind = when {
        this == null -> RemoteFileKind.OTHER
        isDirectory -> RemoteFileKind.DIRECTORY
        isSymlink -> RemoteFileKind.SYMLINK
        isRegularFile -> RemoteFileKind.FILE
        else -> RemoteFileKind.OTHER
    }

    private companion object {
        /** SFTP v3 caps a single read or write request at 32 KiB. */
        const val SFTP_CHUNK_BYTES = 32 * 1024

        /** `rwxr-xr-x`, matching what a shell `mkdir` produces under a default umask. */
        const val DIRECTORY_PERMISSIONS = 493

        /**
         * SFTP v3 does not declare a filename encoding. Modern servers use
         * UTF-8, which is also what the terminal side of this app assumes.
         */
        const val FILENAME_CHARSET = "UTF-8"
    }
}
