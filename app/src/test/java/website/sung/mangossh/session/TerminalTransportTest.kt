package website.sung.mangossh.session

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class TerminalTransportTest {
    @Test fun slowFirstWriteDoesNotReorderOrRetainCallerBuffer() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val collected = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Chunk writes required")
            override fun write(bytes: ByteArray) {
                if (collected.size() == 0) { entered.countDown(); assertTrue(resume.await(2, TimeUnit.SECONDS)) }
                collected.write(bytes)
                finished.countDown()
            }
        }
        val writer = TerminalTransport(scope, output, { throw AssertionError(it) }, { fail("Unexpected overflow") })
        try {
            writer.send(byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = byteArrayOf(2, 3)
            writer.send(second); second.fill(9)
            writer.send(byteArrayOf(4))
            resume.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), collected.toByteArray())
        } finally { resume.countDown(); writer.close(); scope.cancel() }
    }

    @Test fun budgetIncludesBlockedWriteAndOverflowIsReportedOnce() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val overflows = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray) { entered.countDown(); resume.await(2, TimeUnit.SECONDS) }
        }
        val writer = TerminalTransport(scope, output, {}, { overflows.incrementAndGet() }, maxBytes = 4)
        try {
            assertTrue(writer.send(byteArrayOf(1, 2, 3)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(writer.send(byteArrayOf(4, 5)))
            assertFalse(writer.send(byteArrayOf(6)))
            assertEquals(1, overflows.get())
        } finally { resume.countDown(); writer.close(); scope.cancel() }
    }
    @Test fun gracefulFinishUsesSameWriterAndDiscardsQueuedUserInput() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val collected = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Chunk writes required")
            override fun write(bytes: ByteArray) {
                if (collected.size() == 0) { entered.countDown(); assertTrue(resume.await(2, TimeUnit.SECONDS)) }
                collected.write(bytes)
                finished.countDown()
            }
        }
        val writer = TerminalTransport(scope, output, { throw AssertionError(it) }, { fail("Unexpected overflow") })
        try {
            assertTrue(writer.send(byteArrayOf(1)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(writer.send(byteArrayOf(2)))
            val quit = byteArrayOf(3)
            assertTrue(writer.finish(quit))
            quit.fill(9)
            assertFalse(writer.send(byteArrayOf(4)))
            assertFalse(writer.finish(byteArrayOf(5)))
            resume.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 3), collected.toByteArray())
        } finally { resume.countDown(); writer.close(); scope.cancel() }
    }

}
