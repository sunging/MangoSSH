package website.sung.mangossh.session

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Random-access view of one remote file, served from in-memory blocks.
 *
 * Every descriptor an external app opens on the same streamed file shares this
 * cache, because players commonly read the container index and the media data
 * through separate descriptors. A block is fetched once however many readers
 * wait for it, and only a completely fetched block is ever served.
 *
 * After each read the blocks following it are loaded in the background, up to
 * the budget's read-ahead window, so steady playback rarely waits on a round
 * trip; seeking back into blocks still cached needs no network at all.
 *
 * [source] reads at most one SFTP chunk and returns null at end of file.
 */
internal class RemoteStreamCache(
    val size: Long,
    private val budget: RemoteStreamCacheBudget,
    parent: CoroutineScope,
    private val source: suspend (offset: Long, count: Int) -> ByteArray?,
) {
    /** Cached blocks by index; guarded by the budget's lock. */
    internal val blocks = HashMap<Long, RemoteStreamBlock>()

    /** Set when the stream has no open descriptor, which makes its blocks evicted first. */
    @Volatile internal var active = true

    /** Block of the latest read; the budget protects the window after it. */
    @Volatile internal var cursorBlock = 0L

    /** Set once, when the stream is released; guarded by the budget's lock. */
    internal var retired = false

    private val blockBytes = budget.blockBytes
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()
    private val loading = HashMap<Long, Deferred<Unit>>()
    private var readAhead: Job? = null

    /**
     * Copies up to [count] bytes at [offset] into [destination] and returns how
     * many were copied: fewer only at end of file, zero past it.
     */
    suspend fun read(offset: Long, destination: ByteArray, destinationOffset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && destinationOffset >= 0 && destinationOffset <= destination.size - count)
        if (offset >= size || count == 0) return 0
        val end = minOf(size, offset + count)
        cursorBlock = offset / blockBytes
        var position = offset
        while (position < end) {
            val index = position / blockBytes
            val within = (position - index * blockBytes).toInt()
            val wanted = minOf(end - position, (blockBytes - within).toLong()).toInt()
            val target = destinationOffset + (position - offset).toInt()
            position += withBlock(index) { buffer, length ->
                val available = minOf(wanted, length - within)
                if (available <= 0) throw SourceChangedException()
                buffer.duplicate().apply { position(within) }.get(destination, target, available)
                available
            }
        }
        cursorBlock = (end - 1) / blockBytes
        startReadAhead()
        return (end - offset).toInt()
    }

    /** Stops background loads; the budget reclaims the blocks separately. */
    fun close() {
        scope.cancel()
    }

    private suspend fun <T> withBlock(index: Long, use: (ByteBuffer, Int) -> T): T {
        repeat(MAX_ATTEMPTS) {
            budget.pin(this, index)?.let { block ->
                try {
                    return use(block.buffer, block.length)
                } finally {
                    budget.unpin(block)
                }
            }
            load(index).await()
        }
        // Only a budget far smaller than the readers' demand can evict a block
        // this often between loading and copying it.
        throw InterruptedIOException()
    }

    /** Joins the load already running for [index] or starts one. */
    private fun load(index: Long): Deferred<Unit> = synchronized(lock) {
        loading[index]?.let { return it }
        // Lazy start: the entry must be in the map before the load can remove it.
        val load = scope.async(start = CoroutineStart.LAZY) {
            try {
                fetch(index)
            } finally {
                synchronized(lock) { loading.remove(index) }
            }
        }
        loading[index] = load
        load.start()
        load
    }

    private suspend fun fetch(index: Long) {
        if (budget.contains(this, index)) return
        val start = index * blockBytes
        val length = minOf(blockBytes.toLong(), size - start).toInt()
        val buffer = budget.acquire()
        var handedOver = false
        try {
            var filled = 0
            while (filled < length) {
                // Issue a window of chunk reads together; SFTP answers them by request id.
                val replies = coroutineScope {
                    (filled until length step CHUNK_BYTES).take(PIPELINE_DEPTH).map { relative ->
                        async { relative to source(start + relative, minOf(CHUNK_BYTES, length - relative)) }
                    }.awaitAll()
                }
                for ((relative, data) in replies) {
                    // A short reply leaves a gap; later replies are re-requested from the true offset.
                    if (relative != filled) break
                    val requested = minOf(CHUNK_BYTES, length - relative)
                    // The file ended before the size it was offered with.
                    if (data == null || data.isEmpty()) throw SourceChangedException()
                    if (data.size > requested) throw RemoteFileException(RemoteFileFailure.IO_FAILURE)
                    buffer.duplicate().apply { position(relative) }.put(data)
                    filled += data.size
                    if (data.size < requested) break
                }
            }
            handedOver = true
            budget.register(this, index, buffer, length)
        } finally {
            if (!handedOver) budget.recycle(buffer)
        }
    }

    /**
     * Keeps the window after the latest read loaded, one block at a time so
     * read-ahead never competes with a reader for more than one block's worth
     * of SFTP requests. It follows the cursor, so a seek simply moves it.
     */
    private fun startReadAhead(): Unit = synchronized(lock) {
        if (readAhead?.isActive == true) return
        readAhead = scope.launch {
            while (isActive) {
                val cursor = cursorBlock
                val last = minOf(cursor + budget.readAheadBlocks, (size - 1) / blockBytes)
                val next = (cursor + 1..last).firstOrNull { !budget.contains(this@RemoteStreamCache, it) } ?: break
                try {
                    load(next).await()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // The reader that needs this block will surface the failure.
                    break
                }
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = RemoteFileClient.SFTP_CHUNK_BYTES
        const val PIPELINE_DEPTH = RemoteFileClient.SFTP_PIPELINE_DEPTH
        const val MAX_ATTEMPTS = 4
    }
}
