package website.sung.mangossh.session

import android.content.Context
import android.net.Uri
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.isTrustedHostKey
import website.sung.mangossh.data.vault.sameHostKeySlot
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshAgent
import website.sung.mangossh.session.ssh.SshAgentIdentity
import website.sung.mangossh.session.ssh.SshForward
import website.sung.mangossh.session.ssh.SshKeyCodec
import website.sung.mangossh.session.ssh.SshChannel
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.TerminalEmulator
import website.sung.mangossh.data.keys.KeyPassphraseRequiredException
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.TrustedHostKey
import website.sung.mangossh.data.vault.VaultRepository
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.data.settings.ConnectionPreferencesStore
import website.sung.mangossh.data.settings.TerminalAppearanceStore
import website.sung.mangossh.data.settings.TerminalBehaviorStore
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.session.tsnet.EmbeddedTsnetLease
import website.sung.mangossh.session.tsnet.EmbeddedTsnetManager
import website.sung.mangossh.session.tsnet.EmbeddedTsnetUdpRelay
import website.sung.mangossh.session.tsnet.TsnetEnrollmentRequiredException

/**
 * Owns live SSH sockets and keeps all blocking protocol work away from Compose.
 * The controller never trusts a new or changed host key without a user response.
 */
class SshSessionController internal constructor(
    appContext: Context,
    private val vault: VaultRepository,
    private val keyManager: SshKeyManager = SshKeyManager(),
    private val embeddedTsnetManager: EmbeddedTsnetManager,
    private val terminalAppearanceStore: TerminalAppearanceStore,
    private val terminalBehaviorStore: TerminalBehaviorStore,
    private val connectionPreferencesStore: ConnectionPreferencesStore,
    private val appForegroundState: AppForegroundState,
    private val accessState: website.sung.mangossh.security.AppAccessState = website.sung.mangossh.security.AppAccessState(false),
) {
    private val _endedTerminals = MutableStateFlow<List<EndedTerminalRecord>>(emptyList())
    val endedTerminals = _endedTerminals.asStateFlow()

    /** Returns measured SSH transport times; native Mosh network packet timings remain unknown. */
    fun diagnostics(sessionId: String): ConnectionDiagnostics? {
        val managed = sessionsById[sessionId]
        return if (managed != null) diagnosticSnapshot(managed, _sessions.value.firstOrNull { it.id == sessionId }?.phase ?: TerminalSessionPhase.CLOSED)
            else _endedTerminals.value.firstOrNull { it.session.id == sessionId }?.diagnostics
    }

    private fun diagnosticSnapshot(managed: ManagedSession, phase: TerminalSessionPhase): ConnectionDiagnostics {
        val connection = managed.sshFeatureConnection ?: managed.connection
        return ConnectionDiagnostics(managed.configuredRoute, phase,
            connection.lastPacketSentNanos.takeIf { it > 0 }, connection.lastPacketReceivedNanos.takeIf { it > 0 },
            managed.lastConfirmedNanos.takeIf { it > 0 },
            if (managed.protocol == ConnectionProtocol.MOSH) managed.moshProcess != null && managed.lifecycle.isOpen else null,
            if (managed.protocol == ConnectionProtocol.MOSH) managed.sshFeatureConnection != null else null)
    }

    /** Clears only ended emulator history; active sessions are unaffected. */
    fun clearEndedTerminals() {
        synchronized(_endedTerminals) {
            val removed = _endedTerminals.value
            _endedTerminals.value = emptyList()
            removed.forEach { terminalStore.remove(it.session.id) }
        }
    }

    private val context = appContext.applicationContext

    /**
     * Paces keepalives: the user-configured interval while the app is on screen,
     * a longer Doze-tolerant alarm once it is backgrounded and the session wake
     * lock has been released. See [SessionKeepaliveScheduler].
     */
    private val keepaliveScheduler = SessionKeepaliveScheduler(
        appForeground = appForegroundState.foreground,
        alarm = CoalescingKeepaliveAlarm(createKeepaliveAlarm(context), android.os.SystemClock::elapsedRealtime),
        now = android.os.SystemClock::elapsedRealtime,
        backgroundMultiplier = { connectionPreferencesStore.current().backgroundKeepaliveMultiplier },
    )

    /**
     * Last-resort guard for the session scope.
     *
     * `SupervisorJob` stops a failing child from cancelling its siblings, but it
     * does not absorb the failure: without a handler here, anything escaping one
     * of the transport coroutines reaches Android's default uncaught handler and
     * kills the whole process, taking every other live session with it. A single
     * broken transport must never be able to do that.
     */
    private val coroutineFailureHandler = CoroutineExceptionHandler { _, error ->
        MangoLog.warn(MangoLogEvent.SESSION_COROUTINE_FAILED, error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineFailureHandler)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineFailureHandler)
    private val sessionsById = ConcurrentHashMap<String, ManagedSession>()
    private val promptRegistry = SessionPromptRegistry()
    private val remoteFiles = RemoteFileClient()
    private val terminalStore = SessionTerminalStore(
        onKeyboardInput = { sessionId, bytes -> send(sessionId, bytes) },
        onResize = { sessionId, columns, rows -> resize(sessionId, columns, rows) },
        appearanceProvider = terminalAppearanceStore::current,
        behaviorProvider = terminalBehaviorStore::current,
    )

    private val _sessions = MutableStateFlow<List<TerminalSessionState>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _portForwards = MutableStateFlow<List<PortForwardRuntimeState>>(emptyList())
    val portForwards = _portForwards.asStateFlow()

    /**
     * File transfers run beside sessions rather than inside them: they can be
     * paused, resumed, and retried after the browser screen that started them
     * is gone, so the engine is kept separate from session lifetime.
     */
    private val fileTransfers = FileTransferManager(
        context = context,
        scope = scope,
        remoteFiles = remoteFiles,
        connectionOf = ::requireSshFeatureConnection,
        onSessionIdle = ::closeFileTransferIfIdle,
        identityOf = { sessionId -> sessionsById[sessionId]?.transferIdentity() },
    )
    val scpTransfers: StateFlow<List<ScpTransferState>> = fileTransfers.transfers
    /** True while any transfer is queued or moving bytes; paused and finished ones do not count. */
    val hasActiveTransfers: StateFlow<Boolean> = fileTransfers.transfers
        .map { transfers -> transfers.any { it.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val transferConflicts = fileTransfers.conflicts
    /** Resolves only the currently registered transfer preview. */
    fun resolveTransferConflict(id: String, decision: TransferConflictDecision) = fileTransfers.resolveConflict(id, decision)

    private val _resourceSnapshots = MutableStateFlow<Map<String, ServerResourceSnapshot>>(emptyMap())
    val resourceSnapshots = _resourceSnapshots.asStateFlow()

    private val _prompts = MutableStateFlow<List<SessionPrompt>>(emptyList())
    val prompts = _prompts.asStateFlow()

    private val _sessionEndedEvents = MutableSharedFlow<SessionEndedEvent>(extraBufferCapacity = 8)
    /** One-shot notifications used to leave a foreground terminal after it ends. */
    val sessionEndedEvents = _sessionEndedEvents.asSharedFlow()

    /** Transient OSC 52 copy requests for the currently visible terminal UI. */
    val clipboardCopies = terminalStore.clipboardCopies

    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { requestHealthChecks() }
        override fun onLost(network: android.net.Network) { requestHealthChecks() }
    }

    /** Foreground and network events coalesce into at most one in-flight probe per session. */
    private fun requestHealthChecks() {
        sessionsById.forEach { (id, managed) ->
            if (_sessions.value.none { it.id == id && it.phase == TerminalSessionPhase.OPEN }) return@forEach
            if (!managed.healthChecking.compareAndSet(false, true)) return@forEach
            launchOwned(managed) {
                try {
                    val connection = if (managed.protocol == ConnectionProtocol.MOSH) managed.sshFeatureConnection else managed.connection
                    if (connection != null) {
                        try { connection.keepalive(); managed.lastConfirmedNanos = System.nanoTime() }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (managed.protocol == ConnectionProtocol.MOSH) invalidateMoshSshFeatureConnection(id, managed, connection)
                            else finishSession(id, managed, SessionEndReason.CONNECTION_LOST, error)
                        }
                    }
                } finally { managed.healthChecking.set(false) }
            }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        // Pause frame-cadence snapshot rebuilds for every retained emulator
        // while the app is backgrounded; resume (and force a repaint) on return.
        scope.launch {
            appForegroundState.foreground.collect { foreground ->
                terminalStore.setDisplayActive(foreground)
                if (foreground) requestHealthChecks()
            }
        }
        scope.launch {
            accessState.locked.collect { locked ->
                if (locked) _prompts.value.filterIsInstance<SessionPrompt.Authentication>()
                    .filter { (it.title as? SessionPromptText.App)?.kind == SessionPromptTextKind.AGENT_TITLE }
                    .forEach { respondToPrompt(it.requestId, null) }
            }
        }
    }

    /**
     * Starts a user-requested terminal session and protects it with the
     * foreground service before network negotiation begins.
     */
    fun connect(profile: ConnectionProfile): String {
        val sessionId = UUID.randomUUID().toString()
        val managed = ManagedSession(
            connection = SshConnection(profile.hostname, profile.port, profile.legacySshAlgorithms),
            profile = profile,
            defaultPreferences = connectionPreferencesStore.current(),
            initialSnapshot = vault.snapshot.value,
            protocol = profile.protocol,
        )
        sessionsById[sessionId] = managed
        terminalStore.create(sessionId)
        MangoLog.info(MangoLogEvent.SSH_CONNECT_STARTED)
        updateSession(
            TerminalSessionState(
                id = sessionId,
                profileId = profile.id,
                title = profile.label,
                endpoint = profile.endpoint,
                protocol = profile.protocol,
                phase = TerminalSessionPhase.CONNECTING,
                detail = context.appString(R.string.session_connecting),
            ),
        )
        if (!startForegroundOwnership(sessionId, managed)) return sessionId

        managed.connectionJob = launchOwned(managed) {
            when (profile.protocol) {
                ConnectionProtocol.SSH -> runSshSession(sessionId, profile, managed)
                ConnectionProtocol.MOSH -> runMoshSession(sessionId, profile, managed)
            }
        }
        return sessionId
    }

    /**
     * Takes foreground ownership before any network negotiation begins.
     *
     * Android can refuse the start outright depending on the process state, and
     * this runs on the main thread from the connect action, so an escaping
     * `ForegroundServiceStartNotAllowedException` would close the app instead of
     * the connection. Report it as an ordinary connection failure and let the
     * session end with a message the user can act on.
     *
     * Returns whether the session may continue connecting.
     */
    private fun startForegroundOwnership(sessionId: String, managed: ManagedSession): Boolean {
        try {
            SessionForegroundService.start(context)
        } catch (error: RuntimeException) {
            MangoLog.warn(MangoLogEvent.FOREGROUND_SERVICE_START_DENIED, error)
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                messageKind = SessionEndMessageKind.FOREGROUND_SERVICE_UNAVAILABLE,
            )
            return false
        }
        return true
    }

    /**
     * Opens an SSH connection used only for browsing and transferring files.
     *
     * It is released through [releaseFileTransferSession] rather than by the
     * terminal UI.
     */
    fun connectForFileTransfer(profile: ConnectionProfile): String =
        connectWithoutShell(profile, SessionKind.FILE_TRANSFER)

    /**
     * Opens an SSH connection used only to carry port forwards.
     *
     * It closes as soon as the last forward on it stops, so a tunnel no longer
     * requires keeping a terminal open for the same host.
     */
    fun connectForPortForward(profile: ConnectionProfile): String =
        connectWithoutShell(profile, SessionKind.PORT_FORWARD)

    /**
     * Authenticates a connection that carries no shell.
     *
     * Host-key verification and authentication are identical to [connect], but
     * no shell channel, PTY, or terminal emulator is created, so the session
     * must never be presented as an interactive terminal.
     */
    private fun connectWithoutShell(profile: ConnectionProfile, kind: SessionKind): String {
        val sessionId = UUID.randomUUID().toString()
        val managed = ManagedSession(
            connection = SshConnection(profile.hostname, profile.port, profile.legacySshAlgorithms),
            profile = profile,
            defaultPreferences = connectionPreferencesStore.current(),
            initialSnapshot = vault.snapshot.value,
            protocol = ConnectionProtocol.SSH,
            kind = kind,
        )
        sessionsById[sessionId] = managed
        MangoLog.info(MangoLogEvent.SSH_CONNECT_STARTED)
        updateSession(
            TerminalSessionState(
                id = sessionId,
                profileId = profile.id,
                title = profile.label,
                endpoint = profile.endpoint,
                protocol = ConnectionProtocol.SSH,
                phase = TerminalSessionPhase.CONNECTING,
                detail = context.appString(R.string.session_connecting),
                kind = kind,
            ),
        )
        if (!startForegroundOwnership(sessionId, managed)) return sessionId

        managed.connectionJob = launchOwned(managed) {
            runShellessSession(sessionId, profile, managed)
        }
        return sessionId
    }

    /**
     * Marks a file-transfer connection as no longer needed.
     *
     * Unfinished transfers keep it alive so closing the browser cannot abort a
     * transfer the user already started, or strand one they paused on purpose;
     * it closes as soon as the queue for that session drains.
     */
    fun releaseFileTransferSession(sessionId: String) {
        val managed = sessionsById[sessionId] ?: return
        if (managed.kind != SessionKind.FILE_TRANSFER) return
        managed.releaseRequested = true
        closeFileTransferIfIdle(sessionId)
    }

    private fun closeFileTransferIfIdle(sessionId: String) {
        val managed = sessionsById[sessionId] ?: return
        if (managed.kind != SessionKind.FILE_TRANSFER || !managed.releaseRequested) return
        if (!fileTransfers.hasBusyTransfers(sessionId)) {
            finishSession(sessionId, managed, SessionEndReason.USER_REQUEST)
        }
    }

    fun respondToPrompt(requestId: String, values: List<String>?) {
        promptRegistry.complete(requestId, values)
        _prompts.update { prompts -> prompts.filterNot { it.requestId == requestId } }
    }

    fun send(sessionId: String, bytes: ByteArray) {
        sessionsById[sessionId]?.takeIf { it.inputReady }?.writer?.send(bytes)
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        sessionsById[sessionId]?.resizeQueue?.offer(columns, rows)
    }

    /** Installs the only writer before startup commands or user input are admitted. */
    private fun attachTerminalTransport(sessionId: String, managed: ManagedSession, output: java.io.OutputStream) {
        val writer = TerminalTransport(cleanupScope, output,
            onFailure = { finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, it) },
            onOverflow = { finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST,
                messageKind = SessionEndMessageKind.INPUT_OVERFLOW) })
        install(managed, writer, { it.close() }) { managed.writer = it }
        val sizes = TerminalResizeQueue(scope) { columns, rows ->
            when (managed.protocol) {
                ConnectionProtocol.SSH -> managed.session?.resize(columns, rows)
                ConnectionProtocol.MOSH -> managed.moshProcess?.resize(columns, rows)
            }
        }
        install(managed, sizes, { it.close() }) { managed.resizeQueue = it }
    }

    /** Check and publication are indivisible with teardown; rejection releases caller-owned resources. */
    private fun <T> install(managed: ManagedSession, resource: T, release: (T) -> Unit, attach: (T) -> Unit): Boolean {
        val accepted = managed.lifecycle.whileOpen { attach(resource); true } == true
        if (!accepted) step { release(resource) }
        return accepted
    }

    /** Registers before scheduling so teardown also owns jobs that have not started. */
    private fun launchOwned(managed: ManagedSession, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        if (managed.lifecycle.adopt(job) { job.cancel() }) {
            job.invokeOnCompletion { managed.lifecycle.detach(job) }
            job.start()
        }
        return job
    }

    /**
     * Re-applies the terminal viewport size to a freshly opened transport.
     *
     * The terminal screen is on-screen throughout host-key confirmation, OTP,
     * and authentication, so its layout can measure the real row/column count
     * and push it to the retained emulator before any PTY exists. That early
     * [resize] reaches a null channel and is dropped, and the emulator's own
     * dimensions then already match the viewport, so the terminal view never
     * emits another resize. Without this the remote stays at the initial
     * [INITIAL_COLUMNS]x[INITIAL_ROWS] and full-screen programs such as tmux
     * paint only the top portion of the screen.
     */
    private fun syncRemoteSizeToViewport(sessionId: String) {
        val dimensions = terminalStore.terminalFor(sessionId)?.dimensions ?: return
        resize(sessionId, dimensions.columns, dimensions.rows)
    }

    /** Returns the retained emulator for a still-live session. */
    fun setVisibleTerminal(sessionId: String?) = terminalStore.setVisibleSession(sessionId)

    fun terminalEmulator(sessionId: String): TerminalEmulator? = terminalStore.terminalFor(sessionId)

    /** Applies a display-only scheme to retained sessions without reconnecting their transports. */
    fun applyTerminalAppearance(appearance: TerminalAppearance) {
        terminalStore.applyAppearance(appearance)
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        val runtimeId = portForwardRuntimeId(sessionId, rule.id)
        val existing = _portForwards.value.firstOrNull { it.runtimeId == runtimeId }
        if (existing?.phase == PortForwardRuntimePhase.ACTIVE || existing?.phase == PortForwardRuntimePhase.STARTING || existing?.phase == PortForwardRuntimePhase.STOPPING) {
            return
        }
        markPortForwardStarting(runtimeId, sessionId, rule, context.appString(R.string.port_forward_starting))
        activatePortForward(sessionId, rule, runtimeId)
    }

    private fun markPortForwardStarting(
        runtimeId: String,
        sessionId: String,
        rule: PortForwardRule,
        detail: String,
    ) {
        val state = PortForwardRuntimeState(
            runtimeId = runtimeId,
            sessionId = sessionId,
            rule = rule,
            phase = PortForwardRuntimePhase.STARTING,
            detail = detail,
        )
        _portForwards.update { current ->
            current.filterNot { existing ->
                existing.runtimeId == runtimeId ||
                    (existing.rule.id == rule.id && existing.phase == PortForwardRuntimePhase.FAILED)
            } + state
        }
    }

    /**
     * Opens the tunnel for a rule already published as starting.
     *
     * This is separate from [startPortForward] because a rule on its own
     * connection is marked starting while that connection authenticates, and
     * the duplicate-start guard would otherwise reject its own placeholder.
     */
    private fun activatePortForward(sessionId: String, rule: PortForwardRule, runtimeId: String) {
        val attempt = _portForwards.value.firstOrNull { it.runtimeId == runtimeId } ?: return
        val managed = sessionsById[sessionId] ?: run {
            // The session was torn down between the starting placeholder and
            // this call, so its own sweep in closePortForwards has already run
            // and will not run again. Leaving the rule starting would strand
            // it: a restart is refused as a duplicate and stopping returns
            // early because the session is gone.
            _portForwards.update { states ->
                states.map { state ->
                    if (state === attempt) state.copy(phase = PortForwardRuntimePhase.STOPPED,
                        detail = context.appString(R.string.port_forward_session_closed)) else state
                }
            }
            return
        }
        val opening = CompletableDeferred<Throwable?>()
        synchronized(managed.sshFeatureLock) {
            if (_portForwards.value.firstOrNull { it.runtimeId == runtimeId } !== attempt) return
            managed.openingForwards[runtimeId] = opening
        }
        var openingFailure: Throwable? = null
        val job = scope.launch {
            try {
                val connection = requireSshFeatureConnection(sessionId)
                val forward = createPortForward(connection, rule)
                val installed = synchronized(managed.sshFeatureLock) {
                    val stillStarting = _portForwards.value.firstOrNull { state -> state.runtimeId == runtimeId } === attempt &&
                        attempt.phase == PortForwardRuntimePhase.STARTING
                    if (
                        sessionsById[sessionId] === managed &&
                        stillStarting &&
                        (managed.protocol != ConnectionProtocol.MOSH || managed.sshFeatureConnection === connection)
                    ) {
                        managed.forwards[runtimeId] = forward
                        // Publishing the phase in the same critical section that
                        // decided the install keeps a concurrent invalidation or
                        // stop from being overwritten by an ACTIVE the rule no
                        // longer deserves.
                        val carrier = managed.forwardCarrier()
                        _portForwards.update { states ->
                            states.map { state ->
                                if (state.runtimeId != runtimeId) state else state.copy(
                                    phase = PortForwardRuntimePhase.ACTIVE,
                                    detail = context.appString(R.string.port_forward_listening),
                                    boundAddress = forward.handle.boundAddress(),
                                    carrier = carrier,
                                    stopOutcome = null,
                                ).withActivity(forward.handle.activity)
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
                if (!installed) {
                    openingFailure = runCatching { forward.close() }.exceptionOrNull()
                    throw CancellationException()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                openingFailure = error
                synchronized(managed.sshFeatureLock) {
                    if (_portForwards.value.firstOrNull { it.runtimeId == runtimeId } === attempt)
                        updatePortForward(runtimeId, PortForwardRuntimePhase.FAILED, error.toSafeMessage())
                }
                // A connection opened only for this forward has nothing left to do.
                closePortForwardSessionIfIdle(sessionId)
            }
        }
        job.invokeOnCompletion {
            managed.openingForwards.remove(runtimeId, opening)
            opening.complete(openingFailure)
        }
    }

    /**
     * Starts [rule] on a connection opened just for it.
     *
     * A forward no longer depends on an open terminal for the same host. The
     * runtime state is published before the connection exists so the rule shows
     * as starting while authentication runs, and the connection closes again as
     * soon as the forward stops.
     */
    fun startPortForwardOnNewConnection(profile: ConnectionProfile, rule: PortForwardRule) {
        val sessionId = connectForPortForward(profile)
        val runtimeId = portForwardRuntimeId(sessionId, rule.id)
        markPortForwardStarting(runtimeId, sessionId, rule, context.appString(R.string.session_connecting))
        scope.launch {
            val open = awaitSessionOpen(sessionId)
            // Stopping the rule while it authenticates already closed the
            // connection, and that is not a failure to report.
            val stillStarting = _portForwards.value
                .firstOrNull { it.runtimeId == runtimeId }
                ?.phase == PortForwardRuntimePhase.STARTING
            if (!stillStarting) return@launch
            if (open) {
                activatePortForward(sessionId, rule, runtimeId)
            } else {
                // The session-ended collector reports why the connection failed.
                updatePortForward(
                    runtimeId,
                    PortForwardRuntimePhase.FAILED,
                    context.appString(R.string.session_ended_connection_failed),
                )
            }
        }
    }

    /** Suspends until [sessionId] authenticates, returning false when it ended first. */
    private suspend fun awaitSessionOpen(sessionId: String): Boolean = _sessions
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .first { it == null || it.phase == TerminalSessionPhase.OPEN }
        ?.phase == TerminalSessionPhase.OPEN

    fun stopPortForward(sessionId: String, ruleId: String) {
        val runtimeId = portForwardRuntimeId(sessionId, ruleId)
        val managed = sessionsById[sessionId]
        if (managed == null) return
        val (forward, opening) = synchronized(managed.sshFeatureLock) {
            val phase = _portForwards.value.firstOrNull { it.runtimeId == runtimeId }?.phase
            if (phase != PortForwardRuntimePhase.ACTIVE && phase != PortForwardRuntimePhase.STARTING) return
            updatePortForward(runtimeId, PortForwardRuntimePhase.STOPPING, context.appString(R.string.port_forward_stopping))
            managed.forwards.remove(runtimeId) to managed.openingForwards[runtimeId]
        }
        // Remote cancellation waits for a protocol response. Never execute it on the caller's thread.
        cleanupScope.launch {
          val openingFailure = opening?.await()
          closeForwardInBackground(cleanupScope, { forward?.close() }) { closeFailure ->
            val failure = closeFailure ?: openingFailure
            synchronized(managed.sshFeatureLock) {
                if (sessionsById[sessionId] === managed &&
                    _portForwards.value.firstOrNull { it.runtimeId == runtimeId }?.phase == PortForwardRuntimePhase.STOPPING) {
                    val outcome = if (failure == null) forward?.stopOutcome ?: PortForwardStopOutcome.STOPPED else null
                    _portForwards.update { states ->
                        states.map { state ->
                            if (state.runtimeId != runtimeId) state else state.copy(
                                phase = if (outcome == null) PortForwardRuntimePhase.FAILED else PortForwardRuntimePhase.STOPPED,
                                detail = context.appString(when (outcome) {
                                    null -> R.string.port_forward_stop_failed
                                    PortForwardStopOutcome.UNCONFIRMED -> R.string.port_forward_stop_unconfirmed
                                    PortForwardStopOutcome.STOPPED -> R.string.port_forward_stopped
                                }),
                                stopOutcome = outcome,
                                activeConnections = 0,
                            )
                        }
                    }
                }
            }
            closePortForwardSessionIfIdle(sessionId)
          }.join()
        }
    }

    /**
     * Closes a forwarding-only connection once nothing is listening on it.
     *
     * Such a session exists only to carry forwards, so unlike a browser
     * connection it needs no explicit release from the UI.
     */
    private fun closePortForwardSessionIfIdle(sessionId: String) {
        val managed = sessionsById[sessionId] ?: return
        if (managed.kind != SessionKind.PORT_FORWARD) return
        val busy = _portForwards.value.any { state ->
            state.sessionId == sessionId &&
                (
                    state.phase == PortForwardRuntimePhase.ACTIVE ||
                        state.phase == PortForwardRuntimePhase.STARTING || state.phase == PortForwardRuntimePhase.STOPPING
                    )
        }
        if (!busy) finishSession(sessionId, managed, SessionEndReason.USER_REQUEST)
    }

    /** Resolves the directory the session's account starts in, for the file browser. */
    suspend fun resolveRemoteHome(sessionId: String): String = withContext(Dispatchers.IO) {
        remoteFiles.resolveHome(requireSshFeatureConnection(sessionId))
    }

    /** Lists one remote directory for the file browser. */
    suspend fun listRemoteDirectory(sessionId: String, path: String): RemoteDirectoryListing =
        withContext(Dispatchers.IO) {
            remoteFiles.list(
                connection = requireSshFeatureConnection(sessionId),
                sessionId = sessionId,
                path = path,
                maxEntries = MAX_REMOTE_DIRECTORY_ENTRIES,
            )
        }

    /** Resolves what a remote path actually is after following symlinks. */
    suspend fun resolveRemoteKind(sessionId: String, path: String): RemoteFileKind =
        withContext(Dispatchers.IO) {
            remoteFiles.statKind(requireSshFeatureConnection(sessionId), path)
        }

    /**
     * Reads the leading bytes of a remote file for a read-only preview.
     *
     * Returns null when the content is not valid UTF-8 text.
     */
    suspend fun readRemoteTextPreview(sessionId: String, path: String): RemoteTextPreview? =
        withContext(Dispatchers.IO) {
            remoteFiles.readTextPreview(
                connection = requireSshFeatureConnection(sessionId),
                path = path,
                maxBytes = MAX_REMOTE_PREVIEW_BYTES,
            )
        }

    /**
     * Downloads a browsed remote file over SFTP into a caller-selected document.
     *
     * The path never reaches a remote shell, so spaces and other punctuation
     * are supported, and the read loop reports byte progress.
     */
    fun downloadRemoteFile(sessionId: String, remotePath: String, destinationUri: Uri) {
        fileTransfers.downloadFile(sessionId, remotePath, destinationUri)
    }

    /** Downloads a browsed remote directory into a caller-selected folder. */
    fun downloadRemoteDirectory(sessionId: String, remotePath: String, destinationTreeUri: Uri) {
        fileTransfers.downloadDirectory(sessionId, remotePath, destinationTreeUri)
    }

    /**
     * Uploads a caller-selected document into a browsed remote directory over
     * SFTP. The stream is written straight through, so no copy is staged in
     * the application cache first.
     */
    fun uploadRemoteFile(
        sessionId: String,
        sourceUri: Uri,
        displayName: String,
        remoteDirectory: String,
    ) {
        fileTransfers.uploadFile(sessionId, sourceUri, displayName, remoteDirectory)
    }

    /** Uploads a caller-selected local folder into a browsed remote directory. */
    fun uploadRemoteDirectory(
        sessionId: String,
        sourceTreeUri: Uri,
        displayName: String,
        remoteDirectory: String,
    ) {
        fileTransfers.uploadDirectory(sessionId, sourceTreeUri, remoteDirectory, displayName)
    }

    /** Stops a running transfer at the next chunk boundary, keeping its offset. */
    fun pauseTransfer(transferId: String) = fileTransfers.pause(transferId)

    /** Continues a paused transfer on the connection it started on. */
    fun resumeTransfer(transferId: String) = fileTransfers.resume(transferId)

    /** Stops a queued, running, or paused transfer for good. */
    fun cancelTransfer(transferId: String) = fileTransfers.cancel(transferId)

    /** Re-runs a failed or cancelled transfer from the beginning. */
    fun retryTransfer(transferId: String) = fileTransfers.retry(transferId)

    /** Drops finished transfer records; unfinished transfers are kept. */
    fun clearFinishedTransfers() = fileTransfers.clearFinished()

    /** Fixed application wording for a failure raised by a remote file operation. */
    fun remoteFileMessage(error: Throwable): RemoteFileMessage = error.toRemoteFileMessage()

    fun requestServerResources(sessionId: String) {
        scope.launch {
            _resourceSnapshots.update { snapshots -> snapshots - sessionId }
            try {
                val connection = requireSshFeatureConnection(sessionId)
                val report = collectServerResourceReport(connection)
                if (sessionsById.containsKey(sessionId)) {
                    _resourceSnapshots.update { snapshots ->
                        snapshots + (sessionId to ServerResourceSnapshot(sessionId = sessionId, report = report))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!sessionsById.containsKey(sessionId)) return@launch
                val message = context.appString(R.string.terminal_resource_query_failed)
                _resourceSnapshots.update { snapshots ->
                    snapshots + (sessionId to ServerResourceSnapshot(sessionId = sessionId, report = message))
                }
                publishLocalizableNotice(
                    sessionId,
                    "\r\n[MangoSSH] $message\r\n",
                )
            }
        }
    }

    /** Explicitly closes one live transport at the user's request. */
    fun close(sessionId: String) {
        val managed = sessionsById[sessionId] ?: return
        finishSession(sessionId, managed, SessionEndReason.USER_REQUEST)
    }

    /**
     * Ends every connection when Android refuses the foreground ownership that
     * is required to keep it alive outside the activity.
     */
    internal fun onForegroundServiceUnavailable(error: RuntimeException) {
        sessionsById.entries.toList().forEach { (sessionId, managed) ->
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                messageKind = SessionEndMessageKind.FOREGROUND_SERVICE_UNAVAILABLE,
            )
        }
    }

    /**
     * Tears down every session and immediately releases embedded tsnet leases.
     *
     * The controller scope is cancelled at the end, so the normal delayed
     * Mosh release must not be scheduled here or its cleanup would be lost.
     */
    fun clear() {
        sessionsById.toMap().forEach { (sessionId, managed) ->
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.USER_REQUEST,
                deferTsnetMoshRelease = false,
            )
        }
        // Waiters registered but not yet published survive the per-session
        // teardown above only in a narrow race; releasing them here keeps the
        // shutdown from leaving a protocol thread parked in a prompt.
        promptRegistry.cancelAll()
        _prompts.value = emptyList()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }

    /**
     * Performs all terminal cleanup exactly once.
     *
     * `sessionsById.remove(sessionId, managed)` is the lifecycle gate: reader
     * coroutines, a user close action, and connection setup failures may race,
     * but only the first caller is allowed to stop forwards, remove terminal
     * state, and withdraw notifications.
     *
     * Every step is individually guarded. Most callers invoke this as the last
     * statement of a coroutine or of their own `catch` block, so a throw here
     * would have nothing left to catch it and would take the process down along
     * with every unrelated session.
     */
    private fun finishSession(
        sessionId: String,
        managed: ManagedSession,
        reason: SessionEndReason,
        failure: Throwable? = null,
        messageKind: SessionEndMessageKind? = null,
        deferTsnetMoshRelease: Boolean = true,
    ) {
        val callbacks = synchronized(managed.lifecycle.lock) {
            val detached = managed.lifecycle.close() ?: return
            sessionsById.remove(sessionId, managed)
            if (managed.kind == SessionKind.TERMINAL) {
                val state = _sessions.value.firstOrNull { it.id == sessionId }
                if (state != null) synchronized(_endedTerminals) {
                    val record = EndedTerminalRecord(state.copy(phase = TerminalSessionPhase.CLOSED), reason, messageKind,
                        diagnostics = diagnosticSnapshot(managed, state.phase).copy(failure = messageKind, moshRunning = if (managed.protocol == ConnectionProtocol.MOSH) false else null, companionConnected = if (managed.protocol == ConnectionProtocol.MOSH) false else null))
                    val records = listOf(record) + _endedTerminals.value
                    _endedTerminals.value = records.take(10)
                    records.drop(10).forEach { terminalStore.remove(it.session.id) }
                }
            }
            detached
        }
        val gracefulMosh = reason == SessionEndReason.USER_REQUEST && managed.moshProcess != null
        if (!gracefulMosh) managed.writer?.close()
        managed.resizeQueue?.close()
        callbacks.forEach { step(it) }
        // Cleanup survives clear(); worker cancellation cannot abandon native reaping.
        cleanupScope.launch {

        // Withdraw the session before anything else. The terminal screen is
        // reached through this list, so a single update makes it leave, taking
        // its emulator view and any prompt dialog with it as one coherent
        // change. Releasing a prompt waiter first would instead retire the
        // dialog on its own and make the screen change shape twice for one
        // disconnect.
        step { _sessions.update { sessions -> sessions.filterNot { it.id == sessionId } } }

        // Then unblock any protocol thread parked on a host-key or
        // authentication prompt; it can no longer answer anything useful.
        step { releasePromptWaiters(sessionId) }
        step { _prompts.update { prompts -> prompts.filterNot { it.sessionId == sessionId } } }

        step { managed.connectionJob?.cancel() }
        step { managed.keepaliveJob?.cancel() }
        step { managed.sshFeatureKeepaliveJob?.cancel() }
        step { managed.readerJobs.toList().forEach(Job::cancel) }
        step { managed.connection.close() }
        val jumps = synchronized(managed.lifecycle.lock) { managed.jumpConnections.toList().asReversed().also { managed.jumpConnections.clear() } }
        jumps.forEach { connection -> step { connection.close() } }
        step { managed.sshFeatureConnection?.close() }
        step { closePortForwards(sessionId, managed) }
        step { managed.session?.close() }
        step {
            managed.moshProcess?.let { process ->
                if (reason == SessionEndReason.USER_REQUEST) {
                    process.closeGracefully { sequence -> managed.writer?.finish(sequence) == true }
                    cleanupScope.launch { delay(TSNET_MOSH_GRACEFUL_RELEASE_MILLIS); managed.writer?.close() }
                } else {
                    process.close()
                    // A non-user termination cancels the reader, so reap the
                    // direct native child separately from its normal EOF path.
                    runCatching { process.awaitExit() }
                }
            }
        }
        val closeEmbeddedTsnet = {
            step { managed.tsnetUdpRelay?.close() }
            step { managed.tsnetLease?.close() }
        }
        val sshFeatureConnection = synchronized(managed.sshFeatureLock) {
            managed.sshFeatureConnection.also { managed.sshFeatureConnection = null }
        }
        step { sshFeatureConnection?.close() }
        if (sshFeatureConnection !== managed.connection) {
            step { managed.connection.close() }
        }
        if (
            managed.protocol == ConnectionProtocol.MOSH &&
            reason == SessionEndReason.USER_REQUEST &&
            managed.moshProcess != null &&
            deferTsnetMoshRelease
        ) {
            // Keep the loopback UDP relay alive through Mosh's short graceful
            // quit window, then release the final process-wide tsnet reference.
            step {
                cleanupScope.launch {
                    delay(TSNET_MOSH_GRACEFUL_RELEASE_MILLIS)
                    closeEmbeddedTsnet()
                }
            }
        } else {
            closeEmbeddedTsnet()
        }

        step { _resourceSnapshots.update { snapshots -> snapshots - sessionId } }
        step { fileTransfers.onSessionEnded(sessionId) }
        // The emulator is released only after the screen has stopped reading it,
        // which the session withdrawal above already guaranteed.
        if (managed.kind != SessionKind.TERMINAL) step { terminalStore.remove(sessionId) }
        step {
            _sessionEndedEvents.tryEmit(
                SessionEndedEvent(
                    sessionId = sessionId,
                    reason = reason,
                    messageKind = messageKind,
                ),
            )
        }

        when (reason) {
            SessionEndReason.USER_REQUEST -> MangoLog.info(MangoLogEvent.SSH_SESSION_CLOSED)
            SessionEndReason.REMOTE_EXIT -> MangoLog.info(MangoLogEvent.SSH_SESSION_REMOTE_EXIT)

            SessionEndReason.CONNECTION_LOST,
            SessionEndReason.CONNECTION_FAILED -> MangoLog.warn(MangoLogEvent.SSH_SESSION_FAILED, failure)
        }
        // SessionForegroundService observes the now-empty StateFlow and owns
        // its shutdown. Calling stopService here could cancel a pending
        // startForegroundService request before onCreate acknowledges it,
        // leaving Android's foreground-service watchdog armed.
        }
    }

    /**
     * Runs one teardown step, reporting rather than propagating a failure.
     *
     * A single uncooperative resource must not skip the remaining cleanup, and
     * teardown itself must never be the thing that ends the process.
     */
    private fun step(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            MangoLog.warn(MangoLogEvent.SESSION_TEARDOWN_FAILED, error)
        }
    }

    /**
     * Completes any host-key or authentication waiters so background jobs cannot hang.
     *
     * The waiters are cancelled through the registry rather than through the
     * published prompt list, because a prompt is registered before it is
     * published: draining the visible list would miss a prompt raised in that
     * window and strand its protocol thread until the prompt timeout expired.
     */
    private fun releasePromptWaiters(sessionId: String) {
        val released = promptRegistry.cancelSession(sessionId)
        if (released > 0) MangoLog.info(MangoLogEvent.SESSION_PROMPT_ABANDONED)
    }

    private suspend fun runSshSession(
        sessionId: String,
        profile: ConnectionProfile,
        managed: ManagedSession,
    ) {
        if (sessionsById[sessionId] !== managed) return
        val snapshot = managed.initialSnapshot
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            connection.banner = { banner ->
                    publishNotice(sessionId, "\r\n[MangoSSH] ${banner.sanitizeRemoteBanner()}\r\n")
                }
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.appString(R.string.session_verifying_host_key),
            )
            connection.connect((managed.preferences.connectTimeoutSeconds * 1_000).toLong() + KEY_EXCHANGE_TIMEOUT_MILLIS, transportTimeoutMillis = managed.preferences.connectTimeoutSeconds * 1_000L) { algorithm, blob -> HostKeyVerifier(sessionId, profile, snapshot.knownHosts).verifyServerHostKey(connection.hostname, connection.port, algorithm, blob) }

            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.appString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException()
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            val session = connection.openChannel()
            // Host-key verification and authentication can take minutes, so the
            // session may already have been torn down. Storing the channel now
            // would attach a live resource to a ManagedSession nobody will ever
            // clean up again.
            if (sessionsById[sessionId] !== managed) {
                runCatching { session.close() }
                return
            }
            if (!install(managed, session, { it.close() }) { managed.session = it }) return
            session.pty(
                managed.preferences.sshTerminalType.termValue,
                INITIAL_COLUMNS,
                INITIAL_ROWS,
            )
            if (profile.agentForwarding) {
                val grant = website.sung.mangossh.security.AgentGrant(profile.agentPolicy, accessState)
                val agent = VaultSshAgent(
                    snapshot.keys.filter { it.id in (profile.agentPolicy.allowedKeyIds ?: listOfNotNull(profile.keyId)) },
                    keyManager,
                    authorized = { !accessState.locked.value && managed.lifecycle.isOpen },
                    authorizeSignature = {
                        grant.authorize {
                            requestPrompt(SessionPrompt.Authentication(UUID.randomUUID().toString(), sessionId,
                                SessionPromptText.App(SessionPromptTextKind.AGENT_TITLE, profile.label),
                                SessionPromptText.App(SessionPromptTextKind.AGENT_INSTRUCTION), emptyList())) != null
                        }
                    },
                )
                connection.enableAgent(agent)
                val enabled = session.requestAgentForwarding()
                if (!enabled) {
                    publishLocalizableNotice(
                        sessionId,
                        "\r\n[MangoSSH] ${context.appString(R.string.terminal_agent_forwarding_rejected)}\r\n",
                    )
                }
            }
            val workspaceId = prepareWorkspace(sessionId, managed, connection)
            if (workspaceId == null) session.shell() else session.execute(TmuxWorkspaces.attachCommand(workspaceId))

            if (sessionsById[sessionId] !== managed) return

            attachTerminalTransport(sessionId, managed, session.stdin)
            runStartupSnippet(sessionId, profile, snapshot)
            managed.lifecycle.whileOpen { managed.inputReady = true }
            updateSession(sessionId, TerminalSessionPhase.OPEN, context.appString(R.string.session_open))
            MangoLog.info(MangoLogEvent.SSH_SESSION_OPENED)
            syncRemoteSizeToViewport(sessionId)
            startSshReaders(sessionId, managed)
            startSshKeepalive(sessionId, managed)
            offerInterruptedTransfers(sessionId, managed)
            snapshot.portForwards
                .filter { it.profileId == profile.id && it.startOnConnect }
                .forEach { rule -> startPortForward(sessionId, rule) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                messageKind = connectionFailureMessage(error),
            )
        }
    }

    /**
     * Authenticates a transfer-only connection and stops before any shell work.
     *
     * No authentication banner callback is registered because this session has
     * no terminal to display one in, and no startup snippet or auto-start port
     * forward runs: those belong to interactive terminal sessions.
     */
    /** Connects and authenticates without opening a shell, for file transfer and port forwarding. */
    private suspend fun runShellessSession(
        sessionId: String,
        profile: ConnectionProfile,
        managed: ManagedSession,
    ) {
        if (sessionsById[sessionId] !== managed) return
        val snapshot = managed.initialSnapshot
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.appString(R.string.session_verifying_host_key),
            )
            connection.connect((managed.preferences.connectTimeoutSeconds * 1_000).toLong() + KEY_EXCHANGE_TIMEOUT_MILLIS, transportTimeoutMillis = managed.preferences.connectTimeoutSeconds * 1_000L) { algorithm, blob -> HostKeyVerifier(sessionId, profile, snapshot.knownHosts).verifyServerHostKey(connection.hostname, connection.port, algorithm, blob) }

            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.appString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException()
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            if (sessionsById[sessionId] !== managed) return

            updateSession(sessionId, TerminalSessionPhase.OPEN, context.appString(R.string.session_open))
            MangoLog.info(MangoLogEvent.SSH_SESSION_OPENED)
            startSshKeepalive(sessionId, managed)
            offerInterruptedTransfers(sessionId, managed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                messageKind = connectionFailureMessage(error),
            )
        }
    }

    /**
     * Runs the SSH-only Mosh bootstrap, then replaces that transport with the
     * bundled native UDP client. The SSH connection is intentionally closed
     * after `MOSH CONNECT`: Mosh's design does not keep an SSH channel open.
     */
    private suspend fun runMoshSession(
        sessionId: String,
        profile: ConnectionProfile,
        managed: ManagedSession,
    ) {
        if (sessionsById[sessionId] !== managed) return
        val snapshot = managed.initialSnapshot
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            connection.banner = { banner ->
                    publishNotice(sessionId, "\r\n[MangoSSH] ${banner.sanitizeRemoteBanner()}\r\n")
                }
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.appString(R.string.session_verifying_host_key),
            )
            connection.connect((managed.preferences.connectTimeoutSeconds * 1_000).toLong() + KEY_EXCHANGE_TIMEOUT_MILLIS, transportTimeoutMillis = managed.preferences.connectTimeoutSeconds * 1_000L) { algorithm, blob -> HostKeyVerifier(sessionId, profile, snapshot.knownHosts).verifyServerHostKey(connection.hostname, connection.port, algorithm, blob) }
            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.appString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException()
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            updateSession(
                sessionId,
                TerminalSessionPhase.CONNECTING,
                context.appString(R.string.mosh_connecting),
            )
            MangoLog.info(MangoLogEvent.MOSH_BOOTSTRAP_STARTED)
            val bootstrap = runCatching { bootstrapMosh(connection, prepareWorkspace(sessionId, managed, connection)) }.getOrElse { error ->
                MangoLog.warn(MangoLogEvent.MOSH_BOOTSTRAP_FAILED, error)
                throw MoshBootstrapException(error)
            }
            MangoLog.info(MangoLogEvent.MOSH_BOOTSTRAP_SUCCEEDED)

            val relay = if (profile.route == ConnectionRoute.TSNET) {
                val lease = requireNotNull(managed.tsnetLease)
                val started = lease.startUdpRelay(profile.hostname, bootstrap.port)
                // The relay must only become the session's if the session is
                // still the live one; otherwise teardown already ran past the
                // point where it would have closed it.
                if (sessionsById[sessionId] !== managed) {
                    runCatching { started.close() }
                    return
                }
                if (!install(managed, started, { it.close() }) { managed.tsnetUdpRelay = it }) return
                started
            } else {
                null
            }
            val moshProcess = try {
                runCatching {
                    MoshPtyProcess.start(
                        context = context,
                        host = if (relay == null) profile.hostname else TSNET_LOOPBACK_HOST,
                        port = relay?.localPort ?: bootstrap.port,
                        key = bootstrap.key,
                        terminalColumns = INITIAL_COLUMNS,
                        terminalRows = INITIAL_ROWS,
                    )
                }.getOrElse { error ->
                    MangoLog.warn(MangoLogEvent.MOSH_RUNTIME_INSTALL_FAILED, error)
                    throw MoshRuntimeException(error)
                }
            } finally {
                // The native launcher consumes this one-shot UDP session key.
                // Clear the Java copy even when an asset or JNI check fails.
                bootstrap.key.fill('\u0000')
            }
            // A native child adopted by a session that no longer exists would
            // never be signalled or reaped, so stop it here instead.
            if (sessionsById[sessionId] !== managed) {
                runCatching { moshProcess.close() }
                runCatching { moshProcess.awaitExit() }
                return
            }
            if (!install(managed, moshProcess, { it.close(); it.awaitExit() }) { managed.moshProcess = it }) return
            MangoLog.info(MangoLogEvent.MOSH_PROCESS_STARTED)

            attachTerminalTransport(sessionId, managed, moshProcess.output)
            runStartupSnippet(sessionId, profile, snapshot)
            managed.lifecycle.whileOpen { managed.inputReady = true }
            updateSession(sessionId, TerminalSessionPhase.OPEN, context.appString(R.string.mosh_open))
            syncRemoteSizeToViewport(sessionId)
            startMoshReader(sessionId, managed, moshProcess)
            attachMoshSshFeatureConnection(sessionId, managed, connection)
            offerInterruptedTransfers(sessionId, managed)
            snapshot.portForwards
                .filter { it.profileId == profile.id && it.startOnConnect }
                .forEach { rule -> startPortForward(sessionId, rule) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                messageKind = connectionFailureMessage(error),
            )
        }
    }

    /** Returns only fixed localized wording for failure categories that need clarification. */
    private fun connectionFailureMessage(error: Throwable): SessionEndMessageKind? = when (error) {
        is SshAuthenticationException -> SessionEndMessageKind.AUTHENTICATION_FAILED
        is website.sung.mangossh.data.keys.UnsupportedDsaKeyException -> SessionEndMessageKind.DSA_KEY_UNSUPPORTED
        is website.sung.mangossh.data.keys.UnsupportedKeyEncryptionException -> SessionEndMessageKind.KEY_ENCRYPTION_UNSUPPORTED
        is MoshBootstrapException -> SessionEndMessageKind.MOSH_BOOTSTRAP_FAILED
        is MoshRuntimeException -> SessionEndMessageKind.MOSH_RUNTIME_MISSING
        is TsnetEnrollmentRequiredException -> SessionEndMessageKind.TSNET_ENROLLMENT_REQUIRED
        else -> null
    }

    /** Authenticates explicit ordered hops; every resource belongs to the final session. */
    private suspend fun prepareConnectionRoute(profile: ConnectionProfile, managed: ManagedSession) {
        if (profile.jumpProfileIds.isEmpty()) {
            prepareDirectRoute(profile, managed, managed.connection)
            return
        }
        require(profile.protocol == ConnectionProtocol.SSH && profile.jumpProfileIds.size <= 4)
        require(profile.id !in profile.jumpProfileIds && profile.jumpProfileIds.distinct().size == profile.jumpProfileIds.size)
        val snapshot = managed.initialSnapshot
        val sessionId = sessionsById.entries.firstOrNull { it.value === managed }?.key ?: throw CancellationException()
        var previous: SshConnection? = null
        for (id in profile.jumpProfileIds) {
            val hop = snapshot.profiles.single { it.id == id }
            require(hop.protocol == ConnectionProtocol.SSH && hop.jumpProfileIds.isEmpty())
            val connection = SshConnection(hop.hostname, hop.port, hop.legacySshAlgorithms)
            if (!install(managed, connection, { it.close(); it.close() }) { managed.jumpConnections += it }) throw CancellationException()
            connection.monitor { error -> cleanupScope.launch {
                finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, error)
            } }
            if (previous == null) {
                managed.configuredRoute = hop.route
                prepareDirectRoute(hop, managed, connection)
            } else connection.useJump(previous)
            val preferences = hop.overrides.resolve(managed.defaultPreferences)
            connection.connect(preferences.connectTimeoutSeconds * 1_000L + KEY_EXCHANGE_TIMEOUT_MILLIS, transportTimeoutMillis = preferences.connectTimeoutSeconds * 1_000L) { algorithm, blob -> HostKeyVerifier(sessionId, hop, snapshot.knownHosts).verifyServerHostKey(connection.hostname, connection.port, algorithm, blob) }
            if (!authenticate(connection, sessionId, hop, snapshot)) throw SshAuthenticationException()
            if (!managed.lifecycle.isOpen) throw CancellationException()
            previous = connection
        }
        managed.connection.useJump(requireNotNull(previous))
    }

    /** Attaches tsnet's authenticated loopback SOCKS5 transport before SSH connects. */
    private suspend fun prepareDirectRoute(
        profile: ConnectionProfile,
        managed: ManagedSession,
        connection: SshConnection,
    ) {
        if (profile.route != ConnectionRoute.TSNET) return
        val lease = embeddedTsnetManager.acquire()
        if (sessionsById.values.none { it === managed }) {
            lease.close()
            throw CancellationException()
        }
        if (!install(managed, lease, { it.close() }) { managed.tsnetLease = it }) throw CancellationException()
        launchOwned(managed) {
            lease.invalidated.await()
            finishSession(sessionsById.entries.firstOrNull { it.value === managed }?.key ?: return@launchOwned,
                managed, SessionEndReason.CONNECTION_LOST)
        }
        connection.useSocketRoute(lease.proxyData)
    }

    private suspend fun authenticate(connection: SshConnection, sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot): Boolean =
        SshAuthentication(keyManager, ::requestAuthentication).authenticate(connection, sessionId, profile, snapshot)

    /**
     * Starts stdout and stderr readers for an interactive SSH shell.
     *
     * Both streams need to reach EOF for a normal shell exit. A read failure
     * closes the sibling stream immediately so a broken transport cannot leave
     * an orphaned foreground notification behind.
     */
    private fun startSshReaders(sessionId: String, managed: ManagedSession) {
        val session = managed.session ?: return
        managed.readerJobs += launchOwned(managed) {
            onSshStreamEnded(
                sessionId,
                managed,
                readStream(sessionId, session.stdout),
            )
        }
        managed.readerJobs += launchOwned(managed) {
            onSshStreamEnded(
                sessionId,
                managed,
                readStream(sessionId, session.stderr),
            )
        }
    }

    /** The verified server a transfer on this session belongs to; null until its host key is known. */
    private fun ManagedSession.transferIdentity(): TransferHostIdentity? = verifiedHostKey?.let { hostKey ->
        TransferHostIdentity(profile.id, profile.hostname, profile.port, profile.username,
            profile.route, profile.jumpProfileIds, hostKey)
    }

    /**
     * Lets transfers interrupted on an earlier session to the same server continue on
     * this one. Port-forward-only sessions close with their forwards, so they never
     * take transfers over.
     */
    private fun offerInterruptedTransfers(sessionId: String, managed: ManagedSession) {
        if (managed.kind == SessionKind.PORT_FORWARD) return
        fileTransfers.onSessionOpened(sessionId, managed.transferIdentity(), managed.sshFeatureConnection)
    }

    /** Re-read before every background wait so a settings change reaches running sessions. */
    private fun ManagedSession.liveBackgroundMultiplier(): Int =
        profile.overrides.liveBackgroundMultiplier(connectionPreferencesStore.current())

    /**
     * Keeps an authenticated SSH transport visible to idle network devices.
     *
     * Mosh sessions are excluded because their SSH transport closes after the
     * UDP bootstrap and the native Mosh client owns its own network lifecycle.
     * A keepalive interval of zero means the user disabled keepalives, so no
     * job is started; [runSshKeepaliveLoop] requires a positive interval.
     */
    private fun startSshKeepalive(sessionId: String, managed: ManagedSession) {
        managed.connection.monitor { reason ->
            cleanupScope.launch { finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, reason) }
        }
        val keepaliveSeconds = managed.preferences.keepaliveSeconds
        if (keepaliveSeconds <= 0) return
        managed.keepaliveJob = launchOwned(managed) {
            runSshKeepaliveLoop(
                intervalMillis = keepaliveSeconds * 1_000L,
                isSessionActive = { sessionsById[sessionId] === managed },
                sendKeepalive = { managed.connection.keepalive(); managed.lastConfirmedNanos = System.nanoTime() },
                onFailure = { error ->
                    MangoLog.warn(MangoLogEvent.SSH_KEEPALIVE_FAILED, error)
                    finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, error)
                },
                waitForNextKeepalive = { interval -> keepaliveScheduler.waitForNextKeepalive(interval, managed.liveBackgroundMultiplier()) },
            )
        }
    }

    /**
     * Watches Mosh's authenticated companion SSH without coupling its failure
     * to the UDP terminal. Only SSH-backed features are invalidated; the native
     * Mosh process remains the terminal lifecycle owner.
     *
     * The transport monitor is what actually notices a dead peer: a keepalive
     * write lands in the socket buffer and succeeds long after the far end is
     * gone, so waiting for one to throw would leave a corpse installed until
     * the operation using it timed out. The keepalive stays as the prod that
     * makes the transport discover the loss. If the user disabled keepalives
     * (interval of zero), dead-peer detection falls back to the connection
     * monitor alone, so a loss may take longer to surface.
     */
    private fun attachMoshSshFeatureConnection(
        sessionId: String,
        managed: ManagedSession,
        connection: SshConnection,
    ) {
        connection.monitor { reason ->
            // The transport thread must not run teardown that closes channels
            // and sockets, so hand the invalidation to the session scope.
            scope.launch {
                if (invalidateMoshSshFeatureConnection(sessionId, managed, connection)) {
                    MangoLog.warn(MangoLogEvent.MOSH_COMPANION_SSH_DISCONNECTED, reason)
                }
            }
        }
        managed.sshFeatureKeepaliveJob?.cancel()
        val keepaliveSeconds = managed.preferences.keepaliveSeconds
        if (keepaliveSeconds <= 0) return
        managed.sshFeatureKeepaliveJob = launchOwned(managed) {
            runSshKeepaliveLoop(
                intervalMillis = keepaliveSeconds * 1_000L,
                isSessionActive = {
                    sessionsById[sessionId] === managed &&
                        synchronized(managed.sshFeatureLock) {
                            managed.sshFeatureConnection === connection
                        }
                },
                sendKeepalive = { connection.keepalive(); managed.lastConfirmedNanos = System.nanoTime() },
                onFailure = { error ->
                    if (invalidateMoshSshFeatureConnection(sessionId, managed, connection)) {
                        MangoLog.warn(MangoLogEvent.MOSH_COMPANION_SSH_DISCONNECTED, error)
                    }
                },
                waitForNextKeepalive = { interval -> keepaliveScheduler.waitForNextKeepalive(interval, managed.liveBackgroundMultiplier()) },
            )
        }
    }

    /**
     * Reads the single PTY stream used by Mosh and reaps the native child after
     * EOF. A process that exits on its own is represented as a closed terminal
     * rather than leaving a stale foreground notification behind.
     */
    private fun startMoshReader(
        sessionId: String,
        managed: ManagedSession,
        process: MoshPtyProcess,
    ) {
        managed.readerJobs += launchOwned(managed) {
            try {
                val streamEnd = readStream(sessionId, process.input)
                if (sessionsById[sessionId] !== managed) return@launchOwned
                val childExited = runCatching { process.awaitExit() }
                val reason = if (streamEnd is StreamEnd.EOF && childExited.isSuccess) {
                    SessionEndReason.REMOTE_EXIT
                } else {
                    SessionEndReason.CONNECTION_LOST
                }
                if (reason == SessionEndReason.REMOTE_EXIT) {
                    MangoLog.info(MangoLogEvent.MOSH_PROCESS_STOPPED)
                }
                finishSession(sessionId, managed, reason, childExited.exceptionOrNull())
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun onSshStreamEnded(
        sessionId: String,
        managed: ManagedSession,
        streamEnd: StreamEnd,
    ) {
        when (streamEnd) {
            is StreamEnd.Failed -> finishSession(
                sessionId,
                managed,
                SessionEndReason.CONNECTION_LOST,
                streamEnd.error,
            )

            StreamEnd.EOF -> {
                if (managed.completedReaderCount.incrementAndGet() == SSH_STREAM_COUNT) {
                    finishSession(sessionId, managed, SessionEndReason.REMOTE_EXIT)
                }
            }
        }
    }

    /** Reads transport bytes until EOF or a failure and preserves the distinction for lifecycle cleanup. */
    private fun readStream(sessionId: String, input: InputStream): StreamEnd {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) terminalStore.append(sessionId, buffer.copyOf(count))
            }
            return StreamEnd.EOF
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Reported rather than propagated: the caller turns this into the
            // session's end reason, and a reader that threw instead would leave
            // the transport running with no one left to close it.
            return StreamEnd.Failed(error)
        }
    }

    /** Sends an explicitly selected startup snippet after either transport becomes interactive. */
    private fun runStartupSnippet(sessionId: String, profile: ConnectionProfile, snapshot: VaultSnapshot) {
        val snippet = profile.startupSnippetId?.let { id -> snapshot.snippets.firstOrNull { it.id == id } } ?: return
        val text = if (snippet.appendNewline && !snippet.script.endsWith('\n')) {
            snippet.script + "\n"
        } else {
            snippet.script
        }
        sessionsById[sessionId]?.writer?.send(text.encodeToByteArray())
    }

    private suspend fun createPortForward(connection: SshConnection, rule: PortForwardRule): ManagedPortForward {
        require(rule.bindPort in 1..65535) { "Invalid listen port" }
        val bindAddress = InetSocketAddress(rule.bindHost.ifBlank { "127.0.0.1" }, rule.bindPort)
        return when (rule.type) {
            PortForwardType.LOCAL -> {
                val (destinationHost, destinationPort) = rule.requireDestination()
                ManagedPortForward.Local(
                    connection.createLocalPortForwarder(bindAddress, destinationHost, destinationPort),
                )
            }

            PortForwardType.DYNAMIC -> ManagedPortForward.Dynamic(
                connection.createDynamicPortForwarder(bindAddress),
            )

            PortForwardType.REMOTE -> {
                val (destinationHost, destinationPort) = rule.requireDestination()
                ManagedPortForward.Remote(
                    connection.createRemotePortForwarder(
                        rule.bindHost.ifBlank { "127.0.0.1" },
                        rule.bindPort,
                        destinationHost,
                        destinationPort,
                    ),
                )
            }
        }
    }

    private fun PortForwardRule.requireDestination(): Pair<String, Int> {
        val host = requireNotNull(destinationHost?.trim()?.takeIf(String::isNotEmpty)) { "Destination host is required" }
        val port = requireNotNull(destinationPort) { "Destination port is required" }
        require(port in 1..65535) { "Invalid destination port" }
        return host to port
    }

    private fun closePortForwards(sessionId: String, managed: ManagedSession) {
        val sessionClosedDetail = context.appString(R.string.port_forward_session_closed)
        val forwards = synchronized(managed.sshFeatureLock) {
            managed.forwards.values.toList().also {
                managed.forwards.clear()
                _portForwards.update { states ->
                    states.map { state ->
                        if (state.sessionId == sessionId && state.phase != PortForwardRuntimePhase.STOPPED) {
                            // The connection is gone, and with it every listener it carried:
                            // servers drop remote forwards when the connection closes.
                            state.copy(phase = PortForwardRuntimePhase.STOPPED, detail = sessionClosedDetail,
                                stopOutcome = PortForwardStopOutcome.STOPPED, activeConnections = 0)
                        } else {
                            state
                        }
                    }
                }
            }
        }
        forwards.forEach { forward -> runCatching { forward.close() } }
    }

    /**
     * Starts the remote Mosh server through a fixed command and extracts the
     * generated UDP port/key pair. The command supplies an explicit UTF-8
     * locale because non-interactive SSH sessions can otherwise inherit the
     * ASCII `C` locale, which mosh-server rejects. It contains no user-provided
     * text; raw server lines are never surfaced because a valid line contains
     * the sensitive Mosh key.
     */
    private suspend fun prepareWorkspace(sessionId: String, managed: ManagedSession, connection: SshConnection): String? = try {
        TmuxWorkspaces.prepare(connection, managed.profile.workspace)
    } catch (_: WorkspaceUnavailableException) {
        val accepted = requestPrompt(SessionPrompt.Authentication(UUID.randomUUID().toString(), sessionId,
            SessionPromptText.App(SessionPromptTextKind.WORKSPACE_UNAVAILABLE),
            SessionPromptText.App(SessionPromptTextKind.WORKSPACE_FALLBACK), emptyList())) != null
        if (accepted) null else throw WorkspaceUnavailableException()
    }

    /** A workspace reattach is a new session, using the original connection's fixed profile. */
    fun openWorkspace(sessionId: String, workspace: website.sung.mangossh.domain.TmuxWorkspace): String? {
        val profile = sessionsById[sessionId]?.profile ?: return null
        require(workspace.isValid())
        return connect(profile.copy(workspace = workspace, startupSnippetId = null))
    }

    internal suspend fun readEditableText(sessionId: String, path: String): EditableRemoteText = withContext(Dispatchers.IO) {
        BlockingOperation(15_000L).use { remoteFiles.readEditable(requireSshFeatureConnection(sessionId), path, it) }
    }

    internal suspend fun saveEditableText(sessionId: String, source: EditableRemoteText, draft: String,
        alternateName: String?, allowDirectOverwrite: Boolean) = withContext(Dispatchers.IO) {
        BlockingOperation().use { remoteFiles.saveEditable(requireSshFeatureConnection(sessionId), source, draft, alternateName, allowDirectOverwrite, it) }
    }

    /** Reads workspace metadata through the session's SSH feature connection. */
    suspend fun listWorkspaces(sessionId: String): List<RemoteWorkspace> = withContext(Dispatchers.IO) {
        TmuxWorkspaces.list(requireSshFeatureConnection(sessionId))
    }

    private suspend fun bootstrapMosh(connection: SshConnection, workspaceId: String?): MoshBootstrap = BlockingOperation(30_000L).use { operation ->
        val session = connection.openChannel()
        if (!operation.own(session)) throw MoshBootstrapException()
        try {
            session.execute(MOSH_SERVER_COMMAND + (workspaceId?.let { " -- " + TmuxWorkspaces.attachCommand(it) } ?: ""))
            BoundedProtocolReader.lines(session.stdout, MAX_MOSH_BOOTSTRAP_LINES, 4096, 32 * 1024,
                MoshBootstrapParser::parse) ?: throw MoshBootstrapException()
        } finally {
            operation.release(session)
        }
    }

    /**
     * Returns the authenticated connection that carries SSH-only features.
     *
     * SSH terminals and shell-less sessions use their primary connection.
     * Mosh reuses its bootstrap connection while it is healthy and serializes
     * a fresh authentication when that companion was lost. The reconnect does
     * not alter the Mosh terminal phase or launch another remote Mosh server.
     */
    private suspend fun requireSshFeatureConnection(sessionId: String): SshConnection {
        val managed = requireNotNull(sessionsById[sessionId]) { "The SSH session is not open" }
        if (managed.protocol != ConnectionProtocol.MOSH) return managed.connection

        currentHealthyMoshSshFeatureConnection(sessionId, managed)?.let { return it }
        return managed.sshFeatureReconnectMutex.withLock {
            currentHealthyMoshSshFeatureConnection(sessionId, managed)?.let { return@withLock it }
            reconnectMoshSshFeatureConnection(sessionId, managed)
        }
    }

    /**
     * Returns the installed companion when it is still usable.
     *
     * A successful write proves only that the local transport has not already
     * failed, not that the peer is reachable, so liveness is really carried by
     * the connection monitor registered in [attachMoshSshFeatureConnection]:
     * whichever of the two notices first detaches the companion, and the next
     * caller re-authenticates.
     */
    private suspend fun currentHealthyMoshSshFeatureConnection(
        sessionId: String,
        managed: ManagedSession,
    ): SshConnection? {
        val connection = synchronized(managed.sshFeatureLock) { managed.sshFeatureConnection } ?: return null
        return try {
            connection.keepalive()
            managed.lastConfirmedNanos = System.nanoTime()
            connection
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The caller stopped waiting; that says nothing about the companion connection.
            throw cancelled
        } catch (error: Exception) {
            if (invalidateMoshSshFeatureConnection(sessionId, managed, connection)) {
                MangoLog.warn(MangoLogEvent.MOSH_COMPANION_SSH_DISCONNECTED, error)
            }
            null
        }
    }

    /**
     * Picks the profile a companion reconnect authenticates with.
     *
     * The vault entry is preferred so a credential edited while the terminal
     * was open is the one used, but only when it still names the endpoint this
     * session reached: the Mosh terminal is talking UDP to the original host,
     * and a re-pointed profile must not send its companion somewhere else.
     */
    private fun companionProfile(managed: ManagedSession, snapshot: VaultSnapshot): ConnectionProfile {
        val stored = snapshot.profiles.firstOrNull { it.id == managed.profile.id } ?: return managed.profile
        val sameEndpoint = stored.hostname == managed.profile.hostname &&
            stored.port == managed.profile.port &&
            stored.route == managed.profile.route
        return if (sameEndpoint) stored.copy(overrides = managed.profile.overrides,
            agentPolicy = managed.profile.agentPolicy, requireReauthentication = managed.profile.requireReauthentication,
            workspace = managed.profile.workspace, jumpProfileIds = managed.profile.jumpProfileIds,
            agentForwarding = managed.profile.agentForwarding) else managed.profile
    }

    /** Re-authenticates a lost Mosh companion through the route held by the terminal session. */
    private suspend fun reconnectMoshSshFeatureConnection(
        sessionId: String,
        managed: ManagedSession,
    ): SshConnection {
        check(sessionsById[sessionId] === managed) { "The Mosh session is not open" }
        val snapshot = vault.snapshot.value
        val profile = companionProfile(managed, snapshot)
        val connection = SshConnection(profile.hostname, profile.port, profile.legacySshAlgorithms)
        val pending = adoptPendingConnection(managed.lifecycle, connection, cleanupScope)
        MangoLog.info(MangoLogEvent.MOSH_COMPANION_SSH_RECONNECT_STARTED)
        try {
            if (profile.route == ConnectionRoute.TSNET) {
                connection.useSocketRoute(requireNotNull(managed.tsnetLease).proxyData)
            }
            connection.connect((managed.preferences.connectTimeoutSeconds * 1_000).toLong() + KEY_EXCHANGE_TIMEOUT_MILLIS, transportTimeoutMillis = managed.preferences.connectTimeoutSeconds * 1_000L) { algorithm, blob -> HostKeyVerifier(sessionId, profile, snapshot.knownHosts).verifyServerHostKey(connection.hostname, connection.port, algorithm, blob) }
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                throw SshAuthenticationException()
            }
            check(sessionsById[sessionId] === managed) { "The Mosh session is not open" }

            val installed = synchronized(managed.sshFeatureLock) {
                if (managed.sshFeatureConnection == null && sessionsById[sessionId] === managed) {
                    managed.sshFeatureConnection = connection
                    managed.lifecycle.detach(pending)
                    true
                } else {
                    false
                }
            }
            check(installed) { "The Mosh SSH companion changed during reconnect" }
            attachMoshSshFeatureConnection(sessionId, managed, connection)
            MangoLog.info(MangoLogEvent.MOSH_COMPANION_SSH_RECONNECT_SUCCEEDED)
            return connection
        } catch (error: Exception) {
            val release = managed.lifecycle.detach(pending)
            if (release != null) release() else {
                synchronized(managed.sshFeatureLock) {
                    if (managed.sshFeatureConnection === connection) managed.sshFeatureConnection = null
                }
                connection.close()
                cleanupScope.launch { runCatching { connection.close() } }
            }
            MangoLog.warn(MangoLogEvent.MOSH_COMPANION_SSH_RECONNECT_FAILED, error)
            throw error
        }
    }

    /**
     * Detaches one exact companion and fails only the forwards it carried.
     * Returns whether this call was the one that detached it, so a caller that
     * races another detector does not report the loss twice.
     */
    private fun invalidateMoshSshFeatureConnection(
        sessionId: String,
        managed: ManagedSession,
        connection: SshConnection,
    ): Boolean {
        if (managed.protocol != ConnectionProtocol.MOSH) return false
        val detail = context.appString(R.string.port_forward_mosh_companion_disconnected)
        var forwardsToClose: List<ManagedPortForward> = emptyList()
        val detached = synchronized(managed.sshFeatureLock) {
            if (managed.sshFeatureConnection !== connection) {
                false
            } else {
                managed.sshFeatureConnection = null
                forwardsToClose = managed.forwards.values.toList()
                managed.forwards.clear()
                // Failing the rules while the map is cleared, under the lock an
                // activation also holds, leaves no window where a forward this
                // companion carried still reads as running.
                _portForwards.update { states ->
                    failPortForwardsForSession(states, sessionId, detail)
                }
                true
            }
        }
        if (!detached) return false

        forwardsToClose.forEach { forward -> runCatching { forward.close() } }
        runCatching { connection.close() }
        return true
    }

    private suspend fun collectServerResourceReport(connection: SshConnection): String = BlockingOperation(15_000L).use { operation ->
        val session = connection.openChannel()
        check(operation.own(session))
        try {
            session.execute(RESOURCE_COMMAND)
            BoundedProtocolReader.bytes(session.stdout, 32 * 1024).toString(Charsets.UTF_8).trim()
                .ifBlank { context.appString(R.string.resource_report_empty) }
        } finally {
            operation.release(session)
        }
    }

    private fun requestAuthentication(
        sessionId: String,
        title: SessionPromptText,
        instruction: SessionPromptText?,
        fields: List<AuthenticationField>,
    ): List<String>? = requestPrompt(
        SessionPrompt.Authentication(
            requestId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = title,
            instruction = instruction,
            fields = fields,
        ),
    )

    /**
     * Publishes one prompt and blocks the calling protocol thread for the answer.
     *
     * This runs on a trilead transport thread, not on a session coroutine, so
     * `runBlocking` here cannot be released by cancelling the session's job. The
     * registry is what releases it: [releasePromptWaiters] completes the waiter
     * during teardown. Registering before publishing keeps that guarantee — a
     * waiter is always visible to teardown before its prompt is visible to the
     * user.
     *
     * A session that is already gone is never asked: answering it could only
     * feed a transport nobody owns.
     */
    private fun requestPrompt(prompt: SessionPrompt): List<String>? {
        val managed = sessionsById[prompt.sessionId] ?: return null
        val waiter = managed.lifecycle.whileOpen {
            promptRegistry.register(prompt.requestId, prompt.sessionId).also { _prompts.update { it + prompt } }
        } ?: return null
        return runBlocking {
            try {
                withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { waiter.await() }
            } finally {
                promptRegistry.release(prompt.requestId)
                _prompts.update { prompts -> prompts.filterNot { it.requestId == prompt.requestId } }
            }
        }
    }

    private fun publishNotice(sessionId: String, message: String) {
        terminalStore.append(sessionId, message.encodeToByteArray())
    }

    /**
     * Emits an application-owned terminal notice separately from server output.
     *
     * Fixed wording is resolved from Android resources before this method is
     * called, while remote banners continue through [publishNotice] verbatim.
     */
    private fun publishLocalizableNotice(sessionId: String, message: String) {
        terminalStore.append(sessionId, message.encodeToByteArray())
    }

    private fun updateSession(state: TerminalSessionState) {
        _sessions.update { current -> current.filterNot { it.id == state.id } + state }
    }

    private fun updateSession(sessionId: String, phase: TerminalSessionPhase, detail: String?) {
        _sessions.update { current ->
            current.map { state ->
                if (state.id == sessionId) state.copy(phase = phase, detail = detail) else state
            }
        }
    }

    private fun updatePortForward(state: PortForwardRuntimeState) {
        _portForwards.update { current -> current.filterNot { it.runtimeId == state.runtimeId } + state }
    }

    private fun ManagedSession.forwardCarrier(): PortForwardCarrier = when {
        kind == SessionKind.PORT_FORWARD -> PortForwardCarrier.DEDICATED
        protocol == ConnectionProtocol.MOSH -> PortForwardCarrier.MOSH_COMPANION
        else -> PortForwardCarrier.SSH_SESSION
    }

    private fun SshForward.boundAddress(): String =
        if (':' in boundHost) "[$boundHost]:$boundPort" else "$boundHost:$boundPort"

    private fun PortForwardRuntimeState.withActivity(activity: org.connectbot.sshlib.PortForwardActivity) = copy(
        activeConnections = activity.activeConnections,
        totalConnections = activity.totalConnections,
        lastActivityEpochMillis = activity.lastActivityEpochMillis,
    )

    /**
     * Copies connection counts and last activity from every running listener into the
     * published state, and fails a local listener that stopped accepting on its own.
     * Called by the forwards screen while it is visible, so nothing polls in background.
     */
    fun refreshPortForwardActivity() {
        val listenerLost = context.appString(R.string.port_forward_listener_lost)
        sessionsById.values.forEach { managed ->
            val lost = mutableListOf<ManagedPortForward>()
            synchronized(managed.sshFeatureLock) {
                managed.forwards.forEach { (runtimeId, forward) ->
                    val handle = forward.handle
                    val alive = handle.isActive
                    if (!alive) managed.forwards.remove(runtimeId)?.let(lost::add)
                    _portForwards.update { states ->
                        states.map { state ->
                            when {
                                state.runtimeId != runtimeId || state.phase != PortForwardRuntimePhase.ACTIVE -> state
                                !alive -> state.copy(phase = PortForwardRuntimePhase.FAILED, detail = listenerLost, activeConnections = 0)
                                else -> state.withActivity(handle.activity)
                            }
                        }
                    }
                }
            }
            lost.forEach { forward -> cleanupScope.launch { runCatching { forward.close() } } }
        }
    }

    private fun updatePortForward(runtimeId: String, phase: PortForwardRuntimePhase, detail: String?) {
        _portForwards.update { current ->
            current.map { state ->
                if (state.runtimeId == runtimeId) state.copy(phase = phase, detail = detail) else state
            }
        }
    }

    private inner class HostKeyVerifier(
        private val sessionId: String,
        private val profile: ConnectionProfile,
        private val knownHosts: List<TrustedHostKey>,
    ) {
        fun verifyServerHostKey(
            hostname: String,
            port: Int,
            algorithm: String,
            hostKey: ByteArray,
        ): Boolean = verify(hostname, port, algorithm, hostKey).also { accepted ->
            // Remember which key the session's own target presented (not a jump hop's), so a
            // transfer interrupted on this session is only ever resumed on the same server.
            if (accepted) sessionsById[sessionId]
                ?.takeIf { it.connection.hostname == hostname && it.connection.port == port }
                ?.verifiedHostKey = "$algorithm ${hostKeyFingerprint(hostKey)}"
        }

        private fun verify(
            hostname: String,
            port: Int,
            algorithm: String,
            hostKey: ByteArray,
        ): Boolean {
            val encoded = Base64.getEncoder().encodeToString(hostKey)
            val fingerprint = hostKeyFingerprint(hostKey)
            val known = knownHosts.filter { it.hostname == hostname && it.port == port }
            if (isTrustedHostKey(known, hostname, port, algorithm, encoded)) return true

            val previous = known.firstOrNull { it.sameHostKeySlot(hostname, port, algorithm) }
            MangoLog.info(MangoLogEvent.SSH_HOST_KEY_PROMPTED)
            val accepted = requestPrompt(
                SessionPrompt.HostKeyVerification(
                    requestId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    hostname = hostname,
                    port = port,
                    algorithm = algorithm,
                    fingerprint = fingerprint,
                    isChanged = previous != null,
                    previousFingerprint = previous?.fingerprint,
                ),
            )?.firstOrNull() == TRUST_APPROVAL
            if (accepted) {
                // This runs inside the owned key-exchange operation. Persisting the
                // trust decision is a convenience for the next connection, so a
                // vault write failure must not become an exception thrown into
                // the protocol stack; the user already approved this key.
                runCatching {
                    runBlocking {
                        vault.trustHostKey(
                            TrustedHostKey(
                                hostname = hostname,
                                port = port,
                                algorithm = algorithm,
                                keyBlobBase64 = encoded,
                                fingerprint = fingerprint,
                            ),
                        )
                    }
                }.onFailure { error -> MangoLog.warn(MangoLogEvent.VAULT_WRITE_FAILED, error) }
            }
            return accepted
        }
    }

    /** Holds one terminal transport and resources that must close together. */
    private class ManagedSession(
        val connection: SshConnection,
        val profile: ConnectionProfile,
        val defaultPreferences: website.sung.mangossh.domain.ConnectionPreferences,
        val initialSnapshot: VaultSnapshot,
        val protocol: ConnectionProtocol,
        val kind: SessionKind = SessionKind.TERMINAL,
    ) {
        val preferences = profile.overrides.resolve(defaultPreferences)
        /** Guards companion replacement and forward attachment as one lifecycle boundary. */
        val lifecycle = SessionLifecycle()
        val jumpConnections = mutableListOf<SshConnection>()
        @Volatile var lastConfirmedNanos = 0L
        /** Algorithm and fingerprint of the host key this session's target presented and the user trusts. */
        @Volatile var verifiedHostKey: String? = null
        @Volatile var configuredRoute = profile.route
        val sshFeatureLock = lifecycle.lock
        @Volatile var inputReady = false
        @Volatile var writer: TerminalTransport? = null
        @Volatile var resizeQueue: TerminalResizeQueue? = null

        /** Serializes Mosh companion authentication so callers share one reconnect. */
        val sshFeatureReconnectMutex = Mutex()

        /**
         * The authenticated carrier for SFTP, resource queries, and forwards.
         * For Mosh this may be detached and replaced without ending the PTY.
         */
        private val sshFeatures = SshFeatureConnection(lifecycle, connection)
        var sshFeatureConnection: SshConnection?
            get() = sshFeatures.current
            set(value) { sshFeatures.current = value }

        /** Set once the browser is done; the connection closes when its queue drains. */
        @Volatile
        var releaseRequested: Boolean = false

        @Volatile
        var connectionJob: Job? = null

        val healthChecking = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var keepaliveJob: Job? = null

        @Volatile
        var sshFeatureKeepaliveJob: Job? = null

        @Volatile
        var session: SshChannel? = null

        @Volatile
        var moshProcess: MoshPtyProcess? = null

        @Volatile
        var tsnetLease: EmbeddedTsnetLease? = null

        @Volatile
        var tsnetUdpRelay: EmbeddedTsnetUdpRelay? = null

        val readerJobs = ConcurrentHashMap.newKeySet<Job>()
        val completedReaderCount = AtomicInteger(0)
        val forwards = ConcurrentHashMap<String, ManagedPortForward>()
        val openingForwards = ConcurrentHashMap<String, CompletableDeferred<Throwable?>>()
    }

    /** Result of one terminal stream; EOF is distinct from an I/O failure. */
    private sealed interface StreamEnd {
        data object EOF : StreamEnd

        data class Failed(val error: Throwable) : StreamEnd
    }

    private sealed interface ManagedPortForward {
        /** The listener this rule created; the source of its address and activity. */
        val handle: SshForward

        fun close() = handle.close()

        /**
         * What closing proves: a local listener is closed by this app, while a remote
         * listener's cancellation gets no reply from the server.
         */
        val stopOutcome: PortForwardStopOutcome get() = PortForwardStopOutcome.STOPPED

        class Local(override val handle: SshForward) : ManagedPortForward

        class Dynamic(override val handle: SshForward) : ManagedPortForward

        /** Owns the exact listener it created; stopping never looks it up by port. */
        class Remote(override val handle: SshForward) : ManagedPortForward {
            override val stopOutcome get() = PortForwardStopOutcome.UNCONFIRMED
        }
    }

    /** Retains the host allowlist and checks the session grant before and after user approval. */
    private class VaultSshAgent(
        keys: List<StoredSshKey>,
        keyManager: SshKeyManager,
        private val authorized: () -> Boolean,
        private val authorizeSignature: () -> Boolean,
    ) : SshAgent {
        private val keys = keys.filter { !it.requiresPassphrase && it.algorithm != "ssh-dss" }.mapNotNull { stored ->
            runCatching { keyManager.decodeKeyPair(stored).let { pair -> Triple(stored.label, SshKeyCodec.publicKey(pair).publicKeyBlob, pair) } }.getOrNull()
        }
        override fun identities(): List<SshAgentIdentity> = if (authorized()) keys.map { SshAgentIdentity(it.first, it.second) } else emptyList()
        override fun keyForSignature(publicKey: ByteArray): KeyPair? = keys.firstOrNull { it.second.contentEquals(publicKey) }?.third
            ?.takeIf { authorized() && authorizeSignature() && authorized() }
    }
    private fun hostKeyFingerprint(hostKey: ByteArray): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))

    private fun Throwable.toSafeMessage(): String = when (this) {
        is SshAuthenticationException -> context.appString(R.string.session_ended_authentication_failed)
        is KeyPassphraseRequiredException -> context.appString(R.string.message_key_passphrase_required)
        is website.sung.mangossh.data.keys.UnsupportedDsaKeyException -> context.appString(R.string.ssh_dsa_unsupported)
        is website.sung.mangossh.data.keys.UnsupportedKeyEncryptionException -> context.appString(R.string.ssh_key_encryption_unsupported)
        else -> context.appString(R.string.session_ended_connection_lost)
    }

    private fun String.sanitizeRemoteBanner(): String =
        buildString {
            this@sanitizeRemoteBanner.forEach { character ->
                if (character == '\n' || character == '\r' || character == '\t' || !character.isISOControl()) {
                    append(character)
                }
            }
        }.trim().take(MAX_REMOTE_BANNER_CHARS).ifBlank {
            context.appString(R.string.authentication_banner_received)
        }

    /** Authentication rejection intentionally carries a user-safe message only. */

    /** Signals that the remote command did not return a valid Mosh bootstrap record. */
    private class MoshBootstrapException(cause: Throwable? = null) : Exception(cause)

    /** Hides native/asset exception details from terminal UI while preserving them for safe logging. */
    private class MoshRuntimeException(cause: Throwable) : Exception(cause)

    /** Connect timeout in milliseconds, read fresh so a mid-session preference change reaches the next connection. */
    private fun connectTimeoutMillis(): Int = connectionPreferencesStore.current().connectTimeoutSeconds * 1_000

    private companion object {
        // Host-key verification runs inside key exchange, so this must outlive the full user prompt.
        const val KEY_EXCHANGE_TIMEOUT_MILLIS = 5 * 60 * 1_000 + 30_000
        const val PROMPT_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val INITIAL_COLUMNS = 80
        const val INITIAL_ROWS = 24
        const val SSH_STREAM_COUNT = 2
        const val TRUST_APPROVAL = "trust"
        const val MAX_ERROR_LENGTH = 240
        const val MAX_REMOTE_BANNER_CHARS = 2_048
        const val MAX_RESOURCE_REPORT_CHARS = 32 * 1024
        const val MAX_MOSH_BOOTSTRAP_LINES = 32
        const val TSNET_LOOPBACK_HOST = "127.0.0.1"
        const val TSNET_MOSH_GRACEFUL_RELEASE_MILLIS = 2_000L
        const val MOSH_SERVER_COMMAND = "mosh-server new -s -c 256 -l LANG=C.UTF-8"
        const val RESOURCE_COMMAND = "printf 'Host: '; hostname; printf '\\nUptime: '; uptime; printf '\\nLoad: '; cat /proc/loadavg 2>/dev/null || true; printf '\\nMemory:\\n'; free -h 2>/dev/null || true; printf '\\nDisk:\\n'; df -h / 2>/dev/null || true; printf '\\nCPU: '; nproc 2>/dev/null || true"

        fun portForwardRuntimeId(sessionId: String, ruleId: String): String = "$sessionId:$ruleId"
    }
}
