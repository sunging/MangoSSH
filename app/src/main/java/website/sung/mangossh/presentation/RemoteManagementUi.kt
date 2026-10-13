package website.sung.mangossh.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** Dialog intent contains user paths only; all remote work is delegated through callbacks. */
internal data class RemoteManagementAction(val kind: RemoteOperationKind?, val paths: List<String> = emptyList())

@Composable
internal fun remoteOperationLabel(kind: RemoteOperationKind): String = stringResource(when (kind) {
    RemoteOperationKind.CREATE_FILE -> R.string.fm_new_file
    RemoteOperationKind.CREATE_DIRECTORY -> R.string.fm_new_directory
    RemoteOperationKind.RENAME -> R.string.fm_rename
    RemoteOperationKind.COPY -> R.string.fm_copy
    RemoteOperationKind.MOVE -> R.string.fm_move
    RemoteOperationKind.DELETE -> R.string.fm_delete
    RemoteOperationKind.PERMISSIONS -> R.string.fm_permissions
})

/** Compact horizontally scrollable actions keep touch targets reachable on narrow phones. */
@Composable
internal fun RemoteManagementToolbar(state: RemoteBrowserUiState, busy: Boolean,
    onAction: (RemoteManagementAction) -> Unit, onCopy: (List<String>, Boolean) -> Unit,
    onOperation: (RemoteOperationRequest) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit,
    onClearClipboard: () -> Unit, onTasks: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = state.selectedPaths.toList()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(20.dp), strokeWidth = 2.dp)
        if (selected.isNotEmpty()) {
            Text(stringResource(R.string.fm_selected, selected.size), Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.fm_select_all)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = { onCopy(selected, false) }) { Text(stringResource(R.string.fm_copy)) }
            TextButton(onClick = { onCopy(selected, true) }) { Text(stringResource(R.string.fm_cut)) }
            TextButton(onClick = { onOperation(RemoteOperationRequest(state.sessionId, RemoteOperationKind.DELETE, selected)) }) { Text(stringResource(R.string.fm_delete)) }
            TextButton(onClick = { onAction(RemoteManagementAction(RemoteOperationKind.PERMISSIONS, selected)) }) { Text(stringResource(R.string.fm_permissions)) }
        } else {
            Box {
                TextButton(enabled = !state.isConnecting, onClick = { menu = true }) { Text(stringResource(R.string.fm_manage)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(RemoteOperationKind.CREATE_FILE, RemoteOperationKind.CREATE_DIRECTORY).forEach { kind ->
                        DropdownMenuItem(text = { Text(remoteOperationLabel(kind)) }, onClick = { menu = false; onAction(RemoteManagementAction(kind)) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.fm_select_all)) }, onClick = { menu = false; onSelectAll() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fm_platform)) }, enabled = !busy,
                        onClick = { menu = false; onAction(RemoteManagementAction(null)) })
                }
            }
            state.clipboard?.let { clipboard ->
                TextButton(onClick = { onOperation(RemoteOperationRequest(state.sessionId,
                    if (clipboard.move) RemoteOperationKind.MOVE else RemoteOperationKind.COPY, clipboard.paths, state.path)) },
                    enabled = !state.isConnecting && clipboard.sessionId == state.sessionId) {
                    Text(stringResource(R.string.fm_paste, clipboard.paths.size))
                }
                TextButton(onClick = onClearClipboard) { Text(stringResource(R.string.common_cancel)) }
            }
            TextButton(onClick = onTasks) { Text(stringResource(R.string.fm_tasks)) }
        }
    }
}

/** Shared menu for files, folders and links; unsupported editing is excluded at the entry point. */
@Composable
internal fun RemoteEntryManagementMenuItems(entry: RemoteFileEntry, onAction: (RemoteManagementAction) -> Unit,
    onCopy: (List<String>, Boolean) -> Unit, onEdit: () -> Unit, dismiss: () -> Unit) {
    if (entry.kind == RemoteFileKind.FILE) DropdownMenuItem(text = { Text(stringResource(R.string.editor_edit)) }, onClick = { dismiss(); onEdit() })
    listOf(RemoteOperationKind.RENAME, RemoteOperationKind.DELETE, RemoteOperationKind.PERMISSIONS).forEach { kind ->
        DropdownMenuItem(text = { Text(remoteOperationLabel(kind)) }, onClick = { dismiss(); onAction(RemoteManagementAction(kind, listOf(entry.path))) })
    }
    DropdownMenuItem(text = { Text(stringResource(R.string.fm_copy)) }, onClick = { dismiss(); onCopy(listOf(entry.path), false) })
    DropdownMenuItem(text = { Text(stringResource(R.string.fm_cut)) }, onClick = { dismiss(); onCopy(listOf(entry.path), true) })
}

/** Carries the caller's density into the dialog window, including accessibility font scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteManagementDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    BasicAlertDialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    content()
                }
            }
        }
    }
}

/** Input validation is shared with the engine; no network or disk work runs in this dialog. */
@Composable
internal fun RemoteManagementDialog(action: RemoteManagementAction, state: RemoteBrowserUiState,
    onPlatform: (RemotePlatform) -> Unit, onOperation: (RemoteOperationRequest) -> Unit, onDismiss: () -> Unit) {
    val platform = state.platform.resolve(state.path)
    val permission = action.kind == RemoteOperationKind.PERMISSIONS
    val selected = state.entries.filter { it.path in action.paths }
    val initialMode = selected.singleOrNull()?.permissions?.toIntOrNull(8)
    var name by remember(action) { mutableStateOf(if (action.kind == RemoteOperationKind.RENAME) RemoteFilePaths.nameOf(action.paths.single()) else "") }
    var fileMode by remember(action) { mutableStateOf(Integer.toOctalString(if (selected.singleOrNull()?.kind == RemoteFileKind.FILE) (initialMode ?: 420) and 511 else 420).padStart(3, '0')) }
    var directoryMode by remember(action) { mutableStateOf(Integer.toOctalString(if (selected.singleOrNull()?.kind == RemoteFileKind.DIRECTORY) (initialMode ?: 493) and 511 else 493).padStart(3, '0')) }
    var recursive by remember(action) { mutableStateOf(false) }
    var changeSpecial by remember(action) { mutableStateOf(false) }
    var special by remember(action) { mutableIntStateOf((initialMode ?: 0) shr 9 and 7) }
    RemoteManagementDialogFrame(if (action.kind == null) stringResource(R.string.fm_platform) else remoteOperationLabel(action.kind), onDismiss) {
        when {
            action.kind == null || permission && platform != RemotePlatform.POSIX -> {
                Text(stringResource(if (platform == RemotePlatform.WINDOWS && permission) R.string.fm_windows_permissions else R.string.fm_platform_detail))
                if (permission) selected.forEach { Text(it.name + " · " + (it.permissions ?: "—")) }
                listOf(RemotePlatform.AUTO, RemotePlatform.POSIX, RemotePlatform.WINDOWS).forEach { value ->
                    TextButton(onClick = { onPlatform(value); if (action.kind == null) onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(when (value) { RemotePlatform.AUTO -> R.string.fm_platform_auto; RemotePlatform.POSIX -> R.string.fm_platform_posix; RemotePlatform.WINDOWS -> R.string.fm_platform_windows }))
                    }
                }
            }
            permission -> {
                if (selected.any { it.kind == RemoteFileKind.DIRECTORY }) {
                    CheckLine(stringResource(R.string.fm_recursive), recursive) { recursive = it }
                }
                if (recursive || selected.any { it.kind == RemoteFileKind.FILE }) PermissionModeField(stringResource(R.string.fm_file_mode), fileMode) { fileMode = it }
                if (selected.any { it.kind == RemoteFileKind.DIRECTORY }) PermissionModeField(stringResource(R.string.fm_directory_mode), directoryMode) { directoryMode = it }
                CheckLine(stringResource(R.string.fm_change_special), changeSpecial) { changeSpecial = it }
                if (changeSpecial) listOf(4 to R.string.fm_setuid, 2 to R.string.fm_setgid, 1 to R.string.fm_sticky).forEach { (bit, label) ->
                    CheckLine(stringResource(label), special and bit != 0) { special = if (it) special or bit else special and bit.inv() }
                }
                Text(stringResource(R.string.fm_permissions_detail))
                TextButton(enabled = RemoteOperationRules.mode(fileMode) in 0..511 && RemoteOperationRules.mode(directoryMode) in 0..511,
                    onClick = {
                        onOperation(RemoteOperationRequest(state.sessionId, RemoteOperationKind.PERMISSIONS, action.paths,
                            permissions = RemotePermissionChange(checkNotNull(RemoteOperationRules.mode(fileMode)), checkNotNull(RemoteOperationRules.mode(directoryMode)),
                                special.takeIf { changeSpecial }, recursive)))
                        onDismiss()
                    }) { Text(stringResource(R.string.fm_preview)) }
            }
            else -> {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.fm_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("remote-operation-name"))
                val valid = runCatching { RemoteOperationRules.requireName(name, platform) }.isSuccess
                if (name.isNotEmpty() && !valid) Text(stringResource(R.string.fm_invalid_name), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = valid, onClick = {
                    onOperation(RemoteOperationRequest(state.sessionId, checkNotNull(action.kind), action.paths, state.path, name))
                    onDismiss()
                }) { Text(stringResource(R.string.common_save)) }
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun PermissionModeField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    val mode = RemoteOperationRules.mode(value)?.and(511) ?: 0
    listOf(R.string.fm_owner, R.string.fm_group, R.string.fm_other).forEachIndexed { index, group ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(group), Modifier.weight(1f))
            listOf(4, 2, 1).forEachIndexed { bitIndex, bit ->
                val mask = bit shl (6 - index * 3)
                val accessibilityLabel = stringResource(group) + " " + stringResource(listOf(R.string.fm_read, R.string.fm_write, R.string.fm_execute)[bitIndex])
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(listOf(R.string.fm_read_symbol, R.string.fm_write_symbol, R.string.fm_execute_symbol)[bitIndex]))
                    Checkbox(mode and mask != 0, { checked -> onChange(Integer.toOctalString(if (checked) mode or mask else mode and mask.inv()).padStart(3, '0')) },
                        modifier = Modifier.semantics { contentDescription = accessibilityLabel })
                }
            }
        }
    }
}

/** Preview and conflict decisions are bound to a manager-owned prompt, never a remembered path. */
@Composable
internal fun RemoteOperationConfirmation(prompt: RemoteOperationPrompt, onDecision: (RemoteOperationDecision) -> Unit) {
    var alternate by remember(prompt.id) { mutableStateOf("") }
    var saveAs by remember(prompt.id) { mutableStateOf(false) }
    var apply by remember(prompt.id) { mutableStateOf(false) }
    var directConfirmation by remember(prompt.id) { mutableStateOf(false) }
    val cancel = { onDecision(RemoteOperationDecision(RemoteOperationChoice.CANCEL)) }
    RemoteManagementDialogFrame(remoteOperationLabel(prompt.request.kind), cancel) {
        Text(stringResource(if (prompt.conflict) R.string.fm_conflict else R.string.fm_preview_count, prompt.paths.size))
        if (prompt.request.kind == RemoteOperationKind.DELETE) Text(stringResource(R.string.fm_delete_detail))
        if (prompt.request.kind == RemoteOperationKind.MOVE) Text(stringResource(R.string.fm_move_detail))
        prompt.request.destination?.let { Text(it) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) { items(prompt.paths) { Text(it) } }
        if (!prompt.conflict) {
            prompt.request.permissions?.let { Text(stringResource(R.string.fm_permission_summary,
                Integer.toOctalString(it.fileMode), Integer.toOctalString(it.directoryMode))) }
            TextButton(onClick = { onDecision(RemoteOperationDecision(RemoteOperationChoice.PROCEED)) }) { Text(stringResource(R.string.fm_confirm)) }
        } else if (directConfirmation) {
            Text(stringResource(R.string.fm_direct_detail))
            TextButton(onClick = { onDecision(RemoteOperationDecision(RemoteOperationChoice.DIRECT_OVERWRITE)) }) { Text(stringResource(R.string.fm_confirm_direct)) }
        } else {
            CheckLine(stringResource(R.string.transfer_apply_task), apply) { apply = it }
            TextButton(onClick = { onDecision(RemoteOperationDecision(RemoteOperationChoice.SKIP, applyToTask = apply)) }) { Text(stringResource(R.string.transfer_skip)) }
            TextButton(onClick = { saveAs = !saveAs }) { Text(stringResource(R.string.editor_save_as)) }
            if (saveAs) {
                OutlinedTextField(alternate, { alternate = it }, label = { Text(stringResource(R.string.fm_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = runCatching { RemoteOperationRules.requireName(alternate, prompt.request.platform) }.isSuccess,
                    onClick = { onDecision(RemoteOperationDecision(RemoteOperationChoice.SAVE_AS, alternate)) }) { Text(stringResource(R.string.fm_confirm)) }
            }
            if (prompt.replaceAllowed) TextButton(onClick = {
                if (prompt.directOverwrite) directConfirmation = true else onDecision(RemoteOperationDecision(RemoteOperationChoice.REPLACE, applyToTask = apply))
            }) { Text(stringResource(R.string.fm_replace)) }
        }
        TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
    }
}

/** Item-level outcomes make interrupted or partially completed mutations reviewable. */
@Composable
internal fun RemoteOperationCard(operation: RemoteOperationState, onCancel: () -> Unit) {
    var expanded by remember(operation.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(remoteOperationLabel(operation.request.kind), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (operation.phase) {
                RemoteOperationPhase.QUEUED -> R.string.fm_queued
                RemoteOperationPhase.SCANNING -> R.string.fm_scanning
                RemoteOperationPhase.WAITING -> R.string.fm_waiting
                RemoteOperationPhase.RUNNING -> R.string.fm_running
                RemoteOperationPhase.COMMITTING -> R.string.fm_committing
                RemoteOperationPhase.COMPLETED -> R.string.fm_completed
                RemoteOperationPhase.FAILED -> R.string.fm_failed
                RemoteOperationPhase.CANCELLED -> R.string.fm_cancelled
            }))
            if (operation.active) LinearProgressIndicator(Modifier.fillMaxWidth())
            operation.currentPath?.let { Text(it) }
            operation.detail?.let { Text(it.toUiText().asString(), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.fm_result_counts, operation.items.count { it.outcome == RemoteItemOutcome.COMPLETED },
                operation.items.count { it.outcome == RemoteItemOutcome.SKIPPED }, operation.items.count { it.outcome == RemoteItemOutcome.FAILED },
                operation.items.count { it.outcome == RemoteItemOutcome.PENDING }, operation.items.count { it.outcome == RemoteItemOutcome.PARTIAL }))
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.fm_details)) }
            if (expanded) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(operation.items) { item -> Text(stringResource(when (item.outcome) {
                    RemoteItemOutcome.PENDING -> R.string.fm_item_pending
                    RemoteItemOutcome.PARTIAL -> R.string.fm_item_partial
                    RemoteItemOutcome.COMPLETED -> R.string.fm_item_completed
                    RemoteItemOutcome.SKIPPED -> R.string.fm_item_skipped
                    RemoteItemOutcome.FAILED -> R.string.fm_item_failed
                }, item.path)) }
            }
            if (operation.canCancel) TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}
