package website.sung.mangossh.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

class TerminalShortcutConfigCodecTest {
    @Test
    fun roundTripPreservesOrderVisibilityLabelsAndEveryActionShape() {
        val config = TerminalShortcutConfig(
            listOf(
                TerminalShortcutItem("paste", TerminalShortcutAction.Paste, visible = false),
                TerminalShortcutItem("alt", TerminalShortcutAction.Modifier(TerminalModifier.ALT), "Meta"),
                TerminalShortcutItem("text", TerminalShortcutAction.Text("git status\n"), "Status"),
                TerminalShortcutItem("home", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.HOME)),
                TerminalShortcutItem(
                    "ctrl-alt-t",
                    TerminalShortcutAction.Chord(
                        setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
                        TerminalShortcutKey.Character('T'),
                    ),
                ),
                TerminalShortcutItem(
                    "shift-tab",
                    TerminalShortcutAction.Chord(
                        setOf(TerminalModifier.SHIFT),
                        TerminalShortcutKey.Special(TerminalSpecialKey.TAB),
                    ),
                ),
            ),
        )

        assertEquals(config, TerminalShortcutConfigCodec.decode(TerminalShortcutConfigCodec.encode(config)))
    }

    @Test
    fun validEmptyListSurvivesAndUnknownItemsAreDropped() {
        assertEquals(
            TerminalShortcutConfig(emptyList()),
            TerminalShortcutConfigCodec.decode("""{"schemaVersion":1,"items":[]}"""),
        )
        val decoded = TerminalShortcutConfigCodec.decode(
            """{"schemaVersion":1,"items":[{"id":"future","visible":true,"label":null,"action":{"type":"future"}},{"id":"tab","visible":true,"label":null,"action":{"type":"special","key":"tab"}}]}""",
        )

        assertEquals(listOf("tab"), decoded?.items?.map { it.id })
        assertTrue(decoded?.isValid() == true)
    }

    @Test
    fun corruptOrUnsupportedPayloadReturnsNull() {
        assertNull(TerminalShortcutConfigCodec.decode("not-json"))
        assertNull(TerminalShortcutConfigCodec.decode("""{"schemaVersion":2,"items":[]}"""))
    }
}
