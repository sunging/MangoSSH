package website.sung.mangossh.session

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteStreamRegistryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = Random(5).nextBytes(200_000)
    private val budget = RemoteStreamCacheBudget(64L * BLOCK, Long.MAX_VALUE, BLOCK, ByteBuffer::allocate)
    private val revoked = ConcurrentLinkedQueue<String>()
    private val idle = ConcurrentLinkedQueue<String>()
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()
    private val failNextRead = java.util.concurrent.atomic.AtomicBoolean()

    @After fun tearDown() = scope.cancel()

    @Test fun anUnchangedFileKeepsItsTokenAndAChangedOneGetsANewOne() {
        val registry = registry()
        val first = registry.register(SESSION, identity(), "a.mp4", "video/mp4")
        assertEquals(first, registry.register(SESSION, identity(), "a.mp4", "video/mp4"))
        val changed = registry.register(SESSION, identity(modified = 2L), "a.mp4", "video/mp4")
        assertNotEquals(first.token, changed.token)
        assertNotEquals(first.token, registry.register("other", identity(), "a.mp4", "video/mp4").token)
    }

    @Test fun aDescriptorReadsTheFileThroughOneChannel() = runBlocking {
        val registry = registry()
        val target = registry.register(SESSION, identity(), "a.mp4", "video/mp4")
        val stream = requireNotNull(registry.acquire(target.token))
        val destination = ByteArray(5_000)
        assertEquals(destination.size, stream.read(150_000, destination, destination.size))
        assertArrayEquals(source.copyOfRange(150_000, 155_000), destination)
        assertEquals(1, opened.get())
        registry.release(stream)
    }

    @Test fun aBrokenChannelIsReplacedOnce() = runBlocking {
        val registry = registry()
        val stream = requireNotNull(registry.acquire(registry.register(SESSION, identity(), "a", "video/mp4").token))
        failNextRead.set(true)
        val destination = ByteArray(100)
        assertEquals(100, stream.read(0, destination, 100))
        assertEquals(2, opened.get())
        registry.release(stream)
    }

    @Test fun anOpenOrRecentlyClosedStreamKeepsTheSessionBusy() = runBlocking {
        val registry = registry(graceMillis = 100)
        val target = registry.register(SESSION, identity(), "a", "video/mp4")
        assertFalse(registry.isBusy(SESSION))
        val stream = requireNotNull(registry.acquire(target.token))
        stream.read(0, ByteArray(10), 10)
        assertTrue(registry.isBusy(SESSION))
        registry.release(stream)
        assertTrue("busy during the grace period", registry.isBusy(SESSION))
        withTimeout(5_000) { while (idle.isEmpty()) delay(10) }
        assertFalse(registry.isBusy(SESSION))
        assertEquals(listOf(SESSION), idle.toList())
        withTimeout(5_000) { while (closed.get() < opened.get()) delay(10) }
        assertEquals(0L, budget.usage.value.usedBytes)
    }

    @Test fun reopeningWithinTheGracePeriodKeepsTheStream() = runBlocking {
        val registry = registry(graceMillis = 200)
        val target = registry.register(SESSION, identity(), "a", "video/mp4")
        registry.release(requireNotNull(registry.acquire(target.token)))
        val again = requireNotNull(registry.acquire(target.token))
        delay(400)
        assertTrue(idle.isEmpty())
        assertEquals(10, again.read(0, ByteArray(10), 10))
        registry.release(again)
    }

    @Test fun anEndedSessionWithdrawsItsStreams() = runBlocking {
        val registry = registry()
        val target = registry.register(SESSION, identity(), "a", "video/mp4")
        val other = registry.register("other", identity(), "b", "video/mp4")
        val stream = requireNotNull(registry.acquire(target.token))
        registry.onSessionEnded(SESSION)
        assertNull(registry.find(target.token))
        assertNull(registry.acquire(target.token))
        assertEquals(listOf(target.token), revoked.toList())
        assertEquals(other, registry.find(other.token))
        try {
            stream.read(0, ByteArray(10), 10)
            fail("a withdrawn stream must not be readable")
        } catch (_: java.io.InterruptedIOException) {
        }
        registry.release(stream)
        assertFalse(registry.isBusy(SESSION))
    }

    @Test fun theOldestIdleTokenMakesRoomForANewOne() {
        val registry = registry(maxTokens = 2)
        val first = registry.register(SESSION, identity(path = "/1"), "1", "video/mp4")
        val second = registry.register(SESSION, identity(path = "/2"), "2", "video/mp4")
        val open = requireNotNull(registry.acquire(first.token))
        registry.register(SESSION, identity(path = "/3"), "3", "video/mp4")
        assertEquals(listOf(second.token), revoked.toList())
        assertEquals(first, registry.find(first.token))
        registry.release(open)
    }

    private fun registry(graceMillis: Long = 30_000, maxTokens: Int = 16) = RemoteStreamRegistry(
        budget = budget,
        scope = scope,
        openReader = {
            opened.incrementAndGet()
            object : RemoteChunkReader {
                override suspend fun readAt(offset: Long, count: Int): ByteArray? {
                    if (failNextRead.compareAndSet(true, false)) {
                        throw RemoteFileException(RemoteFileFailure.IO_FAILURE)
                    }
                    return if (offset >= source.size) null
                    else source.copyOfRange(offset.toInt(), minOf(source.size, offset.toInt() + count))
                }

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        },
        onRevoked = { revoked += it.token },
        onIdle = { idle += it },
        graceMillis = graceMillis,
        maxTokens = maxTokens,
    )

    private fun identity(path: String = "/video.mp4", modified: Long = 1L) =
        SourceIdentity(path, source.size.toLong(), modified)

    private companion object {
        const val SESSION = "session"
        const val BLOCK = 64 * 1024
    }
}
