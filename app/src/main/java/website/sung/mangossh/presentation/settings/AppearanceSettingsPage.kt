package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.AppThemeMode
import website.sung.mangossh.presentation.LanguageSettingsRow
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.SettingsChoiceRow
import website.sung.mangossh.ui.components.SettingsSwitchRow

/** Appearance detail page: display language, app theme mode, and dynamic color. */
@Composable
internal fun AppearanceSettingsPage(
    state: AppearanceSettingsState,
    callbacks: AppearanceSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoPreferenceGroup {
                LanguageSettingsRow(
                    selected = state.language,
                    onSelect = callbacks.onSetLanguage,
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_appearance_theme_title),
                    options = AppThemeMode.entries,
                    selected = state.themePreferences.mode,
                    optionLabel = { it.label() },
                    onSelect = callbacks.onSetThemeMode,
                    supportingText = stringResource(R.string.settings_appearance_theme_summary),
                    optionTestTag = { "settings_theme_mode_${it.preferenceValue}" },
                    modifier = Modifier.testTag("settings_theme_mode_control"),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_appearance_dynamic_color_title),
                    summary = if (state.dynamicColorSupported) {
                        stringResource(R.string.settings_appearance_dynamic_color_summary)
                    } else {
                        stringResource(R.string.settings_appearance_dynamic_color_unavailable)
                    },
                    checked = state.themePreferences.dynamicColorEnabled,
                    onCheckedChange = callbacks.onSetDynamicColorEnabled,
                    enabled = state.dynamicColorSupported,
                    modifier = Modifier.testTag("settings_dynamic_color_switch"),
                )
            }
        }
    }
}

@Composable
private fun AppThemeMode.label(): String = stringResource(
    when (this) {
        AppThemeMode.SYSTEM -> R.string.settings_appearance_theme_system
        AppThemeMode.LIGHT -> R.string.settings_appearance_theme_light
        AppThemeMode.DARK -> R.string.settings_appearance_theme_dark
    },
)
