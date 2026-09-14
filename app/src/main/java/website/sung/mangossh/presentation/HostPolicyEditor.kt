package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.*

/** Host-only policy controls display inheritance and keep every change in the unsaved draft. */
@Composable
internal fun HostPolicyEditor(
    defaults: ConnectionPreferences,
    overrides: HostConnectionOverrides,
    onOverrides: (HostConnectionOverrides) -> Unit,
    agent: HostAgentPolicy,
    onAgent: (HostAgentPolicy) -> Unit,
    keys: List<StoredSshKey>,
    selectedKeyId: String?,
    reauthenticate: Boolean?,
    onReauthenticate: (Boolean?) -> Unit,
    workspace: TmuxWorkspace,
    onWorkspace: (TmuxWorkspace) -> Unit,
    jumps: List<String>,
    onJumps: (List<String>) -> Unit,
    hosts: List<ConnectionProfile>,
    profileId: String?,
    protocol: ConnectionProtocol,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.host_policy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.host_policy_snapshot), style = MaterialTheme.typography.bodySmall)
        InheritedNumber(R.string.host_policy_timeout, defaults.connectTimeoutSeconds, overrides.connectTimeoutSeconds,
            ConnectionPreferences.CONNECT_TIMEOUT_CHOICES) { onOverrides(overrides.copy(connectTimeoutSeconds = it)) }
        InheritedNumber(R.string.host_policy_keepalive, defaults.keepaliveSeconds, overrides.keepaliveSeconds,
            ConnectionPreferences.KEEPALIVE_CHOICES) { onOverrides(overrides.copy(keepaliveSeconds = it)) }
        InheritedNumber(R.string.host_policy_background, defaults.backgroundKeepaliveMultiplier, overrides.backgroundMultiplier,
            ConnectionPreferences.BACKGROUND_KEEPALIVE_MULTIPLIER_CHOICES) { onOverrides(overrides.copy(backgroundMultiplier = it)) }
        Text(stringResource(R.string.host_policy_terminal, (overrides.terminalType ?: defaults.sshTerminalType).termValue,
            stringResource(if (overrides.terminalType == null) R.string.host_policy_inherited else R.string.host_policy_override)))
        TextButton(onClick = { onOverrides(overrides.copy(terminalType = null)) }) { Text(stringResource(R.string.host_policy_inherit)) }
        SshTerminalType.entries.forEach { type ->
            FilterChip(selected = overrides.terminalType == type, onClick = { onOverrides(overrides.copy(terminalType = type)) }, label = { Text(type.termValue) })
        }
        Text(stringResource(R.string.host_policy_agent), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.host_policy_agent_scope_note), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onAgent(agent.copy(allowedKeyIds = null)) }) { Text(stringResource(R.string.host_policy_selected_key)) }
        val allowed = agent.allowedKeyIds ?: listOfNotNull(selectedKeyId)
        keys.forEach { key ->
            Row {
                Checkbox(checked = key.id in allowed, onCheckedChange = { checked ->
                    onAgent(agent.copy(allowedKeyIds = if (checked) (allowed + key.id).distinct() else allowed - key.id))
                })
                Text(key.label, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Row {
            Checkbox(checked = agent.confirmEachSignature, onCheckedChange = { onAgent(agent.copy(confirmEachSignature = it)) })
            Text(stringResource(R.string.host_policy_confirm_signature), Modifier.padding(top = 12.dp))
        }
        InheritedNumber(R.string.host_policy_agent_lifetime, 0, agent.authorizationSeconds.takeUnless { it == 0 }, listOf(30, 60, 300)) {
            onAgent(agent.copy(authorizationSeconds = it ?: 0))
        }
        Text(stringResource(R.string.host_policy_reauthenticate))
        listOf<Boolean?>(null, false, true).forEach { choice ->
            FilterChip(selected = reauthenticate == choice, onClick = { onReauthenticate(choice) }, label = {
                Text(stringResource(when (choice) { null -> R.string.host_policy_inherit; true -> R.string.host_policy_enabled; false -> R.string.host_policy_disabled }))
            })
        }
        Text(stringResource(R.string.host_policy_workspace), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.host_policy_workspace_exclusive), style = MaterialTheme.typography.bodySmall)
        WorkspaceMode.entries.forEach { mode ->
            FilterChip(selected = workspace.mode == mode, onClick = { onWorkspace(workspace.copy(mode = mode)) }, label = {
                Text(stringResource(when (mode) { WorkspaceMode.DISABLED -> R.string.host_policy_disabled; WorkspaceMode.CREATE -> R.string.workspace_create; WorkspaceMode.ATTACH -> R.string.workspace_attach; WorkspaceMode.CREATE_OR_ATTACH -> R.string.workspace_create_or_attach }))
            })
        }
        if (workspace.mode == WorkspaceMode.CREATE || workspace.mode == WorkspaceMode.CREATE_OR_ATTACH) {
            OutlinedTextField(workspace.name, { onWorkspace(workspace.copy(name = it)) }, label = { Text(stringResource(R.string.workspace_name)) },
                isError = !workspace.isValid(), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (workspace.mode == WorkspaceMode.ATTACH) {
            OutlinedTextField(workspace.sessionId, { onWorkspace(workspace.copy(sessionId = it)) }, label = { Text(stringResource(R.string.workspace_id)) },
                isError = !workspace.isValid(), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        JumpChainEditor(jumps, onJumps, hosts, profileId, protocol)
    }
}

@Composable
private fun InheritedNumber(label: Int, default: Int, value: Int?, choices: List<Int>, onChange: (Int?) -> Unit) {
    Text(stringResource(label) + ": ${value ?: default} · " + stringResource(if (value == null) R.string.host_policy_inherited else R.string.host_policy_override))
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(stringResource(R.string.host_policy_change)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.host_policy_inherit)) }, onClick = { onChange(null); expanded = false })
            (choices + listOfNotNull(value)).distinct().sorted().forEach { choice ->
                DropdownMenuItem(text = { Text(choice.toString()) }, onClick = { onChange(choice); expanded = false })
            }
        }
    }
}
