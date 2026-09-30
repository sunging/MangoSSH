package website.sung.mangossh.session

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteStreamCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = Random(3).nextBytes(10 * BLOCK + 12_345)
    private val requests = ConcurrentLinkedQueue<Long>()

    @After fun tearDown() = scope.cancel()

    @Test fun readsAcrossBlocksMatchTheSourceAndStopAtEndOfFile() = runBlocking {
        val cache = cache(budget(blocks = 32))
        val destination = ByteArray(3 * BLOCK)
        val offset = BLOCK - 100L
        assertEquals(destination.size, cache.read(offset, destination, 0, destination.size))
        assertArrayEquals(source.copyOfRange(offset.toInt(), offset.toInt() + destination.size), destination)

        val tail = ByteArray(BLOCK)
        val start = source.size - 500L
        assertEquals(500, cache.read(start, tail, 0, tail.size))
        assertArrayEquals(source.copyOfRange(start.toInt(), source.size), tail.copyOf(500))
        assertEquals(0, cache.read(source.size.toLong(), tail, 0, tail.size))
    }

    @Test fun aCachedBlockIsServedWithoutAnotherRequest() = runBlocking {
        val cache = cache(budget(blocks = 32))
        val destination = ByteArray(1000)
        cache.read(10, destination, 0, destination.size)
        val firstBlockRequests = requests.count { it < BLOCK }
        cache.read(5000, destination, 0, destination.size)
        assertEquals(firstBlockRequests, requests.count { it < BLOCK })
        assertArrayEquals(source.copyOfRange(5000, 6000), destination)
    }

    @Test fun concurrentReadersOfOneBlockFetchItOnce() = runBlocking {
        val cache = cache(budget(blocks = 32), delayMillis = 5)
        (0 until 8).map { reader ->
            async(Dispatchers.Default) {
                val destination = ByteArray(100)
                cache.read(reader * 100L, destination, 0, destination.size)
                assertArrayEquals(source.copyOfRange(reader * 100, reader * 100 + 100), destination)
            }
        }.awaitAll()
        assertEquals(1, requests.count { it == 0L })
    }

    @Test fun shortRepliesAreReassembledAtTheirTrueOffsets() = runBlocking {
        val cache = cache(budget(blocks = 32), maxReply = 10_000)
        val destination = ByteArray(2 * BLOCK)
        assertEquals(destination.size, cache.read(0, destination, 0, destination.size))
        assertArrayEquals(source.copyOf(destination.size), destination)
    }

    @Test fun aFileShorterThanOfferedFailsInsteadOfServingAHole() = runBlocking {
        val cache = RemoteStreamCache(source.size + BLOCK.toLong(), budget(blocks = 32), scope) { at, count -> slice(at, count) }
        try {
            cache.read(source.size.toLong(), ByteArray(10), 0, 10)
            fail("expected the missing tail to fail")
        } catch (_: SourceChangedException) {
        }
    }

    @Test fun sequentialReadsLoadTheBlocksAhead() = runBlocking {
        val budget = budget(blocks = 32)
        val cache = cache(budget)
        cache.read(0, ByteArray(100), 0, 100)
        awaitQuiet(budget)
        val window = budget.readAheadBlocks
        (1..window).forEach { assertTrue("block $it read ahead", budget.contains(cache, it.toLong())) }
    }

    @Test fun evictionKeepsTheCacheWithinItsLimit() = runBlocking {
        val budget = budget(blocks = 4)
        val cache = cache(budget)
        val destination = ByteArray(BLOCK)
        var offset = 0L
        while (offset < source.size) {
            val read = cache.read(offset, destination, 0, destination.size)
            assertArrayEquals(source.copyOfRange(offset.toInt(), offset.toInt() + read), destination.copyOf(read))
            offset += read
        }
        awaitQuiet(budget)
        assertTrue(budget.usage.value.usedBytes <= budget.usage.value.limitBytes)
    }

    private fun cache(budget: RemoteStreamCacheBudget, delayMillis: Long = 0, maxReply: Int = Int.MAX_VALUE) =
        RemoteStreamCache(source.size.toLong(), budget, scope) { at, count ->
            requests += at
            if (delayMillis > 0) delay(delayMillis)
            slice(at, minOf(count, maxReply))
        }

    private fun budget(blocks: Int) = RemoteStreamCacheBudget(
        configuredBytes = blocks.toLong() * BLOCK,
        ceilingBytes = Long.MAX_VALUE,
        blockBytes = BLOCK,
        allocate = ByteBuffer::allocate,
    )

    private fun slice(at: Long, count: Int): ByteArray? =
        if (at >= source.size) null else source.copyOfRange(at.toInt(), minOf(source.size, at.toInt() + count))

    /** Waits until background read-ahead stops changing the cache. */
    private suspend fun awaitQuiet(budget: RemoteStreamCacheBudget) {
        var last = -1L
        var stable = 0
        while (stable < 5) {
            delay(20)
            val now = budget.usage.value.usedBytes * 31 + requests.size
            if (now == last) stable++ else stable = 0
            last = now
        }
    }

    private companion object {
        const val BLOCK = 64 * 1024
    }
}
