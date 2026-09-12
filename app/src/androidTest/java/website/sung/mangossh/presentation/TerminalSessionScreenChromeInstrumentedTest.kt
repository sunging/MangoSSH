package website.sung.mangossh.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.flow.MutableSharedFlow
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.session.TerminalClipboardCopy
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState

/**
 * Covers the terminal screen's chrome visibility controls: the immersive toggle, and the
 * long-press menu's independent title-bar / shortcut-bar show-hide switches. Pointer-repeat
 * and layout behavior of the shortcut bar itself is covered by
 * [TerminalShortcutBarInstrumentedTest].
 */
class TerminalSessionScreenChromeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private var sessionFontSizeSp by mutableStateOf<Int?>(null)
    private var mounted by mutableStateOf(true)
    private var instanceKey by mutableIntStateOf(0)

    @Test
    fun titleBarAndShortcutBarShowByDefault() {
        mount()
        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
        composeRule.onNodeWithTag("terminal_shortcut_bar").assertExists()
    }

    @Test
    fun longPressMenuTogglesTheShortcutBarIndependentlyOfTheTitleBar() {
        mount()
        openChromeMenu()
        composeRule.onNodeWithTag("terminal_menu_toggle_shortcut_bar").performClick()

        composeRule.onNodeWithTag("terminal_shortcut_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
    }

    @Test
    fun longPressMenuTogglesTheTitleBarAndItReappearsOnAFreshMount() {
        mount()
        openChromeMenu()
        composeRule.onNodeWithTag("terminal_menu_toggle_title_bar").performClick()
        composeRule.onNodeWithTag("terminal_title_bar").assertDoesNotExist()

        // The real app's escape hatch (leave the session in the background, then reopen it)
        // recomposes the whole screen from scratch; simulate that by remounting under a new
        // `key`, which resets every `rememberSaveable` slot including topBarVisible.
        composeRule.runOnIdle { mounted = false }
        composeRule.runOnIdle { instanceKey += 1; mounted = true }

        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
    }

    @Test
    fun tappingImmersiveHidesBothBarsAndTheFloatingExitButtonRestoresThem() {
        mount()
        composeRule.onNodeWithTag("terminal_immersive_toggle").performClick()

        composeRule.onNodeWithTag("terminal_title_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_shortcut_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_immersive_exit").assertExists()

        composeRule.onNodeWithTag("terminal_immersive_exit").performClick()
        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
        composeRule.onNodeWithTag("terminal_shortcut_bar").assertExists()
    }

    @Test
    fun resetZoomMenuItemReportsTheGlobalFontSize() {
        sessionFontSizeSp = 20
        mount()
        openChromeMenu()
        composeRule.onNodeWithTag("terminal_menu_reset_zoom").performClick()
        composeRule.runOnIdle {
            // The default TerminalAppearance() base size committed back by the menu item.
            assertEquals(TerminalAppearance.DEFAULT_FONT_SIZE_SP, sessionFontSizeSp)
        }
    }

    private fun openChromeMenu() {
        composeRule.onNodeWithTag("terminal_immersive_toggle").performTouchInput { longClick() }
    }

    private fun mount() {
        composeRule.setContent {
            if (mounted) {
                key(instanceKey) {
                    val emulator = remember(instanceKey) {
                        TerminalEmulatorFactory.create(
                            initialRows = 24,
                            initialCols = 80,
                            defaultForeground = Color.White,
                            defaultBackground = Color.Black,
                            onKeyboardInput = {},
                        )
                    }
                    TerminalSessionScreen(
                        session = TerminalSessionState(
                            id = "session-$instanceKey",
                            profileId = "profile-1",
                            title = "test-host",
                            endpoint = "test-host:22",
                            protocol = ConnectionProtocol.SSH,
                            // Kept off OPEN so the terminal never requests the real soft keyboard.
                            phase = TerminalSessionPhase.CONNECTING,
                        ),
                        terminalEmulator = emulator,
                        appearance = TerminalAppearance(),
                        behavior = TerminalBehavior(),
                        shortcutConfig = TerminalShortcutConfig.defaults(),
                        clipboardCopies = remember(instanceKey) { MutableSharedFlow<TerminalClipboardCopy>() },
                        onSend = {},
                        resourceSnapshot = null,
                        onRequestResources = {},
                        onOpenFileBrowser = {},
                        onRequestLeave = {},
                        sessionFontSizeSp = sessionFontSizeSp,
                        onSessionFontSizeChange = { sessionFontSizeSp = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
