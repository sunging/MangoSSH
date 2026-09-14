package website.sung.mangossh.session

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ProxyData
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** SSH direct-tcpip streams are the next hop's socket; no local listening port or proxy command is used. */
internal class JumpProxyData(private val previous: Connection) : ProxyData {
    override fun openConnection(hostname: String, port: Int, connectTimeout: Int): Socket {
        val channel = previous.createLocalStreamForwarder(hostname, port)
        return object : Socket() {
            private val ended = AtomicBoolean(false)
            override fun getInputStream() = channel.inputStream
            override fun getOutputStream() = channel.outputStream
            override fun isConnected() = !ended.get()
            override fun isClosed() = ended.get()
            override fun getRemoteSocketAddress() = InetSocketAddress.createUnresolved(hostname, port)
            override fun getLocalSocketAddress() = InetSocketAddress(0)
            override fun close() { if (ended.compareAndSet(false, true)) channel.abort() }
        }
    }
}
