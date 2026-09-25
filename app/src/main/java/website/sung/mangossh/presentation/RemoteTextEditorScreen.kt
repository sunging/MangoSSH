package website.sung.mangossh.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.DiffLine
import website.sung.mangossh.session.EditableRemoteText

/**
 * A stored draft found when the file was opened. [changes] compares the current remote
 * text with the draft (null when too large to show); [remoteChanged] means the file is
 * no longer the one the draft started from.
 */
internal data class RecoveredDraft(
    val text: String,
    val savedAtEpochMillis: Long,
    val remoteChanged: Boolean,
    val changes: List<DiffLine?>?,
)

/** What a Save would change, shown before anything is written; null [changes] means too large to show. */
internal data class EditorReview(val changes: List<DiffLine?>?)

/**
 * Editor state. Drafts have no SavedState representation; unsaved text is stored
 * encrypted on the device by `RemoteDraftStore` under [profileId] and the remote path.
 */
internal data class RemoteEditorUiState(
    val sessionId: String,
    val source: EditableRemoteText,
    val draft: String = source.text,
    val busy: Boolean = false,
    val conflict: Boolean = false,
    val needsDirectApproval: Boolean = false,
    val failed: Boolean = false,
    val metadataFailure: Boolean = false,
    val profileId: String? = null,
    val recovered: RecoveredDraft? = null,
    val review: EditorReview? = null,
)

/**
 * Full-size editor that preserves the draft on every conflict or unsupported replacement
 * outcome. Save first shows the changes; a recovered draft must be restored or discarded
 * before editing, so it is never silently replaced.
 */
@Composable
internal fun RemoteTextEditorScreen(state: RemoteEditorUiState, onChange: (String) -> Unit,
    onSave: (String?, Boolean) -> Unit, onReload: () -> Unit, onClose: () -> Unit,
    onReview: () -> Unit = { onSave(null, false) }, onDismissReview: () -> Unit = {},
    onRestoreDraft: () -> Unit = {}, onDiscardDraft: () -> Unit = {}) {
    var confirmReload by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDirect by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun leave() { if (!state.busy) { if (state.draft != state.source.text) confirmLeave = true else onClose() } }
    BackHandler { leave() }
    val recovered = state.recovered
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
            Text(state.source.path, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val editable = !state.busy && recovered == null
                TextButton(enabled = editable, onClick = onReview) { Text(stringResource(R.string.editor_save)) }
                TextButton(enabled = editable, onClick = { saveAs = true }) { Text(stringResource(R.string.editor_save_as)) }
                TextButton(enabled = !state.busy, onClick = { leave() }) { Text(stringResource(R.string.common_cancel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (recovered != null) RecoveredDraftCard(recovered, onRestoreDraft, onDiscardDraft)
            if (state.conflict) {
                Text(stringResource(R.string.editor_conflict))
                TextButton(enabled = !state.busy, onClick = { confirmReload = true }) { Text(stringResource(R.string.editor_reload)) }
            }
            if (state.needsDirectApproval) {
                Text(stringResource(R.string.editor_no_atomic))
                TextButton(enabled = !state.busy, onClick = { confirmDirect = true }) { Text(stringResource(R.string.editor_direct)) }
            }
            if (state.metadataFailure) Text(stringResource(R.string.editor_metadata_failure), color = MaterialTheme.colorScheme.error)
            if (state.failed) Text(stringResource(R.string.editor_failure), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(state.draft, onChange, readOnly = state.busy || recovered != null,
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
    state.review?.let { review ->
        AlertDialog(onDismissRequest = onDismissReview, title = { Text(stringResource(R.string.editor_review_title)) },
            text = { DiffView(review.changes) },
            confirmButton = { TextButton(onClick = { onSave(null, false) }) { Text(stringResource(R.string.editor_save)) } },
            dismissButton = { TextButton(onClick = onDismissReview) { Text(stringResource(R.string.common_cancel)) } })
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

@Composable
private fun RecoveredDraftCard(recovered: RecoveredDraft, onRestore: () -> Unit, onDiscard: () -> Unit) {
    var showChanges by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.editor_draft_found,
                android.text.format.DateUtils.getRelativeTimeSpanString(recovered.savedAtEpochMillis).toString()))
            if (recovered.remoteChanged) {
                Text(stringResource(R.string.editor_draft_remote_changed), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.editor_draft_restore)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.editor_discard)) }
                TextButton(onClick = { showChanges = !showChanges }) { Text(stringResource(R.string.editor_draft_show_changes)) }
            }
            if (showChanges) DiffView(recovered.changes, Modifier.heightIn(max = 240.dp))
        }
    }
}

/** Condensed line diff; null entries stand for runs of unchanged lines. */
@Composable
private fun DiffView(changes: List<DiffLine?>?, modifier: Modifier = Modifier.heightIn(max = 400.dp)) {
    when {
        changes == null -> Text(stringResource(R.string.editor_diff_too_large))
        changes.none { it != null && it.kind != DiffLine.Kind.SAME } -> Text(stringResource(R.string.editor_diff_none))
        else -> LazyColumn(modifier.fillMaxWidth()) {
            items(changes) { line ->
                val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                when (line?.kind) {
                    null -> Text("⋯", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiffLine.Kind.SAME -> Text("  " + line.text, style = style)
                    DiffLine.Kind.ADDED -> Text("+ " + line.text, style = style, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer))
                    DiffLine.Kind.REMOVED -> Text("- " + line.text, style = style, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer))
                }
            }
        }
    }
}
