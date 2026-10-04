package org.connectbot.terminal

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A host can hand the same [Terminal] composable a different emulator, e.g. when it switches
 * sessions from a notification. Soft-keyboard input arrives through [ImeInputView], which must
 * then follow the new emulator instead of writing into the previous session.
 */
@RunWith(AndroidJUnit4::class)
class TerminalImeRetargetInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sentToFirst = StringBuffer()
    private val sentToSecond = StringBuffer()
    private val first = emulator(sentToFirst)
    private val second = emulator(sentToSecond)
    private var current by mutableStateOf<TerminalEmulator>(first)
    private lateinit var hostView: View

    @Test
    fun softKeyboardInputFollowsTheEmulatorAfterASwap() {
        composeRule.setContent {
            hostView = LocalView.current
            Terminal(terminalEmulator = current, keyboardEnabled = true, showSoftKeyboard = false)
        }
        composeRule.waitForIdle()

        val firstView = imeInputView()
        commitText(firstView, "a")
        awaitText(sentToFirst, "a")

        composeRule.runOnIdle { current = second }
        composeRule.waitForIdle()

        val secondView = imeInputView()
        assertNotSame("The IME view must be rebuilt for the new emulator", firstView, secondView)
        commitText(secondView, "b")
        awaitText(sentToSecond, "b")
        SystemClock.sleep(SETTLE_MS)
        assertEquals("The previous session must not receive the new input", "a", sentToFirst.toString())
    }

    private fun emulator(sink: StringBuffer): TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = { data -> sink.append(String(data)) },
    )

    private fun imeInputView(): ImeInputView {
        var found: ImeInputView? = null
        composeRule.runOnIdle { found = findImeInputView(hostView.rootView) }
        return checkNotNull(found) { "Terminal did not attach an ImeInputView" }
    }

    private fun findImeInputView(view: View): ImeInputView? {
        if (view is ImeInputView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findImeInputView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun commitText(view: ImeInputView, text: String) {
        composeRule.runOnIdle {
            view.onCreateInputConnection(EditorInfo()).commitText(text, 1)
        }
    }

    private fun awaitText(sink: StringBuffer, expected: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && sink.toString() != expected) {
            SystemClock.sleep(POLL_MS)
        }
        assertEquals(expected, sink.toString())
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val POLL_MS = 16L
        const val SETTLE_MS = 100L
    }
}
