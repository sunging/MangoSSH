package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalThemeId

class TerminalAppearanceCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectsAppearanceEditsCustomColorsValidatesAndRestoresDefaults() {
        var appearance by mutableStateOf(TerminalAppearance())
        composeRule.setContent {
            MaterialTheme {
                TerminalAppearanceCard(
                    appearance = appearance,
                    onSetFont = { appearance = appearance.copy(font = it) },
                    onSetFontSize = { appearance = appearance.copy(fontSizeSp = it) },
                    onSetTheme = { appearance = appearance.copy(theme = it, customColors = null) },
                    onSetCustomColors = {
                        appearance = appearance.copy(theme = TerminalThemeId.CUSTOM, customColors = it)
                    },
                    onReset = { appearance = TerminalAppearance() },
                )
            }
        }

        composeRule.onNodeWithTag("terminal_appearance_card").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_theme_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_font_dropdown").performClick()
        composeRule.onNodeWithTag("terminal_font_jetbrains_mono_nl").performClick()
        composeRule.onNodeWithTag("terminal_font_size_increase").performClick()
        composeRule.onNodeWithTag("terminal_theme_dropdown").performClick()
        composeRule.onNodeWithTag("terminal_theme_dracula").performClick()
        composeRule.runOnIdle {
            assertEquals(TerminalFont.JETBRAINS_MONO_NL, appearance.font)
            assertEquals(13, appearance.fontSizeSp)
            assertEquals(TerminalThemeId.DRACULA, appearance.theme)
        }

        composeRule.onNodeWithTag("terminal_customize_colors").performClick()
        composeRule.onNodeWithTag("terminal_custom_foreground").performTextReplacement("#NOTHEX")
        composeRule.onNodeWithTag("terminal_custom_hex_error").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_custom_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("terminal_custom_foreground").performTextReplacement("#FFFFFF")
        composeRule.onNodeWithTag("terminal_custom_background").performTextReplacement("#282A36")
        composeRule.onNodeWithTag("terminal_custom_cursor").performTextReplacement("#F8F8F2")
        composeRule.onNodeWithTag("terminal_custom_save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(TerminalThemeId.CUSTOM, appearance.theme)
            assertEquals(0xFFFFFFFF.toInt(), appearance.customColors?.foregroundArgb)
            assertEquals(0xFF282A36.toInt(), appearance.customColors?.backgroundArgb)
            assertEquals(0xFFF8F8F2.toInt(), appearance.customColors?.cursorArgb)
            assertEquals(0xFFFFFFFF.toInt(), appearance.colorScheme.defaultForegroundArgb)
        }

        composeRule.onNodeWithTag("terminal_restore_defaults").performClick()
        composeRule.runOnIdle {
            assertEquals(TerminalAppearance(), appearance)
        }
    }
}
