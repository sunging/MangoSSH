package website.sung.mangossh.session.tsnet

import kotlinx.coroutines.async

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tsnetbridge.StatusListener
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.data.tsnet.AndroidTsnetStateStore
import website.sung.mangossh.data.tsnet.EmbeddedTsnetStateStore
import website.sung.mangossh.session.SessionForegroundService

internal enum class EmbeddedTsnetPhase {
    UNENROLLED,
    STARTING,
    WAITING_FOR_LOGIN,
    WAITING_FOR_APPROVAL,
    READY_IDLE,
    ACTIVE,
    FAILED,
}

internal data class EmbeddedTsnetStatus(
    val phase: EmbeddedTsnetPhase,
    val activeSessions: Int = 0,
    val authKeyAllowed: Boolean = true,
    /** False only until the stored identity has been read; [phase] is a placeholder until then. */
    val identityResolved: Boolean = true,
)

/** Fixed failure used when a TSNET profile is selected before enrollment. */
internal class TsnetEnrollmentRequiredException : Exception()

/** Fixed failure used when logout is attempted while TSNET sessions are live. */
internal class TsnetSessionsActiveException : Exception()

/**
 * Owns the single process-wide embedded tsnet node.
 *
 * Concurrent SSH/Mosh sessions share one runtime. Pending acquisitions prevent
 * a just-started node from closing before the first lease is issued, and the
 * final idempotent lease close tears down relays, sockets, and Go goroutines.
 */
internal class EmbeddedTsnetManager(
    context: Context,
    private val stateStore: EmbeddedTsnetStateStore = AndroidTsnetStateStore(context),
    private val backendFactory: EmbeddedTsnetBackendFactory =
        GomobileTsnetBackendFactory(context.applicationContext),
    // The Go backend reports status through a callback that hops onto this
    // scope. `SupervisorJob` alone would still let a failure there reach the
    // default uncaught handler and close the app, so the scope carries a
    // reporting handler of its own.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                MangoLog.warn(MangoLogEvent.SESSION_COROUTINE_FAILED, error)
            },
    ),
    private val foregroundStarter: (Context) -> Unit = SessionForegroundService::start,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val stateDirectory = File(appContext.noBackupFilesDir, "embedded-tsnet-runtime")
    private var backend: EmbeddedTsnetBackend? = null
    private var backendToken: Any? = null
    private var runtimeStarting = false
    private val leases = linkedMapOf<Long, EmbeddedTsnetLease>()
    private var nextLeaseId = 0L
    private val activeLeases: Int get() = leases.size
    private val pendingRequests = mutableMapOf<Any, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val pendingAcquires: Int get() = pendingRequests.size
    private var enrollmentHold = false
    private var foregroundFailureGeneration = 0L
    private var enrolledIdentity = false
    private var registrationExists = false

    // Device browsing keeps an enrolled node up while the Tailscale settings
    // page is visible. The app is in the foreground then, so a browse-only
    // node never asks for the foreground service.
    private var browseHold = false
    private val browsingRequested = MutableStateFlow(false)

    private val _status = MutableStateFlow(
        EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED, identityResolved = false),
    )
    val status = _status.asStateFlow()

    private val _authorizationUrls = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot in-memory browser URL events. Callers must not display, persist, or log them. */
    val authorizationUrls = _authorizationUrls.asSharedFlow()

    private val _foregroundRequired = MutableStateFlow(false)
    val foregroundRequired = _foregroundRequired.asStateFlow()

    private val _network = MutableStateFlow<TsnetNetworkSnapshot?>(null)

    /**
     * Latest tailnet view, refreshed only while device browsing is held and
     * cleared when it is released so peer names are not kept in memory.
     */
    val network = _network.asStateFlow()

    init {
        scope.launch {
            // An unreadable store resolves as not enrolled rather than
            // leaving the status unresolved forever.
            val enrolled = runCatching { stateStore.hasEnrolledIdentity() }.getOrDefault(false)
            mutex.withLock {
                if (backend == null && _status.value.phase == EmbeddedTsnetPhase.UNENROLLED) {
                    enrolledIdentity = enrolled
                    registrationExists = enrolled
                    updateStatusLocked(idlePhase())
                }
            }
        }
        // One collector applies visibility changes in order; a newer request
        // cancels a pending release or an older hold's snapshot polling.
        scope.launch {
            browsingRequested.collectLatest { visible ->
                if (visible) {
                    holdForBrowsing()
                    pollNetworkSnapshots()
                } else {
                    delay(BROWSE_RELEASE_DELAY_MILLIS)
                    releaseBrowsing()
                }
            }
        }
    }

    /** Name this installation registers with; stable across restarts and sign-outs. */
    suspend fun nodeName(): String = withContext(Dispatchers.IO) { stateStore.nodeName() }

    /**
     * Keeps an enrolled node running while the device list is on screen.
     *
     * Hiding the list releases the hold after [BROWSE_RELEASE_DELAY_MILLIS], so
     * a quick connect that navigates away can take its session lease before
     * the node would otherwise stop and restart.
     */
    fun setDeviceBrowsing(visible: Boolean) {
        browsingRequested.value = visible
    }

    private suspend fun holdForBrowsing() {
        val enrolled = hasIdentity()
        val shouldStart = mutex.withLock {
            browseHold = true
            if (enrolled && backend == null && !runtimeStarting) {
                runtimeStarting = true
                true
            } else {
                false
            }
        }
        // Started outside the collector so a quick hide cannot cancel it
        // half-way; the release path detaches whatever it produced.
        if (shouldStart) scope.launch { startRuntime(null) }
    }

    private suspend fun releaseBrowsing() {
        val close = mutex.withLock {
            if (!browseHold) return
            browseHold = false
            _network.value = null
            if (backend != null || runtimeStarting) {
                detachIfIdleLocked() ?: run {
                    // Still owned by sessions or enrollment: only the
                    // foreground requirement may have changed.
                    updateStatusLocked(_status.value.phase)
                    null
                }
            } else {
                updateStatusLocked(_status.value.phase)
                null
            }
        }
        close?.let(::closeBackend)
    }

    private suspend fun pollNetworkSnapshots() {
        while (true) {
            status.first { it.phase == EmbeddedTsnetPhase.ACTIVE }
            val current = mutex.withLock { backend.takeIf { browseHold } }
            if (current == null) {
                delay(SNAPSHOT_INTERVAL_MILLIS)
                continue
            }
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    TsnetNetworkSnapshotCodec.decode(current.networkSnapshotJson())
                }
                mutex.withLock {
                    if (browseHold && backend === current) _network.value = snapshot
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                MangoLog.warn(MangoLogEvent.TSNET_SNAPSHOT_FAILED, error)
            }
            delay(SNAPSHOT_INTERVAL_MILLIS)
        }
    }

    suspend fun beginBrowserEnrollment() {
        checkNoActiveSessions()
        restartForEnrollment(null)
    }

    /**
     * Starts first-time enrollment and clears the caller's mutable key buffer
     * on every path. The generated Java String is passed directly to Go and is
     * not retained in manager state, exceptions, SavedState, or persistence.
     */
    suspend fun beginAuthKeyEnrollment(authKey: CharArray) {
        try {
            require(authKey.isNotEmpty() && !hasIdentity())
            checkNoActiveSessions()
            restartForEnrollment(authKey)
        } finally {
            authKey.fill('\u0000')
        }
    }

    suspend fun acquire(): EmbeddedTsnetLease {
        var shouldStart = false
        val requestId = Any()
        val retired = kotlinx.coroutines.CompletableDeferred<Unit>()
        mutex.withLock {
            pendingRequests[requestId] = retired
            if (backend == null && !runtimeStarting) {
                runtimeStarting = true
                shouldStart = true
            }
        }
        try {
            if (shouldStart) startRuntime(null)
            val ready = kotlinx.coroutines.coroutineScope {
                val waiting = async {
                    withTimeout(START_TIMEOUT_MILLIS) {
                        status.first {
                            it.phase == EmbeddedTsnetPhase.ACTIVE ||
                                (it.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN && it.authKeyAllowed) ||
                                it.phase == EmbeddedTsnetPhase.FAILED
                        }
                    }
                }
                try {
                    kotlinx.coroutines.selects.select {
                        retired.onAwait { throw TsnetEnrollmentRequiredException() }
                        waiting.onAwait { it }
                    }
                } finally { waiting.cancel() }
            }
            if (ready.phase != EmbeddedTsnetPhase.ACTIVE) {
                throw TsnetEnrollmentRequiredException()
            }
            return mutex.withLock {
                if (retired.isCompleted) throw TsnetEnrollmentRequiredException()
                val current = backend ?: throw TsnetEnrollmentRequiredException()
                if (_status.value.phase != EmbeddedTsnetPhase.ACTIVE) throw TsnetEnrollmentRequiredException()
                val proxy = TsnetProxyData(current.socksAddress(), current.socksSecret())
                val leaseId = ++nextLeaseId
                val lease = EmbeddedTsnetLease(
                    manager = this,
                    backend = current,
                    leaseId = leaseId,
                    generation = requireNotNull(backendToken),
                    proxyData = proxy,
                )
                pendingRequests.remove(requestId)
                leases[leaseId] = lease
                updateStatusLocked(EmbeddedTsnetPhase.ACTIVE)
                lease
            }
        } catch (error: Exception) {
            val close = mutex.withLock {
                pendingRequests.remove(requestId)
                detachIfIdleLocked()
            }
            close?.let(::closeBackend)
            throw error
        }
    }

    suspend fun logout() {
        checkNoActiveSessions()
        if (!hasIdentity()) {
            withContext(Dispatchers.IO) { stateStore.clearIdentity() }
            mutex.withLock {
                enrolledIdentity = false
                registrationExists = false
                updateStatusLocked(EmbeddedTsnetPhase.UNENROLLED)
            }
            return
        }
        mutex.withLock { enrollmentHold = true }
        val shouldStart = mutex.withLock {
            if (backend == null && !runtimeStarting) {
                runtimeStarting = true
                true
            } else {
                false
            }
        }
        if (shouldStart) startRuntime(null)
        val phase = withTimeout(START_TIMEOUT_MILLIS) {
            status.first {
                it.phase == EmbeddedTsnetPhase.ACTIVE ||
                    it.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN ||
                    it.phase == EmbeddedTsnetPhase.FAILED
            }.phase
        }
        val current = mutex.withLock { backend }
        try {
            if (phase == EmbeddedTsnetPhase.ACTIVE && current != null) {
                withContext(Dispatchers.IO) { current.logout() }
            }
            withContext(Dispatchers.IO) { stateStore.clearIdentity() }
            MangoLog.info(MangoLogEvent.TSNET_LOGOUT_SUCCEEDED)
        } catch (error: Exception) {
            MangoLog.warn(MangoLogEvent.TSNET_LOGOUT_FAILED, error)
            throw IllegalStateException()
        } finally {
            val close = mutex.withLock {
                enrollmentHold = false
                enrolledIdentity = false
                registrationExists = false
                _network.value = null
                val detached = backend
                backend = null
                backendToken = null
                updateStatusLocked(EmbeddedTsnetPhase.UNENROLLED)
                detached
            }
            close?.let(::closeBackend)
        }
    }

    /**
     * Abandons work that Android refused to protect with a foreground service.
     *
     * Active and pending session leases retain the backend reference until
     * their normal release path decrements the counters. Enrollment-only work
     * has no such owner, so its backend is detached and closed immediately.
     */
    internal fun onForegroundServiceUnavailable() {
        // Begin undispatched so the service cannot stop before this state has
        // withdrawn its foreground requirement. Backend close then hops back to
        // IO and never blocks the service's main-thread failure path.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val close = mutex.withLock {
                foregroundFailureGeneration += 1
                runtimeStarting = false
                enrollmentHold = false
                invalidateLeasesLocked(backendToken)
                backendToken = null
                val detached = backend.also { backend = null }
                updateStatusLocked(EmbeddedTsnetPhase.FAILED)
                detached
            }
            close?.let { withContext(Dispatchers.IO) { closeBackend(it) } }
        }
    }

    private suspend fun restartForEnrollment(authKey: CharArray?) {
        val previous = mutex.withLock {
            enrollmentHold = true
            backend.also {
                backend = null
                backendToken = null
                runtimeStarting = true
                updateStatusLocked(EmbeddedTsnetPhase.STARTING)
            }
        }
        // Publish the keepalive requirement before launching the service. Its
        // initial StateFlow collection must not observe false and stop itself
        // during fast enrollment startup.
        try {
            foregroundStarter(appContext)
        } catch (error: RuntimeException) {
            mutex.withLock {
                runtimeStarting = false
                enrollmentHold = false
                updateStatusLocked(EmbeddedTsnetPhase.FAILED)
            }
            previous?.let(::closeBackend)
            MangoLog.warn(MangoLogEvent.TSNET_FAILED, error)
            throw error
        }
        previous?.let(::closeBackend)
        startRuntime(authKey)
    }

    private suspend fun startRuntime(authKey: CharArray?) {
        val token = Any()
        val failureGeneration = mutex.withLock { foregroundFailureGeneration }
        val listener = object : StatusListener {
            override fun onStatus(state: String, authURL: String) {
                scope.launch { handleBackendStatus(token, state, authURL) }
            }
        }
        val created = try {
            withContext(Dispatchers.IO) {
                check(stateDirectory.mkdirs() || stateDirectory.isDirectory)
                backendFactory.create(
                    stateDirectory = stateDirectory.absolutePath,
                    hostname = stateStore.nodeName(),
                    store = stateStore,
                    listener = listener,
                ).let { delegate ->
                    object : EmbeddedTsnetBackend by delegate {
                        private val closed = AtomicBoolean(false)
                        override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
                    }
                }
            }
        } catch (error: Exception) {
            mutex.withLock {
                if (foregroundFailureGeneration == failureGeneration) {
                    invalidateLeasesLocked(backendToken)
                    runtimeStarting = false
                    enrollmentHold = false
                    updateStatusLocked(EmbeddedTsnetPhase.FAILED)
                }
            }
            MangoLog.warn(MangoLogEvent.TSNET_FAILED, error)
            return
        }
        val accepted = mutex.withLock {
            if (
                backend == null &&
                runtimeStarting &&
                foregroundFailureGeneration == failureGeneration
            ) {
                runtimeStarting = false
                backend = created
                backendToken = token
                updateStatusLocked(EmbeddedTsnetPhase.STARTING)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            closeBackend(created)
            return
        }
        MangoLog.info(MangoLogEvent.TSNET_STARTING)
        try {
            withContext(Dispatchers.IO) {
                if (authKey == null) {
                    created.start("")
                } else {
                    created.start(authKey.concatToString())
                }
            }
        } catch (error: Exception) {
            val close = mutex.withLock {
                if (backend === created) {
                    invalidateLeasesLocked(backendToken)
                    backend = null
                    backendToken = null
                    runtimeStarting = false
                    enrollmentHold = false
                    updateStatusLocked(EmbeddedTsnetPhase.FAILED)
                }
                created
            }
            closeBackend(close)
            MangoLog.warn(tsnetStartFailureEvent(error))
        }
    }

    private fun tsnetStartFailureEvent(error: Exception): MangoLogEvent =
        when (error.message) {
            "embedded tsnet server start failed" -> MangoLogEvent.TSNET_SERVER_START_FAILED
            "embedded tsnet local client failed" -> MangoLogEvent.TSNET_LOCAL_CLIENT_FAILED
            "embedded tsnet loopback failed" -> MangoLogEvent.TSNET_LOOPBACK_FAILED
            else -> MangoLogEvent.TSNET_FAILED
        }

    private suspend fun handleBackendStatus(token: Any, rawState: String, authorizationUrl: String) {
        val registrationStillExists = if (rawState == "needs_login") {
            stateStore.hasEnrolledIdentity()
        } else {
            null
        }
        var authorizationUrlToEmit: String? = null
        var close: EmbeddedTsnetBackend? = null
        mutex.withLock {
            if (token !== backendToken) return
            // Merely viewing the device list must never open a browser; login
            // stays an explicit enrollment action.
            if (authorizationUrl.isNotEmpty() && !browseOnlyLocked()) {
                authorizationUrlToEmit = authorizationUrl
            }
            when (rawState) {
                "starting" -> updateStatusLocked(EmbeddedTsnetPhase.STARTING)
                // A registered node reports NoState/NeedsLogin while it loads
                // its saved state. tsnet requests an authorization URL when a
                // sign-in is really needed, so until one arrives the node is
                // still starting and must not look signed out.
                "needs_login" -> if (registrationStillExists == true && authorizationUrl.isEmpty()) {
                    updateStatusLocked(EmbeddedTsnetPhase.STARTING)
                } else {
                    enrolledIdentity = false
                    registrationExists = registrationStillExists == true
                    updateStatusLocked(EmbeddedTsnetPhase.WAITING_FOR_LOGIN)
                }
                "needs_approval" -> updateStatusLocked(EmbeddedTsnetPhase.WAITING_FOR_APPROVAL)
                "running" -> {
                    stateStore.markEnrolled()
                    enrolledIdentity = true
                    registrationExists = true
                    enrollmentHold = false
                    updateStatusLocked(EmbeddedTsnetPhase.ACTIVE)
                    close = detachIfIdleLocked()
                    MangoLog.info(MangoLogEvent.TSNET_RUNNING)
                }
                "stopped" -> {
                    close = backend
                    invalidateLeasesLocked(backendToken)
                    backend = null
                    backendToken = null
                    runtimeStarting = false
                    enrollmentHold = false
                    updateStatusLocked(idlePhase())
                }
                else -> {
                    close = backend
                    invalidateLeasesLocked(backendToken)
                    backend = null
                    backendToken = null
                    runtimeStarting = false
                    enrollmentHold = false
                    updateStatusLocked(EmbeddedTsnetPhase.FAILED)
                    MangoLog.warn(MangoLogEvent.TSNET_FAILED)
                }
            }
        }
        authorizationUrlToEmit?.let(_authorizationUrls::tryEmit)
        close?.let(::closeBackend)
    }

    private suspend fun checkNoActiveSessions() {
        if (mutex.withLock { activeLeases > 0 || pendingAcquires > 0 }) {
            throw TsnetSessionsActiveException()
        }
    }

    private fun detachIfIdleLocked(): EmbeddedTsnetBackend? {
        if (activeLeases != 0 || pendingAcquires != 0 || enrollmentHold || browseHold) return null
        val current = backend
        backend = null
        backendToken = null
        runtimeStarting = false
        updateStatusLocked(idlePhase())
        return current
    }

    private fun idlePhase(): EmbeddedTsnetPhase =
        if (enrolledIdentity) EmbeddedTsnetPhase.READY_IDLE else EmbeddedTsnetPhase.UNENROLLED

    private suspend fun hasIdentity(): Boolean =
        withContext(Dispatchers.IO) { stateStore.hasEnrolledIdentity() }

    private fun updateStatusLocked(phase: EmbeddedTsnetPhase) {
        _status.value = EmbeddedTsnetStatus(
            phase = phase,
            activeSessions = activeLeases,
            authKeyAllowed = !registrationExists,
        )
        val converging = phase == EmbeddedTsnetPhase.STARTING ||
            phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN ||
            phase == EmbeddedTsnetPhase.WAITING_FOR_APPROVAL
        _foregroundRequired.value = (converging && !browseOnlyLocked()) ||
            activeLeases > 0 ||
            pendingAcquires > 0
    }

    /** True when only the visible device list owns the node. */
    private fun browseOnlyLocked(): Boolean =
        browseHold && !enrollmentHold && activeLeases == 0 && pendingAcquires == 0

    private fun closeBackend(value: EmbeddedTsnetBackend) {
        runCatching { value.close() }
    }

    /** Settles leases only in the generation being retired; callbacks are not executed in this lock. */
    private fun invalidateLeasesLocked(generation: Any?) {
        foregroundFailureGeneration++
        pendingRequests.values.forEach { it.complete(Unit) }
        pendingRequests.clear()
        val invalid = leases.values.filter { it.generation === generation }
        invalid.forEach { leases.remove(it.leaseId); it.invalidate() }
    }

    private suspend fun release(leaseId: Long, generation: Any) {
        val close = mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.generation !== generation) return
            leases.remove(leaseId)
            updateStatusLocked(if (backend != null) _status.value.phase else idlePhase())
            detachIfIdleLocked()
        }
        close?.let(::closeBackend)
    }

    internal fun releaseAsync(leaseId: Long, generation: Any) {
        scope.launch { release(leaseId, generation) }
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 30_000L
        const val BROWSE_RELEASE_DELAY_MILLIS = 5_000L
        const val SNAPSHOT_INTERVAL_MILLIS = 5_000L
    }
}

/** Reference-counted access to one running process-wide embedded node. */
internal class EmbeddedTsnetLease(
    private val manager: EmbeddedTsnetManager,
    private val backend: EmbeddedTsnetBackend,
    val leaseId: Long,
    val generation: Any,
    val proxyData: TsnetProxyData,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val relays = mutableSetOf<EmbeddedTsnetUdpRelay>()
    private val invalidation = kotlinx.coroutines.CompletableDeferred<Unit>()
    val invalidated: kotlinx.coroutines.Deferred<Unit> get() = invalidation

    /** Signals loss without running blocking relay cleanup while the manager holds its mutex. */
    internal fun invalidate() { invalidation.complete(Unit) }

    @Synchronized
    fun startUdpRelay(host: String, port: Int): EmbeddedTsnetUdpRelay {
        check(!closed.get() && !invalidation.isCompleted)
        val delegate = backend.startUdpRelay(host, port)
        val relay = object : EmbeddedTsnetUdpRelay {
            private val released = AtomicBoolean(false)
            override val localPort: Int get() = delegate.localPort
            override fun close() { if (released.compareAndSet(false, true)) delegate.close() }
        }
        if (invalidation.isCompleted) { relay.close(); error("Embedded node stopped") }
        return relay.also(relays::add)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relays.toList().forEach { runCatching { it.close() } }
        relays.clear()
        manager.releaseAsync(leaseId, generation)
    }
}
