package website.sung.mangossh.session

import android.content.Context
import android.net.Uri
import website.sung.mangossh.R
import com.trilead.ssh2.AuthAgentCallback
import com.trilead.ssh2.Connection
import com.trilead.ssh2.DynamicPortForwarder
import com.trilead.ssh2.InteractiveCallback
import com.trilead.ssh2.LocalPortForwarder
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.UserAuthBannerCallback
import com.trilead.ssh2.crypto.PublicKeyUtils
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import website.sung.mangossh.data.settings.TerminalAppearanceStore
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
) {
    private val context = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionsById = ConcurrentHashMap<String, ManagedSession>()
    private val promptWaiters = ConcurrentHashMap<String, CompletableDeferred<List<String>?>>()
    private val remoteFiles = RemoteFileClient()
    private val terminalStore = SessionTerminalStore(
        onKeyboardInput = { sessionId, bytes -> send(sessionId, bytes) },
        onResize = { sessionId, columns, rows -> resize(sessionId, columns, rows) },
        appearanceProvider = terminalAppearanceStore::current,
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
        connectionOf = ::requireSshConnection,
        onSessionIdle = ::closeFileTransferIfIdle,
    )
    val scpTransfers: StateFlow<List<ScpTransferState>> = fileTransfers.transfers

    private val _resourceSnapshots = MutableStateFlow<Map<String, ServerResourceSnapshot>>(emptyMap())
    val resourceSnapshots = _resourceSnapshots.asStateFlow()

    private val _prompts = MutableStateFlow<List<SessionPrompt>>(emptyList())
    val prompts = _prompts.asStateFlow()

    private val _sessionEndedEvents = MutableSharedFlow<SessionEndedEvent>(extraBufferCapacity = 8)
    /** One-shot notifications used to leave a foreground terminal after it ends. */
    val sessionEndedEvents = _sessionEndedEvents.asSharedFlow()

    /** Transient OSC 52 copy requests for the currently visible terminal UI. */
    val clipboardCopies = terminalStore.clipboardCopies

    /**
     * Starts a user-requested terminal session and protects it with the
     * foreground service before network negotiation begins.
     */
    fun connect(profile: ConnectionProfile): String {
        val sessionId = UUID.randomUUID().toString()
        val managed = ManagedSession(
            connection = Connection(profile.hostname, profile.port),
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
                detail = context.getString(R.string.session_connecting),
            ),
        )
        SessionForegroundService.start(context)

        managed.connectionJob = scope.launch {
            when (profile.protocol) {
                ConnectionProtocol.SSH -> runSshSession(sessionId, profile, managed)
                ConnectionProtocol.MOSH -> runMoshSession(sessionId, profile, managed)
            }
        }
        return sessionId
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
        check(profile.protocol == ConnectionProtocol.SSH) {
            context.getString(R.string.mosh_not_supported_for_ssh_feature)
        }
        val sessionId = UUID.randomUUID().toString()
        val managed = ManagedSession(
            connection = Connection(profile.hostname, profile.port),
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
                detail = context.getString(R.string.session_connecting),
                kind = kind,
            ),
        )
        SessionForegroundService.start(context)

        managed.connectionJob = scope.launch {
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
        promptWaiters.remove(requestId)?.complete(values)
        _prompts.update { prompts -> prompts.filterNot { it.requestId == requestId } }
    }

    fun send(sessionId: String, bytes: ByteArray) {
        scope.launch {
            val managed = sessionsById[sessionId] ?: return@launch
            val output = managed.session?.stdin ?: managed.moshProcess?.output ?: return@launch
            try {
                output.write(bytes)
                output.flush()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A broken PTY write is an observable transport failure even
                // if a blocked read has not returned yet. End it promptly so
                // the foreground notification cannot outlive the session.
                finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, error)
            }
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        scope.launch {
            runCatching {
                val managed = sessionsById[sessionId] ?: return@runCatching
                when (managed.protocol) {
                    ConnectionProtocol.SSH -> managed.session?.resizePTY(columns, rows, 0, 0)
                    ConnectionProtocol.MOSH -> managed.moshProcess?.resize(columns, rows)
                }
            }
        }
    }

    /** Returns the retained emulator for a still-live session. */
    fun terminalEmulator(sessionId: String): TerminalEmulator? = terminalStore.terminalFor(sessionId)

    /** Applies a display-only scheme to retained sessions without reconnecting their transports. */
    fun applyTerminalAppearance(appearance: TerminalAppearance) {
        terminalStore.applyAppearance(appearance)
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        val runtimeId = portForwardRuntimeId(sessionId, rule.id)
        val existing = _portForwards.value.firstOrNull { it.runtimeId == runtimeId }
        if (existing?.phase == PortForwardRuntimePhase.ACTIVE || existing?.phase == PortForwardRuntimePhase.STARTING) {
            return
        }
        markPortForwardStarting(runtimeId, sessionId, rule, "Starting tunnel")
        activatePortForward(sessionId, rule, runtimeId)
    }

    private fun markPortForwardStarting(
        runtimeId: String,
        sessionId: String,
        rule: PortForwardRule,
        detail: String,
    ) {
        updatePortForward(
            PortForwardRuntimeState(
                runtimeId = runtimeId,
                sessionId = sessionId,
                rule = rule,
                phase = PortForwardRuntimePhase.STARTING,
                detail = detail,
            ),
        )
    }

    /**
     * Opens the tunnel for a rule already published as starting.
     *
     * This is separate from [startPortForward] because a rule on its own
     * connection is marked starting while that connection authenticates, and
     * the duplicate-start guard would otherwise reject its own placeholder.
     */
    private fun activatePortForward(sessionId: String, rule: PortForwardRule, runtimeId: String) {
        scope.launch {
            val managed = sessionsById[sessionId]
            if (managed == null) {
                updatePortForward(runtimeId, PortForwardRuntimePhase.FAILED, "The SSH session is not open")
                return@launch
            }
            if (managed.protocol != ConnectionProtocol.SSH) {
                updatePortForward(
                    runtimeId,
                    PortForwardRuntimePhase.FAILED,
                    context.getString(R.string.mosh_not_supported_for_ssh_feature),
                )
                closePortForwardSessionIfIdle(sessionId)
                return@launch
            }
            try {
                val forward = createPortForward(managed.connection, rule)
                managed.forwards[runtimeId] = forward
                updatePortForward(runtimeId, PortForwardRuntimePhase.ACTIVE, "Listening")
            } catch (error: Exception) {
                updatePortForward(runtimeId, PortForwardRuntimePhase.FAILED, error.toSafeMessage())
                // A connection opened only for this forward has nothing left to do.
                closePortForwardSessionIfIdle(sessionId)
            }
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
        markPortForwardStarting(runtimeId, sessionId, rule, context.getString(R.string.session_connecting))
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
                    context.getString(R.string.session_ended_connection_failed),
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
        runCatching { managed?.forwards?.remove(runtimeId)?.close() }
        updatePortForward(runtimeId, PortForwardRuntimePhase.STOPPED, "Stopped")
        closePortForwardSessionIfIdle(sessionId)
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
                        state.phase == PortForwardRuntimePhase.STARTING
                    )
        }
        if (!busy) finishSession(sessionId, managed, SessionEndReason.USER_REQUEST)
    }

    /** Resolves the directory the session's account starts in, for the file browser. */
    suspend fun resolveRemoteHome(sessionId: String): String = withContext(Dispatchers.IO) {
        remoteFiles.resolveHome(requireSshConnection(sessionId))
    }

    /** Lists one remote directory for the file browser. */
    suspend fun listRemoteDirectory(sessionId: String, path: String): RemoteDirectoryListing =
        withContext(Dispatchers.IO) {
            remoteFiles.list(
                connection = requireSshConnection(sessionId),
                sessionId = sessionId,
                path = path,
                maxEntries = MAX_REMOTE_DIRECTORY_ENTRIES,
            )
        }

    /** Resolves what a remote path actually is after following symlinks. */
    suspend fun resolveRemoteKind(sessionId: String, path: String): RemoteFileKind =
        withContext(Dispatchers.IO) {
            remoteFiles.statKind(requireSshConnection(sessionId), path)
        }

    /**
     * Reads the leading bytes of a remote file for a read-only preview.
     *
     * Returns null when the content is not valid UTF-8 text.
     */
    suspend fun readRemoteTextPreview(sessionId: String, path: String): RemoteTextPreview? =
        withContext(Dispatchers.IO) {
            remoteFiles.readTextPreview(
                connection = requireSshConnection(sessionId),
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
    fun remoteFileMessage(error: Throwable): String = error.toRemoteFileMessage()

    /** Maps a remote file failure onto fixed application wording. */
    private fun Throwable.toRemoteFileMessage(): String =
        context.remoteFileMessageOrNull(this) ?: toSafeMessage()

    fun requestServerResources(sessionId: String) {
        scope.launch {
            try {
                val connection = requireSshConnection(sessionId)
                val report = collectServerResourceReport(connection)
                _resourceSnapshots.update { snapshots ->
                    snapshots + (sessionId to ServerResourceSnapshot(sessionId = sessionId, report = report))
                }
            } catch (error: Exception) {
                publishLocalizableNotice(
                    sessionId,
                    "\r\n[MangoSSH] ${context.getString(R.string.terminal_resource_query_failed)}\r\n",
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
        scope.cancel()
    }

    /**
     * Performs all terminal cleanup exactly once.
     *
     * `sessionsById.remove(sessionId, managed)` is the lifecycle gate: reader
     * coroutines, a user close action, and connection setup failures may race,
     * but only the first caller is allowed to stop forwards, remove terminal
     * state, and withdraw notifications.
     */
    private fun finishSession(
        sessionId: String,
        managed: ManagedSession,
        reason: SessionEndReason,
        failure: Throwable? = null,
        userMessage: String? = null,
        deferTsnetMoshRelease: Boolean = true,
    ) {
        if (!sessionsById.remove(sessionId, managed)) return

        managed.connectionJob?.cancel()
        managed.keepaliveJob?.cancel()
        managed.readerJobs.toList().forEach(Job::cancel)
        cancelPromptsForSession(sessionId)
        closePortForwards(sessionId, managed)
        runCatching { managed.session?.close() }
        managed.moshProcess?.let { process ->
            if (reason == SessionEndReason.USER_REQUEST) {
                runCatching { process.closeGracefully() }
            } else {
                runCatching { process.close() }
                // A non-user termination cancels the reader, so reap the
                // direct native child separately from its normal EOF path.
                scope.launch { runCatching { process.awaitExit() } }
            }
        }
        val closeEmbeddedTsnet = {
            runCatching { managed.tsnetUdpRelay?.close() }
            runCatching { managed.tsnetLease?.close() }
        }
        runCatching { managed.connection.close() }
        if (
            managed.protocol == ConnectionProtocol.MOSH &&
            reason == SessionEndReason.USER_REQUEST &&
            managed.moshProcess != null &&
            deferTsnetMoshRelease
        ) {
            // Keep the loopback UDP relay alive through Mosh's short graceful
            // quit window, then release the final process-wide tsnet reference.
            scope.launch {
                delay(TSNET_MOSH_GRACEFUL_RELEASE_MILLIS)
                closeEmbeddedTsnet()
            }
        } else {
            closeEmbeddedTsnet()
        }

        _resourceSnapshots.update { snapshots -> snapshots - sessionId }
        fileTransfers.onSessionEnded(sessionId)
        terminalStore.remove(sessionId)
        _sessions.update { sessions -> sessions.filterNot { it.id == sessionId } }
        _sessionEndedEvents.tryEmit(
            SessionEndedEvent(
                sessionId = sessionId,
                reason = reason,
                userMessage = userMessage,
            ),
        )

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

    /** Completes any host-key or authentication waiters so background jobs cannot hang. */
    private fun cancelPromptsForSession(sessionId: String) {
        val promptsForSession = _prompts.value.filter { it.sessionId == sessionId }
        promptsForSession.forEach { prompt -> promptWaiters.remove(prompt.requestId)?.complete(null) }
        _prompts.update { prompts -> prompts.filterNot { it.sessionId == sessionId } }
    }

    private suspend fun runSshSession(
        sessionId: String,
        profile: ConnectionProfile,
        managed: ManagedSession,
    ) {
        if (sessionsById[sessionId] !== managed) return
        val snapshot = vault.snapshot.value
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            connection.addUserAuthBanner(
                UserAuthBannerCallback { banner, _ ->
                    publishNotice(sessionId, "\r\n[MangoSSH] ${banner.sanitizeRemoteBanner()}\r\n")
                },
            )
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.getString(R.string.session_verifying_host_key),
            )
            connection.connect(
                HostKeyVerifier(sessionId, profile, snapshot.knownHosts),
                CONNECT_TIMEOUT_MILLIS,
                KEY_EXCHANGE_TIMEOUT_MILLIS,
            )

            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.getString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException("服务器拒绝了此身份验证方式。")
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            val session = connection.openSession()
            managed.session = session
            session.requestPTY("xterm-256color", INITIAL_COLUMNS, INITIAL_ROWS, 0, 0, ByteArray(0))
            if (profile.agentForwarding) {
                val enabled = session.requestAuthAgentForwarding(VaultSshAgent(snapshot.keys, keyManager))
                if (!enabled) {
                    publishLocalizableNotice(
                        sessionId,
                        "\r\n[MangoSSH] ${context.getString(R.string.terminal_agent_forwarding_rejected)}\r\n",
                    )
                }
            }
            session.startShell()

            updateSession(sessionId, TerminalSessionPhase.OPEN, context.getString(R.string.session_open))
            MangoLog.info(MangoLogEvent.SSH_SESSION_OPENED)
            startSshReaders(sessionId, managed)
            startSshKeepalive(sessionId, managed)
            runStartupSnippet(sessionId, profile, snapshot)
            snapshot.portForwards
                .filter { it.profileId == profile.id && it.startOnConnect }
                .forEach { rule -> startPortForward(sessionId, rule) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                userMessage = connectionFailureMessage(error),
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
        val snapshot = vault.snapshot.value
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.getString(R.string.session_verifying_host_key),
            )
            connection.connect(
                HostKeyVerifier(sessionId, profile, snapshot.knownHosts),
                CONNECT_TIMEOUT_MILLIS,
                KEY_EXCHANGE_TIMEOUT_MILLIS,
            )

            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.getString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException("服务器拒绝了此身份验证方式。")
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            updateSession(sessionId, TerminalSessionPhase.OPEN, context.getString(R.string.session_open))
            MangoLog.info(MangoLogEvent.SSH_SESSION_OPENED)
            startSshKeepalive(sessionId, managed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                userMessage = connectionFailureMessage(error),
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
        val snapshot = vault.snapshot.value
        val connection = managed.connection
        try {
            prepareConnectionRoute(profile, managed)
            connection.addUserAuthBanner(
                UserAuthBannerCallback { banner, _ ->
                    publishNotice(sessionId, "\r\n[MangoSSH] ${banner.sanitizeRemoteBanner()}\r\n")
                },
            )
            updateSession(
                sessionId,
                TerminalSessionPhase.VERIFYING_HOST_KEY,
                context.getString(R.string.session_verifying_host_key),
            )
            connection.connect(
                HostKeyVerifier(sessionId, profile, snapshot.knownHosts),
                CONNECT_TIMEOUT_MILLIS,
                KEY_EXCHANGE_TIMEOUT_MILLIS,
            )
            updateSession(
                sessionId,
                TerminalSessionPhase.AUTHENTICATING,
                context.getString(R.string.session_authenticating),
            )
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                MangoLog.warn(MangoLogEvent.SSH_AUTH_FAILED)
                throw SshAuthenticationException("服务器拒绝了此身份验证方式。")
            }
            MangoLog.info(MangoLogEvent.SSH_AUTH_SUCCEEDED)

            updateSession(
                sessionId,
                TerminalSessionPhase.CONNECTING,
                context.getString(R.string.mosh_connecting),
            )
            MangoLog.info(MangoLogEvent.MOSH_BOOTSTRAP_STARTED)
            val bootstrap = runCatching { bootstrapMosh(connection) }.getOrElse { error ->
                MangoLog.warn(MangoLogEvent.MOSH_BOOTSTRAP_FAILED, error)
                throw MoshBootstrapException(error)
            }
            MangoLog.info(MangoLogEvent.MOSH_BOOTSTRAP_SUCCEEDED)
            runCatching { connection.close() }

            val relay = if (profile.route == ConnectionRoute.TSNET) {
                requireNotNull(managed.tsnetLease)
                    .startUdpRelay(profile.hostname, bootstrap.port)
                    .also { managed.tsnetUdpRelay = it }
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
            managed.moshProcess = moshProcess
            MangoLog.info(MangoLogEvent.MOSH_PROCESS_STARTED)

            updateSession(sessionId, TerminalSessionPhase.OPEN, context.getString(R.string.mosh_open))
            startMoshReader(sessionId, managed, moshProcess)
            runStartupSnippet(sessionId, profile, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finishSession(
                sessionId = sessionId,
                managed = managed,
                reason = SessionEndReason.CONNECTION_FAILED,
                failure = error,
                userMessage = connectionFailureMessage(error),
            )
        }
    }

    /** Returns only fixed localized wording for failure categories that need clarification. */
    private fun connectionFailureMessage(error: Exception): String? = when (error) {
        is SshAuthenticationException -> context.getString(R.string.session_ended_authentication_failed)
        is MoshBootstrapException -> context.getString(R.string.mosh_bootstrap_failed)
        is MoshRuntimeException -> context.getString(R.string.mosh_runtime_missing)
        is TsnetEnrollmentRequiredException ->
            context.getString(R.string.embedded_tsnet_enrollment_required)
        else -> null
    }

    /** Attaches tsnet's authenticated loopback SOCKS5 transport before SSH connects. */
    private suspend fun prepareConnectionRoute(
        profile: ConnectionProfile,
        managed: ManagedSession,
    ) {
        if (profile.route != ConnectionRoute.TSNET) return
        val lease = embeddedTsnetManager.acquire()
        if (sessionsById.values.none { it === managed }) {
            lease.close()
            throw CancellationException()
        }
        managed.tsnetLease = lease
        managed.connection.setProxyData(lease.proxyData)
    }

    private fun authenticate(
        connection: Connection,
        sessionId: String,
        profile: ConnectionProfile,
        snapshot: VaultSnapshot,
    ): Boolean {
        if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH && connection.authenticateWithNone(profile.username)) {
            return true
        }
        return when (profile.authentication) {
        AuthenticationMethod.PASSWORD -> {
            val password = requestAuthentication(
                sessionId = sessionId,
                title = "${profile.label} 的密码",
                instruction = "密码仅用于本次连接，不会保存。",
                fields = listOf(AuthenticationField("密码", echo = false)),
            )?.firstOrNull() ?: return false
            connection.authenticateWithPassword(profile.username, password)
        }

        AuthenticationMethod.PRIVATE_KEY -> {
            val key = profile.keyId?.let { keyId -> snapshot.keys.firstOrNull { it.id == keyId } }
                ?: throw SshAuthenticationException("此主机尚未选择私钥。")
            val passphrase = if (key.requiresPassphrase) {
                requestAuthentication(
                    sessionId = sessionId,
                    title = "解锁 ${key.label}",
                    instruction = "私钥口令仅用于本次连接。",
                    fields = listOf(AuthenticationField("私钥口令", echo = false)),
                )?.firstOrNull() ?: return false
            } else {
                null
            }
            connection.authenticateWithPublicKey(
                profile.username,
                keyManager.decodeKeyPair(key, passphrase),
            )
        }

        AuthenticationMethod.KEYBOARD_INTERACTIVE,
        AuthenticationMethod.TAILSCALE_SSH,
        -> connection.authenticateWithKeyboardInteractive(
            profile.username,
            InteractiveCallback { name, instruction, numberOfPrompts, prompts, echo ->
                val fields = (0 until numberOfPrompts).map { index ->
                    AuthenticationField(prompts[index], echo[index])
                }
                requestAuthentication(
                    sessionId = sessionId,
                    title = name.ifBlank {
                        if (profile.authentication == AuthenticationMethod.TAILSCALE_SSH) {
                            "Tailscale SSH 登录"
                        } else {
                            "交互式 SSH 登录"
                        }
                    },
                    instruction = instruction.takeIf(String::isNotBlank),
                    fields = fields,
                )?.takeIf { it.size == numberOfPrompts }?.toTypedArray() ?: emptyArray()
            },
        )
        }
    }

    /**
     * Starts stdout and stderr readers for an interactive SSH shell.
     *
     * Both streams need to reach EOF for a normal shell exit. A read failure
     * closes the sibling stream immediately so a broken transport cannot leave
     * an orphaned foreground notification behind.
     */
    private fun startSshReaders(sessionId: String, managed: ManagedSession) {
        val session = managed.session ?: return
        managed.readerJobs += scope.launch {
            onSshStreamEnded(
                sessionId,
                managed,
                readStream(sessionId, session.stdout),
            )
        }
        managed.readerJobs += scope.launch {
            onSshStreamEnded(
                sessionId,
                managed,
                readStream(sessionId, session.stderr),
            )
        }
    }

    /**
     * Keeps an authenticated SSH transport visible to idle network devices.
     *
     * Mosh sessions are excluded because their SSH transport closes after the
     * UDP bootstrap and the native Mosh client owns its own network lifecycle.
     */
    private fun startSshKeepalive(sessionId: String, managed: ManagedSession) {
        managed.keepaliveJob = scope.launch {
            runSshKeepaliveLoop(
                intervalMillis = SSH_KEEPALIVE_INTERVAL_MILLIS,
                isSessionActive = { sessionsById[sessionId] === managed },
                sendKeepalive = { managed.connection.sendIgnorePacket() },
                onFailure = { error ->
                    MangoLog.warn(MangoLogEvent.SSH_KEEPALIVE_FAILED, error)
                    finishSession(sessionId, managed, SessionEndReason.CONNECTION_LOST, error)
                },
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
        managed.readerJobs += scope.launch {
            try {
                val streamEnd = readStream(sessionId, process.input)
                if (sessionsById[sessionId] !== managed) return@launch
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
        } catch (error: Exception) {
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
        send(sessionId, text.encodeToByteArray())
    }

    private fun createPortForward(connection: Connection, rule: PortForwardRule): ManagedPortForward {
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
                connection.requestRemotePortForwarding(
                    rule.bindHost.ifBlank { "127.0.0.1" },
                    rule.bindPort,
                    destinationHost,
                    destinationPort,
                )
                ManagedPortForward.Remote(connection, rule.bindPort)
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
        managed.forwards.values.toList().forEach { forward -> runCatching { forward.close() } }
        managed.forwards.clear()
        _portForwards.update { states ->
            states.map { state ->
                if (state.sessionId == sessionId && state.phase != PortForwardRuntimePhase.STOPPED) {
                    state.copy(phase = PortForwardRuntimePhase.STOPPED, detail = "SSH session closed")
                } else {
                    state
                }
            }
        }
    }

    /**
     * Starts the remote Mosh server through a fixed command and extracts the
     * generated UDP port/key pair. The command supplies an explicit UTF-8
     * locale because non-interactive SSH sessions can otherwise inherit the
     * ASCII `C` locale, which mosh-server rejects. It contains no user-provided
     * text; raw server lines are never surfaced because a valid line contains
     * the sensitive Mosh key.
     */
    private fun bootstrapMosh(connection: Connection): MoshBootstrap {
        val bootstrapSession = connection.openSession()
        return try {
            bootstrapSession.execCommand(MOSH_SERVER_COMMAND)
            val reader = bootstrapSession.stdout.bufferedReader()
            for (lineIndex in 0 until MAX_MOSH_BOOTSTRAP_LINES) {
                val line = reader.readLine() ?: break
                MoshBootstrapParser.parse(line)?.let { bootstrap -> return bootstrap }
            }
            throw MoshBootstrapException()
        } finally {
            runCatching { bootstrapSession.close() }
        }
    }

    /** Rejects SSH-channel-only actions once the SSH Mosh bootstrap has closed. */
    private fun requireSshConnection(sessionId: String): Connection {
        val managed = requireNotNull(sessionsById[sessionId]) { "The SSH session is not open" }
        check(managed.protocol == ConnectionProtocol.SSH) {
            context.getString(R.string.mosh_not_supported_for_ssh_feature)
        }
        return managed.connection
    }

    private fun collectServerResourceReport(connection: Connection): String {
        val resourceSession = connection.openSession()
        return try {
            resourceSession.execCommand(RESOURCE_COMMAND)
            resourceSession.stdout.bufferedReader().use { reader ->
                reader.readText().take(MAX_RESOURCE_REPORT_CHARS).trim().ifBlank { "No resource data returned" }
            }
        } finally {
            runCatching { resourceSession.close() }
        }
    }

    private fun requestAuthentication(
        sessionId: String,
        title: String,
        instruction: String?,
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

    private fun requestPrompt(prompt: SessionPrompt): List<String>? = runBlocking {
        val waiter = CompletableDeferred<List<String>?>()
        promptWaiters[prompt.requestId] = waiter
        _prompts.update { it + prompt }
        try {
            withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { waiter.await() }
        } finally {
            promptWaiters.remove(prompt.requestId)
            _prompts.update { prompts -> prompts.filterNot { it.requestId == prompt.requestId } }
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
    ) : ServerHostKeyVerifier {
        override fun verifyServerHostKey(
            hostname: String,
            port: Int,
            algorithm: String,
            hostKey: ByteArray,
        ): Boolean {
            val encoded = Base64.getEncoder().encodeToString(hostKey)
            val fingerprint = hostKeyFingerprint(hostKey)
            val known = knownHosts.filter { it.hostname == hostname && it.port == port }
            if (known.any { it.algorithm == algorithm && it.keyBlobBase64 == encoded }) return true

            val previous = known.firstOrNull { it.algorithm == algorithm }
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
            }
            return accepted
        }
    }

    /** Holds one terminal transport and resources that must close together. */
    private class ManagedSession(
        val connection: Connection,
        val protocol: ConnectionProtocol,
        val kind: SessionKind = SessionKind.TERMINAL,
    ) {
        /** Set once the browser is done; the connection closes when its queue drains. */
        @Volatile
        var releaseRequested: Boolean = false

        @Volatile
        var connectionJob: Job? = null

        @Volatile
        var keepaliveJob: Job? = null

        @Volatile
        var session: Session? = null

        @Volatile
        var moshProcess: MoshPtyProcess? = null

        @Volatile
        var tsnetLease: EmbeddedTsnetLease? = null

        @Volatile
        var tsnetUdpRelay: EmbeddedTsnetUdpRelay? = null

        val readerJobs = ConcurrentHashMap.newKeySet<Job>()
        val completedReaderCount = AtomicInteger(0)
        val forwards = ConcurrentHashMap<String, ManagedPortForward>()
    }

    /** Result of one terminal stream; EOF is distinct from an I/O failure. */
    private sealed interface StreamEnd {
        data object EOF : StreamEnd

        data class Failed(val error: Throwable) : StreamEnd
    }

    private sealed interface ManagedPortForward {
        fun close()

        class Local(private val delegate: LocalPortForwarder) : ManagedPortForward {
            override fun close() = delegate.close()
        }

        class Dynamic(private val delegate: DynamicPortForwarder) : ManagedPortForward {
            override fun close() = delegate.close()
        }

        class Remote(
            private val connection: Connection,
            private val remotePort: Int,
        ) : ManagedPortForward {
            override fun close() = connection.cancelRemotePortForwarding(remotePort)
        }
    }

    private class VaultSshAgent(
        keys: List<StoredSshKey>,
        private val keyManager: SshKeyManager,
    ) : AuthAgentCallback {
        private val identities: Map<String, AgentIdentity> = buildMap {
            keys.filterNot(StoredSshKey::requiresPassphrase).forEach { key ->
                runCatching {
                    val keyPair = keyManager.decodeKeyPair(key)
                    val blob = PublicKeyUtils.extractPublicKeyBlob(keyPair.public)
                    put(Base64.getEncoder().encodeToString(blob), AgentIdentity(key.label, blob, keyPair))
                }
            }
        }

        override fun retrieveIdentities(): Map<String, ByteArray> =
            identities.values.associate { identity -> identity.label to identity.publicKeyBlob }

        override fun addIdentity(keyPair: KeyPair, comment: String, confirmUse: Boolean, lifetime: Int): Boolean = false

        override fun removeIdentity(publicKey: ByteArray): Boolean = false

        override fun removeAllIdentities(): Boolean = false

        override fun getKeyPair(publicKey: ByteArray): KeyPair? =
            identities[Base64.getEncoder().encodeToString(publicKey)]?.keyPair

        override fun isAgentLocked(): Boolean = false

        override fun setAgentLock(lockPassphrase: String): Boolean = false

        override fun requestAgentUnlock(lockPassphrase: String): Boolean = false

        private data class AgentIdentity(
            val label: String,
            val publicKeyBlob: ByteArray,
            val keyPair: KeyPair,
        )
    }

    private fun hostKeyFingerprint(hostKey: ByteArray): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))

    private fun Throwable.toSafeMessage(): String = when (this) {
        is SshAuthenticationException -> message ?: "身份验证失败。"
        is KeyPassphraseRequiredException -> "此私钥需要口令。"
        else -> message?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_LENGTH)
            ?: "连接已中断。请检查网络、主机地址和服务器日志。"
    }

    private fun String.sanitizeRemoteBanner(): String =
        buildString {
            this@sanitizeRemoteBanner.forEach { character ->
                if (character == '\n' || character == '\r' || character == '\t' || !character.isISOControl()) {
                    append(character)
                }
            }
        }.trim().take(MAX_REMOTE_BANNER_CHARS).ifBlank { "Authentication banner received" }

    /** Authentication rejection intentionally carries a user-safe message only. */
    private class SshAuthenticationException(message: String) : Exception(message)

    /** Signals that the remote command did not return a valid Mosh bootstrap record. */
    private class MoshBootstrapException(cause: Throwable? = null) : Exception(cause)

    /** Hides native/asset exception details from terminal UI while preserving them for safe logging. */
    private class MoshRuntimeException(cause: Throwable) : Exception(cause)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        // Host-key verification runs inside key exchange, so this must outlive the full user prompt.
        const val KEY_EXCHANGE_TIMEOUT_MILLIS = 5 * 60 * 1_000 + 30_000
        const val PROMPT_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val SSH_KEEPALIVE_INTERVAL_MILLIS = 30_000L
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
