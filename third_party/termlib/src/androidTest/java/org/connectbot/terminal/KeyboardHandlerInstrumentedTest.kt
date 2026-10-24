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
    private lateinit var emulator: TerminalEmulator
    private lateinit var handler: KeyboardHandler

    @Before
    fun setUp() {
        emulator = TerminalEmulatorFactory.create(
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

    /** Shells only understand xterm's legacy bytes, so that is what modified keys send by default. */
    @Test
    fun hardwareModifiedKeysSendLegacyBytesByDefault() {
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER, CTRL)
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER, SHIFT)
        assertKeyEmits("\u001b\r", KeyEvent.KEYCODE_ENTER, ALT)
        assertKeyEmits("\b", KeyEvent.KEYCODE_DEL, CTRL)
        assertKeyEmits("\t", KeyEvent.KEYCODE_TAB, CTRL)
        assertKeyEmits("\u001b[Z", KeyEvent.KEYCODE_TAB, SHIFT)
        assertKeyEmits("\u001b", KeyEvent.KEYCODE_ESCAPE, CTRL)
        assertKeyEmits("\u0001", KeyEvent.KEYCODE_A, CTRL or SHIFT)
        assertKeyEmits("\u0003", KeyEvent.KEYCODE_C, CTRL)
        assertKeyEmits("\u001b", KeyEvent.KEYCODE_LEFT_BRACKET, CTRL)
        assertKeyEmits("\u0000", KeyEvent.KEYCODE_SPACE, CTRL)
        assertKeyEmits("\u001f", KeyEvent.KEYCODE_SLASH, CTRL)
        assertKeyEmits("\u001bx", KeyEvent.KEYCODE_X, ALT)
    }

    /** The shortcut bar's sticky Ctrl combined with a soft-keyboard Enter, by either IME path. */
    @Test
    fun stickyCtrlWithSoftKeyboardEnterSendsCarriageReturn() {
        val sticky = StickyModifiers(ctrl = true)
        handler.modifierManager = sticky
        assertEmits("\r") { handler.onKeyEvent(keyDown(KeyEvent.KEYCODE_ENTER)) }
        assertFalse("Sticky Ctrl is consumed by one key", sticky.ctrl)

        sticky.ctrl = true
        assertEmits("\r") { handler.onTextInput("\n".toByteArray()) }
        assertFalse(sticky.ctrl)
    }

    @Test
    fun kittyKeyboardProtocolDisambiguatesOnlyAfterTheApplicationEnablesIt() {
        assertEmits("\u001b[?0u") { emulator.writeInput("\u001b[?u".toByteArray()) }

        emulator.writeInput("\u001b[>1u".toByteArray())
        assertEmits("\u001b[?1u") { emulator.writeInput("\u001b[?u".toByteArray()) }
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER)
        assertKeyEmits("\u001b[13;5u", KeyEvent.KEYCODE_ENTER, CTRL)
        assertKeyEmits("\u001b[13;2u", KeyEvent.KEYCODE_ENTER, SHIFT)
        assertKeyEmits("\u001b[97;5u", KeyEvent.KEYCODE_A, CTRL)
        assertKeyEmits("\u001b[27u", KeyEvent.KEYCODE_ESCAPE)

        emulator.writeInput("\u001b[<u".toByteArray())
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER, CTRL)
    }

    @Test
    fun modifyOtherKeysEncodesModifiedEnterAfterTheApplicationEnablesIt() {
        emulator.writeInput("\u001b[>4;2m".toByteArray())
        assertKeyEmits("\u001b[27;5;13~", KeyEvent.KEYCODE_ENTER, CTRL)

        emulator.writeInput("\u001b[>4m".toByteArray())
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER, CTRL)
    }

    /** Claude Code and Codex bind Ctrl+J, not Ctrl+Enter, to newline, so the option sends LF. */
    @Test
    fun ctrlEnterSendsLineFeedWhenEnabledWhateverTheApplicationNegotiated() {
        handler.ctrlEnterSendsLineFeed = true
        assertKeyEmits("\n", KeyEvent.KEYCODE_ENTER, CTRL)
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER)
        assertKeyEmits("\r", KeyEvent.KEYCODE_ENTER, CTRL or SHIFT)
        assertKeyEmits("\u001b\r", KeyEvent.KEYCODE_ENTER, CTRL or ALT)

        emulator.writeInput("\u001b[>1u".toByteArray())
        assertKeyEmits("\n", KeyEvent.KEYCODE_ENTER, CTRL)
        assertKeyEmits("\u001b[13;2u", KeyEvent.KEYCODE_ENTER, SHIFT)
        emulator.writeInput("\u001b[<u".toByteArray())

        emulator.writeInput("\u001b[>4;2m".toByteArray())
        assertKeyEmits("\n", KeyEvent.KEYCODE_ENTER, CTRL)
    }

    @Test
    fun stickyCtrlWithSoftKeyboardEnterSendsLineFeedWhenEnabled() {
        handler.ctrlEnterSendsLineFeed = true
        val sticky = StickyModifiers(ctrl = true)
        handler.modifierManager = sticky
        assertEmits("\n") { handler.onKeyEvent(keyDown(KeyEvent.KEYCODE_ENTER)) }

        sticky.ctrl = true
        assertEmits("\n") { handler.onTextInput("\n".toByteArray()) }

        sticky.ctrl = true
        assertEmits("\n") { handler.onCommittedText("\r\n") }
        assertFalse(sticky.ctrl)
    }

    private fun assertKeyEmits(expected: String, code: Int, metaState: Int = 0) {
        assertEmits(expected) {
            assertTrue("Key $code down must be handled", handler.onKeyEvent(keyDown(code, metaState)))
            handler.onKeyEvent(keyUp(code, metaState))
        }
    }

    /** Runs [action] and asserts the terminal sends exactly [expected] in response. */
    private fun assertEmits(expected: String, action: () -> Unit) {
        val expectedBytes = expected.toByteArray().toList()
        synchronized(emitted) { emitted.clear() }
        action()
        awaitBytes { it.size >= expectedBytes.size }
        SystemClock.sleep(SETTLE_MS)
        assertEquals(expected.printable(), String(snapshotEmitted().toByteArray()).printable())
    }

    private fun String.printable(): String = map { ch ->
        if (ch.code < 0x20 || ch.code == 0x7f) "\\x%02x".format(ch.code) else ch.toString()
    }.joinToString("")

    private class StickyModifiers(var ctrl: Boolean = false) : ModifierManager {
        override fun isCtrlActive(): Boolean = ctrl
        override fun isAltActive(): Boolean = false
        override fun isShiftActive(): Boolean = false
        override fun clearTransients() {
            ctrl = false
        }
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
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        const val ALT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    }
}
