package org.connectbot.terminal

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the host-app extension point on the selection overflow menu ([Terminal]'s
 * `selectionMenuExtras` parameter), which a host app uses for entries such as chrome
 * visibility toggles that must stay reachable regardless of what other chrome is hidden.
 *
 * Selection is started through [SelectionController.startSelection] (the same accessibility
 * entry point the emulator's own a11y actions use) rather than a real long-press gesture, to
 * keep this focused on the menu itself rather than long-press timing.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSelectionMenuExtrasInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var emulator: TerminalEmulatorImpl
    private lateinit var selectionController: SelectionController
    private var extraClicks = 0
    private var withExtras by mutableStateOf(true)

    @Test
    fun extraItemAppearsAfterADividerAndDismissClosesTheMenuAndClearsSelection() {
        mountTerminal()
        openSelectionMenu()

        composeRule.onNodeWithTag(EXTRA_ITEM_TAG).assertExists()
        composeRule.onNodeWithTag(EXTRA_ITEM_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals("Extra item's onClick must fire", 1, extraClicks)
            assertEquals("dismiss() must clear the selection", false, selectionController.isSelectionActive)
        }
        // The overflow menu (and the selection it belonged to) is gone: neither the extra
        // item nor the built-in Copy button remain composed.
        composeRule.onNodeWithTag(EXTRA_ITEM_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(COPY_LABEL).assertDoesNotExist()
    }

    @Test
    fun noExtrasParameterRendersNoExtraContent() {
        withExtras = false
        mountTerminal()
        openSelectionMenu()

        composeRule.onNodeWithTag(EXTRA_ITEM_TAG).assertDoesNotExist()
        // The built-in items are still there; only the host-app extension point is absent.
        composeRule.onNodeWithText(COPY_LABEL).assertExists()
    }

    private fun openSelectionMenu() {
        composeRule.runOnIdle {
            selectionController.startSelection(SelectionMode.CHARACTER)
            // The Copy/overflow buttons only render once the gesture that started the
            // selection has ended (selectionManager.isSelecting == false) — mirroring what a
            // real long-press's pointer-up does.
            selectionController.finishSelection()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
    }

    private fun mountTerminal() {
        emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = {},
        ) as TerminalEmulatorImpl
        composeRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.testTag(TERMINAL_TAG),
                forceAccessibilityEnabled = false,
                onSelectionControllerAvailable = { selectionController = it },
                selectionMenuExtras = if (withExtras) {
                    { dismiss ->
                        DropdownMenuItem(
                            modifier = Modifier.testTag(EXTRA_ITEM_TAG),
                            text = { Text("Extra item") },
                            onClick = {
                                extraClicks += 1
                                dismiss()
                            },
                        )
                    }
                } else {
                    null
                },
            )
        }
        composeRule.waitUntil(2_000) { ::selectionController.isInitialized }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TERMINAL_TAG = "terminal_selection_menu_extras"
        const val EXTRA_ITEM_TAG = "terminal_selection_menu_extra_item"
        const val MORE_OPTIONS_DESCRIPTION = "More options"
        const val COPY_LABEL = "Copy"
    }
}
