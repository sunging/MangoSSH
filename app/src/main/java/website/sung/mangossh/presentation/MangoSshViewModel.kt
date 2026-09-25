package website.sung.mangossh.presentation

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulator
import website.sung.mangossh.MangoSshApplication
import website.sung.mangossh.R
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.data.keys.KeyPassphraseRequiredException
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.sync.WebDavClient
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.domain.AppThemeMode
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.HostSortMode
import website.sung.mangossh.domain.matchesHostQuery
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalDelKeyMode
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalRightAltMode
import website.sung.mangossh.domain.SshTerminalType
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalThemeId
import website.sung.mangossh.domain.AppLockDelay
import website.sung.mangossh.domain.shouldLockWhenBackgrounded
import website.sung.mangossh.domain.shouldLockOnResume
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
import website.sung.mangossh.session.SessionEndMessageKind
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.SessionAttention
import website.sung.mangossh.session.sessionAttention
import website.sung.mangossh.session.tsnet.TsnetSessionsActiveException
import website.sung.mangossh.presentation.settings.SettingsDestination
import website.sung.mangossh.presentation.update.DistributionUpdateManager

/** Top-level areas exposed by the Compose navigation bar. */
enum class AppSection {
    HOSTS,
    KEYS,
    FORWARDS,
    SETTINGS,
}

/** One pending foreground-notification destination, retained across app unlock. */
sealed interface SessionNavigationRequest {
    data object OpenSessions : SessionNavigationRequest

    data class OpenSession(val sessionId: String) : SessionNavigationRequest
}

/** Resolves user-visible failure text while keeping orderly session exits silent. */
internal fun resolveSessionEndMessage(
    event: SessionEndedEvent,
): UiText? = event.messageKind?.toUiText() ?: when (event.reason) {
    SessionEndReason.USER_REQUEST,
    SessionEndReason.REMOTE_EXIT -> null

    SessionEndReason.CONNECTION_LOST -> uiText(R.string.session_ended_connection_lost)
    SessionEndReason.CONNECTION_FAILED -> uiText(R.string.session_ended_connection_failed)
}

/** Resolves only the fixed failure category, never remote exception text. */
internal fun SessionEndMessageKind.toUiText(): UiText = uiText(
    when (this) {
        SessionEndMessageKind.AUTHENTICATION_FAILED -> R.string.session_ended_authentication_failed
        SessionEndMessageKind.DSA_KEY_UNSUPPORTED -> R.string.ssh_dsa_unsupported
        SessionEndMessageKind.KEY_ENCRYPTION_UNSUPPORTED -> R.string.ssh_key_encryption_unsupported
        SessionEndMessageKind.MOSH_BOOTSTRAP_FAILED -> R.string.mosh_bootstrap_failed
        SessionEndMessageKind.MOSH_RUNTIME_MISSING -> R.string.mosh_runtime_missing
        SessionEndMessageKind.TSNET_ENROLLMENT_REQUIRED -> R.string.embedded_tsnet_enrollment_required
        SessionEndMessageKind.INPUT_OVERFLOW -> R.string.session_input_overflow
        SessionEndMessageKind.FOREGROUND_SERVICE_UNAVAILABLE -> R.string.session_foreground_service_unavailable
    },
)

/**
 * Bridges encrypted vault state and live session state to Compose.
 *
 * Secret-bearing input is passed directly to the vault/session layer and is
 * never copied into UI state beyond the lifetime required for the operation.
 */
class MangoSshViewModel @JvmOverloads constructor(
    application: Application,
    private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {
    private val runtime = (application as MangoSshApplication).sessionRuntime
    private val vault = runtime.vault
    private val keyManager = runtime.keyManager
    private val sessionController = runtime.sessionController
    private val embeddedTsnetManager = runtime.embeddedTsnetManager
    private val terminalAppearanceStore = runtime.terminalAppearance
    private val terminalBehaviorStore = runtime.terminalBehavior
    private val terminalShortcutStore = runtime.terminalShortcuts
    private val appThemeStore = runtime.appTheme
    private val connectionPreferencesStore = runtime.connectionPreferences
    internal val backupCoordinator = website.sung.mangossh.data.vault.BackupCoordinator(application, vault, viewModelScope, unlocked = { !_appLocked.value }, appVersion = { installedVersionName })
    val backupOperation = backupCoordinator.state
    private val appLockStore = AppLockStore(application)
    private val updatePreferencesStore = runtime.updatePreferences
    private val updateManager = DistributionUpdateManager(
        application = application,
        installedAppInfo = runtime.installedAppInfo,
        preferencesStore = updatePreferencesStore,
        scope = viewModelScope,
    )
    private val hostListPreferencesStore = runtime.hostListPreferences

    private val _hostQuery = MutableStateFlow("")

    /** Free-text filter applied only to the host list screen, never to other pickers of it. */
    val hostQuery = _hostQuery.asStateFlow()

    /** Device-local host list sort preference, shared with the top bar sort menu. */
    val hostSortMode = hostListPreferencesStore.sortMode

    /**
     * Every profile in the selected sort order, unfiltered by search. Other screens such as the
     * port-forward host picker read this list and must keep seeing every host regardless of what
     * is typed into the host list's search field.
     */
    val hosts = combine(vault.snapshot, hostSortMode) { snapshot, mode ->
        snapshot.profiles.sortedWith(mode.comparator())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hosts shown on the host list screen after the search filter is applied. */
    val visibleHosts = combine(hosts, _hostQuery) { list, query ->
        list.filter { it.matchesHostQuery(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Drag-to-reorder and the move/move-to-top menu items only make sense in manual, unfiltered order. */
    val hostsReorderable = combine(hostSortMode, _hostQuery) { mode, query ->
        mode == HostSortMode.MANUAL && query.isBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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

    val endedTerminals = sessionController.endedTerminals
    fun diagnostics(sessionId: String) = sessionController.diagnostics(sessionId)

    /** What, if anything, explains trouble on each open session; see [sessionAttention]. */
    val sessionAttention: StateFlow<Map<String, SessionAttention>> = combine(
        sessionController.sessions, sessionController.sessionHealth, sessionController.network,
    ) { sessions, health, network ->
        sessions.associate { it.id to sessionAttention(it.phase, health[it.id], network) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    fun openWorkspace(sessionId: String, workspace: website.sung.mangossh.domain.TmuxWorkspace): String? {
        val profileId = sessions.value.firstOrNull { it.id == sessionId }?.profileId
        val required = vault.snapshot.value.profiles.firstOrNull { it.id == profileId }?.requireReauthentication
        if (!authorizeSensitive(required) { openWorkspace(sessionId, workspace)?.let { _sessionNavigationRequest.value = SessionNavigationRequest.OpenSession(it) } }) return null
        return sessionController.openWorkspace(sessionId, workspace)
    }
    suspend fun listWorkspaces(sessionId: String) = sessionController.listWorkspaces(sessionId)
    fun clearEndedTerminals() = sessionController.clearEndedTerminals()
    fun reconnectEnded(sessionId: String, allowStartupSnippet: Boolean): String? {
        val ended = endedTerminals.value.firstOrNull { it.session.id == sessionId } ?: return null
        val profile = vault.snapshot.value.profiles.firstOrNull { it.id == ended.session.profileId } ?: return null
        return connect(if (allowStartupSnippet) profile else profile.copy(startupSnippetId = null))
    }
    val sessions = sessionController.sessions
    val activePortForwards = sessionController.portForwards
    val scpTransfers = sessionController.scpTransfers
    val transferConflicts = sessionController.transferConflicts
    fun resolveTransferConflict(id: String, decision: website.sung.mangossh.session.TransferConflictDecision) = sessionController.resolveTransferConflict(id, decision)
    val resourceSnapshots = sessionController.resourceSnapshots
    val sessionPrompts = sessionController.prompts
    val sessionEndedEvents = sessionController.sessionEndedEvents
    val terminalClipboardCopies = sessionController.clipboardCopies
    internal val embeddedTsnetStatus = embeddedTsnetManager.status
    val embeddedTsnetAuthorizationUrls = embeddedTsnetManager.authorizationUrls
    val terminalAppearance = terminalAppearanceStore.appearance
    val terminalBehavior = terminalBehaviorStore.behavior
    val terminalShortcuts = terminalShortcutStore.config
    val appTheme = appThemeStore.preferences
    val connectionPreferences = connectionPreferencesStore.preferences

    /** Installed package version name, read from the platform rather than BuildConfig, for the About page. */
    val installedVersionName: String get() = runtime.installedAppInfo?.versionName.orEmpty()

    /** Installed package version code, read from the platform rather than BuildConfig, for the About page. */
    val installedVersionCode: Long get() = runtime.installedAppInfo?.versionCode ?: 0L

    /** Immutable self-update state for the settings card and the settings navigation badge. */
    val updateState: StateFlow<UpdateUiState> = updateManager.state

    private val _userMessage = MutableStateFlow<UiText?>(null)
    val userMessage = _userMessage.asStateFlow()

    /**
     * Per-session font size committed by a pinch-to-zoom gesture on the terminal, keyed by
     * session id. Device-local UI state only: never persisted across process death and
     * cleared whenever the global [terminalAppearance] font size changes or the session ends.
     */
    private val _sessionFontSizeOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionFontSizeOverrides = _sessionFontSizeOverrides.asStateFlow()


    private val _appLockConfiguration = MutableStateFlow(appLockStore.configuration())
    val appLockConfiguration = _appLockConfiguration.asStateFlow()
    private val _appLocked = runtime.accessState.locked
    private val reauthenticationWindow = website.sung.mangossh.security.ReauthenticationWindow(runtime.accessState)
    private val _reauthenticationPending = MutableStateFlow(false)
    val reauthenticationPending = _reauthenticationPending.asStateFlow()
    private var pendingSensitiveAction: (() -> Unit)? = null
    private var approvedSensitiveAction = false
    private var pendingSensitiveCancel: (() -> Unit)? = null

    private fun authorizeSensitive(override: Boolean? = null, onCancelled: (() -> Unit)? = null, action: () -> Unit): Boolean {
        if (_appLocked.value) { onCancelled?.invoke(); return false }
        val mode = _appLockConfiguration.value.reauthentication
        val required = override ?: (mode != website.sung.mangossh.security.ReauthenticationMode.DISABLED)
        if (!required || approvedSensitiveAction ||
            (mode == website.sung.mangossh.security.ReauthenticationMode.FIVE_MINUTES && reauthenticationWindow.isValid())) return true
        if (!_appLockConfiguration.value.pinConfigured) {
            _userMessage.value = uiText(R.string.message_app_pin_required)
            onCancelled?.invoke()
            return false
        }
        if (pendingSensitiveAction == null) {
            pendingSensitiveAction = action
            pendingSensitiveCancel = onCancelled
            _reauthenticationPending.value = true
        } else onCancelled?.invoke()
        return false
    }

    fun cancelReauthentication() { pendingSensitiveAction = null; pendingSensitiveCancel?.invoke(); pendingSensitiveCancel = null; _reauthenticationPending.value = false }

    /** The pending operation resumes only after the existing PBKDF2 verifier accepts the PIN. */
    fun verifySensitiveAction(pin: String) {
        if (_appLockBusy.value || pendingSensitiveAction == null) return
        _appLockBusy.value = true
        val generation = runtime.accessState.generation
        val requestedAction = pendingSensitiveAction
        val chars = pin.toCharArray()
        viewModelScope.launch {
            try {
                val accepted = withContext(cryptoDispatcher) { appLockStore.verifyPin(chars) }
                // A cancelled prompt cannot authorize a different action queued during slow derivation.
                if (pendingSensitiveAction !== requestedAction) return@launch
                if (accepted && generation == runtime.accessState.generation && !_appLocked.value) {
                    reauthenticationWindow.grant()
                    val action = pendingSensitiveAction
                    pendingSensitiveCancel = null
                    cancelReauthentication()
                    approvedSensitiveAction = true
                    try { action?.invoke() } finally { approvedSensitiveAction = false }
                } else {
                    val remaining = appLockStore.cooldownRemainingMillis()
                    _userMessage.value = if (remaining > 0) uiText(R.string.message_pin_cooldown, (remaining + 999) / 1000)
                        else uiText(R.string.message_pin_incorrect)
                }
            } finally { chars.fill('\u0000'); _appLockBusy.value = false }
        }
    }

    fun setReauthentication(mode: website.sung.mangossh.security.ReauthenticationMode) {
        if (!authorizeSensitive { setReauthentication(mode) }) return
        appLockStore.setReauthentication(mode)
        _appLockConfiguration.value = appLockStore.configuration()
    }

    fun prepareBackupExport(password: String?, remember: Boolean, includeConfig: Boolean) {
        if (!authorizeSensitive { prepareBackupExport(password, remember, includeConfig) }) return
        backupCoordinator.prepareExport(password, remember, includeConfig)
    }

    private val _appLockBusy = MutableStateFlow(false)
    val appLockBusy = _appLockBusy.asStateFlow()
    val appLocked = _appLocked.asStateFlow()

    /** Elapsed-realtime timestamp set by [noteBackgrounded] and consumed by [evaluateAutoLock]. */
    private var backgroundedAtElapsedMillis: Long? = null

    private val _selectedSection = kotlinx.coroutines.flow.MutableStateFlow(AppSection.HOSTS)
    val selectedSection = _selectedSection.asStateFlow()

    private val _settingsDestination = MutableStateFlow<SettingsDestination?>(null)

    /**
     * Open Settings detail page, or `null` for the category hub.
     *
     * Held here rather than in Compose state because the shared top app bar
     * in `MangoSshApp` renders the detail title and back affordance from
     * outside `SettingsScreen`'s own composition, and because leaving the
     * Settings tab must return to the hub. This view model has no
     * `SavedStateHandle`, so a process death restores Settings to the hub —
     * acceptable, and safer than resuming into a security-sensitive page.
     */
    internal val settingsDestination = _settingsDestination.asStateFlow()

    internal fun openSettingsDestination(destination: SettingsDestination) {
        _settingsDestination.value = destination
    }

    internal fun closeSettingsDestination() {
        _settingsDestination.value = null
    }

    private val _remoteEditor = MutableStateFlow<RemoteEditorUiState?>(null)
    internal val remoteEditor = _remoteEditor.asStateFlow()
    private var editorLoading = false

    fun editRemoteText(path: String) {
        val sessionId = _remoteBrowser.value?.sessionId ?: return
        if (editorLoading) return
        editorLoading = true
        viewModelScope.launch {
            try {
                val source = sessionController.readEditableText(sessionId, path)
                _remoteEditor.value = RemoteEditorUiState(sessionId, source)
            } catch (_: Exception) { _userMessage.value = uiText(R.string.editor_failure) }
            finally { editorLoading = false }
        }
    }
    fun changeRemoteDraft(text: String) { _remoteEditor.update { if (it?.busy == false) it.copy(draft = text) else it } }
    fun closeRemoteEditor() { if (_remoteEditor.value?.busy != true) _remoteEditor.value = null }
    fun reloadRemoteEditor() { _remoteEditor.value?.let { editRemoteText(it.source.path) } }
    fun saveRemoteEditor(alternateName: String?, allowDirect: Boolean) {
        val current = _remoteEditor.value ?: return
        if (current.busy) return
        _remoteEditor.value = current.copy(busy = true, conflict = false, needsDirectApproval = false, failed = false)
        viewModelScope.launch {
            try {
                sessionController.saveEditableText(current.sessionId, current.source, current.draft, alternateName, allowDirect)
                _remoteEditor.value = null
                dismissRemotePreview()
                refreshRemoteBrowser()
                _userMessage.value = uiText(R.string.editor_saved)
            } catch (_: website.sung.mangossh.session.SourceChangedException) {
                _remoteEditor.value = current.copy(conflict = true)
            } catch (_: website.sung.mangossh.session.AtomicReplaceUnavailableException) {
                _remoteEditor.value = current.copy(needsDirectApproval = true)
            } catch (_: Exception) { _remoteEditor.value = current.copy(failed = true) }
        }
    }

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
                                ?: uiText(R.string.session_ended_connection_failed),
                        )
                    }
                }
                clearSessionTerminalFontSize(event.sessionId)
            }
        }
        viewModelScope.launch { updateManager.initializeAfterUnlock(appLocked) }
    }

    fun selectSection(section: AppSection) {
        _selectedSection.value = section
        // Re-entering Settings from another tab should always land on the hub.
        _settingsDestination.value = null
    }

    /** The originating editor owns completion, including delayed reauthentication. */
    fun saveHost(draft: ConnectionProfileDraft, operation: EditorSaveOperation = EditorSaveOperation()) {
        if (!operation.begin()) return
        fun persist() {
            if (!operation.pending) return
            if (!draft.isValid()) { operation.finish(uiText(R.string.vault_invalid)); return }
            viewModelScope.launch { operation.finish(mutationError(vault.upsertProfile(draft.toProfile()))) }
        }
        if (authorizeSensitive(vault.snapshot.value.profiles.firstOrNull { it.id == draft.id }?.requireReauthentication,
                onCancelled = operation::cancel) { persist() }) persist()
    }

    private fun mutationError(result: website.sung.mangossh.data.vault.VaultMutationResult): UiText? = when (result) {
        website.sung.mangossh.data.vault.VaultMutationResult.Success -> null
        is website.sung.mangossh.data.vault.VaultMutationResult.ReferencedBy -> uiText(R.string.vault_referenced,
            result.profileIds.mapNotNull { id -> vault.snapshot.value.profiles.firstOrNull { it.id == id }?.label }.joinToString(", "))
        website.sung.mangossh.data.vault.VaultMutationResult.Invalid -> uiText(R.string.vault_invalid)
        website.sung.mangossh.data.vault.VaultMutationResult.NotReady -> uiText(R.string.vault_not_ready)
        website.sung.mangossh.data.vault.VaultMutationResult.StorageFailure -> uiText(R.string.vault_write_failed)
    }

    fun importSshProfiles(profiles: List<ConnectionProfile>) {
        if (!authorizeSensitive { importSshProfiles(profiles) }) return
        viewModelScope.launch {
            val saved = vault.importProfiles(profiles)
            _userMessage.value = uiText(if (saved.isSuccess) R.string.ssh_config_saved else R.string.ssh_config_invalid)
        }
    }

    fun removeHost(id: String) {
        viewModelScope.launch { _userMessage.value = mutationError(vault.removeProfile(id)) }
    }

    fun retryVault() {
        viewModelScope.launch { vault.open() }
    }

    fun setHostQuery(query: String) {
        _hostQuery.value = query
    }

    fun clearHostQuery() {
        _hostQuery.value = ""
    }

    fun setHostSortMode(mode: HostSortMode) {
        hostListPreferencesStore.setSortMode(mode)
    }

    /** Persists a manual drag or menu reorder; [orderedIds] need not include every host. */
    fun reorderHosts(orderedIds: List<String>) {
        viewModelScope.launch { vault.reorderProfiles(orderedIds) }
    }

    /** Moves one host [delta] places within the current manual order; a no-op past either end. */
    fun moveHost(id: String, delta: Int) {
        val currentOrder = hosts.value.map { it.id }
        val from = currentOrder.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, currentOrder.lastIndex)
        if (to == from) return
        reorderHosts(currentOrder.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun moveHostToTop(id: String) {
        val currentOrder = hosts.value.map { it.id }
        val from = currentOrder.indexOf(id)
        if (from <= 0) return
        reorderHosts(currentOrder.toMutableList().apply { add(0, removeAt(from)) })
    }

    /** Starts the selected SSH or Mosh profile and returns its ephemeral session identifier. */
    fun connect(profile: ConnectionProfile): String? {
        if (!authorizeSensitive(profile.requireReauthentication) { connect(profile)?.let { _sessionNavigationRequest.value = SessionNavigationRequest.OpenSession(it) } }) return null
        viewModelScope.launch { vault.recordProfileConnection(profile.id, System.currentTimeMillis()) }
        return sessionController.connect(profile)
    }

    fun disconnect(sessionId: String) {
        sessionController.close(sessionId)
    }

    fun sendTerminalInput(sessionId: String, bytes: ByteArray) {
        sessionController.send(sessionId, bytes)
    }

    fun resizeTerminal(sessionId: String, columns: Int, rows: Int) {
        sessionController.resize(sessionId, columns, rows)
    }

    /** Persists the app-wide theme mode; the whole Compose tree recomposes with the new scheme. */
    fun setAppThemeMode(mode: AppThemeMode) {
        appThemeStore.setMode(mode)
    }

    /** Persists whether Material You dynamic color is used on API 31+. */
    fun setDynamicColorEnabled(enabled: Boolean) {
        appThemeStore.setDynamicColorEnabled(enabled)
    }

    /** Persists a bundled terminal font; composed terminals update without reconnecting. */
    fun setTerminalFont(font: TerminalFont) {
        terminalAppearanceStore.setFont(font)
    }

    /** Persists a global base font size used whenever a terminal display is composed. */
    fun setTerminalFontSize(fontSizeSp: Int) {
        terminalAppearanceStore.setFontSize(fontSizeSp)
        // A new global base size supersedes any session's pinch-to-zoom result.
        _sessionFontSizeOverrides.value = emptyMap()
    }

    /** Remembers a session's pinch-to-zoom result; does not touch the global [terminalAppearance]. */
    fun setSessionTerminalFontSize(sessionId: String, fontSizeSp: Int) {
        _sessionFontSizeOverrides.update { it + (sessionId to fontSizeSp) }
    }

    /** Drops a session's pinch-to-zoom override, returning it to the global base font size. */
    fun clearSessionTerminalFontSize(sessionId: String) {
        _sessionFontSizeOverrides.update { it - sessionId }
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
        _sessionFontSizeOverrides.value = emptyMap()
    }

    /** Persists the complete ordered floating shortcut layout for this device. */
    fun saveTerminalShortcuts(config: TerminalShortcutConfig) {
        terminalShortcutStore.save(config)
    }

    /** Off-screen line budget applied to sessions created after this call; live sessions are unaffected. */
    fun setTerminalScrollbackLines(lines: Int) {
        terminalBehaviorStore.setScrollbackLines(lines)
    }

    /** Applied to sessions created after this call; live sessions are unaffected. */
    fun setTerminalBoldAsBright(enabled: Boolean) {
        terminalBehaviorStore.setBoldAsBright(enabled)
    }

    /** Applied to sessions created after this call; live sessions are unaffected. */
    fun setTerminalAutoDetectUrls(enabled: Boolean) {
        terminalBehaviorStore.setAutoDetectUrls(enabled)
    }

    /** Applies immediately to the terminal currently on screen. */
    fun setTerminalKeepScreenOn(enabled: Boolean) {
        terminalBehaviorStore.setKeepScreenOn(enabled)
    }

    /** Applies immediately to the terminal currently on screen. */
    fun setTerminalRightAltMode(mode: TerminalRightAltMode) {
        terminalBehaviorStore.setRightAltMode(mode)
    }

    /** Applies immediately to the terminal currently on screen. */
    fun setTerminalDelKeyMode(mode: TerminalDelKeyMode) {
        terminalBehaviorStore.setDelKeyMode(mode)
    }

    /** Applies immediately to the terminal currently on screen. */
    fun setTerminalMaxPinchZoomScale(scale: Float) {
        terminalBehaviorStore.setMaxPinchZoomScale(scale)
    }

    /** Applied to connections opened after this call; zero disables SSH transport keepalives. */
    fun setConnectionKeepaliveSeconds(seconds: Int) {
        connectionPreferencesStore.setKeepaliveSeconds(seconds)
    }

    /**
     * Takes effect on the next background wait of every running session, no reconnect
     * needed. Hosts with their own multiplier keep using it.
     */
    fun setConnectionBackgroundKeepaliveMultiplier(multiplier: Int) {
        connectionPreferencesStore.setBackgroundKeepaliveMultiplier(multiplier)
    }

    /** Applied to connections opened after this call. */
    fun setConnectionTimeoutSeconds(seconds: Int) {
        connectionPreferencesStore.setConnectTimeoutSeconds(seconds)
    }

    /** Applied to connections opened after this call; Mosh always requests xterm-256color regardless. */
    fun setSshTerminalType(type: SshTerminalType) {
        connectionPreferencesStore.setSshTerminalType(type)
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

    /** Maps a lifecycle event to resource-backed wording, keeping orderly exits silent. */
    fun sessionEndMessage(event: SessionEndedEvent): UiText? = resolveSessionEndMessage(event)

    fun savePortForward(rule: PortForwardRule, operation: EditorSaveOperation = EditorSaveOperation()) {
        if (!operation.begin()) return
        val isDestinationValid = rule.type == website.sung.mangossh.data.vault.PortForwardType.DYNAMIC ||
            (!rule.destinationHost.isNullOrBlank() && rule.destinationPort in 1..65535)
        if (rule.bindPort !in 1..65535 || !isDestinationValid) {
            operation.finish(uiText(R.string.message_port_forward_incomplete))
            return
        }
        viewModelScope.launch {
            operation.finish(mutationError(vault.upsertPortForward(rule)))
        }
    }

    fun removePortForward(ruleId: String) {
        viewModelScope.launch { _userMessage.value = mutationError(vault.removePortForward(ruleId)) }
    }

    fun saveSnippet(id: String?, label: String, script: String, appendNewline: Boolean, operation: EditorSaveOperation = EditorSaveOperation()) {
        if (!operation.begin()) return
        if (label.isBlank() || script.isBlank()) {
            operation.finish(uiText(R.string.message_snippet_required))
            return
        }
        viewModelScope.launch {
            val result = vault.upsertSnippet(
                CommandSnippet(
                    id = id ?: UUID.randomUUID().toString(),
                    label = label.trim(),
                    script = script,
                    appendNewline = appendNewline,
                ),
            )
            operation.finish(mutationError(result))
        }
    }

    fun removeSnippet(id: String) {
        viewModelScope.launch { _userMessage.value = mutationError(vault.removeSnippet(id)) }
    }

    fun startPortForward(sessionId: String, rule: PortForwardRule) {
        sessionController.startPortForward(sessionId, rule)
    }

    /**
     * Starts [rule] on a connection opened just for it, so a tunnel does not
     * require a terminal session for the same host.
     */
    fun startPortForwardOnNewConnection(profile: ConnectionProfile, rule: PortForwardRule) {
        if (!authorizeSensitive(profile.requireReauthentication) { startPortForwardOnNewConnection(profile, rule) }) return
        runCatching { sessionController.startPortForwardOnNewConnection(profile, rule) }
            .onFailure {
                _userMessage.value = uiText(R.string.session_ended_connection_failed)
            }
    }

    fun stopPortForward(sessionId: String, ruleId: String) {
        sessionController.stopPortForward(sessionId, ruleId)
    }

    /** Refreshes connection counts shown for running forwards; see the forwards screen. */
    fun refreshPortForwardActivity() = sessionController.refreshPortForwardActivity()

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
        if (!authorizeSensitive(profile.requireReauthentication) { openRemoteBrowserForProfile(profile) }) return
        val existing = _remoteBrowser.value
        if (existing != null && existing.ownsSession && existing.profileId == profile.id) return
        closeRemoteBrowser()
        val sessionId = runCatching { sessionController.connectForFileTransfer(profile) }
            .getOrElse { error ->
                _userMessage.value = sessionController.remoteFileMessage(error).toUiText()
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
                        errorMessage = sessionController.remoteFileMessage(error).toUiText(),
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
    fun downloadRemoteFile(sessionId: String, remotePath: String, destination: Uri) {
        if (!pickerSessionStillOpen(sessionId)) return
        runCatching { sessionController.downloadRemoteFile(sessionId, remotePath, destination) }
            .onFailure { error -> _userMessage.value = sessionController.remoteFileMessage(error).toUiText() }
    }

    /** Downloads a browsed remote directory into a folder the user selected. */
    fun downloadRemoteDirectory(sessionId: String, remotePath: String, destinationTree: Uri) {
        if (!pickerSessionStillOpen(sessionId)) return
        runCatching { sessionController.downloadRemoteDirectory(sessionId, remotePath, destinationTree) }
            .onFailure { error -> _userMessage.value = sessionController.remoteFileMessage(error).toUiText() }
    }

    /**
     * A picker result is applied only to the session that launched the picker. When that
     * session ended while the picker was open, the user is told instead of the choice
     * being dropped silently or sent over another connection.
     */
    private fun pickerSessionStillOpen(sessionId: String): Boolean {
        val open = sessionController.sessions.value.any { it.id == sessionId && it.phase == TerminalSessionPhase.OPEN }
        if (!open) _userMessage.value = uiText(R.string.remote_file_transfer_session_closed)
        return open
    }

    /** Uploads a selected document into the directory currently being browsed. */
    fun uploadToRemoteBrowser(source: Uri, displayName: String) {
        val current = _remoteBrowser.value ?: return
        runCatching {
            sessionController.uploadRemoteFile(current.sessionId, source, displayName, current.path)
        }.onFailure { error ->
            _userMessage.value = sessionController.remoteFileMessage(error).toUiText()
        }
    }

    /** Uploads a selected local folder into the directory currently being browsed. */
    fun uploadDirectoryToRemoteBrowser(sourceTree: Uri, displayName: String) {
        val current = _remoteBrowser.value ?: return
        runCatching {
            sessionController.uploadRemoteDirectory(current.sessionId, sourceTree, displayName, current.path)
        }.onFailure { error ->
            _userMessage.value = sessionController.remoteFileMessage(error).toUiText()
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
    /**
     * Opens a file browser on the host an interrupted transfer belongs to. Once that
     * connection verifies the same server, the transfer is handed to it and can resume.
     */
    fun reconnectForTransfer(transfer: ScpTransferState) {
        val profile = vault.snapshot.value.profiles.firstOrNull { it.id == transfer.profileId }
        if (profile == null) {
            _userMessage.value = uiText(R.string.remote_file_transfer_session_closed)
            return
        }
        openRemoteBrowserForProfile(profile)
    }

    fun openRemoteDirectoryFromTransfer(transfer: ScpTransferState) {
        val session = sessions.value.firstOrNull { it.id == transfer.sessionId }
        if (session == null || session.phase != TerminalSessionPhase.OPEN) {
            _userMessage.value = uiText(R.string.remote_file_transfer_session_closed)
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
                            errorMessage = sessionController.remoteFileMessage(error).toUiText(),
                        )
                    }
                }
            }
    }

    fun respondToSessionPrompt(prompt: SessionPrompt, values: List<String>?) {
        sessionController.respondToPrompt(prompt.requestId, values)
    }

    private val _keyOperationBusy = MutableStateFlow(false)
    val keyOperationBusy = _keyOperationBusy.asStateFlow()

    /** Performs a selected private-key export only after the configured access check. */
    fun exportPrivateKey(id: String, destination: Uri) {
        if (_keyOperationBusy.value || !authorizeSensitive { exportPrivateKey(id, destination) }) return
        val key = vault.snapshot.value.keys.firstOrNull { it.id == id } ?: return
        val authorizationGeneration = runtime.accessState.generation
        _keyOperationBusy.value = true
        viewModelScope.launch {
            try {
                withContext(cryptoDispatcher) {
                    check(!runtime.accessState.locked.value && authorizationGeneration == runtime.accessState.generation)
                    val bytes = key.privateKeyPem.encodeToByteArray()
                    try {
                        getApplication<Application>().contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
                            ?: throw java.io.IOException()
                    } finally { bytes.fill(0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _userMessage.value = uiText(R.string.message_key_export_failed)
            } finally { _keyOperationBusy.value = false }
        }
    }

    /** Generates the selected key type away from the main dispatcher and saves it encrypted. */
    fun generateKey(type: SshKeyGenerationType, label: String) {
        if (_keyOperationBusy.value || !authorizeSensitive { generateKey(type, label) }) return
        _keyOperationBusy.value = true
        viewModelScope.launch {
          try {
            runCatching {
                withContext(cryptoDispatcher) {
                    keyManager.generateKey(type, label)
                }
            }
                .onSuccess {
                    _userMessage.value = if (vault.upsertKey(it).isSuccess) {
                        uiText(R.string.message_key_generated, it.label)
                    } else {
                        uiText(R.string.message_vault_save_failed)
                    }
                }
                .onFailure {
                    _userMessage.value = uiText(R.string.message_key_generation_failed)
                }
          } finally { _keyOperationBusy.value = false }
        }
    }

    fun importPrivateKey(label: String, contents: String, passphrase: String?) {
        if (_keyOperationBusy.value || !authorizeSensitive { importPrivateKey(label, contents, passphrase) }) return
        _keyOperationBusy.value = true
        viewModelScope.launch {
          try {
            runCatching { withContext(cryptoDispatcher) { keyManager.importPrivateKey(label, contents, passphrase) } }
                .onSuccess {
                    _userMessage.value = if (vault.upsertKey(it).isSuccess) {
                        uiText(R.string.message_key_imported, it.label)
                    } else {
                        uiText(R.string.message_vault_save_failed)
                    }
                }
                .onFailure { error ->
                    _userMessage.value = uiText(
                        if (error is website.sung.mangossh.data.keys.UnsupportedDsaKeyException) {
                            R.string.ssh_dsa_unsupported
                        } else if (error is website.sung.mangossh.data.keys.UnsupportedKeyEncryptionException) {
                            R.string.ssh_key_encryption_unsupported
                        } else if (error is KeyPassphraseRequiredException) {
                            R.string.message_key_passphrase_required
                        } else {
                            R.string.message_key_import_failed
                        },
                    )
                }
          } finally { _keyOperationBusy.value = false }
        }
    }

    fun removeKey(id: String) {
        if (!authorizeSensitive { removeKey(id) }) return
        viewModelScope.launch { _userMessage.value = mutationError(vault.removeKey(id)) }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun beginEmbeddedTsnetBrowserEnrollment() {
        viewModelScope.launch {
            runCatching { embeddedTsnetManager.beginBrowserEnrollment() }
                .onFailure {
                    _userMessage.value = uiText(R.string.embedded_tsnet_browser_start_failed)
                }
        }
    }

    /** Hands a one-shot mutable key directly to the runtime and clears it on every path. */
    fun beginEmbeddedTsnetAuthKeyEnrollment(authKey: CharArray) {
        viewModelScope.launch {
            try {
                runCatching { embeddedTsnetManager.beginAuthKeyEnrollment(authKey) }
                    .onFailure {
                        _userMessage.value = uiText(R.string.embedded_tsnet_auth_key_failed)
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
                    _userMessage.value = uiText(R.string.embedded_tsnet_logout_complete)
                }
                .onFailure { error ->
                    _userMessage.value = uiText(
                        if (error is TsnetSessionsActiveException) {
                            R.string.embedded_tsnet_logout_sessions_active
                        } else {
                            R.string.embedded_tsnet_logout_failed
                        },
                    )
                }
        }
    }

    fun reportUserMessage(message: UiText) {
        _userMessage.value = message
    }

    /** Reports fixed application wording without resolving it before a locale change. */
    fun reportUserMessage(@StringRes resourceId: Int, vararg arguments: Any) {
        _userMessage.value = uiText(resourceId, *arguments)
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
            _userMessage.value = uiText(R.string.message_webdav_fields_required)
            return
        }
        val config = WebDavConfig(normalizedEndpoint, username.trim(), password, normalizedFileName)
        if (runCatching { WebDavClient().validate(config) }.isFailure) {
            _userMessage.value = uiText(R.string.message_webdav_invalid_configuration)
            return
        }
        viewModelScope.launch {
            val saved = vault.saveWebDavConfig(config)
            _userMessage.value = uiText(if (saved.isSuccess) R.string.message_webdav_saved else R.string.backup_error_storage)
            backupCoordinator.refreshPasswords()
        }
    }

    fun clearWebDavConfig() {
        viewModelScope.launch {
            if (vault.saveWebDavConfig(null).isSuccess) backupCoordinator.refreshPasswords()
        }
    }

    /** Converts the PIN briefly to a mutable array so it can be cleared after the verifier is stored. */
    fun configureAppPin(pin: String) {
        if (!authorizeSensitive { configureAppPin(pin) }) return
        if (_appLockBusy.value) return
        _appLockBusy.value = true
        val generation = runtime.accessState.generation
        val chars = pin.toCharArray()
        viewModelScope.launch {
            try {
                withContext(cryptoDispatcher) { appLockStore.setPin(chars) }
                _appLockConfiguration.value = appLockStore.configuration()
                if (generation == runtime.accessState.generation) runtime.accessState.setLocked(false)
                _userMessage.value = uiText(R.string.message_app_lock_enabled)
            } catch (_: IllegalArgumentException) {
                _userMessage.value = uiText(R.string.message_pin_invalid, AppLockStore.MIN_PIN_LENGTH, AppLockStore.MAX_PIN_LENGTH)
            } finally {
                chars.fill('\u0000')
                _appLockBusy.value = false
            }
        }
    }

    fun clearAppLock() {
        if (!authorizeSensitive { clearAppLock() }) return
        appLockStore.clear()
        _appLockConfiguration.value = appLockStore.configuration()
        runtime.accessState.setLocked(false)
        _userMessage.value = uiText(R.string.message_app_lock_disabled)
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        runCatching { appLockStore.setBiometricEnabled(enabled) }
            .onSuccess { _appLockConfiguration.value = appLockStore.configuration() }
            .onFailure { _userMessage.value = uiText(R.string.message_app_pin_required) }
    }

    fun setAutoLockDelay(delay: AppLockDelay) {
        runCatching { appLockStore.setAutoLockDelay(delay) }
            .onSuccess { _appLockConfiguration.value = appLockStore.configuration() }
            .onFailure { _userMessage.value = uiText(R.string.message_app_pin_required) }
    }

    /** Locks immediately, regardless of the configured auto-lock delay. Used by the explicit "Lock now" action. */
    fun lockForBackground() {
        if (_appLockConfiguration.value.pinConfigured) {
            runtime.accessState.setLocked(true)
            cancelReauthentication()
            backupCoordinator.cancel()
        }
    }

    /**
     * Records the moment the app left the foreground; call from `Activity.onStop()`.
     *
     * Uses [SystemClock.elapsedRealtime] by default so a wall-clock change
     * between backgrounding and resuming cannot extend the grace window (see
     * [shouldLockOnResume]).
     */
    fun noteBackgrounded(nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
        backgroundedAtElapsedMillis = nowElapsedMillis
        val configuration = _appLockConfiguration.value
        if (configuration.pinConfigured && shouldLockWhenBackgrounded(configuration.autoLockDelay)) {
            runtime.accessState.setLocked(true)
            cancelReauthentication()
            backupCoordinator.cancel()
        }
    }

    /**
     * Locks if the configured auto-lock delay has elapsed since [noteBackgrounded]; call from
     * `Activity.onStart()`.
     *
     * A missing timestamp means no real backgrounding was recorded since the
     * last evaluation — either a cold start (where [_appLocked]'s initial
     * value already reflects [AppLockConfiguration.pinConfigured]) or a
     * resume from a configuration change, which `MainActivity.onStop()`
     * deliberately does not report to [noteBackgrounded]. Either way there is
     * nothing to evaluate, so this is a no-op rather than a lock.
     */
    fun evaluateAutoLock(nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
        val backgroundedAt = backgroundedAtElapsedMillis ?: return
        val configuration = _appLockConfiguration.value
        if (configuration.pinConfigured &&
            shouldLockOnResume(configuration.autoLockDelay, backgroundedAt, nowElapsedMillis)
        ) {
            runtime.accessState.setLocked(true)
            cancelReauthentication()
            backupCoordinator.cancel()
        }
        backgroundedAtElapsedMillis = null
    }

    /** Mirrors actual screen visibility without pausing protocol processing. */
    fun setVisibleTerminal(sessionId: String?) = sessionController.setVisibleTerminal(sessionId)

    fun unlockWithPin(pin: String) {
        if (_appLockBusy.value) return
        _appLockBusy.value = true
        val generation = runtime.accessState.generation
        val chars = pin.toCharArray()
        viewModelScope.launch {
            try {
                val accepted = withContext(cryptoDispatcher) { appLockStore.verifyPin(chars) }
                if (accepted && generation == runtime.accessState.generation) {
                    runtime.accessState.setLocked(false)
                } else {
                    val remaining = appLockStore.cooldownRemainingMillis()
                    _userMessage.value = if (remaining > 0) uiText(R.string.message_pin_cooldown, (remaining + 999) / 1000)
                        else uiText(R.string.message_pin_incorrect)
                }
            } finally {
                chars.fill('\u0000')
                _appLockBusy.value = false
            }
        }
    }

    fun unlockWithBiometrics() {
        if (_appLockConfiguration.value.biometricEnabled) runtime.accessState.setLocked(false)
    }

    fun checkForUpdates() = updateManager.checkNow()

    fun downloadUpdate() = updateManager.download()

    fun cancelUpdateDownload() = updateManager.cancelDownload()

    /** Delegates installation to the distribution-specific implementation. */
    fun installReadyUpdate(context: Context) {
        updateManager.installReadyUpdate(context)?.let { _userMessage.value = it }
    }

    /** Surfaces a failure to open a validated project or release URL in a browser. */
    fun reportReleasePageOpenFailed() {
        _userMessage.value = uiText(R.string.github_page_unavailable)
    }

    fun setAutomaticUpdateCheckEnabled(enabled: Boolean) {
        updateManager.setAutomaticCheckEnabled(enabled)
    }

    fun dismissUpdateNotice() = updateManager.dismissNotice()

    /** The validated release page for the release currently offered or in flight, if any. */
    fun releasePageUrl(): String? = updateManager.releasePageUrl()

    override fun onCleared() {
        backupCoordinator.cancel()
        updateManager.close()
    }
}
