package website.sung.mangossh.session.tsnet

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import website.sung.mangossh.core.MangoLogEvent

class TsnetProxyDataTest {
    @Test
    fun authenticatesAndRequestsTheUnmodifiedDestination() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val request = ArrayBlockingQueue<Pair<String, Int>>(1)
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                assertEquals(listOf(5, 1, 2), input.readBytes(3))
                output.write(byteArrayOf(5, 2))
                val auth = input.readBytes(2)
                val username = input.readBytes(auth[1])
                val passwordLength = input.read()
                val password = input.readBytes(passwordLength)
                assertEquals("tsnet", username.decodeToString())
                assertEquals("random-secret", password.decodeToString())
                output.write(byteArrayOf(1, 0))
                val connect = input.readBytes(5)
                val host = input.readBytes(connect[4]).decodeToString()
                val portBytes = input.readBytes(2)
                request.put(host to ((portBytes[0] shl 8) or portBytes[1]))
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34))
                output.flush()
            }
        }

        val proxiedSocket = TsnetProxyData("127.0.0.1:${server.localPort}", "random-secret")
            .openConnection("lab.example.ts.net", 22, 3_000)
        assertEquals(0, proxiedSocket.soTimeout)
        proxiedSocket.close()
        assertEquals("lab.example.ts.net" to 22, request.take())
        worker.join(3_000)
        server.close()
        assertFalse(worker.isAlive)
    }

    @Test
    fun allowsTheFirstTailnetDialToOutliveTheLibraryTimeout() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                input.readBytes(3)
                output.write(byteArrayOf(5, 2))
                val auth = input.readBytes(2)
                input.readBytes(auth[1])
                input.readBytes(input.read())
                output.write(byteArrayOf(1, 0))
                val connect = input.readBytes(5)
                input.readBytes(connect[4])
                input.readBytes(2)
                Thread.sleep(250)
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34))
                output.flush()
            }
        }

        TsnetProxyData("127.0.0.1:${server.localPort}", "runtime-only")
            .openConnection("test-peer", 22, 50)
            .close()

        worker.join(3_000)
        server.close()
        assertFalse(worker.isAlive)
    }

    @Test
    fun exposesOnlyAFixedFailureMessage() {
        var failureEvent: MangoLogEvent? = null
        val error = assertThrows(java.io.IOException::class.java) {
            TsnetProxyData("public.example:1234", "do-not-leak") { failureEvent = it }
                .openConnection("private-target", 22, 100)
        }
        assertEquals(MangoLogEvent.TSNET_PROXY_LOOPBACK_FAILED, failureEvent)
        assertEquals("Embedded tsnet proxy connection failed", error.message)
        assertFalse(error.toString().contains("do-not-leak"))
        assertFalse(error.toString().contains("private-target"))
    }

    @Test
    fun classifiesSocksReplyWithoutExposingTheDestination() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                input.readBytes(3)
                output.write(byteArrayOf(5, 2))
                val auth = input.readBytes(2)
                input.readBytes(auth[1])
                input.readBytes(input.read())
                output.write(byteArrayOf(1, 0))
                val connect = input.readBytes(5)
                input.readBytes(connect[4])
                input.readBytes(2)
                output.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
            }
        }
        var failureEvent: MangoLogEvent? = null

        val error = assertThrows(java.io.IOException::class.java) {
            TsnetProxyData("127.0.0.1:${server.localPort}", "runtime-only") {
                failureEvent = it
            }.openConnection("private-target", 22, 3_000)
        }

        assertEquals(MangoLogEvent.TSNET_PROXY_HOST_UNREACHABLE, failureEvent)
        assertEquals("Embedded tsnet proxy connection failed", error.message)
        assertFalse(error.toString().contains("private-target"))
        worker.join(3_000)
        server.close()
        assertFalse(worker.isAlive)
    }

    private fun java.io.InputStream.readBytes(length: Int): List<Int> =
        List(length) {
            val value = read()
            check(value >= 0)
            value
        }

    private fun List<Int>.decodeToString(): String = map(Int::toByte).toByteArray().decodeToString()
}
