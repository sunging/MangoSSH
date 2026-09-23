package org.connectbot.terminal

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Guards the key-lifecycle contract that keeps a terminal Esc from being rewritten by the
 * platform into a Back navigation: once [KeyboardHandler] consumes a key's ACTION_DOWN it must
 * also consume the matching ACTION_UP, and it must not swallow the up of a key it never
 * handled.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardHandlerInstrumentedTest {
    private val emitted = mutableListOf<Byte>()
    private lateinit var handler: KeyboardHandler

    @Before
    fun setUp() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = { data -> synchronized(emitted) { data.forEach { emitted += it } } },
        )
        handler = KeyboardHandler(emulator)
    }

    @Test
    fun escapeConsumesBothEdgesAndReachesTheTerminalOnce() {
        assertTrue("Esc down must be consumed", handler.onKeyEvent(keyDown(KeyEvent.KEYCODE_ESCAPE)))
        assertTrue(
            "Esc byte must reach the terminal",
            awaitBytes { it.contains(0x1B.toByte()) },
        )

        val afterDown = snapshotEmitted()
        assertTrue("Esc up must be consumed too", handler.onKeyEvent(keyUp(KeyEvent.KEYCODE_ESCAPE)))
        SystemClock.sleep(SETTLE_MS)
        assertEquals("Esc up must not emit anything", afterDown, snapshotEmitted())
    }

    @Test
    fun modifiedEscapeConsumesBothEdges() {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertTrue(handler.onKeyEvent(keyDown(KeyEvent.KEYCODE_ESCAPE, ctrl)))
        assertTrue(handler.onKeyEvent(keyUp(KeyEvent.KEYCODE_ESCAPE, ctrl)))
    }

    @Test
    fun unmappedKeyIsLeftUnhandledOnBothEdges() {
        assertFalse(handler.onKeyEvent(keyDown(KeyEvent.KEYCODE_BACK)))
        assertFalse(handler.onKeyEvent(keyUp(KeyEvent.KEYCODE_BACK)))
    }

    private fun keyDown(code: Int, metaState: Int = 0): ComposeKeyEvent {
        val now = SystemClock.uptimeMillis()
        return ComposeKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, metaState))
    }

    private fun keyUp(code: Int, metaState: Int = 0): ComposeKeyEvent {
        val now = SystemClock.uptimeMillis()
        return ComposeKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, metaState))
    }

    private fun snapshotEmitted(): List<Byte> = synchronized(emitted) { emitted.toList() }

    private fun awaitBytes(predicate: (List<Byte>) -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(snapshotEmitted())) return true
            SystemClock.sleep(POLL_MS)
        }
        return predicate(snapshotEmitted())
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val POLL_MS = 16L
        const val SETTLE_MS = 100L
    }
}
