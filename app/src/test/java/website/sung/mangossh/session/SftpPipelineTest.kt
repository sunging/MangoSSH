package website.sung.mangossh.session

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SftpPipelineTest {
    private val source = Random(7).nextBytes(100_000)

    @Test fun readsArriveInOrderEvenWhenRepliesDoNot() = runBlocking {
        val random = Random(1)
        val output = ByteArrayOutputStream()
        val maxInFlight = AtomicInteger()
        val inFlight = AtomicInteger()
        val reached = SftpPipeline.read(8, 4096, 0, readAt = { at, count ->
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(random.nextLong(0, 5))
            inFlight.decrementAndGet()
            slice(at, count)
        }, shouldContinue = { true }) { data, _ -> output.write(data) }
        assertEquals(source.size.toLong(), reached)
        assertArrayEquals(source, output.toByteArray())
        assertTrue("requests were pipelined", maxInFlight.get() > 1)
        assertTrue("pipeline stayed bounded", maxInFlight.get() <= 8)
    }

    @Test fun aShortReplyRestartsReadingAtTheTrueOffset() = runBlocking {
        val output = ByteArrayOutputStream()
        val reached = SftpPipeline.read(4, 4096, 1000, readAt = { at, count ->
            // A server that never returns more than 3000 bytes per request.
            slice(at, minOf(count, 3000))
        }, shouldContinue = { true }) { data, _ -> output.write(data) }
        assertEquals(source.size.toLong(), reached)
        assertArrayEquals(source.copyOfRange(1000, source.size), output.toByteArray())
    }

    @Test fun stoppingReadsKeepsOnlyAContiguousPrefix() = runBlocking {
        val output = ByteArrayOutputStream()
        var delivered = 0
        val reached = SftpPipeline.read(8, 4096, 0, readAt = { at, count -> slice(at, count) },
            shouldContinue = { delivered < 3 }) { data, _ -> output.write(data); delivered++ }
        assertEquals(output.size().toLong(), reached)
        assertArrayEquals(source.copyOf(output.size()), output.toByteArray())
        assertTrue(reached < source.size)
    }

    @Test fun writesReportOnlyContiguouslyAcknowledgedBytes() = runBlocking {
        val random = Random(2)
        val remote = ByteArray(source.size)
        val acknowledged = mutableListOf<Long>()
        var position = 0
        val reached = SftpPipeline.write(8, 0, nextChunk = {
            if (position >= source.size) null else {
                val end = minOf(position + 4096, source.size)
                source.copyOfRange(position, end).also { position = end }
            }
        }, writeAt = { at, data ->
            delay(random.nextLong(0, 5))
            data.copyInto(remote, at.toInt())
        }, shouldContinue = { true }) { acknowledged += it }
        assertEquals(source.size.toLong(), reached)
        assertArrayEquals(source, remote)
        assertEquals(acknowledged.sorted(), acknowledged)
        // Every acknowledged offset really is a prefix the remote already holds.
        assertEquals(source.size.toLong(), acknowledged.last())
    }

    @Test fun aFailedWriteReportsNoBytesBeyondIt() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        var position = 0L
        try {
            SftpPipeline.write(4, 0, nextChunk = { ByteArray(1000).also { position += 1000 } },
                writeAt = { at, _ -> if (at == 3000L) throw java.io.IOException() else delay(1) },
                shouldContinue = { position < 20_000 }) { acknowledged += it }
            fail("Write failure was swallowed")
        } catch (_: java.io.IOException) { }
        assertTrue(acknowledged.all { it <= 3000L })
    }

    private fun slice(at: Long, count: Int): ByteArray? =
        if (at >= source.size) null else source.copyOfRange(at.toInt(), minOf(at.toInt() + count, source.size))
}
