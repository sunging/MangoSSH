package website.sung.mangossh.presentation

import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
                    onSend = {},
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
