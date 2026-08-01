package website.sung.mangossh.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Bundled terminal fonts that can be selected without relying on device-specific
 * font availability.
 */
enum class TerminalFont(val preferenceValue: String) {
    CASCADIA_MONO_PL("cascadia_mono_pl"),
    JETBRAINS_MONO_NL("jetbrains_mono_nl"),
    FIRA_CODE("fira_code"),
    ;

    companion object {
        val DEFAULT: TerminalFont = CASCADIA_MONO_PL

        /** Resolves a persisted font identifier without accepting device-provided font names. */
        fun fromPreference(value: String?): TerminalFont? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/** Identifies the terminal color scheme selected by the user. */
enum class TerminalThemeId(val preferenceValue: String) {
    MANGO_DARK("mango_dark"),
    DRACULA("dracula"),
    NORD("nord"),
    SOLARIZED_DARK("solarized_dark"),
    SOLARIZED_LIGHT("solarized_light"),
    CUSTOM("custom"),
    ;

    companion object {
        val DEFAULT: TerminalThemeId = MANGO_DARK

        /** Resolves a persisted theme identifier while rejecting unknown or future values. */
        fun fromPreference(value: String?): TerminalThemeId? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/**
 * Three device-local overrides layered over a bundled theme's ANSI palette.
 *
 * The palette and selection colors deliberately remain owned by [baseTheme] so
 * a custom background cannot accidentally leave ANSI application output without
 * a coherent color scheme.
 */
data class TerminalCustomColors(
    val baseTheme: TerminalThemeId,
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val cursorArgb: Int,
)

/** Complete terminal palette consumed by the emulator and the Compose renderer. */
data class TerminalColorScheme(
    val ansiColors: List<Int>,
    val defaultForegroundArgb: Int,
    val defaultBackgroundArgb: Int,
    val cursorArgb: Int,
    val selectionForegroundArgb: Int,
    val selectionBackgroundArgb: Int,
) {
    init {
        require(ansiColors.size == ANSI_COLOR_COUNT) { "A terminal palette must contain 16 ANSI colors." }
        require(ansiColors.all(::isOpaqueArgb)) { "Terminal palette colors must be opaque." }
        require(isOpaqueArgb(defaultForegroundArgb)) { "Default foreground must be opaque." }
        require(isOpaqueArgb(defaultBackgroundArgb)) { "Default background must be opaque." }
        require(isOpaqueArgb(cursorArgb)) { "Cursor color must be opaque." }
        require(isOpaqueArgb(selectionForegroundArgb)) { "Selection foreground must be opaque." }
        require(isOpaqueArgb(selectionBackgroundArgb)) { "Selection background must be opaque." }
    }

    companion object {
        const val ANSI_COLOR_COUNT = 16
    }
}

/**
 * User-selectable terminal rendering preferences.
 *
 * This is intentionally device-local UI state: it does not contain credentials
 * and is not part of an encrypted profile or portable vault backup.
 */
data class TerminalAppearance(
    val font: TerminalFont = TerminalFont.DEFAULT,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val theme: TerminalThemeId = TerminalThemeId.DEFAULT,
    val customColors: TerminalCustomColors? = null,
) {
    val colorScheme: TerminalColorScheme
        get() = when (theme) {
            TerminalThemeId.CUSTOM -> customColors
                ?.takeIf(::isValidCustomColors)
                ?.let { custom -> presetScheme(custom.baseTheme).copy(
                    defaultForegroundArgb = custom.foregroundArgb,
                    defaultBackgroundArgb = custom.backgroundArgb,
                    cursorArgb = custom.cursorArgb,
                ) }
                ?: presetScheme(TerminalThemeId.DEFAULT)

            else -> presetScheme(theme)
        }

    /**
     * Produces a safe persisted value when preferences are damaged or from a
     * newer app version. Invalid custom data returns to the bundled default.
     */
    fun normalized(): TerminalAppearance {
        val normalizedTheme = if (theme == TerminalThemeId.CUSTOM && !isValidCustomColors(customColors)) {
            TerminalThemeId.DEFAULT
        } else {
            theme
        }
        return copy(
            fontSizeSp = fontSizeSp.takeIf { it in MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP } ?: DEFAULT_FONT_SIZE_SP,
            theme = normalizedTheme,
            customColors = if (normalizedTheme == TerminalThemeId.CUSTOM) customColors else null,
        )
    }

    companion object {
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 24
        const val DEFAULT_FONT_SIZE_SP = 12

        /**
         * Requires opaque `#RRGGBB`-equivalent colors and a 3:1 contrast ratio
         * for both text and cursor against the selected background.
         */
        fun isValidCustomColors(colors: TerminalCustomColors?): Boolean = colors != null &&
            colors.baseTheme != TerminalThemeId.CUSTOM &&
            isOpaqueArgb(colors.foregroundArgb) &&
            isOpaqueArgb(colors.backgroundArgb) &&
            isOpaqueArgb(colors.cursorArgb) &&
            contrastRatio(colors.foregroundArgb, colors.backgroundArgb) >= MINIMUM_CONTRAST_RATIO &&
            contrastRatio(colors.cursorArgb, colors.backgroundArgb) >= MINIMUM_CONTRAST_RATIO

        /** Returns the WCAG contrast ratio for two opaque ARGB colors. */
        fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
            val first = relativeLuminance(firstArgb)
            val second = relativeLuminance(secondArgb)
            return (max(first, second) + 0.05) / (min(first, second) + 0.05)
        }

        private const val MINIMUM_CONTRAST_RATIO = 3.0
    }
}

private fun presetScheme(theme: TerminalThemeId): TerminalColorScheme = when (theme) {
    TerminalThemeId.MANGO_DARK -> TerminalColorScheme(
        ansiColors = xtermAnsiColors,
        defaultForegroundArgb = color(0xFFF4F4F4),
        defaultBackgroundArgb = color(0xFF101416),
        cursorArgb = color(0xFF70DB90),
        selectionForegroundArgb = color(0xFF101416),
        selectionBackgroundArgb = color(0xFF70DB90),
    )

    TerminalThemeId.DRACULA -> TerminalColorScheme(
        ansiColors = listOf(
            color(0xFF21222C), color(0xFFFF5555), color(0xFF50FA7B), color(0xFFF1FA8C),
            color(0xFFBD93F9), color(0xFFFF79C6), color(0xFF8BE9FD), color(0xFFF8F8F2),
            color(0xFF6272A4), color(0xFFFF6E6E), color(0xFF69FF94), color(0xFFFFFFA5),
            color(0xFFD6ACFF), color(0xFFFF92DF), color(0xFFA4FFFF), color(0xFFFFFFFF),
        ),
        defaultForegroundArgb = color(0xFFF8F8F2),
        defaultBackgroundArgb = color(0xFF282A36),
        cursorArgb = color(0xFFF8F8F2),
        selectionForegroundArgb = color(0xFFF8F8F2),
        selectionBackgroundArgb = color(0xFF44475A),
    )

    TerminalThemeId.NORD -> TerminalColorScheme(
        ansiColors = listOf(
            color(0xFF3B4252), color(0xFFBF616A), color(0xFFA3BE8C), color(0xFFEBCB8B),
            color(0xFF81A1C1), color(0xFFB48EAD), color(0xFF88C0D0), color(0xFFE5E9F0),
            color(0xFF4C566A), color(0xFFBF616A), color(0xFFA3BE8C), color(0xFFEBCB8B),
            color(0xFF81A1C1), color(0xFFB48EAD), color(0xFF8FBCBB), color(0xFFECEFF4),
        ),
        defaultForegroundArgb = color(0xFFD8DEE9),
        defaultBackgroundArgb = color(0xFF2E3440),
        cursorArgb = color(0xFFD8DEE9),
        selectionForegroundArgb = color(0xFFECEFF4),
        selectionBackgroundArgb = color(0xFF434C5E),
    )

    TerminalThemeId.SOLARIZED_DARK -> solarizedScheme(
        defaultForegroundArgb = color(0xFF839496),
        defaultBackgroundArgb = color(0xFF002B36),
        cursorArgb = color(0xFF93A1A1),
        selectionForegroundArgb = color(0xFF93A1A1),
        selectionBackgroundArgb = color(0xFF073642),
    )

    TerminalThemeId.SOLARIZED_LIGHT -> solarizedScheme(
        defaultForegroundArgb = color(0xFF657B83),
        defaultBackgroundArgb = color(0xFFFDF6E3),
        cursorArgb = color(0xFF586E75),
        selectionForegroundArgb = color(0xFF586E75),
        selectionBackgroundArgb = color(0xFFEEE8D5),
    )

    TerminalThemeId.CUSTOM -> presetScheme(TerminalThemeId.DEFAULT)
}

private fun solarizedScheme(
    defaultForegroundArgb: Int,
    defaultBackgroundArgb: Int,
    cursorArgb: Int,
    selectionForegroundArgb: Int,
    selectionBackgroundArgb: Int,
): TerminalColorScheme = TerminalColorScheme(
    ansiColors = listOf(
        color(0xFF073642), color(0xFFDC322F), color(0xFF859900), color(0xFFB58900),
        color(0xFF268BD2), color(0xFFD33682), color(0xFF2AA198), color(0xFFEEE8D5),
        color(0xFF002B36), color(0xFFCB4B16), color(0xFF586E75), color(0xFF657B83),
        color(0xFF839496), color(0xFF6C71C4), color(0xFF93A1A1), color(0xFFFDF6E3),
    ),
    defaultForegroundArgb = defaultForegroundArgb,
    defaultBackgroundArgb = defaultBackgroundArgb,
    cursorArgb = cursorArgb,
    selectionForegroundArgb = selectionForegroundArgb,
    selectionBackgroundArgb = selectionBackgroundArgb,
)

private val xtermAnsiColors = listOf(
    color(0xFF000000), color(0xFFCD0000), color(0xFF00CD00), color(0xFFCDCD00),
    color(0xFF0000EE), color(0xFFCD00CD), color(0xFF00CDCD), color(0xFFE5E5E5),
    color(0xFF7F7F7F), color(0xFFFF0000), color(0xFF00FF00), color(0xFFFFFF00),
    color(0xFF5C5CFF), color(0xFFFF00FF), color(0xFF00FFFF), color(0xFFFFFFFF),
)

private fun color(value: Long): Int = value.toInt()

private fun isOpaqueArgb(color: Int): Boolean = (color ushr 24) == 0xFF

private fun relativeLuminance(argb: Int): Double {
    fun channel(component: Int): Double {
        val normalized = component / 255.0
        return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((argb shr 16) and 0xFF) +
        0.7152 * channel((argb shr 8) and 0xFF) +
        0.0722 * channel(argb and 0xFF)
}
