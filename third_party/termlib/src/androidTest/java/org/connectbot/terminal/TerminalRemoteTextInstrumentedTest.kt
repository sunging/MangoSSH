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
    // The decoded title is not asserted: this termlib version maps libvterm's
    // property numbers wrongly, so titles never reach the snapshot. Reaching
    // the following output proves the native title path did not abort.
    @Test
    fun malformedUtf8TitleDoesNotStopOutput() {
        val emulator = createEmulator()
        emulator.writeInput(byteArrayOf(0x1B, ']'.code.toByte(), '2'.code.toByte(), ';'.code.toByte(), 'a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0x07))
        emulator.writeInput("after".encodeToByteArray())

        awaitSnapshot(emulator) { snapshot -> snapshot.lines.any { it.text.startsWith("after") } }
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
        emulator.writeInput("dropped".encodeToByteArray())
        emulator.resize(newRows = 6, newCols = 20)
        emulator.dispatchCharacter(0, 'x'.code)
        assertEquals(-1, emulator.setDefaultColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))

        SystemClock.sleep(SETTLE_MS)
        val snapshot = emulator.snapshot.value
        assertEquals(4, snapshot.rows)
        assertTrue(snapshot.lines.any { it.text.startsWith("kept") })
        assertTrue(snapshot.lines.none { it.text.contains("dropped") })
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
