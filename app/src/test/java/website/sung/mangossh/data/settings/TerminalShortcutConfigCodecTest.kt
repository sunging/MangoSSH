package website.sung.mangossh.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

class TerminalShortcutConfigCodecTest {
    @Test
    fun rowCountRoundTripsAndAbsentOrMalformedValuesDefaultToTwoRows() {
        for (rowCount in 1..2) {
            val config = TerminalShortcutConfig.defaults().copy(rowCount = rowCount)
            assertEquals(config, TerminalShortcutConfigCodec.decode(TerminalShortcutConfigCodec.encode(config)))
        }
        for (raw in listOf("null", "0", "3", "1.5", "\"1\"", "true", "{}")) {
            assertEquals(2, TerminalShortcutConfigCodec.decode("""{"schemaVersion":1,"rowCount":$raw,"items":[]}""")?.rowCount)
        }
    }

    @Test
    fun onlyExactLegacyDefaultsWithoutRowCountMigrate() {
        val current = TerminalShortcutConfig.defaults().items.associateBy(TerminalShortcutItem::id)
        val legacy = listOf(
            "default-paste", "default-modifier-ctrl", "default-modifier-alt", "default-modifier-shift",
            "default-escape", "default-tab", "default-ctrl-c", "default-ctrl-d", "default-ctrl-l",
            "default-ctrl-z", "default-up", "default-down", "default-left", "default-right",
            "default-pipe", "default-tilde", "default-slash",
        ).map(current::getValue)
        fun oldPayload(items: List<TerminalShortcutItem>): JSONObject =
            JSONObject(TerminalShortcutConfigCodec.encode(TerminalShortcutConfig(items))).apply { remove("rowCount") }

        assertEquals(TerminalShortcutConfig.defaults(), TerminalShortcutConfigCodec.decode(oldPayload(legacy).toString()))
        for (custom in listOf(
            legacy.reversed(), legacy.dropLast(1), emptyList(),
            legacy.mapIndexed { index, item -> if (index == 0) item.copy(labelOverride = "Clipboard") else item },
            legacy.mapIndexed { index, item -> if (index == 0) item.copy(visible = false) else item },
        )) {
            assertEquals(TerminalShortcutConfig(custom), TerminalShortcutConfigCodec.decode(oldPayload(custom).toString()))
        }
        assertEquals(
            TerminalShortcutConfig(legacy, 1),
            TerminalShortcutConfigCodec.decode(oldPayload(legacy).put("rowCount", 1).toString()),
        )
        val damaged = oldPayload(legacy)
        damaged.getJSONArray("items").put(JSONObject().put("id", "damaged"))
        assertEquals(TerminalShortcutConfig(legacy), TerminalShortcutConfigCodec.decode(damaged.toString()))
    }

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
