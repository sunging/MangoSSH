package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import website.sung.mangossh.presentation.TerminalAppearanceCard

/**
 * Terminal detail page.
 *
 * Font, size, and color theme today; scrollback and other emulator behavior
 * join it once a terminal-behavior preference store lands.
 */
@Composable
internal fun TerminalSettingsPage(
    state: TerminalSettingsState,
    callbacks: TerminalSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TerminalAppearanceCard(
                appearance = state.appearance,
                onSetFont = callbacks.onSetFont,
                onSetFontSize = callbacks.onSetFontSize,
                onSetTheme = callbacks.onSetTheme,
                onSetCustomColors = callbacks.onSetCustomColors,
                onReset = callbacks.onResetAppearance,
            )
        }
    }
}
