package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.AppLockDelay
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.MangoSettingsCard
import website.sung.mangossh.ui.components.SettingsChoiceRow
import website.sung.mangossh.ui.components.SettingsSwitchRow

/** Security & lock detail page: app PIN, lock-now, biometric unlock, and auto-lock delay. */
@Composable
internal fun SecuritySettingsPage(
    state: SecuritySettingsState,
    callbacks: SecuritySettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    var showPinEditor by rememberSaveable { mutableStateOf(false) }
    val lock = state.lock

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoSectionHeader(
                text = stringResource(R.string.ui_app_protection),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            MangoSettingsCard {
                Text(
                    if (lock.pinConfigured) {
                        stringResource(R.string.ui_app_pin_is_enabled_unlocking_is_required_when_returning_to_the_foregroun)
                    } else {
                        stringResource(R.string.ui_no_app_pin_is_set_once_enabled_returning_to_the_foreground_requires_pin)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPinEditor = true }) {
                        Text(if (lock.pinConfigured) stringResource(R.string.ui_change_pin) else stringResource(R.string.ui_set_pin))
                    }
                    if (lock.pinConfigured) {
                        Button(onClick = callbacks.onLockNow) { Text(stringResource(R.string.ui_lock_now)) }
                        TextButton(onClick = callbacks.onClearAppLock) { Text(stringResource(R.string.common_close)) }
                    }
                }
            }
        }
        if (lock.pinConfigured) {
            item {
                MangoPreferenceGroup {
                    SettingsSwitchRow(
                        title = stringResource(R.string.ui_allow_biometric_unlock),
                        checked = lock.biometricEnabled,
                        onCheckedChange = callbacks.onSetBiometricEnabled,
                    )
                    SettingsChoiceRow(
                        label = stringResource(R.string.settings_security_auto_lock_title),
                        options = AppLockDelay.entries,
                        selected = lock.autoLockDelay,
                        optionLabel = { it.label() },
                        onSelect = callbacks.onSetAutoLockDelay,
                        supportingText = stringResource(R.string.settings_security_auto_lock_summary),
                        optionTestTag = { "settings_auto_lock_${it.preferenceValue}" },
                        modifier = Modifier.testTag("settings_auto_lock_control"),
                    )
                }
            }
        }
    }

    if (showPinEditor) {
        AppPinDialog(
            onDismiss = { showPinEditor = false },
            onConfirm = { pin ->
                callbacks.onConfigurePin(pin)
                showPinEditor = false
            },
        )
    }
}

@Composable
private fun AppLockDelay.label(): String = stringResource(
    when (this) {
        AppLockDelay.IMMEDIATELY -> R.string.settings_security_auto_lock_immediately
        AppLockDelay.THIRTY_SECONDS -> R.string.settings_security_auto_lock_30_seconds
        AppLockDelay.ONE_MINUTE -> R.string.settings_security_auto_lock_1_minute
        AppLockDelay.FIVE_MINUTES -> R.string.settings_security_auto_lock_5_minutes
    },
)

@Composable
private fun AppPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val valid = pin.length in 4..12 && pin.all(Char::isDigit) && pin == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_set_app_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_the_pin_is_stored_only_on_this_device_as_a_salted_verifier_and_cannot_be), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.ui_4_12_digit_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.ui_confirm_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmation.isNotEmpty() && confirmation != pin,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
