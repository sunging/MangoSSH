package website.sung.mangossh.session.ssh

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult

/** Remote metadata remains optional; absence must never be confused with zero. */
internal data class SshFileAttributes(
    val size: Long? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val permissions: Int? = null,
    val atime: Int? = null,
    val mtime: Int? = null,
) {
    val isDirectory get() = permissions?.and(0xf000) == 0x4000
    val isSymlink get() = permissions?.and(0xf000) == 0xa000
    val isRegularFile get() = permissions?.and(0xf000) == 0x8000
}

internal class SshFileEntry(val filename: String, val attributes: SshFileAttributes)
internal class SshFileHandle internal constructor(internal val delegate: SftpFileHandle)

/** Server status category only; paths, descriptions and protocol causes are deliberately dropped. */
internal class SshFileFailure(val status: Int) : IOException("SFTP operation failed")

/** Each instance owns one SFTP channel; closing it never disconnects the SSH connection. */
internal class SshFiles internal constructor(
    private val delegate: SftpClient,
    private val released: (SshFiles) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    val atomicReplaceSupported get() = delegate.extensions["posix-rename@openssh.com"] == "1"

    suspend fun canonicalPath(path: String): String = request { delegate.realpath(path) }
    suspend fun stat(path: String): SshFileAttributes = request { delegate.stat(path) }.appAttributes()
    suspend fun lstat(path: String): SshFileAttributes = request { delegate.lstat(path) }.appAttributes()
    suspend fun fstat(handle: SshFileHandle): SshFileAttributes = request { delegate.fstat(handle.delegate) }.appAttributes()
    suspend fun setstat(path: String, attributes: SshFileAttributes) = request { delegate.setstat(path, attributes.libraryAttributes()) }
    suspend fun mkdir(path: String, permissions: Int) = request { delegate.mkdir(path, SftpAttributes(permissions = permissions)) }
    suspend fun remove(path: String) = request { delegate.remove(path) }
    suspend fun rename(source: String, target: String) = request { delegate.rename(source, target) }
    suspend fun replaceAtomically(source: String, target: String) {
        if (!atomicReplaceSupported) throw SshFileFailure(8)
        request { delegate.posixRename(source, target) }
    }

    /** Exclusive creation prevents another process from substituting an upload's temporary file. */
    suspend fun open(path: String, write: Boolean = false, create: Boolean = false,
        truncate: Boolean = false, exclusive: Boolean = false): SshFileHandle {
        val flags = buildSet {
            add(SftpOpenFlag.READ)
            if (write) add(SftpOpenFlag.WRITE)
            if (create) add(SftpOpenFlag.CREATE)
            if (truncate) add(SftpOpenFlag.TRUNCATE)
            if (exclusive) add(SftpOpenFlag.EXCLUDE)
        }
        return SshFileHandle(request { delegate.open(path, flags) })
    }
    suspend fun close(handle: SshFileHandle) = request { delegate.close(handle.delegate) }
    suspend fun read(handle: SshFileHandle, offset: Long, count: Int): ByteArray? =
        request { delegate.read(handle.delegate, offset, count.coerceAtMost(32 * 1024)) }
    /** Copies a bounded protocol chunk into the transfer's reusable buffer. */
    suspend fun read(handle: SshFileHandle, offset: Long, buffer: ByteArray, start: Int, count: Int): Int {
        require(start >= 0 && count >= 0 && start <= buffer.size - count)
        val data = read(handle, offset, count) ?: return -1
        if (data.size > count) throw SshFileFailure(5)
        data.copyInto(buffer, start)
        return data.size
    }
    suspend fun write(handle: SshFileHandle, offset: Long, buffer: ByteArray, start: Int, count: Int) =
        write(handle, offset, buffer.copyOfRange(start, start + count))
    suspend fun write(handle: SshFileHandle, offset: Long, bytes: ByteArray) {
        require(bytes.size <= 32 * 1024)
        request { delegate.write(handle.delegate, offset, bytes) }
    }

    /** Batches are consumed incrementally; callers can request one extra entry to detect truncation. */
    suspend fun list(path: String, limit: Int): List<SshFileEntry> {
        require(limit >= 0)
        val handle = request { delegate.opendir(path) }
        try {
            val entries = ArrayList<SshFileEntry>()
            while (entries.size < limit) {
                currentCoroutineContext().ensureActive()
                val batch = request { delegate.readdir(handle) } ?: break
                batch.take(limit - entries.size).forEach { entries.add(SshFileEntry(it.filename, it.attrs.appAttributes())) }
            }
            return entries
        } finally {
            if (!closed.get()) request { delegate.close(handle) }
        }
    }

    private suspend fun <T> request(operation: suspend () -> SftpResult<T>): T {
        if (closed.get()) throw SshFileFailure(6)
        try {
            return when (val result = operation()) {
                is SftpResult.Success -> result.value
                is SftpResult.ServerError -> throw SshFileFailure(result.statusCode.code)
                else -> throw SshFileFailure(4)
            }
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
            released(this)
        }
    }
}

private fun SftpAttributes.appAttributes() = SshFileAttributes(size, uid, gid, permissions, atime, mtime)
private fun SshFileAttributes.libraryAttributes() = SftpAttributes(size, uid, gid, permissions, atime, mtime)
