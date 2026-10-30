package website.sung.mangossh.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
            val activity = composeRule.activity
            val frame = Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(frame)
            val minimumImeHeight = (100 * activity.resources.displayMetrics.density).toInt()
            activity.window.decorView.height - frame.bottom > minimumImeHeight
        }
        composeRule.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun assertKeyboardDoesNotCoverShortcuts(expectedHeightDp: Int) {
        awaitKeyboard()
        composeRule.runOnIdle {
            val adjustment = composeRule.activity.window.attributes.softInputMode and
                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustment)
        }
        val bar = composeRule.onNodeWithTag("terminal_shortcut_bar")
            .assertIsDisplayed().assertHeightIsEqualTo(expectedHeightDp.dp)
        val bounds = bar.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            val activity = composeRule.activity
            val visibleFrame = Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            val rootLocation = IntArray(2)
            activity.findViewById<View>(android.R.id.content).getLocationOnScreen(rootLocation)
            assertTrue(
                "Shortcut bar bottom must remain above the visible keyboard",
                bounds.bottom + rootLocation[1] <= visibleFrame.bottom + 1,
            )
        }
    }
}
