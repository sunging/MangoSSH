package website.sung.mangossh.presentation

import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

class TerminalShortcutDispatchTest {
    @Test
    fun modifierButtonsToggleAndAllSevenCombinationsApplyOnce() {
        val combinations = listOf(
            setOf(TerminalModifier.CTRL),
            setOf(TerminalModifier.ALT),
            setOf(TerminalModifier.SHIFT),
            setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
            setOf(TerminalModifier.CTRL, TerminalModifier.SHIFT),
            setOf(TerminalModifier.ALT, TerminalModifier.SHIFT),
            setOf(TerminalModifier.CTRL, TerminalModifier.ALT, TerminalModifier.SHIFT),
        )

        combinations.forEach { modifiers ->
            val state = TerminalModifierState()
            modifiers.forEach(state::toggle)
            val output = capture(TerminalShortcutAction.Text("a"), state)
            val expectedCodePoint = if (TerminalModifier.SHIFT in modifiers) 'A'.code else 'a'.code

            assertEquals(listOf(Output.Character(modifiers.sumOf(TerminalModifier::mask), expectedCodePoint)), output.events)
            assertTrue(state.activeModifiers.isEmpty())
        }

        val state = TerminalModifierState()
        state.toggle(TerminalModifier.CTRL)
        state.toggle(TerminalModifier.CTRL)
        assertFalse(state.isCtrlActive())
    }

    @Test
    fun fixedChordUnionsTransientModifiersAndSpecialKeysKeepTerminalSemantics() {
        val state = TerminalModifierState()
        state.toggle(TerminalModifier.SHIFT)
        val characterOutput = capture(
            TerminalShortcutAction.Chord(
                setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
                TerminalShortcutKey.Character('t'),
            ),
            state,
        )
        assertEquals(listOf(Output.Character(7, 'T'.code)), characterOutput.events)
        assertTrue(state.activeModifiers.isEmpty())

        val specialOutput = capture(
            TerminalShortcutAction.Chord(
                setOf(TerminalModifier.SHIFT),
                TerminalShortcutKey.Special(TerminalSpecialKey.TAB),
            ),
            TerminalModifierState(),
        )
        assertEquals(listOf(Output.Key(TerminalModifier.SHIFT.mask, VTermKey.TAB)), specialOutput.events)
    }

    @Test
    fun textConsumesModifiersOnFirstLogicalKeyAndPasteClearsWithoutChangingContent() {
        val state = TerminalModifierState()
        state.toggle(TerminalModifier.CTRL)
        state.toggle(TerminalModifier.ALT)
        val output = capture(TerminalShortcutAction.Text("ab\n"), state)

        assertEquals(
            listOf(
                Output.Character(6, 'a'.code),
                Output.Character(0, 'b'.code),
                Output.Key(0, VTermKey.ENTER),
            ),
            output.events,
        )

        state.toggle(TerminalModifier.CTRL)
        val pasteOutput = capture(TerminalShortcutAction.Paste, state)
        assertEquals(1, pasteOutput.pasteCount)
        assertTrue(pasteOutput.events.isEmpty())
        assertTrue(state.activeModifiers.isEmpty())
    }

    private fun capture(action: TerminalShortcutAction, state: TerminalModifierState): Captured {
        val events = mutableListOf<Output>()
        var pasteCount = 0
        dispatchTerminalShortcut(
            action = action,
            modifierState = state,
            dispatchKey = { modifiers, key -> events += Output.Key(modifiers, key) },
            dispatchCharacter = { modifiers, codePoint -> events += Output.Character(modifiers, codePoint) },
            onPaste = { pasteCount += 1 },
        )
        return Captured(events, pasteCount)
    }

    private data class Captured(val events: List<Output>, val pasteCount: Int)

    private sealed interface Output {
        data class Key(val modifiers: Int, val key: Int) : Output
        data class Character(val modifiers: Int, val codePoint: Int) : Output
    }
}
