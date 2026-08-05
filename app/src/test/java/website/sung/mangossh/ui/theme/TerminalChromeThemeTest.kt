package website.sung.mangossh.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalColorScheme
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalThemeId
import org.junit.Test

class TerminalChromeThemeTest {
    @Test
    fun barsStayAnchoredToTheTerminalBackground() {
        forEachBundledScheme { theme, terminalColors ->
            val chrome = terminalChromeColorScheme(terminalColors)
            assertEquals("$theme surface", terminalColors.defaultBackgroundArgb, chrome.surface.toArgb())
            assertEquals("$theme onSurface", terminalColors.defaultForegroundArgb, chrome.onSurface.toArgb())
            assertTrue(
                "$theme header bar is barely distinguishable from the terminal",
                contrast(chrome.surfaceContainer, chrome.surface) > 1.05,
            )
            assertTrue(
                "$theme header bar overpowers the terminal",
                contrast(chrome.surfaceContainer, chrome.surface) < 2.0,
            )
            assertTrue(
                "$theme key bar does not sit above the header bar",
                contrast(chrome.surfaceContainerHigh, chrome.surface) >
                    contrast(chrome.surfaceContainer, chrome.surface),
            )
        }
    }

    @Test
    fun barTextStaysReadableOnEveryBundledPalette() {
        forEachBundledScheme { theme, terminalColors ->
            val chrome = terminalChromeColorScheme(terminalColors)
            // Low-contrast palettes such as Solarized Dark cannot reach 4.5:1 even
            // in the emulator itself, so a bar must instead keep most of whatever
            // legibility the chosen palette already offers.
            val terminalContrast = contrast(chrome.onSurface, chrome.surface)
            listOf(chrome.surfaceContainer, chrome.surfaceContainerHigh).forEach { bar ->
                assertTrue(
                    "$theme title text is unreadable on its bar",
                    contrast(chrome.onSurface, bar) >= minOf(4.5, 0.75 * terminalContrast),
                )
                assertTrue(
                    "$theme subtitle text is unreadable on its bar",
                    contrast(chrome.onSurfaceVariant, bar) >= 3.0,
                )
            }
            assertTrue(
                "$theme selected chip label is unreadable",
                contrast(chrome.onPrimary, chrome.primary) >= 3.0,
            )
            assertTrue(
                "$theme chip outline is invisible on its bar",
                contrast(chrome.outline, chrome.surfaceContainerHigh) >= 1.4,
            )
        }
    }

    @Test
    fun aCustomLightBackgroundProducesLightChrome() {
        val custom = TerminalAppearance(
            theme = TerminalThemeId.CUSTOM,
            customColors = TerminalCustomColors(
                baseTheme = TerminalThemeId.MANGO_DARK,
                foregroundArgb = 0xFF202020.toInt(),
                backgroundArgb = 0xFFFFFFFF.toInt(),
                cursorArgb = 0xFF006E2E.toInt(),
            ),
        ).colorScheme
        val chrome = terminalChromeColorScheme(custom)

        assertEquals(0xFFFFFFFF.toInt(), chrome.surface.toArgb())
        assertTrue(
            "a light terminal must not be framed by darker-than-background text",
            luminance(chrome.surfaceContainer) < luminance(chrome.surface),
        )
        assertTrue(contrast(chrome.onSurface, chrome.surfaceContainerHigh) >= 4.5)
        assertTrue(contrast(chrome.onSurfaceVariant, chrome.surfaceContainerHigh) >= 3.0)
    }

    private fun forEachBundledScheme(assertion: (TerminalThemeId, TerminalColorScheme) -> Unit) {
        TerminalThemeId.entries
            .filter { it != TerminalThemeId.CUSTOM }
            .forEach { theme -> assertion(theme, TerminalAppearance(theme = theme).colorScheme) }
    }

    private fun contrast(first: Color, second: Color): Double =
        TerminalAppearance.contrastRatio(first.toArgb(), second.toArgb())

    /** Ranks two colors by lightness using the shared WCAG luminance definition. */
    private fun luminance(color: Color): Double =
        TerminalAppearance.contrastRatio(color.toArgb(), 0xFF000000.toInt())
}
