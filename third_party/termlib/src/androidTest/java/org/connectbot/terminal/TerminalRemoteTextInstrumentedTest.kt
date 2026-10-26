package org.connectbot.terminal

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote bytes reach Java through JNI; invalid UTF-8 must not abort the
 * process (ART's NewStringUTF does), and a closed emulator must ignore input.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRemoteTextInstrumentedTest {
    @Test
    fun malformedUtf8TitleBecomesReplacementCharacter() {
        val emulator = createEmulator()
        emulator.writeInput(byteArrayOf(0x1B, ']'.code.toByte(), '2'.code.toByte(), ';'.code.toByte(), 'a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0x07))

        val snapshot = awaitSnapshot(emulator) { it.terminalTitle.isNotEmpty() }
        assertEquals("a�b", snapshot.terminalTitle)
    }

    @Test
    fun malformedUtf8OscPayloadDoesNotStopOutput() {
        val emulator = createEmulator()
        emulator.writeInput(byteArrayOf(0x1B, ']'.code.toByte(), '1'.code.toByte(), '3'.code.toByte(), '3'.code.toByte(), '7'.code.toByte(), ';'.code.toByte(), 0xC3.toByte(), 0x28, 0x80.toByte(), 0x07))
        emulator.writeInput("after".encodeToByteArray())

        awaitSnapshot(emulator) { snapshot -> snapshot.lines.any { it.text.startsWith("after") } }
    }

    @Test
    fun closedEmulatorIgnoresLaterCalls() {
        val emulator = createEmulator()
        emulator.writeInput("kept".encodeToByteArray())
        awaitSnapshot(emulator) { snapshot -> snapshot.lines.any { it.text.startsWith("kept") } }

        emulator.close()
        emulator.close()
        // close() releases the screen, publishing one empty snapshot; later calls change nothing.
        val closed = emulator.snapshot.value
        emulator.writeInput("dropped".encodeToByteArray())
        emulator.resize(newRows = 6, newCols = 20)
        emulator.dispatchCharacter(0, 'x'.code)
        assertEquals(-1, emulator.setDefaultColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))

        SystemClock.sleep(SETTLE_MS)
        val snapshot = emulator.snapshot.value
        assertEquals(closed.sequenceNumber, snapshot.sequenceNumber)
        assertEquals(0, snapshot.rows)
        assertTrue(snapshot.lines.isEmpty())
    }

    private fun createEmulator(): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = 4,
        initialCols = 12,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = {},
    ) as TerminalEmulatorImpl

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
        const val SETTLE_MS = 200L
    }
}
