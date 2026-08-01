package website.sung.mangossh.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalThemeId

@RunWith(AndroidJUnit4::class)
class TerminalAppearanceStoreInstrumentedTest {
    @Test
    fun persistsFontSizePresetAndCustomColorsAcrossStoreRecreation() {
        withTestPreferences { context, _ ->
            val store = TerminalAppearanceStore(context)
            val custom = TerminalCustomColors(
                baseTheme = TerminalThemeId.SOLARIZED_DARK,
                foregroundArgb = 0xFFFDF6E3.toInt(),
                backgroundArgb = 0xFF002B36.toInt(),
                cursorArgb = 0xFFB58900.toInt(),
            )

            store.setFont(TerminalFont.FIRA_CODE)
            store.setFontSize(TerminalAppearance.MAX_FONT_SIZE_SP)
            store.setTheme(TerminalThemeId.DRACULA)
            store.setCustomColors(custom)

            val restored = TerminalAppearanceStore(context).current()
            assertEquals(TerminalFont.FIRA_CODE, restored.font)
            assertEquals(TerminalAppearance.MAX_FONT_SIZE_SP, restored.fontSizeSp)
            assertEquals(TerminalThemeId.CUSTOM, restored.theme)
            assertEquals(custom, restored.customColors)
            assertEquals(custom.foregroundArgb, restored.colorScheme.defaultForegroundArgb)
        }
    }

    @Test
    fun corruptPreferencesFallBackToSafeDefaults() {
        withTestPreferences { context, preferences ->
            preferences.edit()
                .putString("font", "unbundled-font")
                .putString("font_size_sp", "not-an-int")
                .putString("theme", "custom")
                .putString("custom_base_theme", "custom")
                .putString("custom_foreground", "#FFFFFF")
                .putInt("custom_background", 0xFF101416.toInt())
                .putInt("custom_cursor", 0xFF101416.toInt())
                .commit()

            val restored = TerminalAppearanceStore(context).current()
            assertEquals(TerminalFont.DEFAULT, restored.font)
            assertEquals(TerminalAppearance.DEFAULT_FONT_SIZE_SP, restored.fontSizeSp)
            assertEquals(TerminalThemeId.DEFAULT, restored.theme)
            assertNull(restored.customColors)
            assertTrue(restored.colorScheme.ansiColors.all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun retainsBothSizeBoundariesAndResetClearsSavedOverrides() {
        withTestPreferences { context, _ ->
            val store = TerminalAppearanceStore(context)
            store.setFontSize(TerminalAppearance.MIN_FONT_SIZE_SP)
            assertEquals(TerminalAppearance.MIN_FONT_SIZE_SP, store.current().fontSizeSp)
            store.setFontSize(TerminalAppearance.MAX_FONT_SIZE_SP)
            assertEquals(TerminalAppearance.MAX_FONT_SIZE_SP, store.current().fontSizeSp)
            store.setCustomColors(
                TerminalCustomColors(
                    baseTheme = TerminalThemeId.MANGO_DARK,
                    foregroundArgb = 0xFFF4F4F4.toInt(),
                    backgroundArgb = 0xFF101416.toInt(),
                    cursorArgb = 0xFF70DB90.toInt(),
                ),
            )

            store.reset()

            assertEquals(TerminalAppearance(), TerminalAppearanceStore(context).current())
        }
    }

    private fun withTestPreferences(
        block: (Context, SharedPreferences) -> Unit,
    ) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = base.getSharedPreferences(
            "terminal-appearance-test-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        try {
            block(context, preferences)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
