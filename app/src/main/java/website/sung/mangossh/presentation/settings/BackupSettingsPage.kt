package website.sung.mangossh.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.ui.components.MangoSettingsCard

/**
 * Backup & sync detail page: encrypted local export/import and custom WebDAV.
 *
 * [portableExport] is a one-shot decrypted payload; when it changes to
 * non-null this page immediately hands it to the SAF "create document"
 * launcher and then reports it consumed, regardless of whether the user
 * completes or cancels the system picker.
 */
@Composable
internal fun BackupSettingsPage(
    state: BackupSettingsState,
    portableExport: ByteArray?,
    callbacks: BackupSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val vaultStatus = state.vaultStatus
    val webDavConfig = state.webDavConfig
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWebDavEditor by rememberSaveable { mutableStateOf(false) }
    var syncAction by remember { mutableStateOf<SyncAction?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pendingImport = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                }
                if (pendingImport != null) syncAction = SyncAction.MANUAL_IMPORT
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = portableExport
        callbacks.onConsumeExport()
        if (uri != null && data != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(data) }
                }
            }
        }
    }
    LaunchedEffect(portableExport) {
        if (portableExport != null) exportLauncher.launch("mangossh-vault.mssh")
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoSettingsCard {
                Text(stringResource(R.string.ui_encrypted_backup), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { syncAction = SyncAction.MANUAL_EXPORT },
                        enabled = vaultStatus !is VaultStatus.Failed,
                    ) { Text(stringResource(R.string.ui_export)) }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/plain")) },
                        enabled = vaultStatus !is VaultStatus.Failed,
                    ) { Text(stringResource(R.string.ui_import)) }
                }
            }
        }
        item {
            MangoSettingsCard {
                Text(stringResource(R.string.ui_custom_webdav), style = MaterialTheme.typography.titleMedium)
                Text(
                    webDavConfig?.let { "${it.endpoint}/${it.remoteFileName}" } ?: stringResource(R.string.ui_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showWebDavEditor = true }) {
                        Text(if (webDavConfig == null) stringResource(R.string.ui_configure) else stringResource(R.string.common_edit))
                    }
                    if (webDavConfig != null) {
                        Button(onClick = { syncAction = SyncAction.WEBDAV_UPLOAD }) { Text(stringResource(R.string.common_upload)) }
                        Button(onClick = { syncAction = SyncAction.WEBDAV_DOWNLOAD }) { Text(stringResource(R.string.ui_download_and_import)) }
                        TextButton(onClick = callbacks.onClearWebDav) { Text(stringResource(R.string.common_remove)) }
                    }
                }
            }
        }
    }

    if (showWebDavEditor) {
        WebDavConfigDialog(
            initial = webDavConfig,
            onDismiss = { showWebDavEditor = false },
            onSave = { endpoint, username, password, remoteFileName ->
                callbacks.onSaveWebDav(endpoint, username, password, remoteFileName)
                showWebDavEditor = false
            },
        )
    }
    syncAction?.let { action ->
        SyncPassphraseDialog(
            action = action,
            onDismiss = { syncAction = null },
            onConfirm = { passphrase ->
                when (action) {
                    SyncAction.MANUAL_EXPORT -> callbacks.onPrepareExport(passphrase)
                    SyncAction.MANUAL_IMPORT -> pendingImport?.let { bytes -> callbacks.onImport(bytes, passphrase) }
                    SyncAction.WEBDAV_UPLOAD -> callbacks.onUpload(passphrase)
                    SyncAction.WEBDAV_DOWNLOAD -> callbacks.onDownloadAndImport(passphrase)
                }
                pendingImport = null
                syncAction = null
            },
        )
    }
}

private enum class SyncAction(
    @StringRes val titleResource: Int,
    @StringRes val warningResource: Int,
) {
    MANUAL_EXPORT(
        R.string.ui_export_encrypted_backup,
        R.string.ui_this_passphrase_protects_the_exported_backup_file,
    ),
    MANUAL_IMPORT(
        R.string.ui_import_encrypted_backup,
        R.string.ui_importing_replaces_the_current_hosts_keys_snippets_and_sync_configuratio,
    ),
    WEBDAV_UPLOAD(
        R.string.ui_upload_to_webdav,
        R.string.ui_the_backup_will_be_encrypted_with_this_passphrase_before_upload,
    ),
    WEBDAV_DOWNLOAD(
        R.string.ui_import_from_webdav,
        R.string.ui_after_download_the_backup_will_be_decrypted_with_this_passphrase_and_rep,
    ),
}

@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
) {
    var endpoint by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var username by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable(initial?.endpoint) { mutableStateOf(initial?.password.orEmpty()) }
    var remoteFileName by rememberSaveable(initial?.endpoint) {
        mutableStateOf(initial?.remoteFileName ?: "mangossh-vault.mssh")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_configure_webdav)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.ui_only_https_webdav_urls_are_accepted_the_url_should_point_to_the_director), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(stringResource(R.string.ui_webdav_directory_url)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.ui_username)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ui_password_app_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = remoteFileName,
                    onValueChange = { remoteFileName = it },
                    label = { Text(stringResource(R.string.ui_remote_file_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(endpoint, username, password, remoteFileName) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SyncPassphraseDialog(
    action: SyncAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by rememberSaveable(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.titleResource)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(action.warningResource), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ui_sync_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotEmpty()) { Text(stringResource(R.string.common_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
