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
