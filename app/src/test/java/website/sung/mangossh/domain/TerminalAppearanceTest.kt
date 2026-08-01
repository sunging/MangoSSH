package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppearanceTest {
    @Test
    fun defaultsUseTerminalFriendlyFontSizeAndMangoDark() {
        val appearance = TerminalAppearance()

        assertEquals(TerminalFont.CASCADIA_MONO_PL, appearance.font)
        assertEquals(12, appearance.fontSizeSp)
        assertEquals(TerminalThemeId.MANGO_DARK, appearance.theme)
    }

    @Test
    fun preferenceIdsResolveOnlyKnownFontsAndThemes() {
        assertEquals(TerminalFont.JETBRAINS_MONO_NL, TerminalFont.fromPreference("jetbrains_mono_nl"))
        assertEquals(TerminalThemeId.SOLARIZED_LIGHT, TerminalThemeId.fromPreference("solarized_light"))
        assertNull(TerminalFont.fromPreference("device_font"))
        assertNull(TerminalThemeId.fromPreference("automatic"))
    }

    @Test
    fun outOfRangeSizeAndInvalidCustomThemeFallBackToDefaults() {
        val invalidCustom = TerminalCustomColors(
            baseTheme = TerminalThemeId.CUSTOM,
            foregroundArgb = argb(0xFFFFFFFF),
            backgroundArgb = argb(0xFF101416),
            cursorArgb = argb(0xFFFFC857),
        )

        val appearance = TerminalAppearance(
            fontSizeSp = TerminalAppearance.MAX_FONT_SIZE_SP + 1,
            theme = TerminalThemeId.CUSTOM,
            customColors = invalidCustom,
        ).normalized()

        assertEquals(TerminalAppearance.DEFAULT_FONT_SIZE_SP, appearance.fontSizeSp)
        assertEquals(TerminalThemeId.DEFAULT, appearance.theme)
        assertNull(appearance.customColors)
    }

    @Test
    fun everyBundledPresetContainsSixteenOpaqueAnsiColors() {
        TerminalThemeId.entries
            .filterNot { it == TerminalThemeId.CUSTOM }
            .forEach { theme ->
                val scheme = TerminalAppearance(theme = theme).colorScheme

                assertEquals("$theme ANSI color count", 16, scheme.ansiColors.size)
                assertTrue("$theme ANSI opacity", scheme.ansiColors.all { (it ushr 24) == 0xFF })
                assertTrue("$theme foreground opacity", (scheme.defaultForegroundArgb ushr 24) == 0xFF)
                assertTrue("$theme background opacity", (scheme.defaultBackgroundArgb ushr 24) == 0xFF)
                assertTrue("$theme cursor opacity", (scheme.cursorArgb ushr 24) == 0xFF)
                assertTrue("$theme selection foreground opacity", (scheme.selectionForegroundArgb ushr 24) == 0xFF)
                assertTrue("$theme selection background opacity", (scheme.selectionBackgroundArgb ushr 24) == 0xFF)
            }
    }

    @Test
    fun validCustomThemeOverridesOnlyThreeColorsAndInheritsBasePalette() {
        val custom = TerminalCustomColors(
            baseTheme = TerminalThemeId.NORD,
            foregroundArgb = argb(0xFFF5F7FA),
            backgroundArgb = argb(0xFF17212B),
            cursorArgb = argb(0xFF88C0D0),
        )
        val base = TerminalAppearance(theme = TerminalThemeId.NORD).colorScheme
        val scheme = TerminalAppearance(
            theme = TerminalThemeId.CUSTOM,
            customColors = custom,
        ).colorScheme

        assertTrue(TerminalAppearance.isValidCustomColors(custom))
        assertEquals(base.ansiColors, scheme.ansiColors)
        assertEquals(base.selectionForegroundArgb, scheme.selectionForegroundArgb)
        assertEquals(base.selectionBackgroundArgb, scheme.selectionBackgroundArgb)
        assertEquals(custom.foregroundArgb, scheme.defaultForegroundArgb)
        assertEquals(custom.backgroundArgb, scheme.defaultBackgroundArgb)
        assertEquals(custom.cursorArgb, scheme.cursorArgb)
    }

    @Test
    fun customThemeRejectsTransparentOrLowContrastOverrides() {
        val transparent = TerminalCustomColors(
            baseTheme = TerminalThemeId.MANGO_DARK,
            foregroundArgb = argb(0x7FFFFFFF),
            backgroundArgb = argb(0xFF101416),
            cursorArgb = argb(0xFFFFC857),
        )
        val lowContrast = TerminalCustomColors(
            baseTheme = TerminalThemeId.MANGO_DARK,
            foregroundArgb = argb(0xFF15191B),
            backgroundArgb = argb(0xFF101416),
            cursorArgb = argb(0xFF15191B),
        )

        assertFalse(TerminalAppearance.isValidCustomColors(transparent))
        assertFalse(TerminalAppearance.isValidCustomColors(lowContrast))
        assertTrue(
            TerminalAppearance.contrastRatio(argb(0xFFFFFFFF), argb(0xFF101416)) >= 3.0,
        )
    }

    private fun argb(value: Long): Int = value.toInt()
}
