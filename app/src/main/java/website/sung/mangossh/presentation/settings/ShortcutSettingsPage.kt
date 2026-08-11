package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import website.sung.mangossh.presentation.TerminalShortcutSettingsCard

/**
 * Keys & shortcuts detail page.
 *
 * The shortcut bar editor today; right-alt and delete-key input modes join
 * it once a terminal-behavior preference store lands.
 */
@Composable
internal fun ShortcutSettingsPage(
    state: ShortcutSettingsState,
    callbacks: ShortcutSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TerminalShortcutSettingsCard(
                config = state.config,
                onSave = callbacks.onSaveShortcuts,
            )
        }
    }
}
