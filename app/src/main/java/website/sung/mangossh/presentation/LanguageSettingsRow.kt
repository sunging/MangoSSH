package website.sung.mangossh.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R
import website.sung.mangossh.ui.components.SettingsChoiceRow

/** Lets the user choose the app's display language, independent of the device locale. */
@Composable
internal fun LanguageSettingsRow(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    SettingsChoiceRow(
        label = stringResource(R.string.app_language_title),
        options = AppLanguage.entries,
        selected = selected,
        optionLabel = { it.label() },
        onSelect = onSelect,
        supportingText = stringResource(R.string.app_language_description),
        optionTestTag = { "app-language-option_${it.name}" },
        modifier = Modifier.testTag("app-language-selector"),
    )
}

@Composable
private fun AppLanguage.label(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.app_language_system
        AppLanguage.ENGLISH -> R.string.app_language_english
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.app_language_simplified_chinese
    },
)
