package website.sung.mangossh.session

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AsyncForwardStopTest {
    @Test fun unresponsiveCloseDoesNotBlockCallerAndReportsFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val caller = Thread.currentThread()
        val result = CompletableDeferred<Throwable?>()
        val job = closeForwardInBackground(this, {
            assertNotSame(caller, Thread.currentThread())
            entered.countDown(); gate.await(3, TimeUnit.SECONDS)
            throw java.net.SocketTimeoutException()
        }, { result.complete(it) })
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(result.isCompleted)
            gate.countDown()
            assertTrue(withTimeout(2000) { result.await() } is java.net.SocketTimeoutException)
        } finally { gate.countDown(); job.join() }
    }
}
