package website.sung.mangossh.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

/** Resolves built-in labels while leaving every user override untouched. */
@Composable
internal fun TerminalShortcutItem.displayLabel(): String =
    labelOverride ?: action.defaultLabel()

@Composable
internal fun TerminalShortcutAction.defaultLabel(): String = when (this) {
    TerminalShortcutAction.Paste -> stringResource(R.string.terminal_shortcut_paste)
    is TerminalShortcutAction.Modifier -> modifier.label
    is TerminalShortcutAction.Text -> value
    is TerminalShortcutAction.SpecialKey -> key.label
    is TerminalShortcutAction.Chord -> buildString {
        append(modifiers.sortedBy(TerminalModifier::mask).joinToString("+") { it.label })
        append('+')
        append(key.label)
    }
}

internal val TerminalModifier.label: String
    get() = when (this) {
        TerminalModifier.CTRL -> "Ctrl"
        TerminalModifier.ALT -> "Alt"
        TerminalModifier.SHIFT -> "Shift"
    }

internal val TerminalShortcutKey.label: String
    get() = when (this) {
        is TerminalShortcutKey.Character -> value.toString()
        is TerminalShortcutKey.Special -> value.label
    }

internal val TerminalSpecialKey.label: String
    get() = when (this) {
        TerminalSpecialKey.ESCAPE -> "ESC"
        TerminalSpecialKey.TAB -> "TAB"
        TerminalSpecialKey.ENTER -> "ENTER"
        TerminalSpecialKey.BACKSPACE -> "BACKSPACE"
        TerminalSpecialKey.DELETE -> "DEL"
        TerminalSpecialKey.INSERT -> "INS"
        TerminalSpecialKey.UP -> "↑"
        TerminalSpecialKey.DOWN -> "↓"
        TerminalSpecialKey.LEFT -> "←"
        TerminalSpecialKey.RIGHT -> "→"
        TerminalSpecialKey.HOME -> "HOME"
        TerminalSpecialKey.END -> "END"
        TerminalSpecialKey.PAGE_UP -> "PGUP"
        TerminalSpecialKey.PAGE_DOWN -> "PGDN"
        TerminalSpecialKey.F1 -> "F1"
        TerminalSpecialKey.F2 -> "F2"
        TerminalSpecialKey.F3 -> "F3"
        TerminalSpecialKey.F4 -> "F4"
        TerminalSpecialKey.F5 -> "F5"
        TerminalSpecialKey.F6 -> "F6"
        TerminalSpecialKey.F7 -> "F7"
        TerminalSpecialKey.F8 -> "F8"
        TerminalSpecialKey.F9 -> "F9"
        TerminalSpecialKey.F10 -> "F10"
        TerminalSpecialKey.F11 -> "F11"
        TerminalSpecialKey.F12 -> "F12"
    }
