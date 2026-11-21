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
import website.sung.mangossh.session.MAX_REMOTE_PREVIEW_BYTES
import website.sung.mangossh.session.RemoteWriteNames

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
    val instance: Long = 0,
    val revision: Long = 0,
    val encodedBytes: Int = source.encodedSize(draft),
    val inputRejected: Boolean = false,
    val disconnected: Boolean = false,
    val invalidName: Boolean = false,
    val draftPersistence: DraftPersistence = DraftPersistence.NONE,
    val lastDraftSavedAt: Long? = null,
) {
    val identity: RemoteEditorIdentity get() = RemoteEditorIdentity(sessionId, source.path, instance)
    val canSave: Boolean get() = !busy && !disconnected && recovered == null && encodedBytes <= MAX_REMOTE_PREVIEW_BYTES
}

/**
 * Full-size editor that preserves the draft on every conflict or unsupported replacement
 * outcome. Save first shows the changes; a recovered draft must be restored or discarded
 * before editing, so it is never silently replaced.
 */
@Composable
internal fun RemoteTextEditorScreen(state: RemoteEditorUiState, onChange: (String) -> Unit,
    onSave: (String?, Boolean) -> Unit, onReload: () -> Unit, onClose: () -> Unit,
    onReview: () -> Unit = { onSave(null, false) }, onDismissReview: () -> Unit = {},
    onRestoreDraft: () -> Unit = {}, onDiscardDraft: () -> Unit = {}, onRetryDraft: () -> Unit = {},
    onKeepAndClose: () -> Unit = {}, onDiscardAndClose: () -> Unit = onClose) {
    var confirmReload by remember(state.identity, state.revision) { mutableStateOf(false) }
    var confirmLeave by remember(state.identity, state.revision) { mutableStateOf(false) }
    var confirmDirect by remember(state.identity, state.revision) { mutableStateOf(false) }
    var saveAs by remember(state.identity, state.revision) { mutableStateOf(false) }
    var name by remember(state.identity, state.revision) { mutableStateOf("") }
    fun leave() {
        if (state.busy) return
        if (state.draft != state.source.text ||
            state.recovered == null && state.draftPersistence != DraftPersistence.NONE) confirmLeave = true
        else onClose()
    }
    BackHandler { leave() }
    val recovered = state.recovered
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
            Text(state.source.path, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = state.canSave, onClick = onReview) { Text(stringResource(R.string.editor_save)) }
                TextButton(enabled = state.canSave, onClick = { saveAs = true }) { Text(stringResource(R.string.editor_save_as)) }
                TextButton(enabled = !state.busy, onClick = { leave() }) { Text(stringResource(R.string.common_cancel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (recovered != null) RecoveredDraftCard(recovered, state.busy, onRestoreDraft, onDiscardDraft)
            if (state.disconnected) Text(stringResource(R.string.editor_disconnected))
            if (state.inputRejected || state.encodedBytes > MAX_REMOTE_PREVIEW_BYTES) {
                Text(stringResource(R.string.editor_size_limit), color = MaterialTheme.colorScheme.error)
            }
            DraftPersistenceStatus(state, onRetryDraft)
            if (state.conflict) {
                Text(stringResource(R.string.editor_conflict))
                TextButton(enabled = !state.busy && !state.disconnected, onClick = { confirmReload = true }) { Text(stringResource(R.string.editor_reload)) }
            }
            if (state.needsDirectApproval) {
                Text(stringResource(R.string.editor_no_atomic))
                TextButton(enabled = state.canSave, onClick = { confirmDirect = true }) { Text(stringResource(R.string.editor_direct)) }
            }
            if (state.metadataFailure) Text(stringResource(R.string.editor_metadata_failure), color = MaterialTheme.colorScheme.error)
            if (state.invalidName) Text(stringResource(R.string.fm_invalid_name), color = MaterialTheme.colorScheme.error)
            if (state.failed) Text(stringResource(R.string.editor_failure), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(state.draft, onChange, readOnly = state.busy || recovered != null,
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
    state.review?.let { review ->
        AlertDialog(onDismissRequest = onDismissReview, title = { Text(stringResource(R.string.editor_review_title)) },
            text = { DiffView(review.changes, Modifier.heightIn(max = 400.dp)) },
            confirmButton = { TextButton(onClick = { onSave(null, false) }) { Text(stringResource(R.string.editor_save)) } },
            dismissButton = { TextButton(onClick = onDismissReview) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text(stringResource(R.string.editor_reload)) },
        text = { Text(stringResource(R.string.editor_reload_detail)) },
        confirmButton = { TextButton(onClick = { confirmReload = false; onReload() }) { Text(stringResource(R.string.editor_reload)) } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.editor_unsaved)) },
        text = { Text(stringResource(R.string.editor_discard_detail)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onDiscardAndClose() }) { Text(stringResource(R.string.editor_discard)) } },
        dismissButton = {
            if (state.draftPersistence == DraftPersistence.STORED) {
                TextButton(onClick = onKeepAndClose) { Text(stringResource(R.string.editor_keep_and_close)) }
            }
            TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.common_cancel)) }
        })
    if (confirmDirect) AlertDialog(onDismissRequest = { confirmDirect = false }, title = { Text(stringResource(R.string.editor_direct)) },
        text = { Text(stringResource(R.string.editor_direct_risk)) },
        confirmButton = { TextButton(onClick = { confirmDirect = false; onSave(null, true) }) { Text(stringResource(R.string.editor_direct)) } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (saveAs) {
        val valid = remember(name, state.source.platform) { RemoteWriteNames.isValid(name, state.source.platform) }
        AlertDialog(onDismissRequest = { saveAs = false }, title = { Text(stringResource(R.string.editor_save_as)) },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_new_name)) }, singleLine = true,
                    isError = name.isNotEmpty() && !valid, supportingText = {
                        if (name.isNotEmpty() && !valid) Text(stringResource(R.string.fm_invalid_name))
                    })
            },
            confirmButton = { TextButton(enabled = state.canSave && valid, onClick = { saveAs = false; onSave(name, false) }) { Text(stringResource(R.string.editor_save)) } },
            dismissButton = { TextButton(onClick = { saveAs = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun RecoveredDraftCard(recovered: RecoveredDraft, busy: Boolean, onRestore: () -> Unit, onDiscard: () -> Unit) {
    var showChanges by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.editor_draft_found,
                android.text.format.DateUtils.getRelativeTimeSpanString(recovered.savedAtEpochMillis).toString()))
            if (recovered.remoteChanged) {
                Text(stringResource(R.string.editor_draft_remote_changed), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = onRestore) { Text(stringResource(R.string.editor_draft_restore)) }
                TextButton(enabled = !busy, onClick = onDiscard) { Text(stringResource(R.string.editor_discard)) }
                TextButton(onClick = { showChanges = !showChanges }) { Text(stringResource(R.string.editor_draft_show_changes)) }
            }
            if (showChanges) DiffView(recovered.changes, Modifier.heightIn(max = 240.dp))
        }
    }
}

@Composable
private fun DraftPersistenceStatus(state: RemoteEditorUiState, onRetryDraft: () -> Unit) {
    val savedTime = state.lastDraftSavedAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }
    val status = when (state.draftPersistence) {
        DraftPersistence.PENDING -> R.string.editor_draft_pending
        DraftPersistence.WRITING -> R.string.editor_draft_writing
        DraftPersistence.STORED -> R.string.editor_draft_stored
        DraftPersistence.FAILED -> if (state.recovered == null) R.string.editor_draft_failed else R.string.editor_draft_delete_failed
        DraftPersistence.NONE -> null
    }
    if (status != null) Text(stringResource(status), color = if (state.draftPersistence == DraftPersistence.FAILED)
        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (savedTime != null) Text(stringResource(R.string.editor_draft_last_saved_at, savedTime))
    if (state.draftPersistence == DraftPersistence.FAILED && state.recovered == null) {
        TextButton(enabled = !state.busy, onClick = onRetryDraft) { Text(stringResource(R.string.editor_draft_retry)) }
    }
}

/** Condensed line diff; null entries stand for runs of unchanged lines. */
@Composable
private fun DiffView(changes: List<DiffLine?>?, modifier: Modifier = Modifier) {
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
