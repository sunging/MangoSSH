package website.sung.mangossh.presentation

import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableSharedFlow
import org.connectbot.terminal.TerminalEmulatorFactory
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.session.ServerResourceState
import website.sung.mangossh.session.TerminalClipboardCopy
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState

/** Hosts a disconnected terminal for real keyboard/rotation tests without opening the user vault. */
class TerminalImeRegressionActivity : ComponentActivity() {
    internal var terminalVisible by mutableStateOf(true)
    internal var hostSearchActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Match the app's default pan mode; the terminal must override it through a wrapped context.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContent {
            val wrappedContext = remember(this) { ContextWrapper(this) }
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
                Column(modifier = Modifier.testTag("ime_test_hosts")) {
                    if (hostSearchActive) {
                        HostSearchTopBar(
                            query = "fixture-host",
                            onQueryChange = {},
                            onClose = { hostSearchActive = false },
                            focusRequester = remember { FocusRequester() },
                            focusRequested = false,
                            onFocusRequestConsumed = {},
                        )
                    } else {
                        Text("Hosts")
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
