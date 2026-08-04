package website.sung.mangossh.presentation

import android.app.Application
import android.net.Uri
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulator
import website.sung.mangossh.MangoSshApplication
import website.sung.mangossh.R
import website.sung.mangossh.data.keys.KeyPassphraseRequiredException
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.sync.WebDavClient
import website.sung.mangossh.data.sync.WebDavDownloadResult
import website.sung.mangossh.data.sync.WebDavResult
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalThemeId
import website.sung.mangossh.security.AppLockConfiguration
import website.sung.mangossh.security.AppLockStore
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind
import website.sung.mangossh.session.RemoteFilePaths
import website.sung.mangossh.session.ScpTransferState
import website.sung.mangossh.session.SessionEndedEvent
import website.sung.mangossh.session.SessionKind
import website.sung.mangossh.session.SessionPrompt
import website.sung.mangossh.session.SessionEndReason
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.tsnet.TsnetSessionsActiveException

/** Top-level areas exposed by the Compose navigation bar. */
enum class AppSection(val label: String) {
    HOSTS("主机"),
    KEYS("密钥"),
    FORWARDS("转发"),
    SETTINGS("设置"),
}

/** One pending foreground-notification destination, retained across app unlock. */
sealed interface SessionNavigationRequest {
    data object OpenSessions : SessionNavigationRequest

    data class OpenSession(val sessionId: String) : SessionNavigationRequest
}

/** Resolves user-visible failure text while keeping orderly session exits silent. */
internal fun resolveSessionEndMessage(
    event: SessionEndedEvent,
    getString: (Int) -> String,
): String? = event.userMessage ?: when (event.reason) {
    SessionEndReason.USER_REQUEST,
    SessionEndReason.REMOTE_EXIT -> null

    SessionEndReason.CONNECTION_LOST -> getString(R.string.session_ended_connection_lost)
    SessionEndReason.CONNECTION_FAILED -> getString(R.string.session_ended_connection_failed)
}

/**
 * Bridges encrypted vault state and live session state to Compose.
 *
 * Secret-bearing input is passed directly to the vault/session layer and is
 * never copied into UI state beyond the lifetime required for the operation.
 */
class MangoSshViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as MangoSshApplication).sessionRuntime
    private val vault = runtime.vault
    private val keyManager = runtime.keyManager
    private val sessionController = runtime.sessionController
    private val embeddedTsnetManager = runtime.embeddedTsnetManager
    private val terminalAppearanceStore = runtime.terminalAppearance
    private val terminalShortcutStore = runtime.terminalShortcuts
    private val webDavClient = WebDavClient()
    private val appLockStore = AppLockStore(application)

    val hosts = vault.snapshot
        .map { snapshot ->
            snapshot.profiles.sortedWith(
                compareByDescending<ConnectionProfile> { it.favorite }.thenBy { it.label.lowercase() },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultStatus = vault.status

    val keys = vault.snapshot
        .map { it.keys.sortedBy { key -> key.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snippets = vault.snapshot
        .map { it.snippets.sortedBy { snippet -> snippet.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val webDavConfig = vault.snapshot
        .map { it.webDavConfig }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val portForwardRules = vault.snapshot
        .map { snapshot -> snapshot.portForwards.sortedBy { rule -> rule.bindPort } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions = sessionController.sessions
    val activePortForwards = sessionController.portForwards
    val scpTransfers = sessionController.scpTransfers
    val resourceSnapshots = sessionController.resourceSnapshots
    val sessionPrompts = sessionController.prompts
    val sessionEndedEvents = sessionController.sessionEndedEvents
    val terminalClipboardCopies = sessionController.clipboardCopies
    internal val embeddedTsnetStatus = embeddedTsnetManager.status
    val embeddedTsnetAuthorizationUrls = embeddedTsnetManager.authorizationUrls
    val terminalAppearance = terminalAppearanceStore.appearance
    val terminalShortcuts = terminalShortcutStore.config

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage = _userMessage.asStateFlow()

    private val _portableExport = MutableStateFlow<ByteArray?>(null)
    val portableExport = _portableExport.asStateFlow()

    private val _appLockConfiguration = MutableStateFlow(appLockStore.configuration())
    val appLockConfiguration = _appLockConfiguration.asStateFlow()
    private val _appLocked = MutableStateFlow(_appLockConfiguration.value.pinConfigured)
    val appLocked = _appLocked.asStateFlow()

    private val _selectedSection = kotlinx.coroutines.flow.MutableStateFlow(AppSection.HOSTS)
    val selectedSection = _selectedSection.asStateFlow()

    private val _remoteBrowser = MutableStateFlow<RemoteBrowserUiState?>(null)
    /** Null whenever the remote file browser is closed. */
    val remoteBrowser = _remoteBrowser.asStateFlow()

    // Directory listing and preview reads are cancelled when the user moves on,
    // so a slow server cannot overwrite a newer destination.
    private var browserJob: Job? = null
    private var previewJob: Job? = null

    private val _sessionNavigationRequest = MutableStateFlow<SessionNavigationRequest?>(null)
    /** Foreground-notification destination retained until the app lock is cleared. */
    val sessionNavigationRequest = _sessionNavigationRequest.asStateFlow()

    init {
        viewModelScope.launch { vault.open() }
        // A browsed connection can fail while authenticating or drop mid-listing;
        // surface that in the browser instead of leaving a spinner running.
        viewModelScope.launch {
            sessionEndedEvents.collect { event ->
                _remoteBrowser.update { state ->
                    if (state?.sessionId != event.sessionId) {
                        state
                    } else {
                        state.copy(
                            isConnecting = false,
                            isLoading = false,
                            errorMessage = sessionEndMessage(event)
                                ?: getApplication<Application>()
                                    .getString(R.string.session_ended_connection_failed),
                        )
                    }
                }
            }
        }
    }

    fun selectSection(section: AppSection) {
        _selectedSection.value = section
    }

    fun saveHost(draft: ConnectionProfileDraft) {
        if (!draft.isValid()) return
        viewModelScope.launch { vault.upsertProfile(draft.toProfile()) }
    }

    fun removeHost(id: String) {
        viewModelScope.launch { vault.removeProfile(id) }
    }

    fun retryVault() {
        viewModelScope.launch { vault.open() }
    }

    /** Starts the selected SSH or Mosh profile and returns its ephemeral session identifier. */
    fun connect(profile: ConnectionProfile): String = sessionController.connect(profile)

    fun disconnect(sessionId: String) {
        sessionController.close(sessionId)
    }

    fun sendTerminalInput(sessionId: String, bytes: ByteArray) {
        sessionController.send(sessionId, bytes)
    }

    fun resizeTerminal(sessionId: String, columns: Int, rows: Int) {
        sessionController.resize(sessionId, columns, rows)
    }

    /** Persists a bundled terminal font; composed terminals update without reconnecting. */
    fun setTerminalFont(font: TerminalFont) {
        terminalAppearanceStore.setFont(font)
    }

    /** Persists a global base font size used whenever a terminal display is composed. */
    fun setTerminalFontSize(fontSizeSp: Int) {
        terminalAppearanceStore.setFontSize(fontSizeSp)
    }

    /** Applies a bundled terminal palette to existing and future sessions. */
    fun setTerminalTheme(theme: TerminalThemeId) {
        terminalAppearanceStore.setTheme(theme)
        sessionController.applyTerminalAppearance(terminalAppearanceStore.current())
    }

    /** Validated custom display colors layered over a bundled ANSI palette. */
    fun setTerminalCustomColors(colors: TerminalCustomColors) {
        terminalAppearanceStore.setCustomColors(colors)
        sessionController.applyTerminalAppearance(terminalAppearanceStore.current())
    }

    /** Restores the default bundled font, size, and Mango Dark palette. */
    fun resetTerminalAppearance() {
        terminalAppearanceStore.reset()
        sessionController.applyTerminalAppearance(terminalAppearanceStore.current())
    }

    /** Persists the complete ordered floating shortcut layout for this device. */
    fun saveTerminalShortcuts(config: TerminalShortcutConfig) {
        terminalShortcutStore.save(config)
    }

    /** Restores the bundled shortcut layout, including all three transient modifiers. */
    fun resetTerminalShortcuts() {
        terminalShortcutStore.reset()
    }

    /** Returns the retained terminal state for a live session screen. */
    fun terminalEmulator(sessionId: String): TerminalEmulator? = sessionController.terminalEmulator(sessionId)

    /** Records a notification destination without bypassing the app lock. */
    fun requestOpenSession(sessionId: String) {
        _sessionNavigationRequest.value = SessionNavigationRequest.OpenSession(sessionId)
    }

    /** Records a request to show the host screen containing all live sessions. */
    fun requestOpenSessions() {
        _sessionNavigationRequest.value = SessionNavigationRequest.OpenSessions
    }

    /** Marks a notification destination as handled after navigation completes. */
    fun consumeSessionNavigationRequest(request: SessionNavigationRequest) {
        if (_sessionNavigationRequest.value == request) _sessionNavigationRequest.value = null
    }

    /** Maps a lifecycle event to its fixed current-locale message, when one is appropriate. */
    fun sessionEndMessage(event: SessionEndedEvent): String? = resolveSessionEndMessage(event) { resourceId ->
        getApplication<Application>().getString(resourceId)
    }

    fun savePortForward(rule: PortForwardRule) {
        val isDestinationValid = rule.type == website.sung.mangossh.data.vault.PortForwardType.DYNAMIC ||
            (!rule.destinationHost.isNullOrBlank() && rule.destinationPort in 1..65535)
        if (rule.bindPort !in 1..65535 || !isDestinationValid) {
            _userMessage.value = "端口转发配置不完整"
            return
        }
        viewModelScope.launch {
            vault.upsertPortForward(rule)
            _userMessage.value = "已保存端口转发"
        }
    }

    fun removePortForward(ruleId: String) {
        viewModelScope.launch { vault.removePortForward(ruleId) }
    }

    fun saveSnippet(id: String?, label: String, script: String, appendNewline: Boolean) {
        if (label.isBlank() || script.isBlank()) {
            _userMessage.value = "代码片段名称和内容不能为空"
            return
        }
        viewModelScope.launch {
            vault.upsertSnippet(
                CommandSnippet(
                    id = id ?: UUID.randomUUID().toString(),
                    label = label.trim(),
                    script = script,
                    appendNewline = appendNewline,
                ),
            )
            _userMessage.value = "已保存代码片段"
        }
    }

    fun removeSnippet(id: String) {
        viewModelScope.launch { vault.removeSnippet(id) }
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        sessionController.startPortForward(sessionId, rule)
    }

    fun stopPortForward(sessionId: String, ruleId: String) {
        sessionController.stopPortForward(sessionId, ruleId)
    }

    fun requestServerResources(sessionId: String) {
        sessionController.requestServerResources(sessionId)
    }

    /**
     * Opens the remote file browser on a terminal session that is already
     * connected, reusing its authenticated transport.
     *
     * A repeated call for the session already being browsed is ignored so a
     * recomposition cannot throw the user back to their home directory.
     */
    fun openRemoteBrowser(sessionId: String) {
        val existing = _remoteBrowser.value
        if (existing != null && existing.sessionId == sessionId) return
        val session = sessions.value.firstOrNull { it.id == sessionId } ?: return
        closeRemoteBrowser()
        _remoteBrowser.value = RemoteBrowserUiState(
            sessionId = sessionId,
            title = session.title,
            path = RemoteFilePaths.ROOT,
        )
        browserJob = viewModelScope.launch { openHomeDirectory(sessionId) }
    }

    /**
     * Opens the remote file browser on a host that has no session yet.
     *
     * This authenticates a connection dedicated to file transfer, so browsing
     * files does not require starting a shell first. The connection is released
     * when the browser closes, once any transfer it started has finished.
     */
    fun openRemoteBrowserForProfile(profile: ConnectionProfile) {
        val existing = _remoteBrowser.value
        if (existing != null && existing.ownsSession && existing.profileId == profile.id) return
        if (profile.protocol != ConnectionProtocol.SSH) {
            _userMessage.value = getApplication<Application>()
                .getString(R.string.mosh_not_supported_for_ssh_feature)
            return
        }
        closeRemoteBrowser()
        val sessionId = runCatching { sessionController.connectForFileTransfer(profile) }
            .getOrElse { error ->
                _userMessage.value = sessionController.remoteFileMessage(error)
                return
            }
        _remoteBrowser.value = RemoteBrowserUiState(
            sessionId = sessionId,
            title = profile.label,
            path = RemoteFilePaths.ROOT,
            isConnecting = true,
            ownsSession = true,
            profileId = profile.id,
        )
        browserJob = viewModelScope.launch {
            if (!awaitSessionOpen(sessionId)) return@launch
            _remoteBrowser.update { state ->
                if (state?.sessionId == sessionId) state.copy(isConnecting = false) else state
            }
            openHomeDirectory(sessionId)
        }
    }

    /**
     * Suspends until the transfer-only session authenticates.
     *
     * Returns false when it ended first; the failure text is published by the
     * session-ended collector, so no message is produced here.
     */
    private suspend fun awaitSessionOpen(sessionId: String): Boolean = sessions
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .first { it == null || it.phase == TerminalSessionPhase.OPEN }
        ?.phase == TerminalSessionPhase.OPEN

    private suspend fun openHomeDirectory(sessionId: String) {
        val home = runCatching { sessionController.resolveRemoteHome(sessionId) }
            .getOrElse { RemoteFilePaths.ROOT }
        _remoteBrowser.update { state ->
            if (state?.sessionId == sessionId) state.copy(homePath = home) else state
        }
        loadRemoteDirectory(sessionId, home)
    }

    /** Returns to the directory the session's account starts in. */
    fun remoteBrowserHome() {
        val current = _remoteBrowser.value ?: return
        val home = current.homePath ?: return
        if (home == current.path) return
        navigateRemoteBrowser(home)
    }

    /** Lists [path] in the already-open browser. */
    fun navigateRemoteBrowser(path: String) {
        val current = _remoteBrowser.value ?: return
        browserJob?.cancel()
        browserJob = viewModelScope.launch { loadRemoteDirectory(current.sessionId, path) }
    }

    /** Navigates to the parent of the current directory. */
    fun remoteBrowserUp() {
        val current = _remoteBrowser.value ?: return
        if (current.path == RemoteFilePaths.ROOT) return
        navigateRemoteBrowser(RemoteFilePaths.parentOf(current.path))
    }

    /** Re-lists the current directory, for example after an upload. */
    fun refreshRemoteBrowser() {
        val current = _remoteBrowser.value ?: return
        navigateRemoteBrowser(current.path)
    }

    fun closeRemoteBrowser() {
        val current = _remoteBrowser.value
        browserJob?.cancel()
        browserJob = null
        previewJob?.cancel()
        previewJob = null
        _remoteBrowser.value = null
        // A borrowed terminal session keeps running; only a connection this
        // browser opened for itself is handed back.
        if (current?.ownsSession == true) {
            sessionController.releaseFileTransferSession(current.sessionId)
        }
    }

    /**
     * Opens [entry]: directories are listed, files are previewed, and a symlink
     * is resolved on the server first because a listing only reports the link
     * itself, not what it points at.
     */
    fun openRemoteEntry(entry: RemoteFileEntry) {
        val current = _remoteBrowser.value ?: return
        when (entry.kind) {
            RemoteFileKind.DIRECTORY -> navigateRemoteBrowser(entry.path)
            RemoteFileKind.SYMLINK -> viewModelScope.launch {
                val kind = runCatching { sessionController.resolveRemoteKind(current.sessionId, entry.path) }
                    .getOrDefault(RemoteFileKind.FILE)
                if (kind == RemoteFileKind.DIRECTORY) {
                    navigateRemoteBrowser(entry.path)
                } else {
                    previewRemoteFile(entry.path)
                }
            }

            RemoteFileKind.FILE, RemoteFileKind.OTHER -> previewRemoteFile(entry.path)
        }
    }

    /** Loads a read-only text preview of a remote file. */
    fun previewRemoteFile(path: String) {
        val current = _remoteBrowser.value ?: return
        _remoteBrowser.value = current.copy(preview = RemotePreviewUiState(path = path))
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val result = runCatching { sessionController.readRemoteTextPreview(current.sessionId, path) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val preview = result.fold(
                onSuccess = { content ->
                    RemotePreviewUiState(
                        path = path,
                        isLoading = false,
                        content = content,
                        isBinary = content == null,
                    )
                },
                onFailure = { error ->
                    RemotePreviewUiState(
                        path = path,
                        isLoading = false,
                        errorMessage = sessionController.remoteFileMessage(error),
                    )
                },
            )
            _remoteBrowser.update { state ->
                if (state?.preview?.path == path) state.copy(preview = preview) else state
            }
        }
    }

    fun dismissRemotePreview() {
        previewJob?.cancel()
        previewJob = null
        _remoteBrowser.update { state -> state?.copy(preview = null) }
    }

    /** Downloads a browsed remote file into a document the user selected. */
    fun downloadRemoteFile(remotePath: String, destination: Uri) {
        val current = _remoteBrowser.value ?: return
        sessionController.downloadRemoteFile(current.sessionId, remotePath, destination)
    }

    /** Downloads a browsed remote directory into a folder the user selected. */
    fun downloadRemoteDirectory(remotePath: String, destinationTree: Uri) {
        val current = _remoteBrowser.value ?: return
        sessionController.downloadRemoteDirectory(current.sessionId, remotePath, destinationTree)
    }

    /** Uploads a selected document into the directory currently being browsed. */
    fun uploadToRemoteBrowser(source: Uri, displayName: String) {
        val current = _remoteBrowser.value ?: return
        runCatching {
            sessionController.uploadRemoteFile(current.sessionId, source, displayName, current.path)
        }.onFailure { error ->
            _userMessage.value = sessionController.remoteFileMessage(error)
        }
    }

    /** Uploads a selected local folder into the directory currently being browsed. */
    fun uploadDirectoryToRemoteBrowser(sourceTree: Uri, displayName: String) {
        val current = _remoteBrowser.value ?: return
        runCatching {
            sessionController.uploadRemoteDirectory(current.sessionId, sourceTree, displayName, current.path)
        }.onFailure { error ->
            _userMessage.value = sessionController.remoteFileMessage(error)
        }
    }

    fun pauseTransfer(transferId: String) = sessionController.pauseTransfer(transferId)

    fun resumeTransfer(transferId: String) = sessionController.resumeTransfer(transferId)

    fun cancelTransfer(transferId: String) = sessionController.cancelTransfer(transferId)

    fun retryTransfer(transferId: String) = sessionController.retryTransfer(transferId)

    fun clearFinishedTransfers() = sessionController.clearFinishedTransfers()

    /**
     * Reopens the remote browser on the directory a finished upload landed in.
     *
     * Only the connection the upload ran on is reused; a closed session cannot
     * be reopened from here because its prompts would have nowhere to render.
     */
    fun openRemoteDirectoryFromTransfer(transfer: ScpTransferState) {
        val session = sessions.value.firstOrNull { it.id == transfer.sessionId }
        if (session == null || session.phase != TerminalSessionPhase.OPEN) {
            _userMessage.value = getApplication<Application>()
                .getString(R.string.remote_file_transfer_session_closed)
            return
        }
        if (_remoteBrowser.value?.sessionId == transfer.sessionId) {
            navigateRemoteBrowser(transfer.remotePath)
            return
        }
        closeRemoteBrowser()
        // A transfer-only connection stays owned by the browser, so closing it
        // still hands that connection back.
        _remoteBrowser.value = RemoteBrowserUiState(
            sessionId = transfer.sessionId,
            title = session.title,
            path = transfer.remotePath,
            ownsSession = session.kind == SessionKind.FILE_TRANSFER,
            profileId = session.profileId.takeIf { session.kind == SessionKind.FILE_TRANSFER },
        )
        browserJob = viewModelScope.launch {
            val home = runCatching { sessionController.resolveRemoteHome(transfer.sessionId) }
                .getOrElse { RemoteFilePaths.ROOT }
            _remoteBrowser.update { state ->
                if (state?.sessionId == transfer.sessionId) state.copy(homePath = home) else state
            }
            loadRemoteDirectory(transfer.sessionId, transfer.remotePath)
        }
    }

    private suspend fun loadRemoteDirectory(sessionId: String, path: String) {
        _remoteBrowser.update { state ->
            state?.copy(path = path, isLoading = true, errorMessage = null)
        }
        runCatching { sessionController.listRemoteDirectory(sessionId, path) }
            .onSuccess { listing ->
                _remoteBrowser.update { state ->
                    if (state?.sessionId != sessionId) {
                        state
                    } else {
                        state.copy(
                            path = listing.path,
                            entries = listing.entries,
                            truncated = listing.truncated,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _remoteBrowser.update { state ->
                    if (state?.sessionId != sessionId) {
                        state
                    } else {
                        state.copy(
                            isLoading = false,
                            errorMessage = sessionController.remoteFileMessage(error),
                        )
                    }
                }
            }
    }

    fun respondToSessionPrompt(prompt: SessionPrompt, values: List<String>?) {
        sessionController.respondToPrompt(prompt.requestId, values)
    }

    /** Generates the selected key type away from the main dispatcher and saves it encrypted. */
    fun generateKey(type: SshKeyGenerationType, label: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    keyManager.generateKey(type, label)
                }
            }
                .onSuccess {
                    _userMessage.value = if (vault.upsertKey(it)) {
                        "已生成 ${it.label}。"
                    } else {
                        "无法保存加密保险库。数据未被覆盖。"
                    }
                }
                .onFailure {
                    _userMessage.value = "无法生成密钥。"
                }
        }
    }

    fun importPrivateKey(label: String, contents: String, passphrase: String?) {
        viewModelScope.launch {
            runCatching { keyManager.importPrivateKey(label, contents, passphrase) }
                .onSuccess {
                    _userMessage.value = if (vault.upsertKey(it)) {
                        "已导入 ${it.label}。"
                    } else {
                        "无法保存加密保险库。数据未被覆盖。"
                    }
                }
                .onFailure { error ->
                    _userMessage.value = when (error) {
                        is KeyPassphraseRequiredException -> "此密钥需要口令。"
                        else -> "无法导入私钥。请检查格式和口令。"
                    }
                }
        }
    }

    fun removeKey(id: String) {
        viewModelScope.launch { vault.removeKey(id) }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun beginEmbeddedTsnetBrowserEnrollment() {
        viewModelScope.launch {
            runCatching { embeddedTsnetManager.beginBrowserEnrollment() }
                .onFailure {
                    _userMessage.value = getApplication<Application>()
                        .getString(R.string.embedded_tsnet_browser_start_failed)
                }
        }
    }

    /** Hands a one-shot mutable key directly to the runtime and clears it on every path. */
    fun beginEmbeddedTsnetAuthKeyEnrollment(authKey: CharArray) {
        viewModelScope.launch {
            try {
                runCatching { embeddedTsnetManager.beginAuthKeyEnrollment(authKey) }
                    .onFailure {
                        _userMessage.value = getApplication<Application>()
                            .getString(R.string.embedded_tsnet_auth_key_failed)
                    }
            } finally {
                authKey.fill('\u0000')
            }
        }
    }

    fun logoutEmbeddedTsnet() {
        viewModelScope.launch {
            runCatching { embeddedTsnetManager.logout() }
                .onSuccess {
                    _userMessage.value = getApplication<Application>()
                        .getString(R.string.embedded_tsnet_logout_complete)
                }
                .onFailure { error ->
                    _userMessage.value = getApplication<Application>().getString(
                        if (error is TsnetSessionsActiveException) {
                            R.string.embedded_tsnet_logout_sessions_active
                        } else {
                            R.string.embedded_tsnet_logout_failed
                        },
                    )
                }
        }
    }

    fun reportUserMessage(message: String) {
        _userMessage.value = message
    }

    fun saveWebDavConfig(
        endpoint: String,
        username: String,
        password: String,
        remoteFileName: String,
    ) {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        val normalizedFileName = remoteFileName.trim().trimStart('/')
        if (!normalizedEndpoint.startsWith("https://") || username.isBlank() || normalizedFileName.isBlank()) {
            _userMessage.value = "请填写 HTTPS WebDAV 地址、用户名和远端文件名。"
            return
        }
        viewModelScope.launch {
            vault.saveWebDavConfig(
                WebDavConfig(
                    endpoint = normalizedEndpoint,
                    username = username.trim(),
                    password = password,
                    remoteFileName = normalizedFileName,
                ),
            )
            _userMessage.value = "已保存 WebDAV 配置。"
        }
    }

    fun clearWebDavConfig() {
        viewModelScope.launch { vault.saveWebDavConfig(null) }
    }

    fun preparePortableExport(passphrase: String) {
        viewModelScope.launch {
            runCatching { vault.exportPortable(passphrase.toCharArray()) }
                .onSuccess { blob ->
                    _portableExport.value = blob
                    _userMessage.value = "请选择备份文件的保存位置。"
                }
                .onFailure {
                    _userMessage.value = "无法创建加密备份。请确认已设置同步口令。"
                }
        }
    }

    fun consumePortableExport() {
        _portableExport.value = null
    }

    fun importPortable(bytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            runCatching { vault.importPortable(bytes, passphrase.toCharArray()) }
                .onSuccess { _userMessage.value = "已导入加密备份，主机与密钥已替换。" }
                .onFailure { _userMessage.value = "无法导入备份：口令错误或文件已损坏。" }
        }
    }

    fun uploadWebDav(passphrase: String) {
        viewModelScope.launch {
            val config = vault.snapshot.value.webDavConfig
            if (config == null) {
                _userMessage.value = "请先配置 WebDAV。"
                return@launch
            }
            val blob = runCatching { vault.exportPortable(passphrase.toCharArray()) }.getOrElse {
                _userMessage.value = "无法创建加密备份。"
                return@launch
            }
            when (val result = webDavClient.upload(config, blob)) {
                WebDavResult.Success -> _userMessage.value = "已上传加密备份到 WebDAV。"
                is WebDavResult.Failure -> _userMessage.value = result.message
            }
        }
    }

    fun downloadWebDavAndImport(passphrase: String) {
        viewModelScope.launch {
            val config = vault.snapshot.value.webDavConfig
            if (config == null) {
                _userMessage.value = "请先配置 WebDAV。"
                return@launch
            }
            when (val result = webDavClient.download(config)) {
                is WebDavDownloadResult.Failure -> _userMessage.value = result.message
                is WebDavDownloadResult.Success -> {
                    runCatching { vault.importPortable(result.encryptedBlob, passphrase.toCharArray()) }
                        .onSuccess { _userMessage.value = "已从 WebDAV 导入加密备份。" }
                        .onFailure { _userMessage.value = "无法导入 WebDAV 备份：口令错误或文件已损坏。" }
                }
            }
        }
    }

    /** Converts the PIN briefly to a mutable array so it can be cleared after the verifier is stored. */
    fun configureAppPin(pin: String) {
        val chars = pin.toCharArray()
        try {
            appLockStore.setPin(chars)
            _appLockConfiguration.value = appLockStore.configuration()
            _appLocked.value = false
            _userMessage.value = "已启用应用 PIN 解锁。"
        } catch (_: IllegalArgumentException) {
            _userMessage.value = "PIN 必须为 4 到 12 位数字。"
        } finally {
            chars.fill('\u0000')
        }
    }

    fun clearAppLock() {
        appLockStore.clear()
        _appLockConfiguration.value = appLockStore.configuration()
        _appLocked.value = false
        _userMessage.value = "已关闭应用锁。"
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        runCatching { appLockStore.setBiometricEnabled(enabled) }
            .onSuccess { _appLockConfiguration.value = appLockStore.configuration() }
            .onFailure { _userMessage.value = "请先设置应用 PIN。" }
    }

    fun lockForBackground() {
        if (_appLockConfiguration.value.pinConfigured) _appLocked.value = true
    }

    fun unlockWithPin(pin: String) {
        val chars = pin.toCharArray()
        try {
            if (appLockStore.verifyPin(chars)) {
                _appLocked.value = false
            } else {
                _userMessage.value = "PIN 不正确。"
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun unlockWithBiometrics() {
        if (_appLockConfiguration.value.biometricEnabled) _appLocked.value = false
    }

}
