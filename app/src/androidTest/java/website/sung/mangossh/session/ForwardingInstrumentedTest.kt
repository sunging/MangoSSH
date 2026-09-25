package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/** Loopback-only end-to-end checks for all three forwarding types on the disposable runner. */
class ForwardingInstrumentedTest {
    @Test fun localDynamicAndRemoteForwardingKeepTheSharedConnectionUsable() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            connection.createLocalPortForwarder(InetSocketAddress("127.0.0.1", 0), "127.0.0.1", port).use { forward ->
                Socket("127.0.0.1", forward.boundPort).use { socket ->
                    socket.soTimeout = 5_000
                    assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("SSH-"))
                }
            }
            connection.createDynamicPortForwarder(InetSocketAddress("127.0.0.1", 0)).use { forward ->
                Socket("127.0.0.1", forward.boundPort).use { socket ->
                    socket.soTimeout = 5_000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0)); output.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                    output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())); output.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                    input.readUnsignedByte()
                    val addressLength = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Invalid SOCKS response") }
                    input.readFully(ByteArray(addressLength + 2))
                    assertTrue(input.bufferedReader().readLine().startsWith("SSH-"))
                }
            }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 5_000
                val forward = connection.createRemotePortForwarder("127.0.0.1", 22354, "127.0.0.1", listener.localPort)
                // A second listener on the same port but another address; stopping it must not touch the first.
                connection.createRemotePortForwarder("127.0.0.2", 22354, "127.0.0.1", listener.localPort).close()
                val echo = async(Dispatchers.IO) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val value = socket.getInputStream().read()
                        socket.getOutputStream().write(value)
                    }
                }
                try {
                    Socket("127.0.0.1", 22354).use { socket ->
                        socket.soTimeout = 5_000
                        socket.getOutputStream().write(73)
                        assertEquals(73, socket.getInputStream().read())
                    }
                    withTimeout(5_000) { echo.await() }
                } finally { forward.close(); echo.cancel() }
            }
            connection.keepalive()
            assertTrue(RemoteFileClient().resolveHome(connection).startsWith("/"))
        } finally { connection.close() }
    }
}
