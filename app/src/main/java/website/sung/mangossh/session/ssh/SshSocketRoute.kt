package website.sung.mangossh.session.ssh

import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.transport.Transport

/** Register the socket before connecting, so cancellation can interrupt proxy negotiation. */
internal fun interface SshSocketRoute {
    fun openSocket(host: String, port: Int, timeout: Int, register: (Socket) -> Unit): Socket
}

/** Exact-count SSH transport over an authenticated application-owned proxy socket. */
internal class SshSocketTransport(private val socket: Socket) : Transport {
    override val isConnected get() = socket.isConnected && !socket.isClosed
    override suspend fun read(count: Int): ByteArray = withContext(Dispatchers.IO) {
        ByteArray(count).also { bytes ->
            var offset = 0
            while (offset < count) {
                val read = socket.getInputStream().read(bytes, offset, count - offset)
                if (read < 0) throw java.io.EOFException("SSH transport ended")
                offset += read
            }
        }
    }
    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) { socket.getOutputStream().write(data) }
    override suspend fun close() { socket.close() }
}
