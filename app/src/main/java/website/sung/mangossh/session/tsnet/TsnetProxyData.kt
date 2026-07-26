package website.sung.mangossh.session.tsnet

import com.trilead.ssh2.ProxyData
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent

/**
 * Minimal RFC 1928/1929 client for tsnet's loopback-only authenticated SOCKS5
 * listener. Credential and target bytes are never included in exceptions.
 */
internal class TsnetProxyData(
    private val loopbackAddress: String,
    private val secret: String,
    private val failureReporter: (MangoLogEvent) -> Unit = { MangoLog.warn(it) },
) : ProxyData {
    @Throws(IOException::class)
    override fun openConnection(hostname: String, port: Int, connectTimeout: Int): Socket {
        val socket = Socket()
        var failureEvent = MangoLogEvent.TSNET_PROXY_LOOPBACK_FAILED
        try {
            require(port in 1..65535)
            val proxy = parseLoopbackAddress(loopbackAddress)
            socket.connect(proxy, connectTimeout)
            socket.soTimeout = maxOf(connectTimeout, FIRST_TAILNET_DIAL_TIMEOUT_MILLIS)
            failureEvent = MangoLogEvent.TSNET_PROXY_AUTH_FAILED
            authenticate(socket)
            failureEvent = MangoLogEvent.TSNET_PROXY_CONNECT_FAILED
            requestConnect(socket, hostname, port)
            // ProxyData's timeout only bounds connection establishment. The
            // returned SSH transport must remain blocking; otherwise an idle
            // interactive shell is torn down when the connect timeout elapses.
            socket.soTimeout = 0
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            failureReporter(
                if (failureEvent == MangoLogEvent.TSNET_PROXY_CONNECT_FAILED) {
                    connectFailureEvent(error)
                } else {
                    failureEvent
                },
            )
            throw IOException("Embedded tsnet proxy connection failed")
        }
    }

    private fun authenticate(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(SOCKS_VERSION, 1, USERNAME_PASSWORD_METHOD))
        output.flush()
        val greeting = input.readExactly(2)
        require(greeting[0] == SOCKS_VERSION && greeting[1] == USERNAME_PASSWORD_METHOD)

        val username = SOCKS_USERNAME.encodeToByteArray()
        val password = secret.encodeToByteArray()
        require(username.size in 1..255 && password.size in 1..255)
        try {
            output.write(byteArrayOf(AUTH_VERSION, username.size.toByte()))
            output.write(username)
            output.write(password.size)
            output.write(password)
            output.flush()
        } finally {
            password.fill(0)
        }
        val authReply = input.readExactly(2)
        require(authReply[0] == AUTH_VERSION && authReply[1].toInt() == 0)
    }

    private fun requestConnect(socket: Socket, hostname: String, port: Int) {
        val host = hostname.encodeToByteArray()
        require(host.size in 1..255)
        val output = socket.getOutputStream()
        try {
            output.write(byteArrayOf(SOCKS_VERSION, CONNECT_COMMAND, 0, DOMAIN_ADDRESS, host.size.toByte()))
            output.write(host)
            output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
            output.flush()
        } finally {
            host.fill(0)
        }

        val input = socket.getInputStream()
        val header = input.readExactly(4)
        require(header[0] == SOCKS_VERSION)
        val replyCode = header[1].toInt() and 0xff
        if (replyCode != 0) throw SocksReplyException(replyCode)
        val addressLength = when (header[3]) {
            IPV4_ADDRESS -> 4
            IPV6_ADDRESS -> 16
            DOMAIN_ADDRESS -> input.readExactly(1)[0].toInt() and 0xff
            else -> throw IOException("Unsupported SOCKS5 response")
        }
        input.readExactly(addressLength + 2)
    }

    private fun connectFailureEvent(error: Exception): MangoLogEvent {
        if (error is SocketTimeoutException) {
            return MangoLogEvent.TSNET_PROXY_NETWORK_UNREACHABLE
        }
        val reply = error as? SocksReplyException ?: return MangoLogEvent.TSNET_PROXY_CONNECT_FAILED
        return when (reply.replyCode) {
            2 -> MangoLogEvent.TSNET_PROXY_CONNECT_DENIED
            3, 6 -> MangoLogEvent.TSNET_PROXY_NETWORK_UNREACHABLE
            4 -> MangoLogEvent.TSNET_PROXY_HOST_UNREACHABLE
            5 -> MangoLogEvent.TSNET_PROXY_CONNECTION_REFUSED
            7, 8 -> MangoLogEvent.TSNET_PROXY_PROTOCOL_FAILED
            else -> MangoLogEvent.TSNET_PROXY_CONNECT_FAILED
        }
    }

    private fun parseLoopbackAddress(value: String): InetSocketAddress {
        val separator = value.lastIndexOf(':')
        require(separator > 0 && separator < value.lastIndex)
        val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).toInt()
        require(host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true))
        require(port in 1..65535)
        return InetSocketAddress(host, port)
    }

    private fun java.io.InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }

    private companion object {
        const val SOCKS_USERNAME = "tsnet"
        const val SOCKS_VERSION: Byte = 5
        const val AUTH_VERSION: Byte = 1
        const val USERNAME_PASSWORD_METHOD: Byte = 2
        const val CONNECT_COMMAND: Byte = 1
        const val IPV4_ADDRESS: Byte = 1
        const val DOMAIN_ADDRESS: Byte = 3
        const val IPV6_ADDRESS: Byte = 4
        const val FIRST_TAILNET_DIAL_TIMEOUT_MILLIS = 30_000
    }

    private class SocksReplyException(val replyCode: Int) : IOException()
}
