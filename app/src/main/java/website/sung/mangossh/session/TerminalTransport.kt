package website.sung.mangossh.session

import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One FIFO writer; admission is synchronous and the budget includes the in-flight write. */
internal class TerminalTransport(
    scope: CoroutineScope,
    private val output: OutputStream,
    private val onFailure: (Throwable) -> Unit,
    private val onOverflow: () -> Unit,
    private val maxBytes: Int = 1024 * 1024,
) {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var outstandingBytes = 0
    private val writer: Job = scope.launch {
        try {
            for (ignored in wake) {
                while (true) {
                    val bytes = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                    try { output.write(bytes); output.flush() }
                    finally {
                        synchronized(lock) { outstandingBytes -= bytes.size }
                        bytes.fill(0)
                    }
                }
            }
        } catch (failure: Exception) {
            if (synchronized(lock) { !closed }) onFailure(failure)
        } finally { discardQueued() }
    }

    /** Copies before returning so later caller mutations cannot change accepted input. */
    fun send(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var overflow = false
        val accepted = synchronized(lock) {
            when {
                closed -> false
                bytes.size > maxBytes - outstandingBytes -> { closed = true; overflow = true; false }
                else -> {
                    queue.addLast(bytes.copyOf())
                    outstandingBytes += bytes.size
                    wake.trySend(Unit)
                    true
                }
            }
        }
        if (overflow) { close(); onOverflow() }
        return accepted
    }

    /** Stops admission immediately. The lifecycle owner closes the underlying blocking stream. */
    fun close() {
        synchronized(lock) { closed = true }
        discardQueued()
        wake.close()
        writer.cancel()
    }

    /** Queues a bounded shutdown sequence behind the in-flight write, discarding unsent user input. */
    fun finish(sequence: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        closed = true
        discardQueued()
        val accepted = sequence.size <= maxBytes - outstandingBytes
        if (accepted) {
            queue.addLast(sequence.copyOf())
            outstandingBytes += sequence.size
            wake.trySend(Unit)
        }
        wake.close()
        accepted
    }

    private fun discardQueued() = synchronized(lock) {
        queue.forEach { outstandingBytes -= it.size; it.fill(0) }
        queue.clear()
    }
}

/** Coalesces size requests while preserving the most recent dimensions across slow resizes. */
internal class TerminalResizeQueue(scope: CoroutineScope, private val resize: (Int, Int) -> Unit) {
    private val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    private val job = scope.launch { for ((columns, rows) in sizes) runCatching { resize(columns, rows) } }
    fun offer(columns: Int, rows: Int) { if (columns > 0 && rows > 0) sizes.trySend(columns to rows) }
    fun close() { sizes.close(); job.cancel() }
}
