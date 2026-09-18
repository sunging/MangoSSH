package website.sung.mangossh.session.ssh

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SshSession

/** Owns exactly one channel. Consumers drain both output channels before awaiting normal exit. */
internal class SshChannel internal constructor(
    private val delegate: SshSession,
    private val released: (SshChannel) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val stdout: java.io.InputStream = input(delegate.stdout)
    val stderr: java.io.InputStream = input(delegate.stderr)
    val stdin: java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            blocking { delegate.write(bytes.copyOfRange(offset, offset + length)) }
        }
    }
    private fun <T> blocking(action: suspend () -> T): T = runBlocking {
        val pending = scope.async { action() }
        try { pending.await() } finally { pending.cancel() }
    }
    /** Stream bridges are consumed exclusively by existing bounded I/O workers. */
    private fun input(source: ReceiveChannel<ByteArray>): java.io.InputStream = object : java.io.InputStream() {
        private var buffer = ByteArray(0)
        private var offset = 0
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            if (length == 0) return 0
            while (offset == buffer.size) {
                buffer = blocking {
                    val received = source.receiveCatching()
                    if (received.exceptionOrNull() != null) throw SshFailure(SshFailure.Category.CHANNEL)
                    received.getOrNull()
                } ?: return -1
                offset = 0
            }
            val count = minOf(length, buffer.size - offset)
            buffer.copyInto(bytes, start, offset, offset + count)
            offset += count
            return count
        }
        override fun close() = this@SshChannel.close()
    }
    suspend fun pty(type: String, columns: Int, rows: Int): Boolean = accepted(delegate.requestPty(type, columns, rows))
    suspend fun resize(columns: Int, rows: Int): Boolean = delegate.resizeTerminal(columns, rows, 0, 0)
    suspend fun shell(): Boolean = accepted(delegate.requestShell())
    suspend fun requestAgentForwarding(): Boolean = delegate.requestAgentForwarding()
    suspend fun execute(command: String): Boolean = accepted(delegate.requestExec(command))
    private fun accepted(result: Boolean): Boolean = result.also { if (!it) throw SshFailure(SshFailure.Category.CHANNEL) }
    suspend fun write(bytes: ByteArray) = delegate.write(bytes)
    suspend fun eof() = delegate.sendEof()
    suspend fun exitCode(): Int? = (delegate.exitInfo.await() as? SessionExit.Status)?.code?.toInt()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            delegate.close()
            released(this)
        }
    }
}
