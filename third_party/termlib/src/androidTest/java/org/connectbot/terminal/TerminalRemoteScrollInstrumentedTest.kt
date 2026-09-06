package org.connectbot.terminal

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the terminal-property signals the UI relies on to forward a swipe to a
 * full-screen remote program (tmux, vim, less) instead of panning local
 * scrollback, plus the wheel-report encoding used when the program tracks the
 * mouse.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRemoteScrollInstrumentedTest {
    @Test
    fun enteringAndLeavingTheAlternateScreenIsReflectedInTheSnapshot() {
        val emulator = newEmulator()

        emulator.writeInput(csi("?1049h"))
        awaitSnapshot(emulator) { it.isAltScreen }

        emulator.writeInput(csi("?1049l"))
        awaitSnapshot(emulator) { !it.isAltScreen }
    }

    @Test
    fun enablingMouseTrackingIsReflectedInTheSnapshot() {
        val emulator = newEmulator()
        assertFalse(emulator.snapshot.value.mouseTrackingActive)

        // Button-event tracking plus SGR extended coordinates, the mode tmux and
        // vim turn on. libvterm reports the tracking mode via VTERM_PROP_MOUSE
        // regardless of the coordinate encoding.
        emulator.writeInput(csi("?1000h") + csi("?1006h"))
        awaitSnapshot(emulator) { it.mouseTrackingActive }

        emulator.writeInput(csi("?1000l"))
        awaitSnapshot(emulator) { !it.mouseTrackingActive }
    }

    @Test
    fun wheelReportUsesX10EncodingWithOneBasedCoordinates() {
        val up = MouseReport.wheel(scrollUp = true, row = 4, col = 9)
        assertArrayEquals(
            byteArrayOf(ESC, '['.code.toByte(), 'M'.code.toByte(), (32 + 64).toByte(), (32 + 10).toByte(), (32 + 5).toByte()),
            up,
        )

        val down = MouseReport.wheel(scrollUp = false, row = 0, col = 0)
        assertArrayEquals(
            byteArrayOf(ESC, '['.code.toByte(), 'M'.code.toByte(), (32 + 65).toByte(), (32 + 1).toByte(), (32 + 1).toByte()),
            down,
        )
    }

    private fun newEmulator(): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = {},
    ) as TerminalEmulatorImpl

    /** Encodes `ESC [ <params>` (a CSI sequence) without embedding a control char in source. */
    private fun csi(params: String): ByteArray =
        byteArrayOf(ESC, '['.code.toByte()) + params.encodeToByteArray()

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
        const val ESC: Byte = 0x1B
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 16L
    }
}
