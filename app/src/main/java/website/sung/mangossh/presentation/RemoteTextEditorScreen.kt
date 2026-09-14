package website.sung.mangossh.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.EditableRemoteText

/** Drafts deliberately have no SavedState or persistence representation. */
internal data class RemoteEditorUiState(
    val sessionId: String,
    val source: EditableRemoteText,
    val draft: String = source.text,
    val busy: Boolean = false,
    val conflict: Boolean = false,
    val needsDirectApproval: Boolean = false,
    val failed: Boolean = false,
)

/** Full-size editor preserves the draft on every conflict or unsupported replacement outcome. */
@Composable
internal fun RemoteTextEditorScreen(state: RemoteEditorUiState, onChange: (String) -> Unit,
    onSave: (String?, Boolean) -> Unit, onReload: () -> Unit, onClose: () -> Unit) {
    var confirmReload by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDirect by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun leave() { if (!state.busy) { if (state.draft != state.source.text) confirmLeave = true else onClose() } }
    BackHandler { leave() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
            Text(state.source.path, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !state.busy, onClick = { onSave(null, false) }) { Text(stringResource(R.string.editor_save)) }
                TextButton(enabled = !state.busy, onClick = { saveAs = true }) { Text(stringResource(R.string.editor_save_as)) }
                TextButton(enabled = !state.busy, onClick = { leave() }) { Text(stringResource(R.string.common_cancel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.conflict) {
                Text(stringResource(R.string.editor_conflict))
                TextButton(enabled = !state.busy, onClick = { confirmReload = true }) { Text(stringResource(R.string.editor_reload)) }
            }
            if (state.needsDirectApproval) {
                Text(stringResource(R.string.editor_no_atomic))
                TextButton(enabled = !state.busy, onClick = { confirmDirect = true }) { Text(stringResource(R.string.editor_direct)) }
            }
            if (state.failed) Text(stringResource(R.string.editor_failure), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(state.draft, onChange, readOnly = state.busy, modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text(stringResource(R.string.editor_unsaved)) },
        text = { Text(stringResource(R.string.editor_discard_detail)) },
        confirmButton = { TextButton(onClick = { confirmReload = false; onReload() }) { Text(stringResource(R.string.editor_reload)) } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.editor_unsaved)) },
        text = { Text(stringResource(R.string.editor_discard_detail)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmDirect) AlertDialog(onDismissRequest = { confirmDirect = false }, title = { Text(stringResource(R.string.editor_direct)) },
        text = { Text(stringResource(R.string.editor_direct_risk)) },
        confirmButton = { TextButton(onClick = { confirmDirect = false; onSave(null, true) }) { Text(stringResource(R.string.editor_direct)) } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (saveAs) AlertDialog(onDismissRequest = { saveAs = false }, title = { Text(stringResource(R.string.editor_save_as)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_new_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { saveAs = false; onSave(name, false) }) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = { saveAs = false }) { Text(stringResource(R.string.common_cancel)) } })
}
