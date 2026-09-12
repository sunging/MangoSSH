package website.sung.mangossh.presentation

import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem

/**
 * Keeps the intact preset's first twelve keys in keyboard columns, including after relabeling.
 * Structural edits fall back to row-major ordering; hidden entries cannot impersonate the preset.
 */
internal fun TerminalShortcutConfig.displayRows(): List<List<TerminalShortcutItem>> {
    val visible = items.filter(TerminalShortcutItem::visible)
    if (visible.isEmpty()) return emptyList()
    if (rowCount == 1 || visible.size == 1) return listOf(visible)
    val preset = TerminalShortcutConfig.defaults().items.take(12)
    val hasPresetPrefix = items.size >= 12 && items.take(12).zip(preset).all { (item, expected) ->
        item.visible && item.id == expected.id && item.action == expected.action
    }
    if (hasPresetPrefix) {
        val overflow = visible.drop(12)
        val split = (overflow.size + 1) / 2
        return listOf(visible.take(6) + overflow.take(split), visible.subList(6, 12) + overflow.drop(split))
    }
    val split = (visible.size + 1) / 2
    return listOf(visible.take(split), visible.drop(split))
}
