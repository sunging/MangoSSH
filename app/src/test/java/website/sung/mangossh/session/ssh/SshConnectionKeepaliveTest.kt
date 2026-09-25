package website.sung.mangossh.session.ssh

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.PingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionKeepaliveTest {
    @Test fun aWaiterThatStopsWaitingLeavesTheSharedConnectionOpen() = runBlocking {
        val connection = SshConnection("unused.invalid", 22)
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<PingResult>()
        connection.pinger = { started.complete(Unit); reply.await() }
        try {
            val waiter = async { connection.keepalive() }
            withTimeout(10_000) { started.await() }
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled)

            // The abandoned probe keeps running for the connection and still succeeds.
            reply.complete(PingResult.Success(1))
            connection.pinger = { PingResult.Success(1) }
            withTimeout(10_000) { connection.keepalive() }
        } finally { connection.close() }
    }

    @Test fun anUnansweredProbeClosesTheConnection() = runBlocking {
        val connection = SshConnection("unused.invalid", 22)
        connection.pinger = { PingResult.Failure(java.io.IOException()) }
        val failure = runCatching { withTimeout(10_000) { connection.keepalive() } }.exceptionOrNull()
        assertEquals(SshFailure.Category.KEEPALIVE, (failure as SshFailure).category)

        connection.pinger = { PingResult.Success(1) }
        val afterwards = runCatching { connection.keepalive() }.exceptionOrNull()
        assertEquals(SshFailure.Category.CLOSED, (afterwards as SshFailure).category)
    }

    @Test fun concurrentWaitersShareOneProbe() = runBlocking {
        val connection = SshConnection("unused.invalid", 22)
        val pings = AtomicInteger()
        val reply = CompletableDeferred<PingResult>()
        connection.pinger = { pings.incrementAndGet(); reply.await() }
        try {
            val first = async { connection.keepalive() }
            val second = async { connection.keepalive() }
            withTimeout(10_000) { while (pings.get() == 0) kotlinx.coroutines.yield() }
            reply.complete(PingResult.Success(1))
            withTimeout(10_000) { first.await(); second.await() }
            assertEquals(1, pings.get())
        } finally { connection.close() }
    }
}
