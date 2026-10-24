package website.sung.mangossh.session

import java.io.Closeable
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BlockingOperationTest {
    @Test fun stoppedOpenReturnsPromptlyAndClosesALateDescriptor() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val operation = BlockingOperation(10_000)
        try {
            val result = executor.submit<Boolean> {
                try {
                    operation.localIo {
                        started.countDown()
                        // Model a provider that ignores cancellation until it finishes opening.
                        while (release.count > 0) {
                            try { release.await() } catch (_: InterruptedException) { }
                        }
                        Closeable { closed.countDown() }.also(operation::ownLocal)
                    }
                    false
                } catch (_: InterruptedIOException) { true }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            operation.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); operation.close(); executor.shutdownNow() }
    }

    @Test fun idleDeadlineEndsABlockedRead() {
        val blocked = CountDownLatch(1)
        BlockingOperation(150).use { operation ->
            try {
                operation.localIo { blocked.await() }
                fail("An idle provider read must time out")
            } catch (_: SocketTimeoutException) { }
            finally { blocked.countDown() }
        }
    }

    @Test fun waitingForADecisionSuspendsTheIdleDeadline() {
        BlockingOperation(150).use { operation ->
            operation.awaitDecision(true)
            Thread.sleep(400)
            assertTrue(operation.shouldContinue())
            operation.awaitDecision(false)
            assertEquals(7, operation.localIo { 7 })
        }
    }

    @Test fun providerErrorsArePreserved() {
        BlockingOperation().use { operation ->
            val expected = java.io.IOException("synthetic provider failure")
            try {
                operation.localIo<Unit> { throw expected }
                fail("Provider failure was swallowed")
            } catch (actual: java.io.IOException) { assertSame(expected, actual) }
        }
    }
}
