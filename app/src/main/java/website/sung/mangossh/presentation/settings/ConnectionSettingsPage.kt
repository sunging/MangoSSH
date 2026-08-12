package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.ConnectionPreferences
import website.sung.mangossh.domain.SshTerminalType
import website.sung.mangossh.ui.components.MangoSettingsCard
import website.sung.mangossh.ui.components.SettingsDropdownRow

/**
 * Connection detail page: global defaults for new SSH/Mosh connections.
 *
 * These are device-local transport defaults, not part of a saved host
 * profile; a future per-profile override belongs on the profile itself.
 */
@Composable
internal fun ConnectionSettingsPage(
    state: ConnectionSettingsState,
    callbacks: ConnectionSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val preferences = state.preferences
    val newConnectionsNote = stringResource(R.string.settings_applies_to_new_connections)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoSettingsCard(modifier = Modifier.testTag("connection_settings_card")) {
                Text(stringResource(R.string.settings_category_connection_title), style = MaterialTheme.typography.titleMedium)
                SettingsDropdownRow(
                    label = stringResource(R.string.settings_connection_keepalive_title),
                    options = ConnectionPreferences.KEEPALIVE_CHOICES,
                    selected = preferences.keepaliveSeconds,
                    optionLabel = { seconds ->
                        if (seconds <= 0) {
                            stringResource(R.string.settings_connection_keepalive_off)
                        } else {
                            stringResource(R.string.settings_connection_keepalive_value, seconds)
                        }
                    },
                    onSelect = callbacks.onSetKeepaliveSeconds,
                    supportingText = "${stringResource(R.string.settings_connection_keepalive_summary)} $newConnectionsNote",
                    optionTestTag = { "settings_keepalive_$it" },
                    modifier = Modifier.testTag("settings_keepalive_dropdown"),
                )
                SettingsDropdownRow(
                    label = stringResource(R.string.settings_connection_timeout_title),
                    options = ConnectionPreferences.CONNECT_TIMEOUT_CHOICES,
                    selected = preferences.connectTimeoutSeconds,
                    optionLabel = { stringResource(R.string.settings_connection_timeout_value, it) },
                    onSelect = callbacks.onSetConnectTimeoutSeconds,
                    supportingText = "${stringResource(R.string.settings_connection_timeout_summary)} $newConnectionsNote",
                    optionTestTag = { "settings_connect_timeout_$it" },
                    modifier = Modifier.testTag("settings_connect_timeout_dropdown"),
                )
                SettingsDropdownRow(
                    label = stringResource(R.string.settings_connection_terminal_type_title),
                    options = SshTerminalType.entries,
                    selected = preferences.sshTerminalType,
                    optionLabel = { it.label() },
                    onSelect = callbacks.onSetSshTerminalType,
                    supportingText = "${stringResource(R.string.settings_connection_terminal_type_summary)} " +
                        "$newConnectionsNote ${stringResource(R.string.settings_connection_terminal_type_mosh_note)}",
                    optionTestTag = { "settings_terminal_type_${it.preferenceValue}" },
                    modifier = Modifier.testTag("settings_terminal_type_dropdown"),
                )
            }
        }
    }
}

@Composable
private fun SshTerminalType.label(): String = stringResource(
    when (this) {
        SshTerminalType.XTERM_256COLOR -> R.string.settings_connection_terminal_type_xterm_256color
        SshTerminalType.XTERM -> R.string.settings_connection_terminal_type_xterm
        SshTerminalType.SCREEN_256COLOR -> R.string.settings_connection_terminal_type_screen_256color
        SshTerminalType.VT100 -> R.string.settings_connection_terminal_type_vt100
    },
)
