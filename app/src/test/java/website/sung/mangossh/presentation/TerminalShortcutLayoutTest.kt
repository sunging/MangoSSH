package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem

class TerminalShortcutLayoutTest {
    @Test
    fun presetAndRelabeledPresetKeepKeyboardColumnsAndOrderedOverflow() {
        val preset = TerminalShortcutConfig.defaults()
        for (config in listOf(preset, preset.copy(items = preset.items.map { it.copy(labelOverride = "Key") }))) {
            val rows = config.displayRows().map { row -> row.map(TerminalShortcutItem::id) }
            assertEquals(
                listOf("default-paste", "default-modifier-ctrl", "default-modifier-alt", "default-up",
                    "default-modifier-shift", "default-page-up", "default-ctrl-c", "default-ctrl-d",
                    "default-ctrl-l", "default-ctrl-z"),
                rows[0],
            )
            assertEquals(
                listOf("default-escape", "default-tab", "default-left", "default-down", "default-right",
                    "default-page-down", "default-pipe", "default-tilde", "default-slash"),
                rows[1],
            )
        }
    }

    @Test
    fun StructuralEditsUseVisibleRowMajorOrderWithoutLosingAnyItems() {
        val items = TerminalShortcutConfig.defaults().items
        val edits = listOf(
            items.reversed(),
            items.mapIndexed { index, item -> if (index == 4) item.copy(visible = false) else item },
            items.filterIndexed { index, _ -> index != 4 },
            items.take(4) + items.last().copy(id = "inserted") + items.drop(4),
            items.mapIndexed { index, item -> if (index == 4) item.copy(action = items.last().action) else item },
        )
        edits.forEach { edited ->
            val visible = edited.filter(TerminalShortcutItem::visible)
            val rows = TerminalShortcutConfig(edited).displayRows()
            assertEquals(visible, rows.flatten())
            assertEquals((visible.size + 1) / 2, rows.first().size)
        }
    }

    @Test
    fun EmptySingleAndCustomOddEvenListsDoNotInsertPlaceholderItems() {
        val defaults = TerminalShortcutConfig.defaults()
        assertEquals(emptyList<List<TerminalShortcutItem>>(), defaults.copy(items = emptyList()).displayRows())
        assertEquals(listOf(defaults.items.take(1)), defaults.copy(items = defaults.items.take(1)).displayRows())
        assertEquals(listOf(defaults.items), defaults.copy(rowCount = 1).displayRows())
        for (count in 2..11) {
            val items = defaults.items.take(count)
            val rows = defaults.copy(items = items).displayRows()
            assertEquals(items, rows.flatten())
            assertEquals((count + 1) / 2, rows.first().size)
            assertEquals(count / 2, rows.last().size)
        }
    }
}
