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
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalDelKeyMode
import website.sung.mangossh.domain.TerminalRightAltMode
import website.sung.mangossh.presentation.TerminalShortcutSettingsCard
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.SettingsChoiceRow

/** Keys & shortcuts detail page: the shortcut bar editor, plus right-Alt and backspace-key input modes. */
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
            MangoSectionHeader(
                text = stringResource(R.string.terminal_shortcuts_title),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            TerminalShortcutSettingsCard(
                config = state.config,
                onSave = callbacks.onSaveShortcuts,
            )
        }
        item {
            MangoSectionHeader(
                text = stringResource(R.string.settings_shortcuts_input_modes_title),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            TerminalInputModesCard(behavior = state.behavior, callbacks = callbacks)
        }
    }
}

@Composable
private fun TerminalInputModesCard(
    behavior: TerminalBehavior,
    callbacks: ShortcutSettingsCallbacks,
) {
    MangoPreferenceGroup(modifier = Modifier.testTag("terminal_input_modes_card")) {
        SettingsChoiceRow(
            label = stringResource(R.string.settings_shortcuts_right_alt_title),
            options = TerminalRightAltMode.entries,
            selected = behavior.rightAltMode,
            optionLabel = { it.label() },
            onSelect = callbacks.onSetRightAltMode,
            supportingText = stringResource(R.string.settings_shortcuts_right_alt_summary),
            optionTestTag = { "settings_right_alt_${it.preferenceValue}" },
            modifier = Modifier.testTag("settings_right_alt_control"),
        )
        SettingsChoiceRow(
            label = stringResource(R.string.settings_shortcuts_del_key_title),
            options = TerminalDelKeyMode.entries,
            selected = behavior.delKeyMode,
            optionLabel = { it.label() },
            onSelect = callbacks.onSetDelKeyMode,
            supportingText = stringResource(R.string.settings_shortcuts_del_key_summary),
            optionTestTag = { "settings_del_key_${it.preferenceValue}" },
            modifier = Modifier.testTag("settings_del_key_control"),
        )
    }
}

@Composable
private fun TerminalRightAltMode.label(): String = stringResource(
    when (this) {
        TerminalRightAltMode.CHARACTER_MODIFIER -> R.string.settings_shortcuts_right_alt_character_modifier
        TerminalRightAltMode.META -> R.string.settings_shortcuts_right_alt_meta
    },
)

@Composable
private fun TerminalDelKeyMode.label(): String = stringResource(
    when (this) {
        TerminalDelKeyMode.DELETE -> R.string.settings_shortcuts_del_key_delete
        TerminalDelKeyMode.BACKSPACE -> R.string.settings_shortcuts_del_key_backspace
    },
)
