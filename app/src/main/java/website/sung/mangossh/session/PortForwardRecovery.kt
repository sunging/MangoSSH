package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import website.sung.mangossh.data.vault.PortForwardRule
import kotlin.random.Random

/**
 * Process-local forwarding intent, independent of an SSH connection generation.
 * Removing an intent invalidates publication before cancelling its worker; a late
 * connect or listener callback cannot resurrect a stopped or deleted rule.
 */
internal class PortForwardRecovery(
    scope: CoroutineScope,
    network: StateFlow<NetworkStatus>,
    open: suspend (Intent) -> Unit,
    publish: (Intent, PortForwardRuntimePhase, Throwable?) -> Unit,
    retryable: (Throwable) -> Boolean,
    waitRetry: suspend (Long) -> Unit = { delay(it) },
    jitter: (Long) -> Long = { Random.nextLong(it / 5 + 1) },
) {
    private val scope = scope
    private val network = network
    private val open = open
    private val publish = publish
    private val retryable = retryable
    private val waitRetry = waitRetry
    private val jitter = jitter
    /** One user start; its identity guards all asynchronous results until stopped. */
    class Intent(rule: PortForwardRule, preferredSession: String?) {
        val rule = rule
        val preferredSession = preferredSession
        val runtimeId: String = rule.id
        @Volatile var sessionId: String = preferredSession.orEmpty()
        @Volatile var recovering = false
        @Volatile internal var opening = false
        @Volatile internal var listening = false
        internal var lost = CompletableDeferred<Unit>()
        internal lateinit var job: Job
    }

    private val lock = Any()
    private val intents = mutableMapOf<String, Intent>()
    private val _pending = MutableStateFlow(false)
    /** Includes reconnect and prompt waits, so a foreground owner survives socket loss. */
    val pending = _pending.asStateFlow()
    private val _opening = MutableStateFlow<Set<String>>(emptySet())
    /** Only actual attempts need CPU time; backoff delays and network waits do not. */
    val opening = _opening.asStateFlow()

    /** Duplicate starts share the existing intent, including during a recovery wait. */
    fun start(rule: PortForwardRule, preferredSession: String? = null): Intent = synchronized(lock) {
        intents[rule.id]?.let { return it }
        val intent = Intent(rule, preferredSession)
        intent.job = scope.launch(start = CoroutineStart.LAZY) { run(intent) }
        intents[rule.id] = intent
        _pending.value = true
        publish(intent, PortForwardRuntimePhase.STARTING, null)
        intent.job.start()
        intent
    }

    /** Runs a short publication only for the currently owned start generation. */
    fun <T> whileCurrent(intent: Intent, action: () -> T): T? = synchronized(lock) {
        if (intents[intent.rule.id] === intent) action() else null
    }

    fun current(ruleId: String): Intent? = synchronized(lock) { intents[ruleId] }

    /** Cancels before the caller closes sockets, preventing close-triggered recovery. */
    fun stop(ruleId: String): Intent? {
        val intent = synchronized(lock) {
            intents.remove(ruleId).also { _pending.value = intents.isNotEmpty(); _opening.value -= ruleId }
        }
        intent?.job?.cancel()
        return intent
    }

    fun stopAll(): List<Intent> {
        val removed = synchronized(lock) {
            intents.values.toList().also { intents.clear(); _pending.value = false; _opening.value = emptySet() }
        }
        removed.forEach { it.job.cancel() }
        return removed
    }

    /** Completion is idempotent; concurrent transport detectors trigger one recovery. */
    fun disconnected(sessionId: String) = synchronized(lock) {
        intents.values.filter { it.sessionId == sessionId && it.listening }.forEach { it.lost.complete(Unit) }
    }

    fun listenerLost(intent: Intent) = whileCurrent(intent) { intent.lost.complete(Unit) }

    fun waitingForOperation(sessionId: String, waiting: Boolean) = synchronized(lock) {
        intents.values.filter { it.sessionId == sessionId && it.opening }.forEach {
            publish(it, if (waiting) PortForwardRuntimePhase.WAITING_FOR_OPERATION
                else if (it.recovering) PortForwardRuntimePhase.RECONNECTING else PortForwardRuntimePhase.STARTING, null)
        }
    }

    private suspend fun run(intent: Intent) {
        var attempt = 0
        while (current(intent.rule.id) === intent) {
            try {
                if (network.value.health != NetworkHealth.AVAILABLE) {
                    whileCurrent(intent) { publish(intent, PortForwardRuntimePhase.WAITING_FOR_NETWORK, null) }
                    network.first { it.health == NetworkHealth.AVAILABLE }
                }
                whileCurrent(intent) {
                    intent.lost = CompletableDeferred()
                    intent.listening = false
                    intent.opening = true
                    _opening.value += intent.rule.id
                    publish(intent, if (intent.recovering) PortForwardRuntimePhase.RECONNECTING
                        else PortForwardRuntimePhase.STARTING, null)
                }
                try { open(intent) } finally {
                    intent.opening = false
                    synchronized(lock) { if (intents[intent.rule.id] === intent) _opening.value -= intent.rule.id }
                }
                whileCurrent(intent) { intent.listening = true } ?: throw CancellationException()
                attempt = 0
                intent.lost.await()
            } catch (cancelled: CancellationException) {
                // A protocol prompt may cancel its own operation while this worker
                // remains alive. Explicit stop cancels the worker and wins here.
                currentCoroutineContext().ensureActive()
                fail(intent, cancelled)
                return
            } catch (error: Exception) {
                if (!retryable(error)) {
                    fail(intent, error)
                    return
                }
            }
            intent.recovering = true
            whileCurrent(intent) { publish(intent, if (network.value.health == NetworkHealth.AVAILABLE)
                PortForwardRuntimePhase.RECONNECTING else PortForwardRuntimePhase.WAITING_FOR_NETWORK, null) }
            if (network.value.health == NetworkHealth.AVAILABLE) {
                val base = retryDelayMillis(attempt++)
                waitRetry((base + jitter(base)).coerceAtMost(60_000))
            }
        }
    }

    private fun fail(intent: Intent, error: Throwable) = synchronized(lock) {
        if (intents[intent.rule.id] === intent) {
            publish(intent, PortForwardRuntimePhase.FAILED, error)
            intents.remove(intent.rule.id)
            _opening.value -= intent.rule.id
            _pending.value = intents.isNotEmpty()
        }
    }

    companion object {
        /** Bounded exponential retry; waiting for unavailable networks consumes no attempts. */
        fun retryDelayMillis(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000)
    }
}
