package website.sung.mangossh.presentation.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.*
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.MangoSettingsCard
import java.text.DateFormat
import java.util.Date

/** Backup operation UI holds only opaque handles and short-lived password input. */
@Composable
internal fun BackupSettingsPage(state: BackupSettingsState, callbacks: BackupSettingsCallbacks, modifier: Modifier = Modifier) {
    val operation = state.operation
    val busy = operation.phase != BackupPhase.IDLE
    val enabled = state.vaultStatus is VaultStatus.Ready && !busy && operation.preview == null && !operation.remoteConflict && !operation.exportReady
    var action by remember { mutableStateOf<BackupAction?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var historyEntry by remember { mutableStateOf<BackupHistoryEntry?>(null) }
    var editWebDav by remember { mutableStateOf(false) }
    var confirmRemoveWebDav by remember { mutableStateOf(false) }
    var launchedExport by remember { mutableStateOf(false) }
    var remoteHistory by remember { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; action = BackupAction.IMPORT }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        callbacks.onWriteExport(uri)
    }
    LaunchedEffect(Unit) { callbacks.onRefresh() }
    DisposableEffect(Unit) { onDispose { callbacks.onCancel() } }
    LaunchedEffect(operation.exportReady, busy) {
        if (operation.exportReady && !busy && !launchedExport) {
            launchedExport = true
            exportPicker.launch("mangossh-vault.mssh")
        }
        if (!operation.exportReady) launchedExport = false
    }
    Column(modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy || operation.failure != null || operation.completed) {
                MangoSettingsCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("backup_status")) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(when (operation.phase) {
                            BackupPhase.READING -> R.string.backup_reading
                            BackupPhase.DECRYPTING -> R.string.backup_decrypting
                            BackupPhase.UPLOADING -> R.string.backup_uploading
                            else -> R.string.backup_saving
                        }), style = MaterialTheme.typography.bodyMedium)
                    }
                    operation.failure?.let { Text(stringResource(failureText(it)), color = MaterialTheme.colorScheme.error) }
                    if (operation.completed) Text(stringResource(R.string.backup_completed))
                }
            }
            BackupSection(R.string.backup_file_section) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BackupSupportingText(R.string.backup_file_detail)
                    BackupPrimaryActions(R.string.backup_export_action, R.string.backup_import_action, enabled,
                        { action = BackupAction.EXPORT }, { importPicker.launch(arrayOf("*/*")) })
                }
                HorizontalDivider()
                BackupActionRow(R.string.backup_local_history, enabled) { remoteHistory = false; callbacks.onHistory(false) }
                if (operation.rememberedManual) BackupActionRow(R.string.backup_forget_manual, enabled) { callbacks.onForget(false) }
            }
            BackupSection(R.string.backup_webdav_section) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (state.webDavConfig == null) R.string.ui_not_configured else R.string.backup_configured),
                        style = MaterialTheme.typography.titleMedium)
                    BackupSupportingText(R.string.backup_webdav_detail)
                    if (state.webDavConfig == null) {
                        Button(onClick = { editWebDav = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ui_configure_webdav))
                        }
                    } else {
                        BackupPrimaryActions(R.string.backup_upload_action, R.string.ui_download_and_import, enabled,
                            { action = BackupAction.UPLOAD }, { action = BackupAction.DOWNLOAD })
                    }
                }
                if (state.webDavConfig != null) {
                    HorizontalDivider()
                    BackupActionRow(R.string.backup_remote_history, enabled) { remoteHistory = true; callbacks.onHistory(true) }
                    BackupActionRow(R.string.backup_edit_webdav, enabled) { editWebDav = true }
                    if (operation.rememberedRemote) BackupActionRow(R.string.backup_forget_remote, enabled) { callbacks.onForget(true) }
                    HorizontalDivider()
                    BackupActionRow(R.string.backup_remove_webdav, enabled, destructive = true) { confirmRemoveWebDav = true }
                }
            }
        }
    }
    if (confirmRemoveWebDav && state.webDavConfig != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveWebDav = false },
            title = { Text(stringResource(R.string.backup_remove_webdav)) },
            text = { Text(stringResource(R.string.backup_remove_webdav_detail), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    confirmRemoveWebDav = false
                    callbacks.onClearWebDav()
                }) {
                    Text(stringResource(R.string.common_remove), color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveWebDav = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (editWebDav) WebDavEditor(state.webDavConfig, { editWebDav = false }) { endpoint, username, password, file ->
        callbacks.onSaveWebDav(endpoint, username, password, file); editWebDav = false
    }
    action?.let { selected ->
        PasswordDialog(selected, if (selected.remote) operation.rememberedRemote else operation.rememberedManual,
            onDismiss = { action = null; importUri = null; historyEntry = null },
        ) { password, rememberPassword, includeConfig ->
            when (selected) {
                BackupAction.EXPORT -> callbacks.onPrepareExport(password, rememberPassword, includeConfig)
                BackupAction.IMPORT -> importUri?.let { callbacks.onImport(it, password, rememberPassword) }
                BackupAction.UPLOAD -> callbacks.onUpload(password, rememberPassword)
                BackupAction.DOWNLOAD -> callbacks.onDownloadAndImport(password, rememberPassword)
                BackupAction.HISTORY -> historyEntry?.let { callbacks.onRestore(it, password) }
            }
            action = null; importUri = null; historyEntry = null
        }
    }
    operation.preview?.let { preview -> ImportDialog(preview, busy, operation.failure, callbacks.onCancel, callbacks.onCommit) }
    if (operation.remoteConflict) RemoteBackupConflictDialog(
        busy = busy,
        onDownload = { callbacks.onCancel(); action = BackupAction.DOWNLOAD },
        onOverwrite = callbacks.onConfirmUpload,
        onCancel = callbacks.onCancel,
    )
    operation.history?.let { entries ->
        AlertDialog(onDismissRequest = callbacks.onCancel,
            title = { Text(stringResource(if (entries.firstOrNull()?.remote ?: remoteHistory) R.string.backup_remote_history else R.string.backup_local_history)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BackupSupportingText(R.string.backup_history_limit)
                    if (entries.isEmpty()) Text(stringResource(R.string.backup_history_empty), Modifier.padding(vertical = 16.dp))
                    entries.forEach { entry ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(DateFormat.getDateTimeInstance().format(Date(entry.createdAt)),
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.Button, onClick = {
                                    if (entry.remote) {
                                        historyEntry = entry
                                        action = BackupAction.HISTORY
                                        callbacks.onCancel()
                                    } else {
                                        callbacks.onRestore(entry, null)
                                    }
                                }).padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun BackupSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MangoSectionHeader(stringResource(title), Modifier.padding(start = 16.dp).semantics { heading() })
        MangoPreferenceGroup(verticalPadding = 0.dp, content = content)
    }
}

@Composable
private fun BackupSupportingText(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Uses the detail pane's available width, not the window width, for predictable action placement. */
@Composable
private fun BackupPrimaryActions(primary: Int, secondary: Int, enabled: Boolean, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val primaryButton: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onPrimary, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(stringResource(primary)) }
        }
        val secondaryButton: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = onSecondary, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(stringResource(secondary)) }
        }
        if (maxWidth >= 360.dp && fontScale <= 1.3f) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryButton(Modifier.weight(1f).fillMaxHeight())
                secondaryButton(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryButton(Modifier.fillMaxWidth())
                secondaryButton(Modifier.fillMaxWidth())
            }
        }
    }
}

/** A full-width action keeps long translations and large text out of cramped trailing buttons. */
@Composable
private fun BackupActionRow(label: Int, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(stringResource(label),
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
}

/** Keeps all three choices in one scrollable layout, including on short screens and large fonts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteBackupConflictDialog(busy: Boolean, onDownload: () -> Unit, onOverwrite: () -> Unit, onCancel: () -> Unit) {
    BasicAlertDialog(onDismissRequest = { if (!busy) onCancel() }) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(stringResource(R.string.backup_remote_changed),
                    style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.backup_remote_changed_detail),
                    style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor)
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = onDownload) { Text(stringResource(R.string.backup_download_merge)) }
                    TextButton(enabled = !busy, onClick = onOverwrite) { Text(stringResource(R.string.backup_overwrite)) }
                    TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        }
    }
}

private enum class BackupAction(val remote: Boolean = false, val creating: Boolean = false) {
    EXPORT(creating = true), IMPORT, UPLOAD(remote = true, creating = true), DOWNLOAD(remote = true), HISTORY(remote = true),
}

@Composable
private fun PasswordDialog(action: BackupAction, remembered: Boolean, onDismiss: () -> Unit, onConfirm: (String?, Boolean, Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var useSaved by remember { mutableStateOf(remembered && action != BackupAction.HISTORY) }
    var rememberPassword by remember { mutableStateOf(false) }
    var includeConfig by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(when (action) {
            BackupAction.EXPORT -> R.string.backup_export_action
            BackupAction.IMPORT -> R.string.backup_import_action
            BackupAction.UPLOAD -> R.string.backup_upload_action
            BackupAction.DOWNLOAD -> R.string.ui_download_and_import
            BackupAction.HISTORY -> R.string.backup_restore_history
        })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupSupportingText(if (action.creating) R.string.backup_password_create_detail else R.string.backup_password_open_detail)
                if (remembered) CheckLine(stringResource(R.string.backup_use_saved), useSaved) { useSaved = it }
                if (!useSaved) {
                    PasswordField(password, { password = it }, R.string.backup_passphrase)
                    if (action.creating) PasswordField(confirmation, { confirmation = it }, R.string.backup_confirm_password)
                }
                if ((!useSaved && action != BackupAction.HISTORY) || action == BackupAction.EXPORT) HorizontalDivider()
                if (!useSaved && action != BackupAction.HISTORY) CheckLine(stringResource(R.string.backup_remember), rememberPassword) { rememberPassword = it }
                if (action == BackupAction.EXPORT) CheckLine(stringResource(R.string.backup_include_config), includeConfig) { includeConfig = it }
            }
        },
        confirmButton = { TextButton(enabled = useSaved || (password.isNotEmpty() && (!action.creating || password == confirmation)), onClick = {
            onConfirm(if (useSaved) null else password, rememberPassword && !useSaved, includeConfig)
            password = ""; confirmation = ""
        }) { Text(stringResource(R.string.common_continue)) } },
        dismissButton = { TextButton(onClick = { password = ""; confirmation = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ImportDialog(preview: ImportPreview, busy: Boolean, failure: BackupFailure?, onDismiss: () -> Unit, onConfirm: (ImportDecision) -> Unit) {
    var replace by remember(preview.operationId) { mutableStateOf(false) }
    var config by remember(preview.operationId) { mutableStateOf(false) }
    var incoming by remember(preview.operationId) { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.backup_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogSectionHeading(R.string.backup_information)
                Text(preview.metadata?.let { DateFormat.getDateTimeInstance().format(Date(it.createdAt)) } ?: stringResource(R.string.backup_unknown_date),
                    style = MaterialTheme.typography.bodyMedium)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(stringResource(R.string.backup_counts, preview.added, preview.unchanged, preview.conflicts.size),
                        Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                failure?.let { Text(stringResource(failureText(it)), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                DialogSectionHeading(R.string.backup_import_mode)
                CheckLine(stringResource(R.string.backup_replace), replace, !busy) { replace = it }
                if (replace) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.backup_remove_count, preview.removedByReplacement),
                        Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                BackupSupportingText(R.string.backup_merge_detail)
                if (preview.hasWebDavConfig) CheckLine(stringResource(R.string.backup_restore_config), config, !busy) { config = it }
                listOf(false, true).forEach { trust ->
                    val conflicts = preview.conflicts.filter { it.trust == trust && (!replace || trust) }
                    if (conflicts.isNotEmpty()) {
                        HorizontalDivider()
                        DialogSectionHeading(if (trust) R.string.backup_trust_conflicts else R.string.backup_data_conflicts)
                        if (!trust) TextButton(enabled = !busy, onClick = {
                            incoming = incoming + conflicts.map { it.token }
                        }) { Text(stringResource(R.string.backup_all_incoming)) }
                        conflicts.forEach { conflict ->
                            Column {
                                if (conflict.label.isNotEmpty()) Text(conflict.label, style = MaterialTheme.typography.titleSmall)
                                val kind = stringResource(when (conflict.kind) {
                                    "profile" -> R.string.backup_kind_profile
                                    "key" -> R.string.backup_kind_key
                                    "snippet" -> R.string.backup_kind_snippet
                                    "forward" -> R.string.backup_kind_forward
                                    else -> R.string.backup_kind_trust
                                })
                                CheckLine(stringResource(R.string.backup_conflict_item, kind, conflict.ordinal), conflict.token in incoming, !busy) {
                                    incoming = if (it) incoming + conflict.token else incoming - conflict.token
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { onConfirm(ImportDecision(replace, incoming, config, preview.operationId)) }) { Text(stringResource(R.string.backup_apply)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DialogSectionHeading(resource: Int) {
    Text(stringResource(resource), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

/** The label and checkbox form a single accessible toggle with a minimum 48 dp touch target. */
@Composable
private fun CheckLine(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
}

@Composable
private fun WebDavEditor(initial: WebDavConfig?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var filename by remember { mutableStateOf(initial?.remoteFileName ?: "mangossh-vault.mssh") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ui_configure_webdav)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackupSupportingText(R.string.ui_only_https_webdav_urls_are_accepted_the_url_should_point_to_the_director)
            OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_webdav_directory_url)) }, singleLine = true)
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_username)) }, singleLine = true)
            PasswordField(password, { password = it }, R.string.backup_webdav_password)
            OutlinedTextField(filename, { filename = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_remote_file_name)) }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onSave(endpoint, username, password, filename); password = "" }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun failureText(failure: BackupFailure): Int = when (failure) {
    BackupFailure.VERSION -> R.string.backup_error_version
    BackupFailure.INVALID -> R.string.backup_error_invalid
    BackupFailure.TOO_LARGE -> R.string.backup_error_size
    BackupFailure.AUTHENTICATION -> R.string.backup_error_password
    BackupFailure.STORAGE -> R.string.backup_error_storage
    BackupFailure.CHANGED -> R.string.backup_error_changed
    BackupFailure.LOCKED -> R.string.backup_error_locked
    BackupFailure.NETWORK -> R.string.backup_error_network
    BackupFailure.UNSAFE_SERVER -> R.string.backup_error_server
    BackupFailure.PARTIAL -> R.string.backup_error_partial
}
