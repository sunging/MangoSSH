package website.sung.mangossh.session

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Bounded request pipelining for SFTP transfers.
 *
 * Waiting for every 32 KiB request before sending the next caps throughput at one
 * chunk per round trip (about 0.3 MiB/s at 100 ms). The SFTP channel matches replies
 * by request id, so several requests can be outstanding at once; these loops keep up
 * to [depth] in flight while exposing only *contiguous* progress to the caller:
 *
 * - reads are delivered strictly in offset order, so a local file is always a valid
 *   prefix and its length is the resume offset;
 * - a write counts as done only once it and every earlier write are acknowledged,
 *   so a pause never reports bytes that might be missing from the middle.
 *
 * In-flight requests are never cancelled individually: cancelling one SFTP request
 * closes its whole channel. A stop request only stops new requests being issued.
 */
internal object SftpPipeline {
    /**
     * Reads from [startOffset] until end of file or until [shouldContinue] turns false,
     * handing each chunk to [deliver] in order, and returns the offset reached.
     *
     * A short reply means the server returned less than asked (a server limit, or the
     * end of a file that is still growing). The requests already issued past it assumed
     * full chunks, so their replies are drained and discarded and reading restarts at
     * the true offset.
     */
    suspend fun read(
        depth: Int,
        chunkBytes: Int,
        startOffset: Long,
        readAt: suspend (offset: Long, count: Int) -> ByteArray?,
        shouldContinue: () -> Boolean,
        deliver: (data: ByteArray, offsetAfter: Long) -> Unit,
    ): Long = coroutineScope {
        require(depth >= 1 && chunkBytes >= 1)
        val inFlight = ArrayDeque<Pair<Long, Deferred<ByteArray?>>>()
        var offset = startOffset
        var nextOffset = startOffset
        var endOfFile = false
        try {
            while (true) {
                while (!endOfFile && inFlight.size < depth && shouldContinue()) {
                    val at = nextOffset
                    inFlight.addLast(at to async { readAt(at, chunkBytes) })
                    nextOffset += chunkBytes
                }
                val (at, reply) = inFlight.removeFirstOrNull() ?: break
                val data = reply.await()
                check(at == offset)
                if (data == null || data.isEmpty()) {
                    endOfFile = true
                    drain(inFlight)
                    break
                }
                if (data.size > chunkBytes) throw java.io.IOException("Oversized SFTP read")
                offset += data.size
                deliver(data, offset)
                if (data.size < chunkBytes) {
                    drain(inFlight)
                    nextOffset = offset
                }
            }
        } finally {
            drain(inFlight)
        }
        offset
    }

    /**
     * Writes chunks from [nextChunk] starting at [startOffset] until it returns null or
     * [shouldContinue] turns false, and returns the contiguously acknowledged offset.
     * [acknowledged] is called with each new contiguous end.
     */
    suspend fun write(
        depth: Int,
        startOffset: Long,
        nextChunk: () -> ByteArray?,
        writeAt: suspend (offset: Long, data: ByteArray) -> Unit,
        shouldContinue: () -> Boolean,
        acknowledged: (offsetAfter: Long) -> Unit,
    ): Long = coroutineScope {
        require(depth >= 1)
        val inFlight = ArrayDeque<Pair<Long, Deferred<Unit>>>()
        var acked = startOffset
        var issued = startOffset
        var inputDone = false
        try {
            while (true) {
                while (!inputDone && inFlight.size < depth && shouldContinue()) {
                    val chunk = nextChunk()
                    if (chunk == null) { inputDone = true; break }
                    if (chunk.isEmpty()) continue
                    val at = issued
                    issued += chunk.size
                    inFlight.addLast(issued to async { writeAt(at, chunk) })
                }
                val (end, reply) = inFlight.removeFirstOrNull() ?: break
                reply.await()
                acked = end
                acknowledged(acked)
            }
        } finally {
            drain(inFlight)
        }
        acked
    }

    /** Waits for replies nobody will use, so no request outlives its loop. */
    private suspend fun <T> drain(inFlight: ArrayDeque<Pair<Long, Deferred<T>>>) {
        while (true) {
            val (_, reply) = inFlight.removeFirstOrNull() ?: return
            runCatching { reply.await() }
        }
    }
}
