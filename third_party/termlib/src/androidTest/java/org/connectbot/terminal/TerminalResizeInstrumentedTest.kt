package org.connectbot.terminal

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalResizeInstrumentedTest {
    @Test
    fun expandingTerminalRebuildsEveryVisibleRowWithoutDuplicatingBottomLine() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 3,
            initialCols = 12,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = {},
        ) as TerminalEmulatorImpl
        emulator.writeInput("\u001B[1;1Hone\u001B[2;1Htwo\u001B[3;1Hstatus".encodeToByteArray())
        awaitSnapshot(emulator) { it.lines.any { line -> line.text.trimEnd() == "status" } }

        emulator.resize(newRows = 8, newCols = 12)

        val resized = awaitSnapshot(emulator) { it.rows == 8 && it.lines.size == 8 }
        val visibleText = resized.lines.map { it.text.trimEnd() }
        assertEquals(8, visibleText.size)
        assertEquals(1, visibleText.count { it == "status" })
    }

    @Test
    fun growingTerminalRefillsTopRowsFromScrollback() {
        val emulator = createEmulator(rows = 4)
        emulator.writeInput(numberedLines(8).encodeToByteArray())
        awaitSnapshot(emulator) { it.scrollback.size == 4 }

        emulator.resize(newRows = 2, newCols = 12)
        awaitSnapshot(emulator) { it.rows == 2 && it.scrollback.size == 6 }
        emulator.resize(newRows = 4, newCols = 12)

        val grown = awaitSnapshot(emulator) { it.rows == 4 && it.scrollback.size == 4 && it.lines[0].visibleText() == "l5" }
        assertEquals(listOf("l5", "l6", "l7", "l8"), grown.lines.map { it.visibleText() })
    }

    @Test
    fun conptyHistorySurvivesKeyboardStyleShrinkAndGrow() {
        val emulator = createEmulator(rows = 4)
        // Windows ConPTY opens every session by enabling win32-input-mode.
        emulator.writeInput(("\u001B[?9001h" + numberedLines(8)).encodeToByteArray())
        awaitSnapshot(emulator) { it.scrollback.size == 4 }

        emulator.resize(newRows = 2, newCols = 12)
        awaitSnapshot(emulator) { it.rows == 2 && it.scrollback.size == 6 }
        emulator.resize(newRows = 4, newCols = 12)
        val grown = awaitSnapshot(emulator) { it.rows == 4 && it.lines.any { line -> line.visibleText() == "l8" } }
        assertEquals(6, grown.scrollback.size)
        assertEquals(listOf("l7", "l8", "", ""), grown.lines.map { it.visibleText() })

        // ConPTY then repaints its viewport, whose top stayed where it was, from
        // home; the next output lands on the row below the old cursor.
        emulator.writeInput("\u001B[Hl7\u001B[K\r\nl8\u001B[K\r\n\u001B[K\r\n\u001B[K\u001B[3;1Hl9".encodeToByteArray())
        val repainted = awaitSnapshot(emulator) { snapshot ->
            snapshot.lines.any { it.visibleText() == "l9" }
        }
        val history = (repainted.scrollback + repainted.lines).map { it.visibleText() }.filter { it.isNotEmpty() }
        assertEquals((1..9).map { "l$it" }, history)
    }

    private fun createEmulator(rows: Int): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = rows,
        initialCols = 12,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = {},
    ) as TerminalEmulatorImpl

    private fun numberedLines(count: Int): String = (1..count).joinToString("\r\n") { "l$it" }

    // Blank cells are spaces once a row is rebuilt but NULs in placeholder rows.
    private fun TerminalLine.visibleText(): String = text.trimEnd { it == '\u0000' || it.isWhitespace() }

    private fun awaitSnapshot(
        emulator: TerminalEmulatorImpl,
        predicate: (TerminalSnapshot) -> Boolean,
    ): TerminalSnapshot {
        val deadline = SystemClock.uptimeMillis() + SNAPSHOT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val snapshot = emulator.snapshot.value
            if (predicate(snapshot)) return snapshot
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        val snapshot = emulator.snapshot.value
        assertTrue("Terminal snapshot did not settle before timeout", predicate(snapshot))
        return snapshot
    }

    private companion object {
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 16L
    }
}
