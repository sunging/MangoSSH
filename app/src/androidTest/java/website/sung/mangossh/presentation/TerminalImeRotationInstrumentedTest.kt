package website.sung.mangossh.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Exercises real IME/window geometry across activity recreation without user sessions or storage. */
class TerminalImeRotationInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<TerminalImeRegressionActivity>()

    @Test
    fun shortcutsStayAboveTheKeyboardWhenRotatingToLandscapeAndBack() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertKeyboardDoesNotCoverShortcuts(102)
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertKeyboardDoesNotCoverShortcuts(52)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertKeyboardDoesNotCoverShortcuts(102)
    }

    @Test
    fun shortcutsStayAboveTheKeyboardAfterImmersiveModeAndRotation() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertKeyboardDoesNotCoverShortcuts(102)
        composeRule.onNodeWithTag("terminal_immersive_toggle").performClick()
        awaitKeyboard()
        composeRule.onNodeWithTag("terminal_immersive_exit").performClick()
        assertKeyboardDoesNotCoverShortcuts(102)
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertKeyboardDoesNotCoverShortcuts(52)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertKeyboardDoesNotCoverShortcuts(102)
    }

    private fun rotate(requestedOrientation: Int, configurationOrientation: Int) {
        composeRule.runOnUiThread { composeRule.activity.requestedOrientation = requestedOrientation }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.activity.resources.configuration.orientation == configurationOrientation
        }
        composeRule.waitForIdle()
    }

    private fun awaitKeyboard() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.runOnUiThread { composeRule.activity.keyboardFrame().isVisible }
        }
        composeRule.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun assertKeyboardDoesNotCoverShortcuts(expectedHeightDp: Int) {
        val bars = composeRule.onAllNodesWithTag("terminal_shortcut_bar")
        var latestLayout: ShortcutLayout? = null
        var unchangedSince = 0L
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val bar = bars.fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
                // Read the live Compose node and Android's keyboard edge in the same UI turn.
                val layout = composeRule.runOnUiThread {
                    val activity = composeRule.activity
                    val placedBar = bar?.takeIf { it.layoutInfo.isAttached && it.layoutInfo.isPlaced }
                    ShortcutLayout(
                        keyboard = activity.keyboardFrame(),
                        barBottomPx = placedBar?.let { it.positionOnScreen.y + it.size.height },
                        barHeightDp = placedBar?.let { it.size.height / it.layoutInfo.density.density },
                        adjustment = activity.window.attributes.softInputMode and
                            WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
                    )
                }
                val now = SystemClock.uptimeMillis()
                if (layout != latestLayout) {
                    latestLayout = layout
                    unchangedSince = now
                }
                // Let the IME animation and Compose layout settle after rotation.
                now - unchangedSince >= 100 &&
                    layout.keyboard.isVisible &&
                    layout.adjustment == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE &&
                    layout.barHeightDp != null && abs(layout.barHeightDp - expectedHeightDp) <= 0.5f &&
                    layout.barBottomPx != null && layout.barBottomPx <= layout.keyboard.topPx + 1
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Shortcut bar must settle above the visible keyboard at ${expectedHeightDp}dp " +
                    "with soft-input adjustment ${WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE}. " +
                    "Last sample = $latestLayout",
                timeout,
            )
        }
        composeRule.onNodeWithTag("terminal_shortcut_bar").assertIsDisplayed()
    }

    private fun TerminalImeRegressionActivity.keyboardFrame(): KeyboardFrame {
        val decor = window.decorView
        val visibleFrame = Rect()
        val decorLocation = IntArray(2)
        decor.getWindowVisibleDisplayFrame(visibleFrame)
        decor.getLocationOnScreen(decorLocation)
        return KeyboardFrame(
            topPx = visibleFrame.bottom,
            heightPx = decorLocation[1] + decor.height - visibleFrame.bottom,
            minimumHeightPx = (100 * resources.displayMetrics.density).toInt(),
        )
    }

    private data class KeyboardFrame(val topPx: Int, val heightPx: Int, val minimumHeightPx: Int) {
        val isVisible: Boolean get() = heightPx > minimumHeightPx
    }

    private data class ShortcutLayout(
        val keyboard: KeyboardFrame,
        val barBottomPx: Float?,
        val barHeightDp: Float?,
        val adjustment: Int,
    )
}
