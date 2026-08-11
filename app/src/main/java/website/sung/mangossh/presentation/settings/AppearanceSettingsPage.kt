package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import website.sung.mangossh.presentation.LanguageSettingsCard

/**
 * Appearance detail page.
 *
 * Language is the only control today; app theme mode and dynamic color join
 * it once that store lands.
 */
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
            LanguageSettingsCard(
                selected = state.language,
                onSelect = callbacks.onSetLanguage,
            )
        }
    }
}
