package website.sung.mangossh.session


import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns cancellable channels and the no-progress deadline for one execution.
 * A timeout aborts only this operation's channels; it never closes a shared connection.
 */
internal class BlockingOperation(private val idleMillis: Long = 30_000L) : TransferControl, Closeable {
    private val lock = Any()
    private val channels = mutableSetOf<Closeable>()
    private val stopped = AtomicBoolean(false)
    @Volatile private var lastProgress = System.nanoTime()
    @Volatile private var timedOut = false
    @Volatile private var awaitingDecision = false
    private val localResources = mutableSetOf<Closeable>()
    private val closing = mutableMapOf<Closeable, java.util.concurrent.Future<*>>()
    private val timer = scheduler.scheduleWithFixedDelay({
        if (!awaitingDecision && !stopped.get() && System.nanoTime() - lastProgress >= TimeUnit.MILLISECONDS.toNanos(idleMillis)) {
            timedOut = true
            close()
        }
    }, idleMillis, minOf(idleMillis, 1_000L), TimeUnit.MILLISECONDS)

    override fun shouldContinue(): Boolean {
        if (timedOut) throw SocketTimeoutException("Operation timed out")
        return !stopped.get()
    }
    override fun progressed() { lastProgress = System.nanoTime() }
    fun awaitDecision(waiting: Boolean) { lastProgress = System.nanoTime(); awaitingDecision = waiting }
    override fun ownLocal(resource: Closeable) {
        val accepted = synchronized(lock) { if (stopped.get()) false else { localResources += resource; true } }
        if (!accepted) { resource.close(); throw java.io.InterruptedIOException() }
    }
    override fun releaseLocal(resource: Closeable) {
        val future = synchronized(lock) { closing.getOrPut(resource) { closers.submit { resource.close() } } }
        try {
            while (true) {
                try { future.get(100, TimeUnit.MILLISECONDS); break }
                catch (_: java.util.concurrent.TimeoutException) {
                    if (!shouldContinue()) throw java.io.InterruptedIOException()
                } catch (error: java.util.concurrent.ExecutionException) {
                    throw (error.cause ?: error)
                }
            }
            if (!shouldContinue()) throw java.io.InterruptedIOException()
        } finally { synchronized(lock) { localResources.remove(resource); closing.remove(resource) } }
    }
    override fun own(session: Closeable): Boolean {
        val accepted = synchronized(lock) { if (stopped.get()) false else { channels += session; true } }
        if (!accepted) session.close()
        return accepted
    }
    override fun release(session: Closeable) { synchronized(lock) { channels.remove(session) }; session.close() }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        timer.cancel(false)
        val owned = synchronized(lock) { channels.toList().also { channels.clear() } }
        owned.forEach { runCatching { it.close() } }
        val local = synchronized(lock) { localResources.filterNot { it in closing }.also { localResources.clear() } }
        local.forEach { resource -> closers.execute { runCatching { resource.close() } } }
    }

    private companion object {
        val closers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "MangoSSH-CancelLocalIO").apply { isDaemon = true }
        }
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MangoSSH-OperationDeadline").apply { isDaemon = true }
        }
    }
}
