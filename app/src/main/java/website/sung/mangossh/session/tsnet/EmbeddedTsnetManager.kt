package website.sung.mangossh.session.tsnet

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val foregroundStarter: (Context) -> Unit = SessionForegroundService::start,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val stateDirectory = File(appContext.noBackupFilesDir, "embedded-tsnet-runtime")
    private var backend: EmbeddedTsnetBackend? = null
    private var backendToken: Any? = null
    private var runtimeStarting = false
    private var activeLeases = 0
    private var pendingAcquires = 0
    private var enrollmentHold = false
    private var enrolledIdentity = false
    private var registrationExists = false

    private val _status = MutableStateFlow(EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED))
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

    init {
        scope.launch {
            val enrolled = stateStore.hasEnrolledIdentity()
            mutex.withLock {
                if (backend == null && _status.value.phase == EmbeddedTsnetPhase.UNENROLLED) {
                    enrolledIdentity = enrolled
                    registrationExists = enrolled
                    updateStatusLocked(idlePhase())
                }
            }
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
        mutex.withLock {
            pendingAcquires += 1
            if (backend == null && !runtimeStarting) {
                runtimeStarting = true
                shouldStart = true
            }
        }
        try {
            if (shouldStart) startRuntime(null)
            val ready = withTimeout(START_TIMEOUT_MILLIS) {
                status.first {
                    it.phase == EmbeddedTsnetPhase.ACTIVE ||
                        (
                            it.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN &&
                                it.authKeyAllowed
                            ) ||
                        it.phase == EmbeddedTsnetPhase.FAILED
                }
            }
            if (ready.phase != EmbeddedTsnetPhase.ACTIVE) {
                throw TsnetEnrollmentRequiredException()
            }
            return mutex.withLock {
                val current = backend ?: throw TsnetEnrollmentRequiredException()
                pendingAcquires -= 1
                activeLeases += 1
                updateStatusLocked(EmbeddedTsnetPhase.ACTIVE)
                EmbeddedTsnetLease(
                    manager = this,
                    backend = current,
                    proxyData = TsnetProxyData(current.socksAddress(), current.socksSecret()),
                )
            }
        } catch (error: Exception) {
            val close = mutex.withLock {
                if (pendingAcquires > 0) pendingAcquires -= 1
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
                val detached = backend
                backend = null
                backendToken = null
                updateStatusLocked(EmbeddedTsnetPhase.UNENROLLED)
                detached
            }
            close?.let(::closeBackend)
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
                )
            }
        } catch (error: Exception) {
            mutex.withLock {
                runtimeStarting = false
                enrollmentHold = false
                updateStatusLocked(EmbeddedTsnetPhase.FAILED)
            }
            MangoLog.warn(MangoLogEvent.TSNET_FAILED, error)
            return
        }
        val accepted = mutex.withLock {
            runtimeStarting = false
            if (backend == null) {
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
                    backend = null
                    backendToken = null
                }
                runtimeStarting = false
                enrollmentHold = false
                updateStatusLocked(EmbeddedTsnetPhase.FAILED)
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
            if (authorizationUrl.isNotEmpty()) {
                authorizationUrlToEmit = authorizationUrl
            }
            when (rawState) {
                "starting" -> updateStatusLocked(EmbeddedTsnetPhase.STARTING)
                "needs_login" -> {
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
                    backend = null
                    backendToken = null
                    runtimeStarting = false
                    enrollmentHold = false
                    updateStatusLocked(idlePhase())
                }
                else -> {
                    close = backend
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
        if (activeLeases != 0 || pendingAcquires != 0 || enrollmentHold) return null
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
        _foregroundRequired.value = phase == EmbeddedTsnetPhase.STARTING ||
            phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN ||
            phase == EmbeddedTsnetPhase.WAITING_FOR_APPROVAL ||
            activeLeases > 0 ||
            pendingAcquires > 0
    }

    private fun closeBackend(value: EmbeddedTsnetBackend) {
        runCatching { value.close() }
    }

    private suspend fun release(leaseBackend: EmbeddedTsnetBackend) {
        val close = mutex.withLock {
            if (activeLeases > 0 && backend === leaseBackend) activeLeases -= 1
            updateStatusLocked(if (activeLeases > 0) EmbeddedTsnetPhase.ACTIVE else idlePhase())
            detachIfIdleLocked()
        }
        close?.let(::closeBackend)
    }

    internal fun releaseAsync(leaseBackend: EmbeddedTsnetBackend) {
        scope.launch { release(leaseBackend) }
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 30_000L
    }
}

/** Reference-counted access to one running process-wide embedded node. */
internal class EmbeddedTsnetLease(
    private val manager: EmbeddedTsnetManager,
    private val backend: EmbeddedTsnetBackend,
    val proxyData: TsnetProxyData,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val relays = mutableSetOf<EmbeddedTsnetUdpRelay>()

    @Synchronized
    fun startUdpRelay(host: String, port: Int): EmbeddedTsnetUdpRelay {
        check(!closed.get())
        return backend.startUdpRelay(host, port).also(relays::add)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relays.toList().forEach { runCatching { it.close() } }
        relays.clear()
        manager.releaseAsync(backend)
    }
}
