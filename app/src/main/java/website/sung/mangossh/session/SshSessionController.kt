package website.sung.mangossh.session

import android.content.Context
import android.net.Uri
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
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Owns live SSH sockets and keeps all blocking protocol work away from Compose.
 * The controller never trusts a new or changed host key without a user response.
 */
class SshSessionController(
    appContext: Context,
    private val vault: VaultRepository,
    private val keyManager: SshKeyManager = SshKeyManager(),
) {
    private val context = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionsById = ConcurrentHashMap<String, ManagedSession>()
    private val promptWaiters = ConcurrentHashMap<String, CompletableDeferred<List<String>?>>()

    private val _sessions = MutableStateFlow<List<TerminalSessionState>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _portForwards = MutableStateFlow<List<PortForwardRuntimeState>>(emptyList())
    val portForwards = _portForwards.asStateFlow()

    private val _scpTransfers = MutableStateFlow<List<ScpTransferState>>(emptyList())
    val scpTransfers = _scpTransfers.asStateFlow()

    private val _resourceSnapshots = MutableStateFlow<Map<String, ServerResourceSnapshot>>(emptyMap())
    val resourceSnapshots = _resourceSnapshots.asStateFlow()

    private val _prompts = MutableStateFlow<List<SessionPrompt>>(emptyList())
    val prompts = _prompts.asStateFlow()

    private val _output = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    val output = _output.asSharedFlow()

    fun connect(profile: ConnectionProfile): String {
        val sessionId = UUID.randomUUID().toString()
        updateSession(
            TerminalSessionState(
                id = sessionId,
                profileId = profile.id,
                title = profile.label,
                endpoint = profile.endpoint,
                phase = TerminalSessionPhase.CONNECTING,
                detail = "正在建立安全连接…",
            ),
        )

        if (profile.protocol == ConnectionProtocol.MOSH) {
            updateSession(
                sessionId = sessionId,
                phase = TerminalSessionPhase.FAILED,
                detail = "此构建未捆绑原生 Mosh 运行时，无法将其伪装成 SSH 连接。请改用 SSH。",
            )
            return sessionId
        }

        scope.launch {
            runSshSession(sessionId, profile)
        }
        return sessionId
    }

    fun respondToPrompt(requestId: String, values: List<String>?) {
        promptWaiters.remove(requestId)?.complete(values)
        _prompts.update { prompts -> prompts.filterNot { it.requestId == requestId } }
    }

    fun send(sessionId: String, bytes: ByteArray) {
        scope.launch {
            val session = sessionsById[sessionId]?.session ?: return@launch
            runCatching {
                session.stdin.write(bytes)
                session.stdin.flush()
            }.onFailure { error ->
                publishNotice(sessionId, "\r\n[MangoSSH] 写入失败：${error.toSafeMessage()}\r\n")
            }
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        scope.launch {
            runCatching {
                sessionsById[sessionId]?.session?.resizePTY(columns, rows, 0, 0)
            }
        }
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        val runtimeId = portForwardRuntimeId(sessionId, rule.id)
        val existing = _portForwards.value.firstOrNull { it.runtimeId == runtimeId }
        if (existing?.phase == PortForwardRuntimePhase.ACTIVE || existing?.phase == PortForwardRuntimePhase.STARTING) {
            return
        }
        updatePortForward(
            PortForwardRuntimeState(
                runtimeId = runtimeId,
                sessionId = sessionId,
                rule = rule,
                phase = PortForwardRuntimePhase.STARTING,
                detail = "Starting tunnel",
            ),
        )
        scope.launch {
            val managed = sessionsById[sessionId]
            if (managed == null) {
                updatePortForward(runtimeId, PortForwardRuntimePhase.FAILED, "The SSH session is not open")
                return@launch
            }
            try {
                val forward = createPortForward(managed.connection, rule)
                managed.forwards[runtimeId] = forward
                updatePortForward(runtimeId, PortForwardRuntimePhase.ACTIVE, "Listening")
            } catch (error: Exception) {
                updatePortForward(runtimeId, PortForwardRuntimePhase.FAILED, error.toSafeMessage())
            }
        }
    }

    fun stopPortForward(sessionId: String, ruleId: String) {
        val runtimeId = portForwardRuntimeId(sessionId, ruleId)
        val managed = sessionsById[sessionId]
        runCatching { managed?.forwards?.remove(runtimeId)?.close() }
        updatePortForward(runtimeId, PortForwardRuntimePhase.STOPPED, "Stopped")
    }

    fun uploadScp(sessionId: String, sourceUri: Uri, displayName: String, remoteDirectory: String) {
        val safeRemoteDirectory = requireSafeScpRemotePath(remoteDirectory)
        val remoteFileName = sanitizeScpFileName(displayName)
        val transferId = UUID.randomUUID().toString()
        updateScpTransfer(
            ScpTransferState(
                id = transferId,
                sessionId = sessionId,
                direction = ScpTransferDirection.UPLOAD,
                displayName = remoteFileName,
                remotePath = safeRemoteDirectory,
                phase = ScpTransferPhase.QUEUED,
            ),
        )
        scope.launch {
            var stagingFile: File? = null
            try {
                val connection = requireNotNull(sessionsById[sessionId]?.connection) { "The SSH session is not open" }
                updateScpTransfer(transferId, ScpTransferPhase.RUNNING, "Preparing file")
                stagingFile = File.createTempFile("mangossh-scp-", ".upload", context.cacheDir)
                requireNotNull(context.contentResolver.openInputStream(sourceUri)) { "Cannot open selected file" }.use { input ->
                    stagingFile.outputStream().use { output -> copyWithLimit(input, output) }
                }
                updateScpTransfer(transferId, ScpTransferPhase.RUNNING, "Uploading")
                connection.createSCPClient().put(
                    stagingFile.absolutePath,
                    remoteFileName,
                    safeRemoteDirectory,
                    "0600",
                )
                updateScpTransfer(transferId, ScpTransferPhase.COMPLETED, "Completed")
            } catch (error: Exception) {
                updateScpTransfer(transferId, ScpTransferPhase.FAILED, error.toSafeMessage())
            } finally {
                stagingFile?.delete()
            }
        }
    }

    fun downloadScp(sessionId: String, remotePath: String, destinationUri: Uri) {
        val safeRemotePath = requireSafeScpRemotePath(remotePath)
        val transferId = UUID.randomUUID().toString()
        val displayName = safeRemotePath.substringAfterLast('/').ifBlank { "download" }
        updateScpTransfer(
            ScpTransferState(
                id = transferId,
                sessionId = sessionId,
                direction = ScpTransferDirection.DOWNLOAD,
                displayName = displayName,
                remotePath = safeRemotePath,
                phase = ScpTransferPhase.QUEUED,
            ),
        )
        scope.launch {
            try {
                val connection = requireNotNull(sessionsById[sessionId]?.connection) { "The SSH session is not open" }
                updateScpTransfer(transferId, ScpTransferPhase.RUNNING, "Downloading")
                requireNotNull(context.contentResolver.openOutputStream(destinationUri, "w")) {
                    "Cannot create destination file"
                }.use { output ->
                    connection.createSCPClient().get(safeRemotePath, output)
                }
                updateScpTransfer(transferId, ScpTransferPhase.COMPLETED, "Completed")
            } catch (error: Exception) {
                updateScpTransfer(transferId, ScpTransferPhase.FAILED, error.toSafeMessage())
            }
        }
    }

    fun requestServerResources(sessionId: String) {
        scope.launch {
            try {
                val connection = requireNotNull(sessionsById[sessionId]?.connection) { "The SSH session is not open" }
                val report = collectServerResourceReport(connection)
                _resourceSnapshots.update { snapshots ->
                    snapshots + (sessionId to ServerResourceSnapshot(sessionId = sessionId, report = report))
                }
            } catch (error: Exception) {
                publishNotice(sessionId, "\r\n[MangoSSH] Resource query failed: ${error.toSafeMessage()}\r\n")
            }
        }
    }

    fun close(sessionId: String) {
        val managed = sessionsById.remove(sessionId) ?: return
        managed.readerJobs.forEach(Job::cancel)
        closePortForwards(sessionId, managed)
        runCatching { managed.session?.close() }
        runCatching { managed.connection.close() }
        updateSession(sessionId, TerminalSessionPhase.CLOSED, "连接已关闭")
        if (sessionsById.isEmpty()) {
            SessionForegroundService.stop(context)
        }
    }

    fun clear() {
        sessionsById.keys.toList().forEach(::close)
        scope.cancel()
    }

    private suspend fun runSshSession(sessionId: String, profile: ConnectionProfile) {
        val snapshot = vault.snapshot.value
        val connection = Connection(profile.hostname, profile.port)
        val managed = ManagedSession(connection)
        sessionsById[sessionId] = managed
        try {
            connection.addUserAuthBanner(
                UserAuthBannerCallback { banner, _ ->
                    publishNotice(sessionId, "\r\n[MangoSSH] ${banner.sanitizeRemoteBanner()}\r\n")
                },
            )
            updateSession(sessionId, TerminalSessionPhase.VERIFYING_HOST_KEY, "正在验证服务器指纹…")
            connection.connect(
                HostKeyVerifier(sessionId, profile, snapshot.knownHosts),
                CONNECT_TIMEOUT_MILLIS,
                KEY_EXCHANGE_TIMEOUT_MILLIS,
            )

            updateSession(sessionId, TerminalSessionPhase.AUTHENTICATING, "正在验证身份…")
            if (!authenticate(connection, sessionId, profile, snapshot)) {
                throw SshAuthenticationException("服务器拒绝了此身份验证方式。")
            }

            val session = connection.openSession()
            managed.session = session
            session.requestPTY("xterm-256color", INITIAL_COLUMNS, INITIAL_ROWS, 0, 0, ByteArray(0))
            if (profile.agentForwarding) {
                val enabled = session.requestAuthAgentForwarding(VaultSshAgent(snapshot.keys, keyManager))
                if (!enabled) publishNotice(sessionId, "\r\n[MangoSSH] 服务器未接受 SSH 代理转发。\r\n")
            }
            session.startShell()

            updateSession(sessionId, TerminalSessionPhase.OPEN, "已连接")
            SessionForegroundService.start(context)
            startReaders(sessionId, managed)
            runStartupSnippet(sessionId, session, profile, snapshot)
            snapshot.portForwards
                .filter { it.profileId == profile.id && it.startOnConnect }
                .forEach { rule -> startPortForward(sessionId, rule) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            sessionsById.remove(sessionId)
            runCatching { managed.session?.close() }
            runCatching { connection.close() }
            updateSession(sessionId, TerminalSessionPhase.FAILED, error.toSafeMessage())
            if (sessionsById.isEmpty()) SessionForegroundService.stop(context)
        }
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

    private fun startReaders(sessionId: String, managed: ManagedSession) {
        val session = managed.session ?: return
        managed.readerJobs += scope.launch { readStream(sessionId, session.stdout, TerminalOutputSource.STDOUT) }
        managed.readerJobs += scope.launch { readStream(sessionId, session.stderr, TerminalOutputSource.STDERR) }
    }

    private suspend fun readStream(sessionId: String, input: InputStream, source: TerminalOutputSource) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) _output.emit(TerminalOutput(sessionId, buffer.copyOf(count), source))
            }
        } catch (_: Exception) {
            // A closed SSH channel commonly ends a blocking stream with an IOException.
        }
    }

    private fun runStartupSnippet(
        sessionId: String,
        session: Session,
        profile: ConnectionProfile,
        snapshot: VaultSnapshot,
    ) {
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

    private fun copyWithLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= MAX_SCP_STAGING_BYTES) { "Selected file is too large for SCP staging" }
            output.write(buffer, 0, count)
        }
    }

    private fun requireSafeScpRemotePath(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && !normalized.startsWith('-')) { "Remote SCP path is invalid" }
        require(normalized.all { character ->
            character.isLetterOrDigit() || character in SAFE_SCP_PATH_CHARACTERS
        }) { "Remote SCP path contains unsupported shell characters" }
        return normalized
    }

    private fun sanitizeScpFileName(value: String): String {
        val normalized = value.substringAfterLast('/').trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_SCP_FILENAME_CHARS) { "SCP file name is invalid" }
        require(normalized.none { character -> character == '\r' || character == '\n' || character == '\u0000' }) {
            "SCP file name contains control characters"
        }
        return normalized
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
        _output.tryEmit(TerminalOutput(sessionId, message.encodeToByteArray(), TerminalOutputSource.LOCAL_NOTICE))
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

    private fun updateScpTransfer(state: ScpTransferState) {
        _scpTransfers.update { current -> current.filterNot { it.id == state.id } + state }
    }

    private fun updateScpTransfer(transferId: String, phase: ScpTransferPhase, detail: String?) {
        _scpTransfers.update { current ->
            current.map { state ->
                if (state.id == transferId) state.copy(phase = phase, detail = detail) else state
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

    private class ManagedSession(val connection: Connection) {
        @Volatile
        var session: Session? = null
        val readerJobs = mutableListOf<Job>()
        val forwards = ConcurrentHashMap<String, ManagedPortForward>()
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

    private class SshAuthenticationException(message: String) : Exception(message)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val KEY_EXCHANGE_TIMEOUT_MILLIS = 30_000
        const val PROMPT_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val INITIAL_COLUMNS = 80
        const val INITIAL_ROWS = 24
        const val TRUST_APPROVAL = "trust"
        const val MAX_ERROR_LENGTH = 240
        const val MAX_REMOTE_BANNER_CHARS = 2_048
        const val MAX_SCP_STAGING_BYTES = 1024L * 1024L * 1024L
        const val MAX_SCP_FILENAME_CHARS = 255
        const val SAFE_SCP_PATH_CHARACTERS = "._/@%+=:,~-"
        const val MAX_RESOURCE_REPORT_CHARS = 32 * 1024
        const val RESOURCE_COMMAND = "printf 'Host: '; hostname; printf '\\nUptime: '; uptime; printf '\\nLoad: '; cat /proc/loadavg 2>/dev/null || true; printf '\\nMemory:\\n'; free -h 2>/dev/null || true; printf '\\nDisk:\\n'; df -h / 2>/dev/null || true; printf '\\nCPU: '; nproc 2>/dev/null || true"

        fun portForwardRuntimeId(sessionId: String, ruleId: String): String = "$sessionId:$ruleId"
    }
}
