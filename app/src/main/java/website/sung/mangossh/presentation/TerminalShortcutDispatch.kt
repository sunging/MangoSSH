package website.sung.mangossh.presentation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.VTermKey
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

/** Session-local, observable implementation of termlib's transient modifier contract. */
@Stable
internal class TerminalModifierState : ModifierManager {
    var activeModifiers: Set<TerminalModifier> by mutableStateOf(emptySet())
        private set

    fun toggle(modifier: TerminalModifier) {
        activeModifiers = if (modifier in activeModifiers) {
            activeModifiers - modifier
        } else {
            activeModifiers + modifier
        }
    }

    fun isActive(modifier: TerminalModifier): Boolean = modifier in activeModifiers

    override fun isCtrlActive(): Boolean = isActive(TerminalModifier.CTRL)

    override fun isAltActive(): Boolean = isActive(TerminalModifier.ALT)

    override fun isShiftActive(): Boolean = isActive(TerminalModifier.SHIFT)

    override fun clearTransients() {
        activeModifiers = emptySet()
    }
}

/**
 * Dispatches one configured action without ever logging its label or payload.
 *
 * Fixed chord modifiers are unioned with the transient toolbar state. A text
 * macro consumes modifiers on its first logical key only; subsequent code
 * points are emitted normally.
 */
internal fun dispatchTerminalShortcut(
    action: TerminalShortcutAction,
    modifierState: TerminalModifierState,
    terminalEmulator: TerminalEmulator,
    onPaste: () -> Unit,
) = dispatchTerminalShortcut(
    action = action,
    modifierState = modifierState,
    dispatchKey = terminalEmulator::dispatchKey,
    dispatchCharacter = terminalEmulator::dispatchCharacter,
    onPaste = onPaste,
)

/** Function-based dispatch seam used to verify terminal output without a live JNI emulator. */
internal fun dispatchTerminalShortcut(
    action: TerminalShortcutAction,
    modifierState: TerminalModifierState,
    dispatchKey: (modifiers: Int, key: Int) -> Unit,
    dispatchCharacter: (modifiers: Int, codePoint: Int) -> Unit,
    onPaste: () -> Unit,
) {
    when (action) {
        TerminalShortcutAction.Paste -> {
            onPaste()
            modifierState.clearTransients()
        }

        is TerminalShortcutAction.Modifier -> modifierState.toggle(action.modifier)
        is TerminalShortcutAction.Text -> {
            dispatchText(dispatchKey, dispatchCharacter, action.value, modifierState.activeModifiers.modifierMask())
            modifierState.clearTransients()
        }

        is TerminalShortcutAction.SpecialKey -> {
            dispatchKey(
                modifierState.activeModifiers.modifierMask(),
                action.key.vtermKey(),
            )
            modifierState.clearTransients()
        }

        is TerminalShortcutAction.Chord -> {
            val modifiers = modifierState.activeModifiers + action.modifiers
            dispatchShortcutKey(dispatchKey, dispatchCharacter, action.key, modifiers.modifierMask())
            modifierState.clearTransients()
        }
    }
}

private fun dispatchText(
    dispatchKey: (Int, Int) -> Unit,
    dispatchCharacter: (Int, Int) -> Unit,
    text: String,
    initialModifiers: Int,
) {
    var index = 0
    var modifiers = initialModifiers
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint == '\n'.code || codePoint == '\r'.code) {
            dispatchKey(modifiers, VTermKey.ENTER)
            if (codePoint == '\r'.code && index + 1 < text.length && text[index + 1] == '\n') {
                index += 1
            }
        } else {
            dispatchCharacter(modifiers, shiftedCodePoint(codePoint, modifiers))
        }
        modifiers = 0
        index += Character.charCount(codePoint)
    }
}

private fun dispatchShortcutKey(
    dispatchKey: (Int, Int) -> Unit,
    dispatchCharacter: (Int, Int) -> Unit,
    key: TerminalShortcutKey,
    modifiers: Int,
) {
    when (key) {
        is TerminalShortcutKey.Character -> dispatchCharacter(
            modifiers,
            shiftedCodePoint(key.value.code, modifiers),
        )

        is TerminalShortcutKey.Special -> dispatchKey(modifiers, key.value.vtermKey())
    }
}

private fun Set<TerminalModifier>.modifierMask(): Int = fold(0) { mask, modifier -> mask or modifier.mask }

/** Applies the conventional US-ASCII shifted glyph for configured character buttons. */
private fun shiftedCodePoint(codePoint: Int, modifiers: Int): Int {
    if (modifiers and TerminalModifier.SHIFT.mask == 0 || codePoint !in 0x20..0x7E) return codePoint
    val character = codePoint.toChar()
    return when (character) {
        in 'a'..'z' -> character.uppercaseChar()
        '1' -> '!'
        '2' -> '@'
        '3' -> '#'
        '4' -> '$'
        '5' -> '%'
        '6' -> '^'
        '7' -> '&'
        '8' -> '*'
        '9' -> '('
        '0' -> ')'
        '-' -> '_'
        '=' -> '+'
        '[' -> '{'
        ']' -> '}'
        '\\' -> '|'
        ';' -> ':'
        '\'' -> '"'
        ',' -> '<'
        '.' -> '>'
        '/' -> '?'
        '`' -> '~'
        else -> character
    }.code
}

private fun TerminalSpecialKey.vtermKey(): Int = when (this) {
    TerminalSpecialKey.ESCAPE -> VTermKey.ESCAPE
    TerminalSpecialKey.TAB -> VTermKey.TAB
    TerminalSpecialKey.ENTER -> VTermKey.ENTER
    TerminalSpecialKey.BACKSPACE -> VTermKey.BACKSPACE
    TerminalSpecialKey.DELETE -> VTermKey.DEL
    TerminalSpecialKey.INSERT -> VTermKey.INS
    TerminalSpecialKey.UP -> VTermKey.UP
    TerminalSpecialKey.DOWN -> VTermKey.DOWN
    TerminalSpecialKey.LEFT -> VTermKey.LEFT
    TerminalSpecialKey.RIGHT -> VTermKey.RIGHT
    TerminalSpecialKey.HOME -> VTermKey.HOME
    TerminalSpecialKey.END -> VTermKey.END
    TerminalSpecialKey.PAGE_UP -> VTermKey.PAGEUP
    TerminalSpecialKey.PAGE_DOWN -> VTermKey.PAGEDOWN
    TerminalSpecialKey.F1 -> VTermKey.FUNCTION_1
    TerminalSpecialKey.F2 -> VTermKey.FUNCTION_2
    TerminalSpecialKey.F3 -> VTermKey.FUNCTION_3
    TerminalSpecialKey.F4 -> VTermKey.FUNCTION_4
    TerminalSpecialKey.F5 -> VTermKey.FUNCTION_5
    TerminalSpecialKey.F6 -> VTermKey.FUNCTION_6
    TerminalSpecialKey.F7 -> VTermKey.FUNCTION_7
    TerminalSpecialKey.F8 -> VTermKey.FUNCTION_8
    TerminalSpecialKey.F9 -> VTermKey.FUNCTION_9
    TerminalSpecialKey.F10 -> VTermKey.FUNCTION_10
    TerminalSpecialKey.F11 -> VTermKey.FUNCTION_11
    TerminalSpecialKey.F12 -> VTermKey.FUNCTION_12
}
