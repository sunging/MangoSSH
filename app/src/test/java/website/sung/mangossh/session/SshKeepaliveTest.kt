package website.sung.mangossh.session

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SshKeepaliveTest {
    @Test
    fun sendsAtTheConfiguredIntervalOnlyWhileTheSessionIsActive() = runBlocking {
        var active = true
        var sends = 0
        val waits = mutableListOf<Long>()

        runSshKeepaliveLoop(
            intervalMillis = 30_000,
            isSessionActive = { active },
            sendKeepalive = { sends += 1 },
            onFailure = { throw AssertionError("Unexpected keepalive failure", it) },
            waitForNextKeepalive = { interval ->
                waits += interval
                if (waits.size == 3) active = false
            },
        )

        assertEquals(listOf(30_000L, 30_000L, 30_000L), waits)
        assertEquals(2, sends)
    }

    @Test
    fun reportsTheFirstSendFailureAndStops() = runBlocking {
        val expected = IOException("transport closed")
        var sends = 0
        var reported: Throwable? = null

        runSshKeepaliveLoop(
            intervalMillis = 30_000,
            isSessionActive = { true },
            sendKeepalive = {
                sends += 1
                throw expected
            },
            onFailure = { reported = it },
            waitForNextKeepalive = {},
        )

        assertEquals(1, sends)
        assertSame(expected, reported)
    }

    @Test
    fun ignoresAWriteFailureCausedByAConcurrentSessionClose() = runBlocking {
        var active = true
        var failureReported = false

        runSshKeepaliveLoop(
            intervalMillis = 30_000,
            isSessionActive = { active },
            sendKeepalive = {
                active = false
                throw IOException("transport closed")
            },
            onFailure = { failureReported = true },
            waitForNextKeepalive = {},
        )

        assertFalse(failureReported)
    }

    @Test
    fun containsAFailingFailureReport() = runBlocking {
        // The loop is the body of a root coroutine and [onFailure] tears the
        // session down, so a throw from the report itself would have nothing
        // left to catch it and would end the process.
        runSshKeepaliveLoop(
            intervalMillis = 30_000,
            isSessionActive = { true },
            sendKeepalive = { throw IOException("transport closed") },
            onFailure = { throw IllegalStateException("teardown failed") },
            waitForNextKeepalive = {},
        )
    }

    @Test
    fun preservesCoroutineCancellation() {
        var failureReported = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runSshKeepaliveLoop(
                    intervalMillis = 30_000,
                    isSessionActive = { true },
                    sendKeepalive = { throw CancellationException("cancelled") },
                    onFailure = { failureReported = true },
                    waitForNextKeepalive = {},
                )
            }
        }

        assertFalse(failureReported)
    }
}
