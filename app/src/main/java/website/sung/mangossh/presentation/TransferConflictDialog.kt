package website.sung.mangossh.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** Decisions contain no persistent authorization and direct replacement needs a separate confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferConflictDialog(
    conflict: TransferConflict,
    modifier: Modifier = Modifier,
    resolve: (TransferConflictDecision) -> Unit,
) {
    val contentDensity = LocalDensity.current
    val context = LocalContext.current
    val directoryPreview = conflict.promptKind == TransferPromptKind.DIRECTORY_PREVIEW
    val download = conflict.direction == ScpTransferDirection.DOWNLOAD
    val singleUpload = conflict.direction == ScpTransferDirection.UPLOAD && conflict.taskKind == ScpTransferKind.FILE
    var applyAll by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(conflict.verifySha256) }
    var direct by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun choose(action: TransferConflictAction, alternate: String? = null, uri: String? = null, createdDocument: Boolean = false) {
        resolve(TransferConflictDecision(action, alternate, uri, applyAll, verify, localDocumentCreated = createdDocument))
    }
    val picker = rememberLauncherForActivityResult(DownloadDocumentContract()) { uri ->
        if (uri != null) choose(TransferConflictAction.SAVE_AS, uri = uri.toString(), createdDocument = true)
    }
    BasicAlertDialog(onDismissRequest = {}) {
        // Dialog creates a separate Android view; retain the caller's scoped font scaling.
        CompositionLocalProvider(LocalDensity provides contentDensity) {
            Surface(
                modifier = modifier,
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text(stringResource(when {
                        !directoryPreview -> R.string.transfer_preview_title
                        download -> R.string.transfer_directory_download_preview_title
                        else -> R.string.transfer_directory_preview_title
                    }),
                        style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                    Spacer(Modifier.height(16.dp))
                    Text(conflict.name, style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor)
                    if (directoryPreview) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.transfer_directory_destination, conflict.destinationDirectory.orEmpty()))
                        Text(stringResource(R.string.transfer_directory_files, conflict.totalFiles ?: 0))
                        conflict.totalDirectories?.let { Text(stringResource(R.string.transfer_directory_folders, it)) }
                        Text(if (conflict.totalBytes == null) stringResource(R.string.transfer_directory_size_unknown)
                            else stringResource(R.string.transfer_directory_size, formatByteSize(context, conflict.totalBytes)))
                    }
                    if (conflict.targetExists) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(when {
                            conflict.atomicReplace -> R.string.transfer_target_exists
                            conflict.direction == ScpTransferDirection.DOWNLOAD -> R.string.transfer_local_no_atomic
                            else -> R.string.editor_no_atomic
                        }),
                            style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor)
                    }
                    Spacer(Modifier.height(16.dp))
                    TransferOptionRow(verify, { verify = it }, stringResource(R.string.transfer_verify_sha256))
                    if (!directoryPreview && !singleUpload) {
                        TransferOptionRow(applyAll, { applyAll = it }, stringResource(R.string.transfer_apply_task))
                    }
                    Spacer(Modifier.height(24.dp))
                    FlowRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            choose(if (directoryPreview || singleUpload) TransferConflictAction.CANCEL else TransferConflictAction.SKIP)
                        }) { Text(stringResource(if (directoryPreview || singleUpload) R.string.common_cancel else R.string.transfer_skip)) }
                        if (directoryPreview) {
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { choose(TransferConflictAction.PROCEED) }) {
                                Text(stringResource(if (download) R.string.transfer_start_download else R.string.transfer_start_upload))
                            }
                        } else {
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                if (conflict.direction == ScpTransferDirection.DOWNLOAD) picker.launch(conflict.name) else saveAs = true
                            }) { Text(stringResource(R.string.editor_save_as)) }
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                if (conflict.targetExists && !conflict.atomicReplace) direct = true else choose(TransferConflictAction.REPLACE)
                            }) { Text(stringResource(if (conflict.targetExists && !conflict.atomicReplace) R.string.editor_direct else R.string.editor_save)) }
                        }
                    }
                }
            }
        }
    }
    if (direct) AlertDialog(onDismissRequest = { direct = false }, title = { Text(stringResource(R.string.editor_direct)) },
        text = { Text(stringResource(R.string.editor_direct_risk)) },
        confirmButton = { TextButton(onClick = { choose(TransferConflictAction.DIRECT_OVERWRITE) }) { Text(stringResource(R.string.editor_direct)) } },
        dismissButton = { TextButton(onClick = { direct = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (saveAs) AlertDialog(onDismissRequest = { saveAs = false }, title = { Text(stringResource(R.string.editor_save_as)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_new_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && '/' !in name && name != "." && name != "..", onClick = { choose(TransferConflictAction.SAVE_AS, name) }) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = { saveAs = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** One checkbox semantics node and touch target covers both the indicator and its wrapping label. */
@Composable
private fun TransferOptionRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor)
    }
}
