package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.sung.mangossh.R
import website.sung.mangossh.domain.TmuxWorkspace
import website.sung.mangossh.domain.WorkspaceMode
import website.sung.mangossh.session.ConnectionDiagnostics
import website.sung.mangossh.session.RemoteWorkspace

/** Displays measured states separately from the explicitly configured, unverified network route. */
@Composable
internal fun ConnectionDiagnosticsDialog(value: ConnectionDiagnostics?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val unknown = stringResource(R.string.diagnostics_unknown)
    fun age(stamp: Long?): String = stamp?.let { ((System.nanoTime() - it).coerceAtLeast(0) / 1_000_000_000L).toString() } ?: unknown
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.connection_diagnostics)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diagnostics_route_note))
            if (value == null) Text(unknown) else {
                Text(stringResource(R.string.diagnostics_route, value.route.name))
                Text(stringResource(R.string.diagnostics_phase, value.phase.label()))
                value.failure?.let { Text(it.toUiText().asString()) }
                Text(stringResource(R.string.diagnostics_sent, age(value.sshLastSentNanos)))
                Text(stringResource(R.string.diagnostics_received, age(value.sshLastReceivedNanos)))
                Text(stringResource(R.string.diagnostics_confirmed, age(value.sshLastConfirmedNanos)))
                Text(stringResource(R.string.diagnostics_mosh, value.moshRunning?.let { stringResource(if (it) R.string.host_policy_enabled else R.string.host_policy_disabled) } ?: unknown))
                Text(stringResource(R.string.diagnostics_companion, value.companionConnected?.let { stringResource(if (it) R.string.host_policy_enabled else R.string.host_policy_disabled) } ?: unknown))
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { if (value != null) TextButton(onClick = { scope.launch {
            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("MangoSSH diagnostics", value.export())))
        } }) { Text(stringResource(R.string.diagnostics_copy)) } })
}

/** Workspace actions open a new transport and never write a command into the current foreground program. */
@Composable
internal fun WorkspaceDialog(load: suspend () -> List<RemoteWorkspace>, onOpen: (TmuxWorkspace) -> Unit, onDismiss: () -> Unit) {
    var workspaces by remember { mutableStateOf<List<RemoteWorkspace>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { load() }.onSuccess { workspaces = it }.onFailure { failed = true } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.host_policy_workspace)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workspace_new_transport))
            when {
                failed -> Text(stringResource(R.string.workspace_unavailable))
                workspaces == null -> CircularProgressIndicator()
                else -> workspaces.orEmpty().forEach { workspace ->
                    OutlinedButton(onClick = { onOpen(TmuxWorkspace(WorkspaceMode.ATTACH, sessionId = workspace.id)) }) {
                        Text("${workspace.id} · ${workspace.name}")
                    }
                }
            }
            if (!failed && workspaces != null) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.workspace_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                val workspace = TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, name)
                TextButton(enabled = workspace.isValid(), onClick = { onOpen(workspace) }) { Text(stringResource(R.string.workspace_create_or_attach)) }
                TextButton(enabled = workspace.isValid(), onClick = { onOpen(workspace.copy(mode = WorkspaceMode.CREATE)) }) { Text(stringResource(R.string.workspace_create)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
