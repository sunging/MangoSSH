package website.sung.mangossh.presentation

import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.RightAltMode
import website.sung.mangossh.domain.TerminalDelKeyMode
import website.sung.mangossh.domain.TerminalRightAltMode

/** Maps the persisted preference enum to termlib's own type, kept out of `domain` so it stays termlib-free. */
internal fun TerminalRightAltMode.toTermlib(): RightAltMode = when (this) {
    TerminalRightAltMode.CHARACTER_MODIFIER -> RightAltMode.CharacterModifier
    TerminalRightAltMode.META -> RightAltMode.Meta
}

/** Maps the persisted preference enum to termlib's own type, kept out of `domain` so it stays termlib-free. */
internal fun TerminalDelKeyMode.toTermlib(): DelKeyMode = when (this) {
    TerminalDelKeyMode.DELETE -> DelKeyMode.Delete
    TerminalDelKeyMode.BACKSPACE -> DelKeyMode.Backspace
}
