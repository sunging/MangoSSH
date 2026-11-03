package website.sung.mangossh.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
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
import org.connectbot.terminal.R as TerminalR

/**
 * Covers the terminal screen's chrome visibility controls: the immersive toggle, the title
 * bar's long-press menu, and the same two toggles reachable from the terminal's text-selection
 * overflow menu — the escape hatch that stays reachable even once the title bar itself is
 * hidden. Pointer-repeat and layout behavior of the shortcut bar itself is covered by
 * [TerminalShortcutBarInstrumentedTest].
 */
class TerminalSessionScreenChromeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private var sessionFontSizeSp by mutableStateOf<Int?>(null)
    private var mounted by mutableStateOf(true)
    private var instanceKey by mutableIntStateOf(0)
    private var phase by mutableStateOf(TerminalSessionPhase.CONNECTING)
    private var reconnects = 0

    @Test fun closedTerminalRecoveryFitsCompactLandscape() {
        phase = TerminalSessionPhase.CLOSED
        mount(DpSize(800.dp, 360.dp))
        composeRule.onNodeWithTag("terminal_reconnect").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test fun closedTerminalRecoveryFitsTablet() {
        phase = TerminalSessionPhase.CLOSED
        mount(DpSize(1280.dp, 800.dp))
        composeRule.onNodeWithTag("terminal_reconnect").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test
    fun recoveryOnlyAppearsForClosedTerminalsAndKeepsTheOutputSurface() {
        mount()
        TerminalSessionPhase.entries.filter { it != TerminalSessionPhase.CLOSED }.forEach { state ->
            composeRule.runOnIdle { phase = state }
            composeRule.onNodeWithTag("terminal_reconnect").assertDoesNotExist()
        }
        composeRule.runOnIdle { phase = TerminalSessionPhase.CLOSED }
        composeRule.onNodeWithTag("terminal_reconnect").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("terminal_surface").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test
    fun recoverySurvivesHiddenTitleBarAndImmersiveMode() {
        mount()
        openChromeMenu()
        composeRule.onNodeWithTag("terminal_menu_toggle_title_bar").performClick()
        composeRule.runOnIdle { phase = TerminalSessionPhase.CLOSED }
        composeRule.onNodeWithTag("terminal_reconnect").assertIsDisplayed().performClick()
        composeRule.runOnIdle { mounted = false }
        composeRule.runOnIdle { instanceKey += 1; mounted = true }
        composeRule.onNodeWithTag("terminal_immersive_toggle").performClick()
        composeRule.onNodeWithTag("terminal_title_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_reconnect").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(2, reconnects) }
    }

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

    @Test
    fun selectionMenuRestoresTheTitleBarAfterItWasHiddenFromTheTopBarMenu() {
        mount()
        openChromeMenu()
        composeRule.onNodeWithTag("terminal_menu_toggle_title_bar").performClick()
        composeRule.onNodeWithTag("terminal_title_bar").assertDoesNotExist()

        // With the title bar (and its own long-press menu) gone, this is the only way back.
        openSelectionMenu()
        composeRule.onNodeWithTag("terminal_selection_menu_toggle_title_bar").performClick()

        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
    }

    @Test
    fun selectionMenuTogglesTheShortcutBar() {
        mount()
        openSelectionMenu()
        composeRule.onNodeWithTag("terminal_selection_menu_toggle_shortcut_bar").performClick()

        composeRule.onNodeWithTag("terminal_shortcut_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
    }

    @Test
    fun selectionMenuLeavesImmersiveModeWhenShowingABar() {
        mount()
        composeRule.onNodeWithTag("terminal_immersive_toggle").performClick()
        composeRule.onNodeWithTag("terminal_title_bar").assertDoesNotExist()

        openSelectionMenu()
        composeRule.onNodeWithTag("terminal_selection_menu_toggle_title_bar").performClick()

        composeRule.onNodeWithTag("terminal_title_bar").assertExists()
        composeRule.onNodeWithTag("terminal_immersive_exit").assertDoesNotExist()
    }

    private fun openChromeMenu() {
        composeRule.onNodeWithTag("terminal_immersive_toggle").performTouchInput { longClick() }
    }

    private fun openSelectionMenu() {
        composeRule.onNodeWithTag("terminal_surface").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val moreOptionsDescription = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(TerminalR.string.terminal_selection_more_options_description)
        composeRule.onNodeWithContentDescription(moreOptionsDescription).performClick()
        composeRule.waitForIdle()
    }

    private fun mount(size: DpSize? = null) {
        composeRule.setContent {
            val content: @Composable () -> Unit = {
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
                                // Most chrome tests stay CONNECTING to avoid opening the real IME.
                                phase = phase,
                            ),
                            terminalEmulator = emulator,
                            appearance = TerminalAppearance(),
                            behavior = TerminalBehavior(),
                            shortcutConfig = TerminalShortcutConfig.defaults(),
                            clipboardCopies = remember(instanceKey) { MutableSharedFlow<TerminalClipboardCopy>() },
                            onSend = {},
                            resourceState = website.sung.mangossh.session.ServerResourceState(),
                            onResourceMonitoringChange = {},
                            onRequestResources = {},
                            onOpenFileBrowser = {},
                            onRequestLeave = {},
                            onReconnect = { reconnects++ },
                            sessionFontSizeSp = sessionFontSizeSp,
                            onSessionFontSizeChange = { sessionFontSizeSp = it },
                        )
                    }
                }
            }
            if (size == null) content() else {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size), content)
            }
        }
        composeRule.waitForIdle()
    }
}
