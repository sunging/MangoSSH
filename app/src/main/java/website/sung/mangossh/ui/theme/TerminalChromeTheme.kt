package website.sung.mangossh.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalColorScheme

/**
 * Builds the Material color scheme used by the chrome around emulator output.
 *
 * The session screen paints the terminal with the user's own palette, so its
 * header, key bar, chips, and dialogs must follow that palette rather than the
 * device light/dark theme. Deriving them keeps a dark terminal from being
 * framed by light system-themed bars, and keeps bar text legible because it is
 * contrasted against a surface built from the same background color.
 */
fun terminalChromeColorScheme(terminalColors: TerminalColorScheme): ColorScheme {
    val background = Color(terminalColors.defaultBackgroundArgb)
    val foreground = Color(terminalColors.defaultForegroundArgb)
    val accent = Color(terminalColors.cursorArgb)
    val darkBackground = isDarkColor(terminalColors.defaultBackgroundArgb)
    val errorArgb = terminalColors.ansiColors[if (darkBackground) BRIGHT_RED_INDEX else RED_INDEX]
    val error = Color(errorArgb)
    val onAccent = readableOn(terminalColors.cursorArgb, background, foreground)
    val accentContainer = blend(background, accent, ACCENT_CONTAINER_TINT)
    val highestBar = blend(background, foreground, SURFACE_HIGH_TINT)
    val base = if (darkBackground) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = foreground,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = foreground,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = foreground,
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = blend(background, foreground, SURFACE_HIGHEST_TINT),
        onSurfaceVariant = mutedText(foreground, background, highestBar),
        surfaceTint = accent,
        surfaceContainerLowest = blend(background, foreground, SURFACE_LOWEST_TINT),
        surfaceContainerLow = blend(background, foreground, SURFACE_LOW_TINT),
        surfaceContainer = blend(background, foreground, SURFACE_TINT),
        surfaceContainerHigh = blend(background, foreground, SURFACE_HIGH_TINT),
        surfaceContainerHighest = blend(background, foreground, SURFACE_HIGHEST_TINT),
        outline = blend(background, foreground, OUTLINE_TINT),
        outlineVariant = blend(background, foreground, OUTLINE_VARIANT_TINT),
        error = error,
        onError = readableOn(errorArgb, background, foreground),
        errorContainer = blend(background, error, ACCENT_CONTAINER_TINT),
        onErrorContainer = foreground,
    )
}

/**
 * Mixes two opaque colors in sRGB component space.
 *
 * Component mixing is used instead of a perceptual interpolation so a tint
 * fraction always produces the same, deliberately subtle offset from the
 * terminal background regardless of the Compose version in use.
 */
private fun blend(base: Color, toward: Color, fraction: Float): Color = Color(
    red = base.red + (toward.red - base.red) * fraction,
    green = base.green + (toward.green - base.green) * fraction,
    blue = base.blue + (toward.blue - base.blue) * fraction,
)

/** Reports whether a background needs the dark-theme component treatment. */
private fun isDarkColor(argb: Int): Boolean =
    TerminalAppearance.contrastRatio(WHITE_ARGB, argb) >=
        TerminalAppearance.contrastRatio(BLACK_ARGB, argb)

/**
 * Recedes secondary labels from body text without dropping them below the
 * minimum readable contrast on the most tinted bar. Palettes whose own
 * foreground barely clears that threshold keep their full text color.
 */
private fun mutedText(foreground: Color, background: Color, against: Color): Color {
    val againstArgb = against.toArgb()
    return MUTED_TEXT_TINTS
        .map { blend(foreground, background, it) }
        .firstOrNull {
            TerminalAppearance.contrastRatio(it.toArgb(), againstArgb) >= MINIMUM_LABEL_CONTRAST
        }
        ?: foreground
}

/** Picks whichever terminal color stays readable on a filled component. */
private fun readableOn(fillArgb: Int, background: Color, foreground: Color): Color =
    if (
        TerminalAppearance.contrastRatio(fillArgb, background.toArgb()) >=
        TerminalAppearance.contrastRatio(fillArgb, foreground.toArgb())
    ) {
        background
    } else {
        foreground
    }

private const val RED_INDEX = 1
private const val BRIGHT_RED_INDEX = 9
private const val WHITE_ARGB = 0xFFFFFFFF.toInt()
private const val BLACK_ARGB = 0xFF000000.toInt()

// Surfaces are lifted by blending the terminal background toward its own text
// color, so every elevation level keeps the palette's hue.
private const val SURFACE_LOWEST_TINT = 0.03f
private const val SURFACE_LOW_TINT = 0.06f
private const val SURFACE_TINT = 0.09f
private const val SURFACE_HIGH_TINT = 0.13f
private const val SURFACE_HIGHEST_TINT = 0.18f
private const val OUTLINE_TINT = 0.45f
private const val OUTLINE_VARIANT_TINT = 0.25f
private const val ACCENT_CONTAINER_TINT = 0.24f

// Tried strongest first; the first level that stays legible on the key bar wins.
private val MUTED_TEXT_TINTS = listOf(0.35f, 0.22f, 0.12f)
private const val MINIMUM_LABEL_CONTRAST = 3.0
