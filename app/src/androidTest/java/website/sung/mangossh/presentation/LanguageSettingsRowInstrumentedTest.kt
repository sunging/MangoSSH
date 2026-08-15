package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LanguageSettingsRowInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesSystemEnglishAndSimplifiedChineseChoices() {
        var selected by mutableStateOf(AppLanguage.SYSTEM)
        composeRule.setContent {
            MaterialTheme {
                LanguageSettingsRow(
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag("app-language-selector").performClick()
        AppLanguage.entries.forEach { option ->
            composeRule.onNodeWithTag("app-language-option_${option.name}").assertExists()
        }
        composeRule.onNodeWithTag("app-language-option_SIMPLIFIED_CHINESE").performClick()
        composeRule.runOnIdle { assertEquals(AppLanguage.SIMPLIFIED_CHINESE, selected) }
    }
}
