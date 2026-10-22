package website.sung.mangossh.session

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerResourceCollectorTest {
    @Test fun detectionFallsBackToWindowsAndCachesOnlyOnThatConnection() = runBlocking {
        val collector = ServerResourceCollector()
        var calls = 0
        val execute: suspend (String) -> ResourceCommandResult = { command ->
            calls++
            if (command == ServerResourceCommands.windows) ResourceCommandResult(ServerResourcesTest.windows(), 0)
            else ResourceCommandResult("", 1)
        }
        val identity = Any()
        assertEquals(ServerResourcePlatform.WINDOWS, collector.collect(identity, execute).platform)
        assertEquals(2, calls)
        collector.collect(identity, execute)
        assertEquals(3, calls)
        collector.collect(Any(), execute)
        assertEquals(5, calls)
    }

    @Test fun scriptsAreFixedAndWindowsCommandFitsCmdLimit() {
        assertTrue(ServerResourceCommands.windows.length < 8191)
        assertFalse(ServerResourceCommands.windowsScript.contains("ExecutionPolicy"))
        assertTrue(ServerResourceCommands.windowsScript.contains("SilentlyContinue"))
    }

    @Test fun bothStreamsDrainConcurrentlyAndNormalExitClosesChannel() = runBlocking {
        val drainedError = CountDownLatch(1)
        val channel = FakeChannel(object : InputStream() {
            var read = false
            override fun read(): Int {
                check(drainedError.await(2, TimeUnit.SECONDS))
                return if (read) -1 else 'x'.code.also { read = true }
            }
        }, object : InputStream() {
            override fun read(): Int { drainedError.countDown(); return -1 }
        })
        val result = withTimeout(3000) { executeResourceCommand(channel, "fixed") }
        assertEquals("x", result.output)
        assertEquals(0, result.exitCode)
        assertTrue(channel.closed.get())
    }

    @Test fun cancellationClosesChannelWhileStdoutIsBlocked() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val channel = FakeChannel(object : InputStream() {
            override fun read(): Int { started.complete(Unit); released.await(); throw IOException() }
        }, ByteArrayInputStream(byteArrayOf()), onClose = { released.countDown() })
        val job = launch(Dispatchers.IO) { executeResourceCommand(channel, "fixed") }
        withTimeout(3000) { started.await() }
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(channel.closed.get())
    }

    @Test fun cancellationOfBothBlockedStreamsNeverBecomesAnIoFailure() = runBlocking {
        repeat(40) {
            val started = CountDownLatch(2)
            val released = CountDownLatch(1)
            fun stream() = object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    check(released.await(3, TimeUnit.SECONDS))
                    throw IOException("closed synthetic channel")
                }
            }
            val channel = FakeChannel(stream(), stream(), onClose = { released.countDown() })
            val job = launch(Dispatchers.IO) { executeResourceCommand(channel, "fixed") }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(channel.closed.get())
            } finally { channel.close(); job.cancelAndJoin() }
        }
    }

    @Test fun activeQueryStillPropagatesReadFailure() = runBlocking {
        val failure = IOException("synthetic read failure")
        val channel = FakeChannel(object : InputStream() {
            override fun read(): Int = throw failure
        }, ByteArrayInputStream(byteArrayOf()))
        try { executeResourceCommand(channel, "fixed"); fail("Read failure was hidden") }
        catch (actual: IOException) {
            // Coroutine stack recovery may copy exceptions when JVM assertions are enabled.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertTrue(channel.closed.get())
    }

    @Test fun oversizedOutputOrStderrClosesOnlyQueryChannel() = runBlocking {
        for ((output, error) in listOf(ByteArray(128 * 1024 + 1) to byteArrayOf(), byteArrayOf() to ByteArray(16 * 1024 + 1))) {
            val channel = FakeChannel(ByteArrayInputStream(output), ByteArrayInputStream(error))
            try { executeResourceCommand(channel, "fixed"); fail("Oversized peer output accepted") }
            catch (_: IOException) { assertTrue(channel.closed.get()) }
        }
    }

    private class FakeChannel(override val stdout: InputStream, override val stderr: InputStream,
        val onClose: () -> Unit = {}) : ResourceExecChannel {
        val closed = AtomicBoolean()
        override suspend fun execute(command: String) = Unit
        override suspend fun exitCode(): Int = 0
        override fun close() { if (closed.compareAndSet(false, true)) onClose() }
    }
}
