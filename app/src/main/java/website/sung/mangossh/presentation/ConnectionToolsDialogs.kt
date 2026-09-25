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
import website.sung.mangossh.session.CompanionHealth
import website.sung.mangossh.session.ConnectionDiagnostics
import website.sung.mangossh.session.NetworkHealth
import website.sung.mangossh.session.NetworkStatus
import website.sung.mangossh.session.SessionAttention
import website.sung.mangossh.session.RemoteWorkspace

/**
 * Displays measured states separately from the explicitly configured, unverified network route.
 * [load] is polled once a second while the dialog is open, so ages and states stay current.
 */
@Composable
internal fun ConnectionDiagnosticsDialog(load: () -> ConnectionDiagnostics?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val value by produceState(load()) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            this.value = load()
        }
    }
    val unknown = stringResource(R.string.diagnostics_unknown)
    fun age(stamp: Long?): String = stamp?.let { ((System.nanoTime() - it).coerceAtLeast(0) / 1_000_000_000L).toString() } ?: unknown
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.connection_diagnostics)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diagnostics_route_note))
            val current = value
            if (current == null) Text(unknown) else {
                Text(stringResource(R.string.diagnostics_route, current.route.name))
                Text(stringResource(R.string.diagnostics_phase, current.phase.label()))
                current.failure?.let { Text(it.toUiText().asString()) }
                current.network?.let { Text(stringResource(R.string.diagnostics_network, it.label())) }
                Text(stringResource(R.string.diagnostics_sent, age(current.sshLastSentNanos)))
                Text(stringResource(R.string.diagnostics_received, age(current.sshLastReceivedNanos)))
                Text(stringResource(R.string.diagnostics_confirmed, age(current.sshLastConfirmedNanos)))
                Text(stringResource(R.string.diagnostics_mosh, current.moshRunning?.let { stringResource(if (it) R.string.ui_running else R.string.port_forward_stopped) } ?: unknown))
                if (current.moshRunning != null) {
                    val companion = current.companion?.label()
                        ?: current.companionConnected?.let { stringResource(if (it) R.string.companion_connected else R.string.companion_lost) }
                        ?: unknown
                    Text(stringResource(R.string.diagnostics_companion, companion))
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { val current = value; if (current != null) TextButton(onClick = { scope.launch {
            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("MangoSSH diagnostics", current.export())))
        } }) { Text(stringResource(R.string.diagnostics_copy)) } })
}

@Composable
private fun CompanionHealth.label(): String = stringResource(when (this) {
    CompanionHealth.CONNECTED -> R.string.companion_connected
    CompanionHealth.LOST -> R.string.companion_lost
    CompanionHealth.RECONNECTING -> R.string.companion_reconnecting
    CompanionHealth.RECONNECT_FAILED -> R.string.companion_reconnect_failed
})

@Composable
private fun NetworkStatus.label(): String = listOfNotNull(
    stringResource(when (health) {
        NetworkHealth.AVAILABLE -> R.string.network_available
        NetworkHealth.LOST -> R.string.network_lost
        NetworkHealth.BLOCKED -> R.string.network_blocked
    }),
    if (health == NetworkHealth.AVAILABLE && !validated) stringResource(R.string.network_unvalidated) else null,
    if (health == NetworkHealth.AVAILABLE && metered) stringResource(R.string.network_metered) else null,
).joinToString(" · ")

/** Short label for a session that needs attention, or null when it does not. */
@Composable
internal fun SessionAttention.label(): String? = when (this) {
    SessionAttention.NONE -> null
    SessionAttention.NETWORK_LOST -> stringResource(R.string.attention_network_lost)
    SessionAttention.NETWORK_BLOCKED -> stringResource(R.string.attention_network_blocked)
    SessionAttention.NETWORK_UNVALIDATED -> stringResource(R.string.attention_network_unvalidated)
    SessionAttention.COMPANION_LOST -> stringResource(R.string.attention_companion_lost)
    SessionAttention.COMPANION_RECONNECTING -> stringResource(R.string.attention_companion_reconnecting)
    SessionAttention.COMPANION_RECONNECT_FAILED -> stringResource(R.string.attention_companion_reconnect_failed)
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
