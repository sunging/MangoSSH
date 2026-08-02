package website.sung.mangossh.domain

import androidx.compose.runtime.Immutable

/** One of the transient terminal modifiers supported by libvterm. */
enum class TerminalModifier(val preferenceValue: String, val mask: Int) {
    SHIFT("shift", 1),
    ALT("alt", 2),
    CTRL("ctrl", 4),
    ;

    companion object {
        /** Resolves only identifiers written by this application. */
        fun fromPreference(value: String?): TerminalModifier? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/** Structured terminal keys whose emitted sequences depend on the active terminal mode. */
enum class TerminalSpecialKey(val preferenceValue: String) {
    ESCAPE("escape"),
    TAB("tab"),
    ENTER("enter"),
    BACKSPACE("backspace"),
    DELETE("delete"),
    INSERT("insert"),
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    HOME("home"),
    END("end"),
    PAGE_UP("page_up"),
    PAGE_DOWN("page_down"),
    F1("f1"),
    F2("f2"),
    F3("f3"),
    F4("f4"),
    F5("f5"),
    F6("f6"),
    F7("f7"),
    F8("f8"),
    F9("f9"),
    F10("f10"),
    F11("f11"),
    F12("f12"),
    ;

    companion object {
        /** Resolves only identifiers written by this application. */
        fun fromPreference(value: String?): TerminalSpecialKey? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/** The single character or special key at the end of a configured chord. */
@Immutable
sealed interface TerminalShortcutKey {
    /** A printable ASCII key, kept layout-independent in persisted settings. */
    @Immutable
    data class Character(val value: Char) : TerminalShortcutKey

    /** A terminal-aware non-character key. */
    @Immutable
    data class Special(val value: TerminalSpecialKey) : TerminalShortcutKey
}

/** Action performed when a visible floating shortcut is pressed. */
@Immutable
sealed interface TerminalShortcutAction {
    /** Paste the current Android clipboard without applying modifiers. */
    data object Paste : TerminalShortcutAction

    /** Toggle one transient modifier without emitting terminal input. */
    @Immutable
    data class Modifier(val modifier: TerminalModifier) : TerminalShortcutAction

    /** Send the supplied text exactly as entered by the user. */
    @Immutable
    data class Text(val value: String) : TerminalShortcutAction

    /** Dispatch one terminal-aware key. */
    @Immutable
    data class SpecialKey(val key: TerminalSpecialKey) : TerminalShortcutAction

    /** Dispatch one key with a fixed, non-empty set of modifiers. */
    @Immutable
    data class Chord(
        val modifiers: Set<TerminalModifier>,
        val key: TerminalShortcutKey,
    ) : TerminalShortcutAction
}

/** One ordered entry in the global floating terminal shortcut bar. */
@Immutable
data class TerminalShortcutItem(
    val id: String,
    val action: TerminalShortcutAction,
    val labelOverride: String? = null,
    val visible: Boolean = true,
)

/**
 * Complete device-local shortcut configuration.
 *
 * Values are deliberately excluded from the encrypted profile backup. Text
 * actions are intended for non-secret convenience input and must never be
 * logged by consumers.
 */
@Immutable
data class TerminalShortcutConfig(
    val items: List<TerminalShortcutItem> = defaultItems(),
) {
    /** Drops damaged, duplicate, or over-limit entries while preserving valid ordering. */
    fun normalized(): TerminalShortcutConfig {
        val acceptedIds = mutableSetOf<String>()
        val acceptedUtilities = mutableSetOf<String>()
        val accepted = buildList {
            items.forEach { item ->
                if (size >= MAX_ITEMS || !item.isValid() || !acceptedIds.add(item.id)) return@forEach
                val utility = item.action.utilityIdentity()
                if (utility != null && !acceptedUtilities.add(utility)) return@forEach
                add(item)
            }
        }
        return TerminalShortcutConfig(accepted)
    }

    /** True when no normalization would alter the configuration. */
    fun isValid(): Boolean = items.size <= MAX_ITEMS && normalized().items == items

    companion object {
        const val MAX_ITEMS = 32
        const val MAX_LABEL_CODE_POINTS = 16
        const val MAX_TEXT_CODE_POINTS = 256
        const val MAX_ID_CODE_POINTS = 80

        /** Returns a fresh copy of the bundled default toolbar. */
        fun defaults(): TerminalShortcutConfig = TerminalShortcutConfig(defaultItems())

        private fun defaultItems(): List<TerminalShortcutItem> = listOf(
            TerminalShortcutItem("default-paste", TerminalShortcutAction.Paste),
            TerminalShortcutItem("default-modifier-ctrl", TerminalShortcutAction.Modifier(TerminalModifier.CTRL)),
            TerminalShortcutItem("default-modifier-alt", TerminalShortcutAction.Modifier(TerminalModifier.ALT)),
            TerminalShortcutItem("default-modifier-shift", TerminalShortcutAction.Modifier(TerminalModifier.SHIFT)),
            TerminalShortcutItem("default-escape", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.ESCAPE)),
            TerminalShortcutItem("default-tab", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.TAB)),
            defaultCtrlChord('C'),
            defaultCtrlChord('D'),
            defaultCtrlChord('L'),
            defaultCtrlChord('Z'),
            TerminalShortcutItem("default-up", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.UP)),
            TerminalShortcutItem("default-down", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.DOWN)),
            TerminalShortcutItem("default-left", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.LEFT)),
            TerminalShortcutItem("default-right", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.RIGHT)),
            TerminalShortcutItem("default-pipe", TerminalShortcutAction.Text("|"), labelOverride = "|"),
            TerminalShortcutItem("default-tilde", TerminalShortcutAction.Text("~"), labelOverride = "~"),
            TerminalShortcutItem("default-slash", TerminalShortcutAction.Text("/"), labelOverride = "/"),
        )

        private fun defaultCtrlChord(character: Char): TerminalShortcutItem = TerminalShortcutItem(
            id = "default-ctrl-${character.lowercaseChar()}",
            action = TerminalShortcutAction.Chord(
                modifiers = setOf(TerminalModifier.CTRL),
                key = TerminalShortcutKey.Character(character),
            ),
        )
    }
}

private fun TerminalShortcutItem.isValid(): Boolean {
    if (id.isBlank() || id.codePointCount() > TerminalShortcutConfig.MAX_ID_CODE_POINTS) return false
    if (labelOverride != null && (
            labelOverride.isBlank() ||
                labelOverride.codePointCount() > TerminalShortcutConfig.MAX_LABEL_CODE_POINTS
        )
    ) {
        return false
    }
    return when (val value = action) {
        TerminalShortcutAction.Paste -> true
        is TerminalShortcutAction.Modifier -> true
        is TerminalShortcutAction.SpecialKey -> true
        is TerminalShortcutAction.Text ->
            labelOverride != null &&
                value.value.isNotEmpty() &&
                value.value.codePointCount() <= TerminalShortcutConfig.MAX_TEXT_CODE_POINTS

        is TerminalShortcutAction.Chord ->
            value.modifiers.isNotEmpty() && value.key.isValid()
    }
}

private fun TerminalShortcutKey.isValid(): Boolean = when (this) {
    is TerminalShortcutKey.Character -> value.code in PRINTABLE_ASCII_RANGE
    is TerminalShortcutKey.Special -> true
}

private fun TerminalShortcutAction.utilityIdentity(): String? = when (this) {
    TerminalShortcutAction.Paste -> "paste"
    is TerminalShortcutAction.Modifier -> "modifier:${modifier.preferenceValue}"
    else -> null
}

private fun String.codePointCount(): Int = codePointCount(0, length)

private val PRINTABLE_ASCII_RANGE = 0x20..0x7E
