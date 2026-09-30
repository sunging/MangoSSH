package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.settings.StreamingPreferencesStore
import website.sung.mangossh.domain.ConnectionPreferences
import website.sung.mangossh.domain.SshTerminalType
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.SettingsChoiceRow

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
            MangoSectionHeader(
                text = stringResource(R.string.settings_connection_defaults_header),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            MangoPreferenceGroup(modifier = Modifier.testTag("connection_settings_card")) {
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_connection_keepalive_title),
                    options = ConnectionPreferences.keepaliveChoices(preferences.keepaliveSeconds),
                    selected = preferences.keepaliveSeconds,
                    optionLabel = { seconds ->
                        val label = if (seconds <= 0) {
                            stringResource(R.string.settings_connection_keepalive_off)
                        } else {
                            stringResource(R.string.settings_connection_keepalive_value, seconds)
                        }
                        markCustom(label, custom = seconds !in ConnectionPreferences.KEEPALIVE_CHOICES)
                    },
                    onSelect = callbacks.onSetKeepaliveSeconds,
                    supportingText = "${stringResource(R.string.settings_connection_keepalive_summary)} $newConnectionsNote",
                    optionTestTag = { "settings_keepalive_$it" },
                    modifier = Modifier.testTag("settings_keepalive_control"),
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_connection_bg_keepalive_title),
                    options = ConnectionPreferences.backgroundKeepaliveMultiplierChoices(
                        preferences.backgroundKeepaliveMultiplier,
                    ),
                    selected = preferences.backgroundKeepaliveMultiplier,
                    optionLabel = { multiplier ->
                        val label = if (multiplier <= 1) {
                            stringResource(R.string.settings_connection_bg_keepalive_same)
                        } else {
                            stringResource(R.string.settings_connection_bg_keepalive_value, multiplier)
                        }
                        markCustom(
                            label,
                            custom = multiplier !in ConnectionPreferences.BACKGROUND_KEEPALIVE_MULTIPLIER_CHOICES,
                        )
                    },
                    onSelect = callbacks.onSetBackgroundKeepaliveMultiplier,
                    supportingText = stringResource(R.string.settings_connection_bg_keepalive_summary),
                    optionTestTag = { "settings_bg_keepalive_$it" },
                    modifier = Modifier.testTag("settings_bg_keepalive_control"),
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_connection_timeout_title),
                    options = ConnectionPreferences.connectTimeoutChoices(preferences.connectTimeoutSeconds),
                    selected = preferences.connectTimeoutSeconds,
                    optionLabel = { seconds ->
                        markCustom(
                            stringResource(R.string.settings_connection_timeout_value, seconds),
                            custom = seconds !in ConnectionPreferences.CONNECT_TIMEOUT_CHOICES,
                        )
                    },
                    onSelect = callbacks.onSetConnectTimeoutSeconds,
                    supportingText = "${stringResource(R.string.settings_connection_timeout_summary)} $newConnectionsNote",
                    optionTestTag = { "settings_connect_timeout_$it" },
                    modifier = Modifier.testTag("settings_connect_timeout_control"),
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_connection_terminal_type_title),
                    options = SshTerminalType.entries,
                    selected = preferences.sshTerminalType,
                    optionLabel = { it.label() },
                    onSelect = callbacks.onSetSshTerminalType,
                    supportingText = "${stringResource(R.string.settings_connection_terminal_type_summary)} " +
                        "$newConnectionsNote ${stringResource(R.string.settings_connection_terminal_type_mosh_note)}",
                    optionTestTag = { "settings_terminal_type_${it.preferenceValue}" },
                    modifier = Modifier.testTag("settings_terminal_type_control"),
                )
            }
        }
        item {
            MangoSectionHeader(
                text = stringResource(R.string.settings_streaming_header),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            val usage = state.streamingCacheUsage
            val current = state.streamingCacheLimitMebibytes
            MangoPreferenceGroup(modifier = Modifier.testTag("streaming_settings_card")) {
                SettingsChoiceRow(
                    label = stringResource(R.string.settings_streaming_cache_title),
                    options = StreamingPreferencesStore.cacheLimitChoices(current),
                    selected = current,
                    optionLabel = { mebibytes ->
                        val label = mebibyteLabel(mebibytes)
                        val capped = usage != null && mebibytes * MEBIBYTE > usage.ceilingBytes
                        markCustom(
                            if (capped) stringResource(R.string.settings_streaming_cache_capped, label) else label,
                            custom = mebibytes !in StreamingPreferencesStore.CACHE_LIMIT_CHOICES_MIB,
                        )
                    },
                    onSelect = callbacks.onSetStreamingCacheLimitMebibytes,
                    supportingText = buildString {
                        append(stringResource(R.string.settings_streaming_cache_summary))
                        if (usage != null) {
                            append(' ')
                            append(
                                stringResource(
                                    R.string.settings_streaming_cache_usage,
                                    mebibyteLabel(((usage.usedBytes + MEBIBYTE - 1) / MEBIBYTE).toInt()),
                                    mebibyteLabel((usage.limitBytes / MEBIBYTE).toInt()),
                                ),
                            )
                        }
                    },
                    optionTestTag = { "settings_streaming_cache_$it" },
                    modifier = Modifier.testTag("settings_streaming_cache_control"),
                )
            }
        }
    }
}

/** Whole gibibytes read as GB, everything else as MB. */
@Composable
private fun mebibyteLabel(mebibytes: Int): String = if (mebibytes >= 1024 && mebibytes % 1024 == 0) {
    stringResource(R.string.settings_streaming_cache_value_gb, mebibytes / 1024)
} else {
    stringResource(R.string.settings_streaming_cache_value_mb, mebibytes)
}

private const val MEBIBYTE = 1024L * 1024

@Composable
private fun SshTerminalType.label(): String = stringResource(
    when (this) {
        SshTerminalType.XTERM_256COLOR -> R.string.settings_connection_terminal_type_xterm_256color
        SshTerminalType.XTERM -> R.string.settings_connection_terminal_type_xterm
        SshTerminalType.SCREEN_256COLOR -> R.string.settings_connection_terminal_type_screen_256color
        SshTerminalType.VT100 -> R.string.settings_connection_terminal_type_vt100
    },
)
