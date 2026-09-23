package org.connectbot.terminal

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual touch routing so a swipe cannot silently become focused-input arrow keys. */
@RunWith(AndroidJUnit4::class)
class TerminalScrollGestureInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val output = ConcurrentLinkedQueue<ByteArray>()
    private lateinit var emulator: TerminalEmulatorImpl
    private lateinit var scrollController: ScrollController
    private var charWidth = 0f
    private var charHeight = 0f

    @Test
    fun alternateScreenWithoutMouseIgnoresSwipesAtDifferentLocationsAndPreservesKeyboardInput() {
        mountTerminal()
        seedHistory()
        setModes(alternate = true, mouse = false)

        for (col in listOf(2, emulator.snapshot.value.cols - 3)) {
            swipeCells(col, startRow = 2, endRow = 7)
            swipeCells(col, startRow = 9, endRow = 4)
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle {
            assertTrue("Swipes must not generate terminal input", output.isEmpty())
            assertEquals("Must not reveal primary-screen history", 0, scrollController.scrollbackPosition)
            emulator.dispatchKey(0, VTermKey.UP)
        }
        composeRule.waitUntil(2_000) { output.isNotEmpty() }
        assertEquals("\u001B[A", output.single().decodeToString())
    }

    @Test
    fun mouseTrackingReportsWheelDirectionAndTouchedCellOnEitherScreenWithoutInertia() {
        mountTerminal()
        seedHistory()
        for (alternate in listOf(false, true)) {
            setModes(alternate = alternate, mouse = true)
            assertWheelSwipe(col = 3, startRow = 2, endRow = 7, button = 64)
            assertWheelSwipe(col = emulator.snapshot.value.cols - 3, startRow = 9, endRow = 4, button = 65)
        }
    }

    @Test
    fun primaryScreenWithoutMouseScrollsHistoryWithInertiaAndSendsNoInput() {
        mountTerminal()
        seedHistory()
        setModes(alternate = false, mouse = false)
        composeRule.mainClock.autoAdvance = false
        val terminal = composeRule.onNodeWithTag(TERMINAL_TAG)
        terminal.performTouchInput {
            down(cell(3, 2))
            moveTo(cell(3, 4), delayMillis = 64)
            // Supply several recent motion samples so the velocity tracker can
            // estimate release speed, as it would during a real finger swipe.
            for (row in 5..9) moveTo(cell(3, row), delayMillis = 16)
        }
        var beforeRelease = 0
        composeRule.runOnIdle {
            beforeRelease = scrollController.scrollbackPosition
            assertTrue("Drag must scroll primary history", beforeRelease > 0)
        }
        terminal.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.runOnIdle {
            assertTrue(
                "Local history must retain fling inertia: before=$beforeRelease, after=${scrollController.scrollbackPosition}",
                scrollController.scrollbackPosition > beforeRelease,
            )
            assertTrue(scrollController.scrollbackPosition <= scrollController.maxScrollback)
            assertTrue("Local history gestures must not send input", output.isEmpty())
        }
    }

    @Test
    fun disablingMouseTrackingStopsWheelReportsOnAlternateScreen() {
        mountTerminal()
        setModes(alternate = true, mouse = true)
        assertWheelSwipe(col = 3, startRow = 2, endRow = 7, button = 64)
        setModes(alternate = true, mouse = false)
        swipeCells(col = 3, startRow = 2, endRow = 7)
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle {
            assertTrue("Disabled tracking must not fall back to keys", output.isEmpty())
            assertEquals(0, scrollController.scrollbackPosition)
        }
    }

    private fun mountTerminal() {
        emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            maxScrollbackLines = 500,
            onKeyboardInput = { output.add(it.copyOf()) },
        ) as TerminalEmulatorImpl
        composeRule.setContent {
            val fontSizePx = with(LocalDensity.current) { 11.sp.toPx() }
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = fontSizePx
            }
            charWidth = paint.measureText("M")
            charHeight = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.testTag(TERMINAL_TAG),
                forceAccessibilityEnabled = false,
                onScrollControllerAvailable = { scrollController = it },
            )
        }
        composeRule.waitUntil(2_000) { ::scrollController.isInitialized && charHeight > 0f }
        composeRule.waitForIdle()
    }

    /** Retains primary history even when testing an alternate screen, to catch accidental local panning. */
    private fun seedHistory() {
        emulator.writeInput(buildString {
            repeat(emulator.snapshot.value.rows + 200) { append("history line\r\n") }
        }.encodeToByteArray())
        composeRule.waitUntil(2_000) { emulator.snapshot.value.scrollback.size >= 150 }
        composeRule.waitForIdle()
    }

    private fun setModes(alternate: Boolean, mouse: Boolean) {
        emulator.writeInput((
            "\u001B[?1049${if (alternate) 'h' else 'l'}" +
                "\u001B[?1000${if (mouse) 'h' else 'l'}"
            ).encodeToByteArray())
        composeRule.waitUntil(2_000) {
            emulator.snapshot.value.let { it.isAltScreen == alternate && it.mouseTrackingActive == mouse }
        }
        composeRule.waitForIdle()
        output.clear()
    }

    private fun cell(col: Int, row: Int): Offset = Offset((col + 0.5f) * charWidth, (row + 0.5f) * charHeight)

    private fun swipeCells(col: Int, startRow: Int, endRow: Int) {
        composeRule.onNodeWithTag(TERMINAL_TAG).performTouchInput {
            advanceEventTime(400)
            down(cell(col, startRow))
            moveTo(cell(col, endRow), delayMillis = 100)
            up()
        }
        composeRule.waitForIdle()
    }

    private fun assertWheelSwipe(col: Int, startRow: Int, endRow: Int, button: Int) {
        output.clear()
        swipeCells(col, startRow, endRow)
        composeRule.waitUntil(2_000) { output.isNotEmpty() }
        val reports = output.toList()
        assertFalse(reports.isEmpty())
        reports.forEach { report ->
            assertEquals("Only X10 wheel reports may be emitted", 6, report.size)
            assertEquals(0x1B, report[0].toInt())
            assertEquals('['.code, report[1].toInt())
            assertEquals('M'.code, report[2].toInt())
            assertEquals(32 + button, report[3].toInt() and 0xFF)
            assertEquals(33 + col, report[4].toInt() and 0xFF)
            assertEquals(33 + endRow, report[5].toInt() and 0xFF)
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle {
            assertEquals("Remote wheel reports must stop on release", reports.size, output.size)
            assertEquals(0, scrollController.scrollbackPosition)
        }
    }

    private companion object {
        const val TERMINAL_TAG = "terminal_scroll_gesture"
    }
}
