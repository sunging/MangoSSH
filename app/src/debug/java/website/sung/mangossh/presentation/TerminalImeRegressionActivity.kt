package website.sung.mangossh.presentation

import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableSharedFlow
import org.connectbot.terminal.TerminalEmulatorFactory
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.matchesHostQuery
import website.sung.mangossh.session.ServerResourceState
import website.sung.mangossh.session.TerminalClipboardCopy
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState

/** Hosts a disconnected terminal for real keyboard/rotation tests without opening the user vault. */
class TerminalImeRegressionActivity : ComponentActivity() {
    internal var terminalVisible by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Match the app's default pan mode; the terminal must override it through a wrapped context.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContent {
            val wrappedContext = remember(this) { ContextWrapper(this) }
            var searching by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var requestSearchFocus by remember { mutableStateOf(false) }
            val searchFocus = remember { FocusRequester() }
            val emulator = remember {
                TerminalEmulatorFactory.create(
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = Color.White,
                    defaultBackground = Color.Black,
                    onKeyboardInput = {},
                )
            }
            HideTerminalImeOnHostListReturn(
                terminalVisible = terminalVisible,
                hostListVisible = !terminalVisible,
            )
            if (!terminalVisible) {
                Scaffold(
                    modifier = Modifier.testTag("ime_test_hosts"),
                    topBar = {
                        if (searching) {
                            HostSearchTopBar(
                                query = query,
                                onQueryChange = { query = it },
                                onClose = {
                                    searching = false
                                    query = ""
                                },
                                focusRequester = searchFocus,
                                focusRequested = requestSearchFocus,
                                onFocusRequestConsumed = { requestSearchFocus = false },
                            )
                        } else {
                            IconButton(onClick = {
                                requestSearchFocus = true
                                searching = true
                            }) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.common_search))
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        FixtureHosts(query, onConnect = { terminalVisible = true })
                    }
                }
                return@setContent
            }
            CompositionLocalProvider(LocalContext provides wrappedContext) {
                TerminalSessionScreen(
                    session = TerminalSessionState(
                        id = "ime-rotation-test",
                        profileId = "ime-rotation-test",
                        title = "Terminal test",
                        endpoint = "",
                        protocol = ConnectionProtocol.SSH,
                        phase = TerminalSessionPhase.OPEN,
                    ),
                    terminalEmulator = emulator,
                    appearance = TerminalAppearance(),
                    behavior = TerminalBehavior(),
                    shortcutConfig = TerminalShortcutConfig.defaults(),
                    clipboardCopies = remember { MutableSharedFlow<TerminalClipboardCopy>() },
                    pasteGeneration = { 0L },
                    resourceState = ServerResourceState(),
                    onRequestResources = {},
                    onResourceMonitoringChange = {},
                    onOpenFileBrowser = {},
                    onRequestLeave = {},
                    sessionFontSizeSp = null,
                    onSessionFontSizeChange = {},
                )
            }
        }
    }
}

private val fixtureProfiles = listOf("Terminal fixture", "Other host").map { label ->
    ConnectionProfile(id = label, label = label, hostname = "example.invalid", username = "fixture")
}

/** Uses the same clickable host rows as the app while keeping profiles and connections in memory. */
@Composable
private fun FixtureHosts(query: String, onConnect: () -> Unit) {
    HostsScreen(
        hosts = fixtureProfiles.filter { it.matchesHostQuery(query) },
        hasAnyHost = true,
        reorderable = false,
        sessions = emptyList(),
        vaultStatus = VaultStatus.Ready,
        onConnectHost = { onConnect() },
        onEditHost = {},
        onRemoveHost = {},
        onBrowseHostFiles = {},
        onOpenSession = {},
        onDisconnectSession = {},
        onReorderHosts = {},
        onMoveHost = { _, _ -> },
        onMoveHostToTop = {},
    )
}
