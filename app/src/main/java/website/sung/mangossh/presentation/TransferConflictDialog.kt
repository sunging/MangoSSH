package website.sung.mangossh.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** Decisions contain no persistent authorization and direct replacement needs a separate confirmation. */
@Composable
internal fun TransferConflictDialog(conflict: TransferConflict, resolve: (TransferConflictDecision) -> Unit) {
    var applyAll by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun choose(action: TransferConflictAction, alternate: String? = null, uri: String? = null) {
        resolve(TransferConflictDecision(action, alternate, uri, applyAll, verify))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) choose(TransferConflictAction.SAVE_AS, uri = uri.toString())
    }
    AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.transfer_preview_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(conflict.name)
            if (conflict.targetExists) Text(stringResource(if (conflict.atomicReplace) R.string.transfer_target_exists else R.string.editor_no_atomic))
            Row { Checkbox(verify, { verify = it }); Text(stringResource(R.string.transfer_verify_sha256)) }
            Row { Checkbox(applyAll, { applyAll = it }); Text(stringResource(R.string.transfer_apply_task)) }
            TextButton(onClick = { choose(TransferConflictAction.SKIP) }) { Text(stringResource(R.string.transfer_skip)) }
            TextButton(onClick = {
                if (conflict.direction == ScpTransferDirection.DOWNLOAD) picker.launch(conflict.name) else saveAs = true
            }) { Text(stringResource(R.string.editor_save_as)) }
        } },
        confirmButton = { TextButton(onClick = {
            if (conflict.targetExists && !conflict.atomicReplace) direct = true else choose(TransferConflictAction.REPLACE)
        }) { Text(stringResource(if (conflict.targetExists && !conflict.atomicReplace) R.string.editor_direct else R.string.editor_save)) } })
    if (direct) AlertDialog(onDismissRequest = { direct = false }, title = { Text(stringResource(R.string.editor_direct)) },
        text = { Text(stringResource(R.string.editor_direct_risk)) },
        confirmButton = { TextButton(onClick = { choose(TransferConflictAction.DIRECT_OVERWRITE) }) { Text(stringResource(R.string.editor_direct)) } },
        dismissButton = { TextButton(onClick = { direct = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (saveAs) AlertDialog(onDismissRequest = { saveAs = false }, title = { Text(stringResource(R.string.editor_save_as)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_new_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && '/' !in name && name != "." && name != "..", onClick = { choose(TransferConflictAction.SAVE_AS, name) }) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = { saveAs = false }) { Text(stringResource(R.string.common_cancel)) } })
}
