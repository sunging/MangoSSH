package website.sung.mangossh.presentation

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.keys.KeyEditRequest
import website.sung.mangossh.data.keys.KeyPassphraseChange
import website.sung.mangossh.data.keys.canChangePassphrase
import website.sung.mangossh.data.vault.StoredSshKey

/** Which half of a stored key pair an export carries. */
enum class KeyExportPart { PUBLIC, PRIVATE }

/** Where an export goes once the user has picked a destination (and a file, for [File]). */
sealed interface KeyExportTarget {
    data object Clipboard : KeyExportTarget

    /** Hands the text to an app the user picks in the system share sheet. */
    data object Share : KeyExportTarget

    /** A document the user created through the system file picker. */
    class File(val uri: Uri) : KeyExportTarget
}

/** The destination chosen in [ExportKeyDialog], before a file has been picked. */
internal enum class KeyExportDestination { CLIPBOARD, FILE, SHARE }

/**
 * Offers every export of one key in a single place: pick the public or private half, then a
 * destination. The public half is preselected; the private half shows a warning, and the view
 * model still requires the configured access check before any private export runs.
 */
@Composable
internal fun ExportKeyDialog(
    key: StoredSshKey,
    onDismiss: () -> Unit,
    onSelect: (KeyExportPart, KeyExportDestination) -> Unit,
) {
    var part by rememberSaveable(key.id) { mutableStateOf(KeyExportPart.PUBLIC) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_export_key_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(key.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    key.algorithm,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                    KeyExportPart.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = part == option,
                            onClick = { part = option },
                            shape = SegmentedButtonDefaults.itemShape(index, KeyExportPart.entries.size),
                        ) {
                            Text(stringResource(if (option == KeyExportPart.PUBLIC) R.string.ui_public_key else R.string.ui_private_key))
                        }
                    }
                }
                if (part == KeyExportPart.PRIVATE) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.ui_private_key_export_warning),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                KeyExportDestination.entries.forEach { destination ->
                    val (icon, label) = when (destination) {
                        KeyExportDestination.CLIPBOARD -> Icons.Outlined.ContentCopy to R.string.ui_copy_to_clipboard
                        KeyExportDestination.FILE -> Icons.Outlined.SaveAlt to R.string.ui_export_to_file
                        KeyExportDestination.SHARE -> Icons.Outlined.Share to R.string.ui_share_to_app
                    }
                    KeyExportRow(icon, stringResource(label)) { onSelect(part, destination) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun KeyExportRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A masked passphrase field. Callers keep its value in plain `remember`, never in saved state. */
@Composable
internal fun PassphraseField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean = false,
    supportingText: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A new passphrase and its confirmation; the mismatch hint appears once the confirmation is typed. */
@Composable
internal fun NewPassphraseFields(passphrase: String, onPassphraseChange: (String) -> Unit, confirmation: String,
    onConfirmationChange: (String) -> Unit, label: String) {
    PassphraseField(passphrase, onPassphraseChange, label)
    val mismatch = confirmation.isNotEmpty() && confirmation != passphrase
    PassphraseField(confirmation, onConfirmationChange, stringResource(R.string.ui_confirm_passphrase), isError = mismatch,
        supportingText = if (mismatch) stringResource(R.string.ui_passphrases_do_not_match) else null)
}

/** Opt-in to keeping the passphrase inside the encrypted vault; the whole row toggles. */
@Composable
internal fun RememberPassphraseRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        // Material's disabled-content opacity, so the labels dim along with the checkbox.
        Column(Modifier.weight(1f).padding(start = 8.dp).alpha(if (enabled) 1f else 0.38f)) {
            Text(stringResource(R.string.ui_remember_passphrase), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.ui_remember_passphrase_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the edit dialog does with an already encrypted key. */
private enum class EncryptedKeyAction { KEEP, CHANGE, REMOVE }

/**
 * Renames a stored key and changes or remembers its passphrase. Passphrases stay in plain
 * `remember`, so they never reach the saved instance state; only the key id and non-secret
 * choices survive recreation. Formats that cannot be re-encrypted offer only a rename.
 */
@Composable
internal fun EditKeyDialog(
    key: StoredSshKey,
    onDismiss: () -> Unit,
    onConfirm: (KeyEditRequest) -> Unit,
) {
    val canChange = remember(key.privateKeyPem, key.algorithm) { key.canChangePassphrase() }
    val encrypted = key.requiresPassphrase
    var label by rememberSaveable(key.id) { mutableStateOf(key.label) }
    var action by rememberSaveable(key.id) { mutableStateOf(EncryptedKeyAction.KEEP) }
    var protect by rememberSaveable(key.id) { mutableStateOf(false) }
    var rememberPassphrase by rememberSaveable(key.id) { mutableStateOf(key.savedPassphrase != null) }
    var current by remember(key.id) { mutableStateOf("") }
    var newPassphrase by remember(key.id) { mutableStateOf("") }
    var confirmation by remember(key.id) { mutableStateOf("") }

    val encryptedAfter = if (encrypted) action != EncryptedKeyAction.REMOVE else protect
    val needsNew = canChange && if (encrypted) action == EncryptedKeyAction.CHANGE else protect
    // Decryption is needed to re-encode, or to prove a passphrase before remembering it.
    val needsCurrent = canChange && encrypted && key.savedPassphrase == null &&
        (action != EncryptedKeyAction.KEEP || rememberPassphrase)
    val valid = (!needsNew || (newPassphrase.isNotEmpty() && newPassphrase == confirmation)) &&
        (!needsCurrent || current.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_edit_key)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ui_key_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (!canChange) {
                    Text(stringResource(R.string.ui_key_passphrase_cannot_change), style = MaterialTheme.typography.bodySmall)
                } else {
                    if (encrypted) {
                        Column {
                            EncryptedKeyAction.entries.forEach { option ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .selectable(selected = action == option, role = Role.RadioButton) { action = option },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = action == option, onClick = null)
                                    Text(
                                        stringResource(
                                            when (option) {
                                                EncryptedKeyAction.KEEP -> R.string.ui_passphrase_keep
                                                EncryptedKeyAction.CHANGE -> R.string.ui_passphrase_change
                                                EncryptedKeyAction.REMOVE -> R.string.ui_passphrase_remove
                                            },
                                        ),
                                        Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = protect, role = Role.Switch) { protect = it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.ui_protect_with_passphrase), Modifier.weight(1f))
                            Switch(checked = protect, onCheckedChange = null)
                        }
                    }
                    if (needsCurrent) {
                        PassphraseField(current, { current = it }, stringResource(R.string.ui_current_passphrase))
                    }
                    if (needsNew) {
                        NewPassphraseFields(newPassphrase, { newPassphrase = it }, confirmation, { confirmation = it },
                            stringResource(R.string.ui_new_passphrase))
                    }
                    // Only an encrypted result has a passphrase to remember.
                    if (encryptedAfter) {
                        RememberPassphraseRow(rememberPassphrase, enabled = true) { rememberPassphrase = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val change = when {
                        needsNew -> KeyPassphraseChange.Set(newPassphrase)
                        canChange && encrypted && action == EncryptedKeyAction.REMOVE -> KeyPassphraseChange.Remove
                        else -> KeyPassphraseChange.Keep
                    }
                    onConfirm(
                        KeyEditRequest(
                            label = label.trim(),
                            currentPassphrase = current.takeIf { needsCurrent && it.isNotEmpty() },
                            passphrase = change,
                            rememberPassphrase = if (canChange) rememberPassphrase && encryptedAfter else key.savedPassphrase != null,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
