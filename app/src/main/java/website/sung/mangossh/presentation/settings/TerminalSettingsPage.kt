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
import java.util.Locale
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.presentation.TerminalAppearanceCard
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.SettingsChoiceRow
import website.sung.mangossh.ui.components.SettingsSwitchRow

/** Terminal detail page: font, size, and color theme, plus emulator behavior. */
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
            MangoSectionHeader(
                text = stringResource(R.string.terminal_appearance_title),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
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
        item {
            MangoSectionHeader(
                text = stringResource(R.string.settings_terminal_behavior_title),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            TerminalBehaviorCard(behavior = state.behavior, callbacks = callbacks)
        }
    }
}

@Composable
private fun TerminalBehaviorCard(
    behavior: TerminalBehavior,
    callbacks: TerminalSettingsCallbacks,
) {
    val newSessionsNote = stringResource(R.string.settings_applies_to_new_sessions)
    MangoPreferenceGroup(modifier = Modifier.testTag("terminal_behavior_card")) {
        SettingsChoiceRow(
            label = stringResource(R.string.settings_terminal_scrollback_title),
            options = TerminalBehavior.SCROLLBACK_CHOICES,
            selected = behavior.scrollbackLines,
            optionLabel = { stringResource(R.string.settings_terminal_scrollback_value, it) },
            onSelect = callbacks.onSetScrollbackLines,
            supportingText = "${stringResource(R.string.settings_terminal_scrollback_summary)} $newSessionsNote",
            optionTestTag = { "settings_scrollback_$it" },
            modifier = Modifier.testTag("settings_scrollback_control"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_terminal_bold_as_bright_title),
            summary = "${stringResource(R.string.settings_terminal_bold_as_bright_summary)} $newSessionsNote",
            checked = behavior.boldAsBright,
            onCheckedChange = callbacks.onSetBoldAsBright,
            modifier = Modifier.testTag("settings_bold_as_bright_switch"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_terminal_auto_detect_urls_title),
            summary = "${stringResource(R.string.settings_terminal_auto_detect_urls_summary)} $newSessionsNote",
            checked = behavior.autoDetectUrls,
            onCheckedChange = callbacks.onSetAutoDetectUrls,
            modifier = Modifier.testTag("settings_auto_detect_urls_switch"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_terminal_keep_screen_on_title),
            summary = stringResource(R.string.settings_terminal_keep_screen_on_summary),
            checked = behavior.keepScreenOn,
            onCheckedChange = callbacks.onSetKeepScreenOn,
            modifier = Modifier.testTag("settings_keep_screen_on_switch"),
        )
        SettingsChoiceRow(
            label = stringResource(R.string.settings_terminal_pinch_zoom_title),
            options = TerminalBehavior.PINCH_ZOOM_CHOICES,
            selected = behavior.maxPinchZoomScale,
            optionLabel = { scale ->
                stringResource(R.string.settings_terminal_pinch_zoom_value, String.format(Locale.US, "%.1f", scale))
            },
            onSelect = callbacks.onSetMaxPinchZoomScale,
            supportingText = stringResource(R.string.settings_terminal_pinch_zoom_summary),
            optionTestTag = { "settings_pinch_zoom_${String.format(Locale.US, "%.1f", it)}" },
            modifier = Modifier.testTag("settings_pinch_zoom_control"),
        )
    }
}
