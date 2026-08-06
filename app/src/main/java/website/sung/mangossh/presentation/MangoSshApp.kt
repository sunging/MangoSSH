@file:OptIn(ExperimentalMaterial3Api::class)

package website.sung.mangossh.presentation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.data.vault.VaultFailureReason
import website.sung.mangossh.R
import website.sung.mangossh.data.keys.SshKeyGenerationType
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProfileDraft
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.domain.HostSortMode
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalThemeId
import website.sung.mangossh.session.SessionKind
import website.sung.mangossh.session.SessionPrompt
import website.sung.mangossh.session.SessionPromptText
import website.sung.mangossh.session.SessionPromptTextKind
import website.sung.mangossh.session.PortForwardRuntimePhase
import website.sung.mangossh.session.PortForwardRuntimeState
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus
import website.sung.mangossh.security.AppLockConfiguration

private sealed interface PendingRemovalRequest {
    val id: String

    data class Host(override val id: String) : PendingRemovalRequest
    data class Key(override val id: String) : PendingRemovalRequest
    data class PortForward(override val id: String) : PendingRemovalRequest
    data class Snippet(override val id: String) : PendingRemovalRequest
}

@Composable
fun MangoSshApp(
    viewModel: MangoSshViewModel,
    onRequestBiometricUnlock: (() -> Unit)? = null,
    onRequestNotificationPermission: ((() -> Unit) -> Unit)? = null,
    selectedAppLanguage: AppLanguage,
    onSetAppLanguage: (AppLanguage) -> Unit,
) {
    val appLocked by viewModel.appLocked.collectAsStateWithLifecycle()
    val appLockConfiguration by viewModel.appLockConfiguration.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val sessionNavigationRequest by viewModel.sessionNavigationRequest.collectAsStateWithLifecycle()
    val embeddedTsnetStatus by viewModel.embeddedTsnetStatus.collectAsStateWithLifecycle()
    val terminalAppearance by viewModel.terminalAppearance.collectAsStateWithLifecycle()
    val terminalShortcutConfig by viewModel.terminalShortcuts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var activeSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var leaveSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentActiveSessionId by rememberUpdatedState(activeSessionId)
    LaunchedEffect(viewModel) {
        viewModel.embeddedTsnetAuthorizationUrls.collect { value ->
            val uri = runCatching { value.toUri() }.getOrNull()
            if (uri?.scheme != "https" || uri.host != "login.tailscale.com") {
                viewModel.reportUserMessage(R.string.embedded_tsnet_invalid_authorization_url)
                return@collect
            }
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
                viewModel.reportUserMessage(R.string.embedded_tsnet_browser_start_failed)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.sessionEndedEvents.collect { event ->
            if (event.sessionId == currentActiveSessionId) {
                activeSessionId = null
                leaveSessionId = null
                viewModel.sessionEndMessage(event)?.let(viewModel::reportUserMessage)
            }
        }
    }
    LaunchedEffect(sessionNavigationRequest, appLocked, sessions) {
        val request = sessionNavigationRequest ?: return@LaunchedEffect
        if (appLocked) return@LaunchedEffect

        when (request) {
            SessionNavigationRequest.OpenSessions -> {
                viewModel.selectSection(AppSection.HOSTS)
                activeSessionId = null
                leaveSessionId = null
            }

            is SessionNavigationRequest.OpenSession -> {
                val session = sessions.firstOrNull { it.id == request.sessionId }
                when {
                    session == null -> viewModel.reportUserMessage(R.string.session_notification_target_unavailable)
                    session.kind == SessionKind.TERMINAL -> {
                        activeSessionId = request.sessionId
                        leaveSessionId = null
                    }
                    // A shell-less connection has no terminal to open, so its
                    // notification leads to the page that owns it instead.
                    else -> {
                        activeSessionId = null
                        leaveSessionId = null
                        viewModel.selectSection(
                            if (session.kind == SessionKind.PORT_FORWARD) {
                                AppSection.FORWARDS
                            } else {
                                AppSection.HOSTS
                            },
                        )
                    }
                }
            }
        }
        viewModel.consumeSessionNavigationRequest(request)
    }
    if (appLocked) {
        AppLockScreen(
            configuration = appLockConfiguration,
            message = userMessage,
            onUnlockWithPin = viewModel::unlockWithPin,
            onRequestBiometric = onRequestBiometricUnlock,
            onDismissMessage = viewModel::dismissUserMessage,
        )
        return
    }

    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val visibleHosts by viewModel.visibleHosts.collectAsStateWithLifecycle()
    val hostQuery by viewModel.hostQuery.collectAsStateWithLifecycle()
    val hostSortMode by viewModel.hostSortMode.collectAsStateWithLifecycle()
    val hostsReorderable by viewModel.hostsReorderable.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    val selectedSection by viewModel.selectedSection.collectAsStateWithLifecycle()
    val vaultStatus by viewModel.vaultStatus.collectAsStateWithLifecycle()
    val sessionPrompts by viewModel.sessionPrompts.collectAsStateWithLifecycle()
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val activePortForwards by viewModel.activePortForwards.collectAsStateWithLifecycle()
    val scpTransfers by viewModel.scpTransfers.collectAsStateWithLifecycle()
    val resourceSnapshots by viewModel.resourceSnapshots.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val portableExport by viewModel.portableExport.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateCallbacks = remember(viewModel) {
        UpdateCardCallbacks(
            onCheckNow = viewModel::checkForUpdates,
            onDownload = viewModel::downloadUpdate,
            onCancelDownload = viewModel::cancelUpdateDownload,
            onInstall = {
                val uri = viewModel.readyInstallUri()
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.reportInstallHandoffFailed()
                    }
                }
            },
            onDismissNotice = viewModel::dismissUpdateNotice,
            onSetAutomaticCheck = viewModel::setAutomaticUpdateCheckEnabled,
            onOpenReleasePage = {
                val uri = runCatching { viewModel.releasePageUrl()?.toUri() }.getOrNull()
                if (uri?.scheme != "https" || uri.host != "github.com") {
                    viewModel.reportReleasePageOpenFailed()
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }.onFailure { viewModel.reportReleasePageOpenFailed() }
                }
            },
        )
    }
    var editingHost by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showHostEditor by rememberSaveable { mutableStateOf(false) }
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<PendingRemovalRequest?>(null) }
    var hostSearchActive by rememberSaveable { mutableStateOf(false) }
    val hostSearchFocusRequester = remember { FocusRequester() }

    fun closeHostSearch() {
        hostSearchActive = false
        viewModel.clearHostQuery()
    }

    // Leaving the host list mid-search should not leave a stale filter behind
    // for the next visit, since the search field itself is HOSTS-only chrome.
    LaunchedEffect(selectedSection) {
        if (selectedSection != AppSection.HOSTS && hostSearchActive) closeHostSearch()
    }

    fun openHostEditor(host: ConnectionProfile? = null) {
        editingHost = host
        showHostEditor = true
    }

    // The remote browser is layered in front of the terminal so leaving it
    // returns to whichever screen opened it. A browser-owned connection has no
    // shell, so its host-key and authentication prompts are rendered here.
    val remoteBrowserState by viewModel.remoteBrowser.collectAsStateWithLifecycle()
    remoteBrowserState?.let { browserState ->
        RemoteFileBrowserScreen(
            state = browserState,
            onNavigate = viewModel::navigateRemoteBrowser,
            onHome = viewModel::remoteBrowserHome,
            onUp = viewModel::remoteBrowserUp,
            onRefresh = viewModel::refreshRemoteBrowser,
            onOpenEntry = viewModel::openRemoteEntry,
            onDownload = viewModel::downloadRemoteFile,
            onDownloadDirectory = viewModel::downloadRemoteDirectory,
            onUpload = viewModel::uploadToRemoteBrowser,
            onUploadDirectory = viewModel::uploadDirectoryToRemoteBrowser,
            onDismissPreview = viewModel::dismissRemotePreview,
            onClose = viewModel::closeRemoteBrowser,
        )
        sessionPrompts.firstOrNull { it.sessionId == browserState.sessionId }?.let { prompt ->
            SessionPromptDialog(
                prompt = prompt,
                onRespond = { values -> viewModel.respondToSessionPrompt(prompt, values) },
            )
        }
        return
    }

    val activeSession = activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (activeSession != null) {
        val activePrompt = sessionPrompts.firstOrNull { it.sessionId == activeSession.id }
        val terminalEmulator = viewModel.terminalEmulator(activeSession.id)
        if (terminalEmulator == null) {
            LaunchedEffect(activeSession.id) { activeSessionId = null }
            return
        }
        BackHandler(enabled = activePrompt == null) {
            leaveSessionId = activeSession.id
        }
        TerminalSessionScreen(
            session = activeSession,
            terminalEmulator = terminalEmulator,
            appearance = terminalAppearance,
            shortcutConfig = terminalShortcutConfig,
            clipboardCopies = viewModel.terminalClipboardCopies,
            onSend = { bytes -> viewModel.sendTerminalInput(activeSession.id, bytes) },
            resourceSnapshot = resourceSnapshots[activeSession.id],
            onRequestResources = { viewModel.requestServerResources(activeSession.id) },
            onOpenFileBrowser = { viewModel.openRemoteBrowser(activeSession.id) },
            onRequestLeave = { leaveSessionId = activeSession.id },
        )
        activePrompt?.let { prompt ->
            SessionPromptDialog(
                prompt = prompt,
                onRespond = { values -> viewModel.respondToSessionPrompt(prompt, values) },
            )
        }
        if (leaveSessionId == activeSession.id) {
            AlertDialog(
                onDismissRequest = { leaveSessionId = null },
                title = { Text(stringResource(R.string.session_leave_title)) },
                text = { Text(stringResource(R.string.session_leave_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.disconnect(activeSession.id)
                            activeSessionId = null
                            leaveSessionId = null
                        },
                    ) {
                        Text(stringResource(R.string.session_leave_disconnect))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                activeSessionId = null
                                leaveSessionId = null
                            },
                        ) {
                            Text(stringResource(R.string.session_leave_background))
                        }
                        TextButton(onClick = { leaveSessionId = null }) {
                            Text(stringResource(R.string.session_leave_cancel))
                        }
                    }
                },
            )
        }
        return
    }

    // A forwarding-only connection has no screen of its own, so its host-key and
    // authentication prompts are rendered over whichever tab is showing.
    val backgroundPrompt = sessionPrompts.firstOrNull { prompt ->
        sessions.firstOrNull { it.id == prompt.sessionId }?.kind == SessionKind.PORT_FORWARD
    }

    BackHandler(enabled = hostSearchActive) { closeHostSearch() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties {
                canFocus = !showHostEditor &&
                    pendingRemoval == null &&
                    userMessage == null &&
                    backgroundPrompt == null
            },
    ) {
        Scaffold(
            topBar = {
                if (selectedSection == AppSection.HOSTS && hostSearchActive) {
                    HostSearchTopBar(
                        query = hostQuery,
                        onQueryChange = viewModel::setHostQuery,
                        onClose = ::closeHostSearch,
                        focusRequester = hostSearchFocusRequester,
                    )
                } else {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "MangoSSH",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = sectionSubtitle(selectedSection),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            // Transfers are started from the host list, so their
                            // status lives here rather than on a tab of its own.
                            if (selectedSection == AppSection.HOSTS && scpTransfers.isNotEmpty()) {
                                FileTransferStatusAction(
                                    transfers = scpTransfers,
                                    onClick = { showTransfers = true },
                                )
                            }
                            if (selectedSection == AppSection.HOSTS) {
                                IconButton(onClick = { hostSearchActive = true }) {
                                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.common_search))
                                }
                                HostSortMenu(sortMode = hostSortMode, onSelect = viewModel::setHostSortMode)
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (selectedSection == AppSection.HOSTS && vaultStatus !is VaultStatus.Failed) {
                    ExtendedFloatingActionButton(
                        modifier = Modifier.testTag("home-add-host-action"),
                        onClick = { openHostEditor() },
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.ui_new_host_profile)) },
                    )
                }
            },
            bottomBar = {
                MangoNavigationBar(
                    selectedSection = selectedSection,
                    onSelectSection = viewModel::selectSection,
                    showUpdateBadge = updateState.hasPendingUpdate,
                )
            },
        ) { contentPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    when (selectedSection) {
                        AppSection.HOSTS -> HostsScreen(
                            onBrowseHostFiles = viewModel::openRemoteBrowserForProfile,
                            hosts = visibleHosts,
                            hasAnyHost = hosts.isNotEmpty(),
                            reorderable = hostsReorderable,
                            sessions = sessions,
                            vaultStatus = vaultStatus,
                            onEditHost = { openHostEditor(it) },
                            onRemoveHost = { pendingRemoval = PendingRemovalRequest.Host(it) },
                            onConnectHost = { host ->
                                val connect = { activeSessionId = viewModel.connect(host) }
                                onRequestNotificationPermission?.invoke(connect) ?: connect()
                            },
                            onOpenSession = { sessionId -> activeSessionId = sessionId },
                            onDisconnectSession = viewModel::disconnect,
                            onReorderHosts = viewModel::reorderHosts,
                            onMoveHost = viewModel::moveHost,
                            onMoveHostToTop = viewModel::moveHostToTop,
                        )

                        AppSection.KEYS -> KeysScreen(
                            vaultStatus = vaultStatus,
                            keys = keys,
                            onGenerate = viewModel::generateKey,
                            onImport = viewModel::importPrivateKey,
                            onRemove = { pendingRemoval = PendingRemovalRequest.Key(it) },
                        )
                        AppSection.FORWARDS -> PortForwardsScreen(
                            hosts = hosts,
                            rules = portForwardRules,
                            activeForwards = activePortForwards,
                            sessions = sessions,
                            onSaveRule = viewModel::savePortForward,
                            onRemoveRule = { pendingRemoval = PendingRemovalRequest.PortForward(it) },
                            onStartRule = viewModel::startPortForward,
                            onStartOnNewConnection = viewModel::startPortForwardOnNewConnection,
                            onStopRule = viewModel::stopPortForward,
                        )
                        AppSection.SETTINGS -> SettingsScreen(
                            vaultStatus = vaultStatus,
                            webDavConfig = webDavConfig,
                            snippets = snippets,
                            portableExport = portableExport,
                            onSaveWebDav = viewModel::saveWebDavConfig,
                            onClearWebDav = viewModel::clearWebDavConfig,
                            onPrepareExport = viewModel::preparePortableExport,
                            onConsumeExport = viewModel::consumePortableExport,
                            onImport = viewModel::importPortable,
                            onUpload = viewModel::uploadWebDav,
                            onDownloadAndImport = viewModel::downloadWebDavAndImport,
                            appLockConfiguration = appLockConfiguration,
                            onConfigureAppPin = viewModel::configureAppPin,
                            onClearAppLock = viewModel::clearAppLock,
                            onSetBiometricEnabled = viewModel::setBiometricUnlockEnabled,
                            onLockNow = viewModel::lockForBackground,
                            onSaveSnippet = viewModel::saveSnippet,
                            onRemoveSnippet = { pendingRemoval = PendingRemovalRequest.Snippet(it) },
                            embeddedTsnetStatus = embeddedTsnetStatus,
                            onBeginTsnetBrowserEnrollment = viewModel::beginEmbeddedTsnetBrowserEnrollment,
                            onBeginTsnetAuthKeyEnrollment = viewModel::beginEmbeddedTsnetAuthKeyEnrollment,
                            onLogoutTsnet = viewModel::logoutEmbeddedTsnet,
                            terminalAppearance = terminalAppearance,
                            terminalShortcutConfig = terminalShortcutConfig,
                            onSetTerminalFont = viewModel::setTerminalFont,
                            onSetTerminalFontSize = viewModel::setTerminalFontSize,
                            onSetTerminalTheme = viewModel::setTerminalTheme,
                            onSetTerminalCustomColors = viewModel::setTerminalCustomColors,
                            onResetTerminalAppearance = viewModel::resetTerminalAppearance,
                            onSaveTerminalShortcuts = viewModel::saveTerminalShortcuts,
                            selectedAppLanguage = selectedAppLanguage,
                            onSetAppLanguage = onSetAppLanguage,
                            updateState = updateState,
                            updateCallbacks = updateCallbacks,
                        )
                    }
                }
            }
        }
    }

    backgroundPrompt?.let { prompt ->
        SessionPromptDialog(
            prompt = prompt,
            onRespond = { values -> viewModel.respondToSessionPrompt(prompt, values) },
        )
    }

    // Clearing the last record closes the sheet with the top bar action.
    if (showTransfers && scpTransfers.isNotEmpty()) {
        FileTransfersSheet(
            transfers = scpTransfers,
            onPause = viewModel::pauseTransfer,
            onResume = viewModel::resumeTransfer,
            onCancel = viewModel::cancelTransfer,
            onRetry = viewModel::retryTransfer,
            onOpenRemoteDirectory = { transfer ->
                showTransfers = false
                viewModel.openRemoteDirectoryFromTransfer(transfer)
            },
            onClearFinished = viewModel::clearFinishedTransfers,
            onDismiss = { showTransfers = false },
        )
    }

    if (showHostEditor) {
        HostEditorSheet(
            initialHost = editingHost,
            keys = keys,
            snippets = snippets,
            onDismiss = {
                showHostEditor = false
                editingHost = null
            },
            onSave = { draft ->
                viewModel.saveHost(draft)
                showHostEditor = false
                editingHost = null
            },
        )
    }

    pendingRemoval?.let { request ->
        RemovalConfirmationDialog(
            request = request,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                when (request) {
                    is PendingRemovalRequest.Host -> viewModel.removeHost(request.id)
                    is PendingRemovalRequest.Key -> viewModel.removeKey(request.id)
                    is PendingRemovalRequest.PortForward -> viewModel.removePortForward(request.id)
                    is PendingRemovalRequest.Snippet -> viewModel.removeSnippet(request.id)
                }
                pendingRemoval = null
            },
        )
    }

    userMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUserMessage,
            title = { Text("MangoSSH") },
            text = { Text(message.asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissUserMessage) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun MangoNavigationBar(
    selectedSection: AppSection,
    onSelectSection: (AppSection) -> Unit,
    showUpdateBadge: Boolean,
) {
    val updateBadgeDescription = stringResource(R.string.app_update_badge_description)
    NavigationBar {
        AppSection.entries.forEach { section ->
            NavigationBarItem(
                selected = section == selectedSection,
                onClick = { onSelectSection(section) },
                icon = {
                    val badged = showUpdateBadge && section == AppSection.SETTINGS
                    BadgedBox(
                        badge = {
                            if (badged) {
                                Badge(modifier = Modifier.semantics { contentDescription = updateBadgeDescription })
                            }
                        },
                    ) {
                        Icon(
                            imageVector = section.icon(),
                            contentDescription = section.label(),
                        )
                    }
                },
                label = { Text(section.label()) },
            )
        }
    }
}

/** Replaces the host list's top bar while search is active; a back arrow restores it. */
@Composable
private fun HostSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.ui_search_hosts)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_close))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ui_clear_search))
                }
            }
        },
    )
}

/** Top bar overflow menu that switches the host list between manual, name, and recency order. */
@Composable
private fun HostSortMenu(sortMode: HostSortMode, onSelect: (HostSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.ui_sort_hosts))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf(
                HostSortMode.MANUAL to R.string.ui_sort_mode_manual,
                HostSortMode.LABEL to R.string.ui_sort_mode_label,
                HostSortMode.RECENT to R.string.ui_sort_mode_recent,
            )
            options.forEach { (mode, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    leadingIcon = {
                        if (mode == sortMode) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun RemovalConfirmationDialog(
    request: PendingRemovalRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, message) = when (request) {
        is PendingRemovalRequest.Host -> stringResource(R.string.ui_remove_host) to
            stringResource(R.string.ui_the_host_and_its_port_forwarding_rules_will_be_permanently_removed_this)

        is PendingRemovalRequest.Key -> stringResource(R.string.ui_remove_private_key) to
            stringResource(R.string.ui_the_private_key_will_be_permanently_removed_and_unlinked_from_its_host_p)

        is PendingRemovalRequest.PortForward -> stringResource(R.string.ui_remove_port_forward) to
            stringResource(R.string.ui_this_port_forwarding_rule_will_be_permanently_removed_this_cannot_be_und)

        is PendingRemovalRequest.Snippet -> stringResource(R.string.ui_remove_snippet) to
            stringResource(R.string.ui_this_snippet_will_be_permanently_removed_and_linked_host_profiles_will_s)
    }
    DestructiveConfirmationDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * Shared confirmation boundary for irreversible vault mutations.
 *
 * Keeping the final callback behind an explicit confirmation prevents an
 * accidental tap from deleting encrypted profiles, keys, or dependent data.
 */
@Composable
internal fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("removal-confirmation-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm-removal"),
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.ui_remove_permanently))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_keep))
            }
        },
    )
}

@Composable
private fun AppLockScreen(
    configuration: AppLockConfiguration,
    message: UiText?,
    onUnlockWithPin: (String) -> Unit,
    onRequestBiometric: (() -> Unit)?,
    onDismissMessage: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ui_mangossh_is_locked), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_unlock_with_your_app_pin),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = { Text(stringResource(R.string.ui_app_pin)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onUnlockWithPin(pin)
                    pin = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.isNotEmpty(),
            ) {
                Text(stringResource(R.string.ui_unlock_with_pin))
            }
            if (configuration.biometricEnabled && onRequestBiometric != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRequestBiometric, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_use_biometrics))
                }
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismissMessage) { Text(it.asString()) }
            }
        }
    }
}

@Composable
private fun KeysScreen(
    vaultStatus: VaultStatus,
    keys: List<StoredSshKey>,
    onGenerate: (type: SshKeyGenerationType, label: String) -> Unit,
    onImport: (label: String, contents: String, passphrase: String?) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<StoredSshKey?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            pendingImport = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().decodeToString()
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        val key = pendingExport
        pendingExport = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(key.privateKeyPem.encodeToByteArray())
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vaultStatus !is VaultStatus.Ready) {
            item { SecurityBanner(vaultStatus) }
        }
        item {
            Text(stringResource(R.string.ui_key_vault), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_generated_or_imported_private_keys_stay_only_in_the_encrypted_vault_and),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showGenerator = true }, enabled = vaultStatus !is VaultStatus.Failed) {
                    Text(stringResource(R.string.ui_generate_key))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/x-pem-file", "text/plain", "application/octet-stream")) },
                    enabled = vaultStatus !is VaultStatus.Failed,
                ) {
                    Text(stringResource(R.string.ui_import_private_key))
                }
            }
        }
        if (keys.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ui_no_keys_yet_generate_one_with_an_algorithm_supported_by_your_server_or_i),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(keys, key = { it.id }) { key ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(key.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${key.algorithm} · ${key.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (key.requiresPassphrase) {
                        Text(
                            stringResource(R.string.ui_this_private_key_also_requires_its_passphrase),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("SSH public key", key.publicKey)),
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.ui_copy_public_key))
                        }
                        TextButton(
                            onClick = {
                                pendingExport = key
                                exportLauncher.launch("${key.label.replace(' ', '_')}.pem")
                            },
                        ) {
                            Text(stringResource(R.string.ui_export_private_key))
                        }
                        TextButton(onClick = { onRemove(key.id) }) {
                            Text(stringResource(R.string.common_remove))
                        }
                    }
                }
            }
        }
    }

    if (showGenerator) {
        GenerateKeyDialog(
            onDismiss = { showGenerator = false },
            onConfirm = { type, label ->
                onGenerate(type, label)
                showGenerator = false
            },
        )
    }
    pendingImport?.let { contents ->
        ImportKeyDialog(
            onDismiss = { pendingImport = null },
            onConfirm = { label, passphrase ->
                onImport(label, contents, passphrase.takeIf(String::isNotEmpty))
                pendingImport = null
            },
        )
    }
}

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (type: SshKeyGenerationType, label: String) -> Unit,
) {
    var algorithm by rememberSaveable { mutableStateOf(KeyGenerationAlgorithm.ED25519) }
    var keyLength by rememberSaveable { mutableStateOf(algorithm.defaultLength) }
    var label by rememberSaveable {
        mutableStateOf(defaultGeneratedKeyLabel(algorithm, keyLength))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_generate_ssh_key)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeyGenerationDropdown(
                    label = stringResource(R.string.ui_key_algorithm),
                    selected = algorithm,
                    options = KeyGenerationAlgorithm.entries,
                    displayText = KeyGenerationAlgorithm::uiLabel,
                    onSelected = { selected ->
                        val previousDefault = defaultGeneratedKeyLabel(algorithm, keyLength)
                        algorithm = selected
                        keyLength = selected.defaultLength
                        if (label == previousDefault) {
                            label = defaultGeneratedKeyLabel(algorithm, keyLength)
                        }
                    },
                )
                KeyGenerationDropdown(
                    label = stringResource(R.string.ui_key_length),
                    selected = keyLength,
                    options = algorithm.availableLengths,
                    displayText = Int::toString,
                    onSelected = { selected ->
                        val previousDefault = defaultGeneratedKeyLabel(algorithm, keyLength)
                        keyLength = selected
                        if (label == previousDefault) {
                            label = defaultGeneratedKeyLabel(algorithm, keyLength)
                        }
                    },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ui_key_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(algorithm.toGenerationType(keyLength), label.trim()) },
            ) {
                Text(stringResource(R.string.ui_generate))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun <T> KeyGenerationDropdown(
    label: String,
    selected: T,
    options: List<T>,
    displayText: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayText(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayText(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class KeyGenerationAlgorithm(
    val defaultLength: Int,
    val availableLengths: List<Int>,
) {
    ED25519(256, listOf(256)),
    ECDSA(256, listOf(256, 384, 521)),
    RSA(3072, listOf(2048, 3072, 4096));

    fun uiLabel(): String = when (this) {
        ED25519 -> "Ed25519"
        ECDSA -> "ECDSA"
        RSA -> "RSA"
    }

    fun toGenerationType(keyLength: Int): SshKeyGenerationType = when (this) {
        ED25519 -> SshKeyGenerationType.ED25519
        ECDSA -> when (keyLength) {
            256 -> SshKeyGenerationType.ECDSA_P256
            384 -> SshKeyGenerationType.ECDSA_P384
            521 -> SshKeyGenerationType.ECDSA_P521
            else -> error("Unsupported ECDSA key length: $keyLength")
        }
        RSA -> when (keyLength) {
            2048 -> SshKeyGenerationType.RSA_2048
            3072 -> SshKeyGenerationType.RSA_3072
            4096 -> SshKeyGenerationType.RSA_4096
            else -> error("Unsupported RSA key length: $keyLength")
        }
    }
}

private fun defaultGeneratedKeyLabel(algorithm: KeyGenerationAlgorithm, keyLength: Int): String =
    when (algorithm) {
        KeyGenerationAlgorithm.ED25519 -> "MangoSSH Ed25519"
        KeyGenerationAlgorithm.ECDSA -> "MangoSSH ECDSA P-$keyLength"
        KeyGenerationAlgorithm.RSA -> "MangoSSH RSA $keyLength"
    }

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, passphrase: String) -> Unit,
) {
    val defaultLabel = stringResource(R.string.ui_imported_private_key)
    var label by rememberSaveable { mutableStateOf(defaultLabel) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_import_private_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.ui_openssh_and_pem_private_keys_are_supported_leave_the_passphrase_blank_fo),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ui_key_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ui_private_key_passphrase_optional)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), passphrase) }) { Text(stringResource(R.string.ui_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PortForwardsScreen(
    hosts: List<ConnectionProfile>,
    rules: List<PortForwardRule>,
    activeForwards: List<PortForwardRuntimeState>,
    sessions: List<website.sung.mangossh.session.TerminalSessionState>,
    onSaveRule: (PortForwardRule) -> Unit,
    onRemoveRule: (String) -> Unit,
    onStartRule: (String, PortForwardRule) -> Unit,
    onStartOnNewConnection: (ConnectionProfile, PortForwardRule) -> Unit,
    onStopRule: (String, String) -> Unit,
) {
    var editingRule by remember { mutableStateOf<PortForwardRule?>(null) }
    var showRuleEditor by rememberSaveable { mutableStateOf(false) }
    // Mosh uses a UDP terminal after its bootstrap and has no SSH channels for
    // forwarding, so these actions list SSH sessions only.
    val openSshSessions = sessions.filter {
        it.phase == TerminalSessionPhase.OPEN &&
            it.protocol == ConnectionProtocol.SSH &&
            it.kind == SessionKind.TERMINAL
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.ui_port_forwarding), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_local_remote_and_socks5_forwards_attach_to_an_open_ssh_session_and_stop),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                onClick = {
                    editingRule = null
                    showRuleEditor = true
                },
                enabled = hosts.isNotEmpty(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_new_forward))
            }
        }
        if (hosts.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.ui_create_a_host_profile_before_adding_a_port_forward),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rules.isEmpty() && hosts.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.ui_no_port_forwarding_rules_yet_a_rule_can_start_automatically_after_connec),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rules, key = { it.id }) { rule ->
            val profile = hosts.firstOrNull { it.id == rule.profileId }
            val active = activeForwards.firstOrNull {
                it.rule.id == rule.id &&
                    (it.phase == PortForwardRuntimePhase.ACTIVE || it.phase == PortForwardRuntimePhase.STARTING)
            }
            val eligibleSession = openSshSessions.firstOrNull { it.profileId == rule.profileId }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            R.string.port_forward_title,
                            rule.type.label(),
                            profile?.label ?: stringResource(R.string.port_forward_deleted_host),
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rule.displayDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A forward without a terminal session runs on a connection
                    // opened only for it, which is worth showing: it explains why
                    // the host is connected while no terminal is open.
                    val ownConnection = active != null &&
                        sessions.firstOrNull { it.id == active.sessionId }?.kind == SessionKind.PORT_FORWARD
                    val status = active?.let { runtime ->
                        when (runtime.phase) {
                            PortForwardRuntimePhase.ACTIVE -> stringResource(R.string.ui_running)
                            PortForwardRuntimePhase.STARTING -> stringResource(R.string.ui_starting)
                            else -> ""
                        }
                    } ?: if (rule.startOnConnect) stringResource(R.string.ui_start_on_connection) else stringResource(R.string.ui_not_started)
                    Spacer(Modifier.height(4.dp))
                    val statusText = status
                    val ownConnectionText = stringResource(R.string.ui_own_connection)
                    Text(
                        if (ownConnection) "$statusText · $ownConnectionText" else statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    val canStart = profile != null && profile.protocol == ConnectionProtocol.SSH
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active != null) {
                            Button(onClick = { onStopRule(active.sessionId, rule.id) }) { Text(stringResource(R.string.common_stop)) }
                        } else {
                            Button(
                                onClick = {
                                    val session = eligibleSession
                                    if (session != null) {
                                        onStartRule(session.id, rule)
                                    } else if (profile != null) {
                                        onStartOnNewConnection(profile, rule)
                                    }
                                },
                                enabled = canStart,
                            ) { Text(stringResource(R.string.common_start)) }
                        }
                        OutlinedButton(
                            onClick = {
                                editingRule = rule
                                showRuleEditor = true
                            },
                        ) { Text(stringResource(R.string.common_edit)) }
                        TextButton(onClick = { onRemoveRule(rule.id) }) { Text(stringResource(R.string.common_remove)) }
                    }
                    if (active == null && canStart && eligibleSession == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ui_starting_opens_a_connection_dedicated_to_this_forward_so_no_terminal_is),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (profile != null && profile.protocol != ConnectionProtocol.SSH) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.mosh_not_supported_for_ssh_feature),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showRuleEditor) {
        PortForwardRuleDialog(
            initial = editingRule,
            hosts = hosts,
            onDismiss = {
                showRuleEditor = false
                editingRule = null
            },
            onSave = { rule ->
                onSaveRule(rule)
                showRuleEditor = false
                editingRule = null
            },
        )
    }
}

@Composable
private fun PortForwardRuleDialog(
    initial: PortForwardRule?,
    hosts: List<ConnectionProfile>,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit,
) {
    var profileId by rememberSaveable(initial?.id) { mutableStateOf(initial?.profileId ?: hosts.firstOrNull()?.id.orEmpty()) }
    var type by rememberSaveable(initial?.id) { mutableStateOf(initial?.type ?: PortForwardType.LOCAL) }
    var bindHost by rememberSaveable(initial?.id) { mutableStateOf(initial?.bindHost ?: "127.0.0.1") }
    var bindPort by rememberSaveable(initial?.id) { mutableStateOf(initial?.bindPort?.toString().orEmpty()) }
    var destinationHost by rememberSaveable(initial?.id) { mutableStateOf(initial?.destinationHost.orEmpty()) }
    var destinationPort by rememberSaveable(initial?.id) { mutableStateOf(initial?.destinationPort?.toString().orEmpty()) }
    var startOnConnect by rememberSaveable(initial?.id) { mutableStateOf(initial?.startOnConnect ?: false) }
    val bindPortValue = bindPort.toIntOrNull()
    val destinationPortValue = destinationPort.toIntOrNull()
    val validDestination = type == PortForwardType.DYNAMIC ||
        (destinationHost.isNotBlank() && destinationPortValue in 1..65535)
    val canSave = profileId.isNotBlank() && bindPortValue in 1..65535 && validDestination

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.ui_new_port_forward) else stringResource(R.string.ui_edit_port_forward)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.ui_host), style = MaterialTheme.typography.titleSmall)
                hosts.forEach { host ->
                    FilterChip(
                        selected = profileId == host.id,
                        onClick = { profileId = host.id },
                        label = { Text(host.label) },
                    )
                }
                Text(stringResource(R.string.ui_type), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortForwardType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = bindHost,
                    onValueChange = { bindHost = it },
                    label = { Text(if (type == PortForwardType.REMOTE) stringResource(R.string.ui_remote_bind_address) else stringResource(R.string.ui_local_bind_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bindPort,
                    onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (type == PortForwardType.REMOTE) stringResource(R.string.ui_remote_listen_port) else stringResource(R.string.ui_local_listen_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = bindPort.isNotEmpty() && bindPortValue !in 1..65535,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type != PortForwardType.DYNAMIC) {
                    OutlinedTextField(
                        value = destinationHost,
                        onValueChange = { destinationHost = it },
                        label = { Text(stringResource(R.string.ui_destination_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = destinationPort,
                        onValueChange = { destinationPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.ui_destination_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = destinationPort.isNotEmpty() && destinationPortValue !in 1..65535,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.ui_a_socks5_proxy_does_not_need_a_destination_host_or_port), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = startOnConnect, onCheckedChange = { startOnConnect = it })
                    Text(stringResource(R.string.ui_start_on_connection))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PortForwardRule(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            profileId = profileId,
                            type = type,
                            bindHost = bindHost.trim().ifEmpty { "127.0.0.1" },
                            bindPort = requireNotNull(bindPortValue),
                            destinationHost = destinationHost.trim().takeIf { type != PortForwardType.DYNAMIC && it.isNotEmpty() },
                            destinationPort = destinationPortValue.takeIf { type != PortForwardType.DYNAMIC },
                            startOnConnect = startOnConnect,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

internal fun android.net.Uri.displayName(context: android.content.Context): String {
    val fromProvider = context.contentResolver
        .query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    return fromProvider?.takeIf(String::isNotBlank)
        ?: lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "upload"
}

private fun PortForwardRule.displayDescription(): String = when (type) {
    PortForwardType.LOCAL -> "$bindHost:$bindPort → ${destinationHost.orEmpty()}:${destinationPort ?: "?"}"
    PortForwardType.REMOTE -> "$bindHost:$bindPort ← ${destinationHost.orEmpty()}:${destinationPort ?: "?"}"
    PortForwardType.DYNAMIC -> "$bindHost:$bindPort · SOCKS5"
}

@Composable
internal fun EmbeddedTsnetCard(
    status: EmbeddedTsnetStatus,
    onBeginBrowserEnrollment: () -> Unit,
    onBeginAuthKeyEnrollment: (CharArray) -> Unit,
    onLogout: () -> Unit,
) {
    var showAuthKeyDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val statusText = when (status.phase) {
        EmbeddedTsnetPhase.UNENROLLED -> stringResource(R.string.embedded_tsnet_status_unenrolled)
        EmbeddedTsnetPhase.STARTING -> stringResource(R.string.embedded_tsnet_status_starting)
        EmbeddedTsnetPhase.WAITING_FOR_LOGIN ->
            stringResource(R.string.embedded_tsnet_status_waiting_login)
        EmbeddedTsnetPhase.WAITING_FOR_APPROVAL ->
            stringResource(R.string.embedded_tsnet_status_waiting_approval)
        EmbeddedTsnetPhase.READY_IDLE -> stringResource(R.string.embedded_tsnet_status_ready_idle)
        EmbeddedTsnetPhase.ACTIVE ->
            pluralStringResource(
                R.plurals.embedded_tsnet_status_active,
                status.activeSessions,
                status.activeSessions,
            )
        EmbeddedTsnetPhase.FAILED -> stringResource(R.string.embedded_tsnet_status_failed)
    }
    val canStartEnrollment = status.phase == EmbeddedTsnetPhase.UNENROLLED ||
        status.phase == EmbeddedTsnetPhase.FAILED ||
        status.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN
    val canLogout = status.phase == EmbeddedTsnetPhase.READY_IDLE ||
        status.phase == EmbeddedTsnetPhase.ACTIVE

    Card(modifier = Modifier.fillMaxWidth().testTag("embedded_tsnet_card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.embedded_tsnet_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.embedded_tsnet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statusText,
                modifier = Modifier.testTag("embedded_tsnet_status"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (canStartEnrollment) {
                Button(
                    onClick = onBeginBrowserEnrollment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("embedded_tsnet_browser_login"),
                ) {
                    Text(stringResource(R.string.embedded_tsnet_browser_login))
                }
                if (status.authKeyAllowed) {
                    OutlinedButton(
                        onClick = { showAuthKeyDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("embedded_tsnet_auth_key_login"),
                    ) {
                        Text(stringResource(R.string.embedded_tsnet_auth_key_login))
                    }
                }
            }
            if (canLogout) {
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    enabled = status.activeSessions == 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("embedded_tsnet_logout"),
                ) {
                    Text(stringResource(R.string.embedded_tsnet_logout))
                }
            }
        }
    }

    if (showAuthKeyDialog) {
        EmbeddedTsnetAuthKeyDialog(
            onDismiss = { showAuthKeyDialog = false },
            onConfirm = { key ->
                showAuthKeyDialog = false
                onBeginAuthKeyEnrollment(key)
            },
        )
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.embedded_tsnet_logout_title)) },
            text = { Text(stringResource(R.string.embedded_tsnet_logout_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                ) {
                    Text(stringResource(R.string.embedded_tsnet_logout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.embedded_tsnet_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmbeddedTsnetAuthKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    // Deliberately not rememberSaveable: activity recreation must discard the
    // one-shot credential rather than place it in SavedState.
    var authKey by remember { mutableStateOf("") }

    fun clearAndDismiss() {
        authKey = ""
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::clearAndDismiss,
        title = { Text(stringResource(R.string.embedded_tsnet_auth_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.embedded_tsnet_auth_key_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it },
                    modifier = Modifier.testTag("embedded_tsnet_auth_key_input"),
                    label = { Text(stringResource(R.string.embedded_tsnet_auth_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = authKey.isNotBlank(),
                modifier = Modifier.testTag("embedded_tsnet_auth_key_confirm"),
                onClick = {
                    val transient = authKey.toCharArray()
                    authKey = ""
                    onConfirm(transient)
                },
            ) {
                Text(stringResource(R.string.embedded_tsnet_auth_key_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = ::clearAndDismiss) {
                Text(stringResource(R.string.embedded_tsnet_cancel))
            }
        },
    )
}

@Composable
private fun SettingsScreen(
    vaultStatus: VaultStatus,
    webDavConfig: WebDavConfig?,
    snippets: List<CommandSnippet>,
    portableExport: ByteArray?,
    onSaveWebDav: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
    onClearWebDav: () -> Unit,
    onPrepareExport: (String) -> Unit,
    onConsumeExport: () -> Unit,
    onImport: (ByteArray, String) -> Unit,
    onUpload: (String) -> Unit,
    onDownloadAndImport: (String) -> Unit,
    appLockConfiguration: AppLockConfiguration,
    onConfigureAppPin: (String) -> Unit,
    onClearAppLock: () -> Unit,
    onSetBiometricEnabled: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onSaveSnippet: (String?, String, String, Boolean) -> Unit,
    onRemoveSnippet: (String) -> Unit,
    embeddedTsnetStatus: EmbeddedTsnetStatus,
    onBeginTsnetBrowserEnrollment: () -> Unit,
    onBeginTsnetAuthKeyEnrollment: (CharArray) -> Unit,
    onLogoutTsnet: () -> Unit,
    terminalAppearance: TerminalAppearance,
    terminalShortcutConfig: TerminalShortcutConfig,
    onSetTerminalFont: (TerminalFont) -> Unit,
    onSetTerminalFontSize: (Int) -> Unit,
    onSetTerminalTheme: (TerminalThemeId) -> Unit,
    onSetTerminalCustomColors: (TerminalCustomColors) -> Unit,
    onResetTerminalAppearance: () -> Unit,
    onSaveTerminalShortcuts: (TerminalShortcutConfig) -> Unit,
    selectedAppLanguage: AppLanguage,
    onSetAppLanguage: (AppLanguage) -> Unit,
    updateState: UpdateUiState,
    updateCallbacks: UpdateCardCallbacks,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWebDavEditor by rememberSaveable { mutableStateOf(false) }
    var showPinEditor by rememberSaveable { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var showSnippetEditor by rememberSaveable { mutableStateOf(false) }
    var syncAction by remember { mutableStateOf<SyncAction?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pendingImport = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                }
                if (pendingImport != null) syncAction = SyncAction.MANUAL_IMPORT
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = portableExport
        onConsumeExport()
        if (uri != null && data != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(data) }
                }
            }
        }
    }
    LaunchedEffect(portableExport) {
        if (portableExport != null) exportLauncher.launch("mangossh-vault.mssh")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LanguageSettingsCard(
                selected = selectedAppLanguage,
                onSelect = onSetAppLanguage,
            )
        }
        if (updateState.supported) {
            item { UpdateSettingsCard(state = updateState, callbacks = updateCallbacks) }
        }
        if (vaultStatus !is VaultStatus.Ready) {
            item { SecurityBanner(vaultStatus) }
        }
        item {
            TerminalAppearanceCard(
                appearance = terminalAppearance,
                onSetFont = onSetTerminalFont,
                onSetFontSize = onSetTerminalFontSize,
                onSetTheme = onSetTerminalTheme,
                onSetCustomColors = onSetTerminalCustomColors,
                onReset = onResetTerminalAppearance,
            )
        }
        item {
            TerminalShortcutSettingsCard(
                config = terminalShortcutConfig,
                onSave = onSaveTerminalShortcuts,
            )
        }
        item {
            EmbeddedTsnetCard(
                status = embeddedTsnetStatus,
                onBeginBrowserEnrollment = onBeginTsnetBrowserEnrollment,
                onBeginAuthKeyEnrollment = onBeginTsnetAuthKeyEnrollment,
                onLogout = onLogoutTsnet,
            )
        }
        item {
            Text(stringResource(R.string.ui_security_and_sync), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ui_exports_and_webdav_uploads_are_encrypted_again_with_a_separate_sync_pass),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_encrypted_backup), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { syncAction = SyncAction.MANUAL_EXPORT },
                            enabled = vaultStatus !is VaultStatus.Failed,
                        ) { Text(stringResource(R.string.ui_export)) }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/plain")) },
                            enabled = vaultStatus !is VaultStatus.Failed,
                        ) { Text(stringResource(R.string.ui_import)) }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_custom_webdav), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        webDavConfig?.let { "${it.endpoint}/${it.remoteFileName}" } ?: stringResource(R.string.ui_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showWebDavEditor = true }) {
                            Text(if (webDavConfig == null) stringResource(R.string.ui_configure) else stringResource(R.string.common_edit))
                        }
                        if (webDavConfig != null) {
                            Button(onClick = { syncAction = SyncAction.WEBDAV_UPLOAD }) { Text(stringResource(R.string.common_upload)) }
                            Button(onClick = { syncAction = SyncAction.WEBDAV_DOWNLOAD }) { Text(stringResource(R.string.ui_download_and_import)) }
                            TextButton(onClick = onClearWebDav) { Text(stringResource(R.string.common_remove)) }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_app_protection), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (appLockConfiguration.pinConfigured) {
                            stringResource(R.string.ui_app_pin_is_enabled_unlocking_is_required_when_returning_to_the_foregroun)
                        } else {
                            stringResource(R.string.ui_no_app_pin_is_set_once_enabled_returning_to_the_foreground_requires_pin)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPinEditor = true }) {
                            Text(if (appLockConfiguration.pinConfigured) stringResource(R.string.ui_change_pin) else stringResource(R.string.ui_set_pin))
                        }
                        if (appLockConfiguration.pinConfigured) {
                            Button(onClick = onLockNow) { Text(stringResource(R.string.ui_lock_now)) }
                            TextButton(onClick = onClearAppLock) { Text(stringResource(R.string.common_close)) }
                        }
                    }
                    if (appLockConfiguration.pinConfigured) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = appLockConfiguration.biometricEnabled,
                                onCheckedChange = onSetBiometricEnabled,
                            )
                            Text(stringResource(R.string.ui_allow_biometric_unlock), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_post_connect_snippets), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ui_a_snippet_selected_in_the_host_editor_is_sent_automatically_when_the_she),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            editingSnippet = null
                            showSnippetEditor = true
                        },
                    ) { Text(stringResource(R.string.ui_new_snippet_2)) }
                }
            }
        }
        if (snippets.isEmpty()) {
            item {
                Text(stringResource(R.string.ui_no_snippets_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(snippets, key = { it.id }) { snippet ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(snippet.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            snippet.script,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    editingSnippet = snippet
                                    showSnippetEditor = true
                                },
                            ) { Text(stringResource(R.string.common_edit)) }
                            TextButton(onClick = { onRemoveSnippet(snippet.id) }) { Text(stringResource(R.string.common_remove)) }
                        }
                    }
                }
            }
        }
    }

    if (showWebDavEditor) {
        WebDavConfigDialog(
            initial = webDavConfig,
            onDismiss = { showWebDavEditor = false },
            onSave = { endpoint, username, password, remoteFileName ->
                onSaveWebDav(endpoint, username, password, remoteFileName)
                showWebDavEditor = false
            },
        )
    }
    if (showPinEditor) {
        AppPinDialog(
            onDismiss = { showPinEditor = false },
            onConfirm = { pin ->
                onConfigureAppPin(pin)
                showPinEditor = false
            },
        )
    }
    if (showSnippetEditor) {
        CommandSnippetDialog(
            initial = editingSnippet,
            onDismiss = {
                showSnippetEditor = false
                editingSnippet = null
            },
            onSave = { label, script, appendNewline ->
                onSaveSnippet(editingSnippet?.id, label, script, appendNewline)
                showSnippetEditor = false
                editingSnippet = null
            },
        )
    }
    syncAction?.let { action ->
        SyncPassphraseDialog(
            action = action,
            onDismiss = { syncAction = null },
            onConfirm = { passphrase ->
                when (action) {
                    SyncAction.MANUAL_EXPORT -> onPrepareExport(passphrase)
                    SyncAction.MANUAL_IMPORT -> pendingImport?.let { bytes -> onImport(bytes, passphrase) }
                    SyncAction.WEBDAV_UPLOAD -> onUpload(passphrase)
                    SyncAction.WEBDAV_DOWNLOAD -> onDownloadAndImport(passphrase)
                }
                pendingImport = null
                syncAction = null
            },
        )
    }
}

@Composable
internal fun LanguageSettingsCard(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag("app-language-card")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.app_language_title), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.app_language_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedButton(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("app-language-selector"),
                    onClick = { expanded = true },
                ) {
                    Text(selected.label())
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem(
                            modifier = Modifier.testTag("app-language-option_${option.name}"),
                            text = { Text(option.label()) },
                            onClick = {
                                expanded = false
                                if (option != selected) onSelect(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLanguage.label(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.app_language_system
        AppLanguage.ENGLISH -> R.string.app_language_english
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.app_language_simplified_chinese
    },
)

private enum class SyncAction(
    @StringRes val titleResource: Int,
    @StringRes val warningResource: Int,
) {
    MANUAL_EXPORT(
        R.string.ui_export_encrypted_backup,
        R.string.ui_this_passphrase_protects_the_exported_backup_file,
    ),
    MANUAL_IMPORT(
        R.string.ui_import_encrypted_backup,
        R.string.ui_importing_replaces_the_current_hosts_keys_snippets_and_sync_configuratio,
    ),
    WEBDAV_UPLOAD(
        R.string.ui_upload_to_webdav,
        R.string.ui_the_backup_will_be_encrypted_with_this_passphrase_before_upload,
    ),
    WEBDAV_DOWNLOAD(
        R.string.ui_import_from_webdav,
        R.string.ui_after_download_the_backup_will_be_decrypted_with_this_passphrase_and_rep,
    ),
}

@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
) {
    var endpoint by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var username by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.password.orEmpty()) }
    var remoteFileName by rememberSaveable(initial?.endpoint) {
        mutableStateOf(initial?.remoteFileName ?: "mangossh-vault.mssh")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_configure_webdav)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.ui_only_https_webdav_urls_are_accepted_the_url_should_point_to_the_director), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(stringResource(R.string.ui_webdav_directory_url)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.ui_username)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ui_password_app_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = remoteFileName,
                    onValueChange = { remoteFileName = it },
                    label = { Text(stringResource(R.string.ui_remote_file_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(endpoint, username, password, remoteFileName) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SyncPassphraseDialog(
    action: SyncAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by rememberSaveable(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.titleResource)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(action.warningResource), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ui_sync_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotEmpty()) { Text(stringResource(R.string.common_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CommandSnippetDialog(
    initial: CommandSnippet?,
    onDismiss: () -> Unit,
    onSave: (label: String, script: String, appendNewline: Boolean) -> Unit,
) {
    var label by rememberSaveable(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var script by rememberSaveable(initial?.id) { mutableStateOf(initial?.script.orEmpty()) }
    var appendNewline by rememberSaveable(initial?.id) { mutableStateOf(initial?.appendNewline ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.ui_new_snippet) else stringResource(R.string.ui_edit_snippet)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ui_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text(stringResource(R.string.ui_shell_command_or_script)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = appendNewline, onCheckedChange = { appendNewline = it })
                    Text(stringResource(R.string.ui_append_newline_automatically))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), script, appendNewline) },
                enabled = label.isNotBlank() && script.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun AppPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val valid = pin.length in 4..12 && pin.all(Char::isDigit) && pin == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_set_app_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_the_pin_is_stored_only_on_this_device_as_a_salted_verifier_and_cannot_be), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.ui_4_12_digit_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.ui_confirm_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmation.isNotEmpty() && confirmation != pin,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun HostEditorSheet(
    initialHost: ConnectionProfile?,
    keys: List<StoredSshKey>,
    snippets: List<CommandSnippet>,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfileDraft) -> Unit,
) {
    var label by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.label.orEmpty()) }
    var hostname by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.hostname.orEmpty()) }
    var portText by rememberSaveable(initialHost?.id) { mutableStateOf((initialHost?.port ?: 22).toString()) }
    var username by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.username.orEmpty()) }
    var protocol by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.protocol ?: ConnectionProtocol.SSH) }
    var route by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.route ?: ConnectionRoute.DIRECT) }
    var authentication by rememberSaveable(initialHost?.id) {
        mutableStateOf(initialHost?.authentication ?: AuthenticationMethod.PRIVATE_KEY)
    }
    var keyId by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.keyId) }
    var startupSnippetId by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.startupSnippetId) }
    var agentForwarding by rememberSaveable(initialHost?.id) { mutableStateOf(initialHost?.agentForwarding ?: false) }
    val port = portText.toIntOrNull()
    val usesSystemTailscale = route == ConnectionRoute.TAILNET
    val authenticationIsConfigured = usesSystemTailscale ||
        authentication != AuthenticationMethod.PRIVATE_KEY ||
        keys.any { it.id == keyId }
    val canSave = hostname.isNotBlank() &&
        username.isNotBlank() &&
        port != null &&
        port in 1..65535 &&
        authenticationIsConfigured
    val firstFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialHost?.id) {
        firstFieldFocusRequester.requestFocus()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    onExit = { cancelFocusChange() }
                }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                text = if (initialHost == null) stringResource(R.string.ui_new_server) else stringResource(R.string.ui_edit_server),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.ui_name_optional)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstFieldFocusRequester),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text(stringResource(R.string.ui_hostname_or_ip_address)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = hostname.isBlank(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.ui_username)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = username.isBlank(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.ui_port)) },
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = port == null || port !in 1..65535,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ui_protocol), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionProtocol.entries.forEach { option ->
                    FilterChip(
                        selected = protocol == option,
                        onClick = { protocol = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ui_network_route), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ConnectionRouteSelector(
                selected = route,
                onSelected = { option ->
                    if (route != option) {
                        authentication = authenticationAfterRouteSelection(option, authentication)
                    }
                    route = option
                },
            )
            if (usesSystemTailscale) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ui_tailnet_routing_reaches_the_target_through_the_device_s_enabled_tailscal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.ui_authentication), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthenticationMethod.entries
                        .filterNot {
                            route == ConnectionRoute.DIRECT &&
                                it == AuthenticationMethod.TAILSCALE_SSH
                        }
                        .forEach { option ->
                            FilterChip(
                                selected = authentication == option,
                                onClick = { authentication = option },
                                label = { Text(option.label()) },
                            )
                        }
                }
                if (route == ConnectionRoute.TSNET) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ui_embedded_tailscale_proxies_only_this_profile_s_ssh_and_mosh_traffic_tail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (authentication == AuthenticationMethod.PRIVATE_KEY) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.ui_shared_private_key), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (keys.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_generate_or_import_a_private_key_on_the_keys_page_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { key ->
                                FilterChip(
                                    selected = keyId == key.id,
                                    onClick = { keyId = key.id },
                                    label = { Text(key.label) },
                                )
                            }
                        }
                    }
                }
            }
            if (protocol == ConnectionProtocol.MOSH) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ui_mosh_uses_a_gpl_3_0_or_later_native_client_its_source_and_license_are_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.ui_run_after_connection), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = startupSnippetId == null,
                    onClick = { startupSnippetId = null },
                    label = { Text(stringResource(R.string.ui_do_not_run)) },
                )
                snippets.forEach { snippet ->
                    FilterChip(
                        selected = startupSnippetId == snippet.id,
                        onClick = { startupSnippetId = snippet.id },
                        label = { Text(snippet.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (protocol == ConnectionProtocol.SSH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agentForwarding, onCheckedChange = { agentForwarding = it })
                    Text(stringResource(R.string.ui_enable_ssh_agent_forwarding), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    onSave(
                        ConnectionProfileDraft(
                            id = initialHost?.id,
                            label = label,
                            hostname = hostname,
                            port = requireNotNull(port),
                            username = username,
                            protocol = protocol,
                            route = route,
                            authentication = authentication,
                            keyId = keyId,
                            startupSnippetId = startupSnippetId,
                            agentForwarding = protocol == ConnectionProtocol.SSH && agentForwarding,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
            ) {
                Text(stringResource(R.string.ui_save_profile))
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

/** Three-route selector kept vertical so all choices remain visible on narrow phones. */
@Composable
internal fun ConnectionRouteSelector(
    selected: ConnectionRoute,
    onSelected: (ConnectionRoute) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionRoute.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                modifier = Modifier.testTag("connection_route_${option.name}"),
                label = { Text(option.label()) },
            )
        }
    }
}

internal fun authenticationAfterRouteSelection(
    route: ConnectionRoute,
    current: AuthenticationMethod,
): AuthenticationMethod = when {
    route == ConnectionRoute.TAILNET || route == ConnectionRoute.TSNET ->
        AuthenticationMethod.TAILSCALE_SSH
    current == AuthenticationMethod.TAILSCALE_SSH ->
        AuthenticationMethod.PRIVATE_KEY
    else -> current
}

@Composable
private fun SessionPromptDialog(
    prompt: SessionPrompt,
    onRespond: (List<String>?) -> Unit,
) {
    when (prompt) {
        is SessionPrompt.HostKeyVerification -> {
            AlertDialog(
                onDismissRequest = { onRespond(null) },
                title = {
                    Text(if (prompt.isChanged) stringResource(R.string.ui_server_fingerprint_changed) else stringResource(R.string.ui_verify_server_fingerprint))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (prompt.isChanged) {
                                stringResource(R.string.ui_the_saved_server_key_differs_from_this_connection_continue_only_after_co)
                            } else {
                                stringResource(R.string.ui_this_is_the_first_connection_to_this_server_verify_the_fingerprint_with)
                            },
                        )
                        Text("${prompt.hostname}:${prompt.port} · ${prompt.algorithm}")
                        SelectionContainer {
                            Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall)
                        }
                        prompt.previousFingerprint?.let { previous ->
                            Text(
                                stringResource(R.string.ui_previous_fingerprint) + previous,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRespond(listOf("trust")) }) {
                        Text(if (prompt.isChanged) stringResource(R.string.ui_replace_and_trust) else stringResource(R.string.ui_trust_and_connect))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onRespond(null) }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        is SessionPrompt.Authentication -> {
            val answers = remember(prompt.requestId) {
                mutableStateListOf<String>().apply { repeat(prompt.fields.size) { add("") } }
            }
            AlertDialog(
                onDismissRequest = { onRespond(null) },
                title = { Text(prompt.title.asString()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        prompt.instruction?.let { instruction ->
                            SelectionContainer { Text(instruction.asString()) }
                        }
                        prompt.fields.forEachIndexed { index, field ->
                            OutlinedTextField(
                                value = answers[index],
                                onValueChange = { answers[index] = it },
                                label = {
                                    Text(
                                        field.label.asString().ifBlank {
                                            stringResource(R.string.ui_enter_value)
                                        },
                                    )
                                },
                                singleLine = true,
                                visualTransformation = if (field.echo) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRespond(answers.toList()) }) {
                        Text(if (prompt.fields.isEmpty()) stringResource(R.string.common_continue) else stringResource(R.string.common_submit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onRespond(null) }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    }
}

private fun AppSection.icon(): ImageVector = when (this) {
    AppSection.HOSTS -> Icons.Outlined.Dns
    AppSection.KEYS -> Icons.Outlined.Key
    AppSection.FORWARDS -> Icons.Outlined.SwapHoriz
    AppSection.SETTINGS -> Icons.Outlined.Settings
}

@Composable
private fun sectionSubtitle(section: AppSection): String = when (section) {
    AppSection.HOSTS -> stringResource(R.string.ui_connection_workspace)
    AppSection.KEYS -> stringResource(R.string.ui_shared_keys_and_agent)
    AppSection.FORWARDS -> stringResource(R.string.ui_ssh_port_forwarding)
    AppSection.SETTINGS -> stringResource(R.string.ui_app_lock_sync_and_tailnet)
}

@Composable
private fun VaultStatus.summary(): String = when (this) {
    VaultStatus.Loading -> stringResource(R.string.ui_secure_vault_opening)
    VaultStatus.Ready -> stringResource(R.string.ui_secure_vault_encrypted_by_android_keystore)
    is VaultStatus.Failed -> stringResource(R.string.ui_secure_vault_unavailable)
}

@Composable
internal fun VaultStatus.description(): String = when (this) {
    VaultStatus.Loading -> stringResource(R.string.ui_opening_the_local_encrypted_vault_server_fingerprints_are_still_verified)
    VaultStatus.Ready -> stringResource(R.string.ui_local_configuration_is_encrypted_with_android_keystore_and_aes_gcm_serve)
    is VaultStatus.Failed -> failureMessage()
}

@Composable
internal fun VaultStatus.Failed.failureMessage(): String = stringResource(
    when (reason) {
        VaultFailureReason.OPEN -> R.string.message_vault_open_failed
        VaultFailureReason.WRITE -> R.string.message_vault_save_failed
    },
)

@Composable
private fun AppSection.label(): String = stringResource(
    when (this) {
        AppSection.HOSTS -> R.string.nav_hosts
        AppSection.KEYS -> R.string.nav_keys
        AppSection.FORWARDS -> R.string.nav_forwards
        AppSection.SETTINGS -> R.string.nav_settings
    },
)

@Composable
internal fun ConnectionRoute.label(): String = stringResource(
    when (this) {
        ConnectionRoute.DIRECT -> R.string.connection_route_direct
        ConnectionRoute.TAILNET -> R.string.connection_route_system_tailscale
        ConnectionRoute.TSNET -> R.string.connection_route_embedded_tailscale
    },
)

@Composable
private fun AuthenticationMethod.label(): String = stringResource(
    when (this) {
        AuthenticationMethod.PRIVATE_KEY -> R.string.authentication_private_key
        AuthenticationMethod.PASSWORD -> R.string.authentication_password
        AuthenticationMethod.KEYBOARD_INTERACTIVE -> R.string.authentication_interactive
        AuthenticationMethod.TAILSCALE_SSH -> R.string.authentication_tailscale_ssh
    },
)

@Composable
private fun PortForwardType.label(): String = stringResource(
    when (this) {
        PortForwardType.LOCAL -> R.string.port_forward_type_local
        PortForwardType.REMOTE -> R.string.port_forward_type_remote
        PortForwardType.DYNAMIC -> R.string.port_forward_type_dynamic
    },
)

@Composable
private fun SessionPromptText.asString(): String = when (this) {
    is SessionPromptText.Verbatim -> value
    is SessionPromptText.App -> when (kind) {
        SessionPromptTextKind.PASSWORD_TITLE ->
            stringResource(R.string.authentication_password_title, requireNotNull(argument))
        SessionPromptTextKind.PASSWORD_INSTRUCTION ->
            stringResource(R.string.authentication_password_instruction)
        SessionPromptTextKind.PASSWORD_FIELD ->
            stringResource(R.string.authentication_password_field)
        SessionPromptTextKind.UNLOCK_KEY_TITLE ->
            stringResource(R.string.authentication_unlock_key_title, requireNotNull(argument))
        SessionPromptTextKind.KEY_PASSPHRASE_INSTRUCTION ->
            stringResource(R.string.authentication_key_passphrase_instruction)
        SessionPromptTextKind.KEY_PASSPHRASE_FIELD ->
            stringResource(R.string.authentication_key_passphrase_field)
        SessionPromptTextKind.TAILSCALE_LOGIN_TITLE ->
            stringResource(R.string.authentication_tailscale_login_title)
        SessionPromptTextKind.INTERACTIVE_LOGIN_TITLE ->
            stringResource(R.string.authentication_interactive_login_title)
    }
}
