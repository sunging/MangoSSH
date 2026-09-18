package website.sung.mangossh.session.ssh

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionTest {
    @Test fun lateFactoryCompletionAfterCancellationClosesTransport() = runBlocking {
        val transport = SilentTransport()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = SshConnection("unused.invalid", 22, customTransport = TransportFactory {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                started.complete(Unit)
                release.await()
                transport
            }
        })
        val attempt = async { connection.connect(30_000) { _, _ -> true } }
        withTimeout(2_000) { started.await() }
        attempt.cancelAndJoin()
        release.complete(Unit)
        withTimeout(2_000) { transport.closed.await() }
        assertTrue(attempt.isCancelled)
    }
    @Test fun cancellationDuringBannerWaitClosesOwnedTransport() = runBlocking {
        val transport = SilentTransport()
        val connection = SshConnection("unused.invalid", 22, customTransport = TransportFactory { transport })
        val attempt = async { connection.connect(30_000) { _, _ -> true } }
        withTimeout(2_000) { transport.reading.await() }
        attempt.cancelAndJoin()
        withTimeout(2_000) { transport.closed.await() }
        assertTrue(attempt.isCancelled)
        connection.close()
    }

    @Test fun closedGenerationNeverStartsAnotherTransport() = runBlocking {
        val creates = AtomicInteger()
        val connection = SshConnection("unused.invalid", 22, customTransport = TransportFactory {
            creates.incrementAndGet()
            SilentTransport()
        })
        connection.close()
        val failure = runCatching { connection.connect(1_000) { _, _ -> true } }.exceptionOrNull()
        assertTrue(failure is SshFailure || failure is CancellationException)
        assertEquals(0, creates.get())
    }

    private class SilentTransport : Transport {
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        override val isConnected get() = !closed.isCompleted
        override suspend fun read(count: Int): ByteArray { reading.complete(Unit); awaitCancellation() }
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun close() { closed.complete(Unit) }
    }
}
