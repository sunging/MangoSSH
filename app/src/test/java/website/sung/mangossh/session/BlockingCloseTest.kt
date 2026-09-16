package website.sung.mangossh.session

import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BlockingCloseTest {
    @Test fun providerCloseCannotHideTheOperationDeadline() {
        val gate = CountDownLatch(1)
        val operation = BlockingOperation(50)
        val resource = Closeable { gate.await(3, TimeUnit.SECONDS) }
        try {
            operation.ownLocal(resource)
            val started = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) { operation.releaseLocal(resource) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
        } finally { gate.countDown(); operation.close() }
    }
}
