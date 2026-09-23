package website.sung.mangossh.session

import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingConnectionInstrumentedTest {
    @Test fun closingLifecycleAbortsHandshakeBeforeProtocolTimeout() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val connection = SshConnection("127.0.0.1", server.localPort)
        val lifecycle = SessionLifecycle()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = CountDownLatch(1)
        adoptPendingConnection(lifecycle, connection, cleanup)
        val worker = Thread {
            try { runBlocking { connection.connect(30_000) { _, _ -> true } } }
            catch (_: Exception) { } finally { finished.countDown() }
        }
        try {
            server.soTimeout = 5000
            worker.start()
            server.accept().use {
                lifecycle.close()!!.forEach { release -> release() }
                assertTrue("Owned socket must abort before handshake timeout", finished.await(2, TimeUnit.SECONDS))
            }
        } finally { connection.close(); server.close(); worker.join(2000); cleanup.coroutineContext[Job]!!.children.toList().joinAll(); cleanup.cancel() }
    }
}
