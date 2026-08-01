package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalThemeId

/**
 * Stores non-secret, device-local terminal rendering preferences.
 *
 * Profiles and backups intentionally exclude this state because it describes
 * the local display rather than a connection's behavior or credentials.
 */
class TerminalAppearanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _appearance = MutableStateFlow(readAppearance())

    /** Observable current appearance for presentation and session runtime consumers. */
    val appearance: StateFlow<TerminalAppearance> = _appearance.asStateFlow()

    /** Returns a normalized snapshot that session creation can apply before remote output arrives. */
    fun current(): TerminalAppearance = _appearance.value

    /** Persists one of the bundled font identifiers. */
    fun setFont(font: TerminalFont) = update { it.copy(font = font) }

    /** Persists a bounded baseline size in scale-independent pixels. */
    fun setFontSize(fontSizeSp: Int) {
        require(fontSizeSp in TerminalAppearance.MIN_FONT_SIZE_SP..TerminalAppearance.MAX_FONT_SIZE_SP) {
            "Terminal font size is outside the supported range."
        }
        update { it.copy(fontSizeSp = fontSizeSp) }
    }

    /** Switches to a bundled palette and discards any prior custom overrides. */
    fun setTheme(theme: TerminalThemeId) {
        require(theme != TerminalThemeId.CUSTOM) { "Use setCustomColors for custom themes." }
        update { it.copy(theme = theme, customColors = null) }
    }

    /** Saves only validated custom foreground, background, and cursor overrides. */
    fun setCustomColors(colors: TerminalCustomColors) {
        require(TerminalAppearance.isValidCustomColors(colors)) { "Terminal custom colors are invalid." }
        update { it.copy(theme = TerminalThemeId.CUSTOM, customColors = colors) }
    }

    /** Restores the non-secret terminal appearance defaults. */
    fun reset() = update { TerminalAppearance() }

    private fun update(transform: (TerminalAppearance) -> TerminalAppearance) {
        val next = transform(_appearance.value).normalized()
        persist(next)
        _appearance.value = next
    }

    private fun readAppearance(): TerminalAppearance {
        val font = TerminalFont.fromPreference(readString(KEY_FONT)) ?: TerminalFont.DEFAULT
        val fontSize = readInt(KEY_FONT_SIZE_SP, TerminalAppearance.DEFAULT_FONT_SIZE_SP)
            .takeIf { it in TerminalAppearance.MIN_FONT_SIZE_SP..TerminalAppearance.MAX_FONT_SIZE_SP }
            ?: TerminalAppearance.DEFAULT_FONT_SIZE_SP
        val theme = TerminalThemeId.fromPreference(readString(KEY_THEME)) ?: TerminalThemeId.DEFAULT
        val customColors = readCustomColors()
        return TerminalAppearance(
            font = font,
            fontSizeSp = fontSize,
            theme = theme,
            customColors = customColors,
        ).normalized()
    }

    private fun readCustomColors(): TerminalCustomColors? {
        if (
            !preferences.contains(KEY_CUSTOM_BASE_THEME) ||
            !preferences.contains(KEY_CUSTOM_FOREGROUND) ||
            !preferences.contains(KEY_CUSTOM_BACKGROUND) ||
            !preferences.contains(KEY_CUSTOM_CURSOR)
        ) {
            return null
        }
        val baseTheme = TerminalThemeId.fromPreference(readString(KEY_CUSTOM_BASE_THEME))
            ?.takeIf { it != TerminalThemeId.CUSTOM }
            ?: return null
        return TerminalCustomColors(
            baseTheme = baseTheme,
            foregroundArgb = readInt(KEY_CUSTOM_FOREGROUND, 0),
            backgroundArgb = readInt(KEY_CUSTOM_BACKGROUND, 0),
            cursorArgb = readInt(KEY_CUSTOM_CURSOR, 0),
        ).takeIf { TerminalAppearance.isValidCustomColors(it) }
    }

    /** Treats type-mismatched or otherwise damaged preference values as absent. */
    private fun readString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readInt(key: String, defaultValue: Int): Int = runCatching {
        preferences.getInt(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun persist(appearance: TerminalAppearance) {
        preferences.edit {
            putString(KEY_FONT, appearance.font.preferenceValue)
            putInt(KEY_FONT_SIZE_SP, appearance.fontSizeSp)
            putString(KEY_THEME, appearance.theme.preferenceValue)
            appearance.customColors?.let { colors ->
                putString(KEY_CUSTOM_BASE_THEME, colors.baseTheme.preferenceValue)
                putInt(KEY_CUSTOM_FOREGROUND, colors.foregroundArgb)
                putInt(KEY_CUSTOM_BACKGROUND, colors.backgroundArgb)
                putInt(KEY_CUSTOM_CURSOR, colors.cursorArgb)
            } ?: run {
                remove(KEY_CUSTOM_BASE_THEME)
                remove(KEY_CUSTOM_FOREGROUND)
                remove(KEY_CUSTOM_BACKGROUND)
                remove(KEY_CUSTOM_CURSOR)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-terminal-appearance"
        const val KEY_FONT = "font"
        const val KEY_FONT_SIZE_SP = "font_size_sp"
        const val KEY_THEME = "theme"
        const val KEY_CUSTOM_BASE_THEME = "custom_base_theme"
        const val KEY_CUSTOM_FOREGROUND = "custom_foreground"
        const val KEY_CUSTOM_BACKGROUND = "custom_background"
        const val KEY_CUSTOM_CURSOR = "custom_cursor"
    }
}
