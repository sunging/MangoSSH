package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShortcutTest {
    @Test
    fun defaultsContainEditableUtilitiesAndStructuredControlChordsInOrder() {
        val actions = TerminalShortcutConfig.defaults().items.map(TerminalShortcutItem::action)

        assertEquals(TerminalShortcutAction.Paste, actions[0])
        assertEquals(TerminalShortcutAction.Modifier(TerminalModifier.CTRL), actions[1])
        assertEquals(TerminalShortcutAction.Modifier(TerminalModifier.ALT), actions[2])
        assertEquals(TerminalShortcutAction.Modifier(TerminalModifier.SHIFT), actions[3])
        assertEquals(TerminalShortcutAction.SpecialKey(TerminalSpecialKey.ESCAPE), actions[4])
        assertEquals(TerminalShortcutAction.SpecialKey(TerminalSpecialKey.TAB), actions[5])
        assertEquals(
            TerminalShortcutAction.Chord(
                setOf(TerminalModifier.CTRL),
                TerminalShortcutKey.Character('C'),
            ),
            actions[6],
        )
        assertEquals(17, actions.size)
        assertTrue(TerminalShortcutConfig.defaults().isValid())
    }

    @Test
    fun normalizationDropsDuplicateUtilitiesIdsAndInvalidItemsButKeepsEmptyConfig() {
        val original = TerminalShortcutConfig.defaults().items.first()
        val normalized = TerminalShortcutConfig(
            listOf(
                original,
                original.copy(id = "duplicate-paste"),
                TerminalShortcutItem("duplicate-id", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.HOME)),
                TerminalShortcutItem("duplicate-id", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.END)),
                TerminalShortcutItem("invalid-text", TerminalShortcutAction.Text("status")),
                TerminalShortcutItem(
                    "valid-text",
                    TerminalShortcutAction.Text("status"),
                    labelOverride = "Status",
                ),
            ),
        ).normalized()

        assertEquals(listOf("default-paste", "duplicate-id", "valid-text"), normalized.items.map { it.id })
        assertTrue(TerminalShortcutConfig(emptyList()).isValid())
    }

    @Test
    fun chordsRequireModifiersAndPrintableAsciiCharacters() {
        val noModifiers = TerminalShortcutConfig(
            listOf(
                TerminalShortcutItem(
                    "no-modifiers",
                    TerminalShortcutAction.Chord(emptySet(), TerminalShortcutKey.Character('T')),
                ),
            ),
        )
        val nonAscii = TerminalShortcutConfig(
            listOf(
                TerminalShortcutItem(
                    "non-ascii",
                    TerminalShortcutAction.Chord(
                        setOf(TerminalModifier.ALT),
                        TerminalShortcutKey.Character('芒'),
                    ),
                ),
            ),
        )
        val valid = TerminalShortcutConfig(
            listOf(
                TerminalShortcutItem(
                    "ctrl-alt-t",
                    TerminalShortcutAction.Chord(
                        setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
                        TerminalShortcutKey.Character('T'),
                    ),
                ),
            ),
        )

        assertFalse(noModifiers.isValid())
        assertFalse(nonAscii.isValid())
        assertTrue(valid.isValid())
    }
}
