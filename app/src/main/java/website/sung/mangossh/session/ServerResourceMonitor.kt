package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One sequential sampler per visible session. A watch generation gates every publication,
 * so a late result cannot repopulate a closed session or replace a newer watch's data.
 */
internal class ServerResourceMonitor(
    private val scope: CoroutineScope,
    private val collect: suspend (sessionId: String, manual: Boolean) -> ServerResourceSample,
    private val intervalMillis: Long = 5_000,
    private val timeoutMillis: Long = 15_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Watch {
        val requests = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job
        var refreshing = false
    }
    private class SamplingGate {
        val mutex = Mutex()
        var users = 0
    }
    private val lock = Any()
    private val watches = mutableMapOf<String, Watch>()
    private val gates = mutableMapOf<String, SamplingGate>()
    private val mutableStates = MutableStateFlow<Map<String, ServerResourceState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    /** Opening a panel starts sampling; automatic queries never initiate authentication. */
    fun start(sessionId: String) = synchronized(lock) {
        if (watches.containsKey(sessionId)) return@synchronized
        val watch = Watch()
        // A replaced watch may still be closing blocking I/O. Keep its gate alive until
        // every owner finishes so rapid close/open cycles cannot overlap collectors.
        val gate = gates.getOrPut(sessionId) { SamplingGate() }
        gate.users++
        watch.job = scope.launch(start = CoroutineStart.LAZY) {
            gate.mutex.withLock { sample(sessionId, watch) }
        }
        watch.job.invokeOnCompletion {
            synchronized(lock) { if (--gate.users == 0) gates.remove(sessionId) }
        }
        watches[sessionId] = watch
        watch.job.start()
    }

    /** Requests during collection merge into the current request instead of queuing more work. */
    fun refresh(sessionId: String) = synchronized(lock) {
        watches[sessionId]?.takeUnless { it.refreshing }?.requests?.trySend(Unit)
        Unit
    }

    /** Withdraw ownership before cancellation; cancellation is not a query failure. */
    fun stop(sessionId: String, remove: Boolean = false) {
        val watch = synchronized(lock) {
            watches.remove(sessionId).also {
                mutableStates.update { states ->
                    if (remove) states - sessionId else states[sessionId]?.let { state ->
                        states + (sessionId to state.copy(isRefreshing = false))
                    } ?: states
                }
            }
        }
        watch?.job?.cancel()
        watch?.requests?.close()
    }

    private suspend fun sample(sessionId: String, watch: Watch) {
        var previous: ServerResourceSample? = null
        var manual = false
        try {
            while (watch.job.isActive) {
                val startedAt = System.nanoTime()
                publish(sessionId, watch) { watch.refreshing = true; it.copy(isRefreshing = true) }
                var paused = false
                try {
                    val sample = withTimeout(timeoutMillis) { collect(sessionId, manual) }
                    val snapshot = sample.snapshot(previous, now())
                    publish(sessionId, watch) { ServerResourceState(snapshot, isRefreshing = true) }
                    previous = sample
                } catch (_: TimeoutCancellationException) {
                    previous = null
                    publish(sessionId, watch) { it.copy(failure = ServerResourceFailure.TIMEOUT) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    previous = null
                    val failure = (error as? ResourceQueryException)?.failure ?: ServerResourceFailure.QUERY
                    paused = failure == ServerResourceFailure.CONNECTION || failure == ServerResourceFailure.UNSUPPORTED
                    publish(sessionId, watch) { it.copy(failure = failure) }
                } finally {
                    publish(sessionId, watch) { watch.refreshing = false; it.copy(isRefreshing = false) }
                }
                manual = if (paused) { watch.requests.receive(); true }
                else {
                    val remaining = (intervalMillis - (System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
                    withTimeoutOrNull(remaining) { watch.requests.receive(); true } ?: false
                }
            }
        } finally {
            publish(sessionId, watch) { it.copy(isRefreshing = false) }
        }
    }

    private fun publish(sessionId: String, watch: Watch, transform: (ServerResourceState) -> ServerResourceState) = synchronized(lock) {
        if (watches[sessionId] === watch) mutableStates.update { it + (sessionId to transform(it[sessionId] ?: ServerResourceState())) }
    }
}
