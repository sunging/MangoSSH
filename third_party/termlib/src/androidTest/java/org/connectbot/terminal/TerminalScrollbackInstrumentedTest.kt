package org.connectbot.terminal

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalScrollbackInstrumentedTest {
    @Test
    fun scrollbackIsCappedAtTheConfiguredLimitDroppingTheOldestLineFirst() {
        val maxScrollbackLines = 5
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 3,
            initialCols = 20,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = {},
            maxScrollbackLines = maxScrollbackLines,
        ) as TerminalEmulatorImpl

        val totalLines = maxScrollbackLines + 20
        val output = buildString {
            for (line in 1..totalLines) append("line$line\r\n")
        }
        emulator.writeInput(output.encodeToByteArray())

        val settled = awaitSnapshot(emulator) {
            it.lines.any { line -> line.text.trimEnd() == "line$totalLines" }
        }

        assertEquals(maxScrollbackLines, settled.scrollback.size)
        assertTrue(
            "Expected the oldest lines to be dropped first",
            settled.scrollback.none { it.text.trimEnd() == "line1" },
        )
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
