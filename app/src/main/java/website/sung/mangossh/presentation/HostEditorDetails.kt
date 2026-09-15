package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.*
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.SettingsSwitchRow

/** Detail pages expose only the settings for the selected category, while all edits stay local. */
@Composable
internal fun HostEditorDetails(controller: HostEditorController, hosts: List<ConnectionProfile>,
    defaults: ConnectionPreferences, keys: List<StoredSshKey>, snippets: List<CommandSnippet>) {
    when (controller.page) {
        HostEditorPage.CONNECTION -> ConnectionDetail(controller, keys)
        HostEditorPage.JUMPS -> JumpChainEditor(controller.draft.jumpIds,
            { controller.draft = controller.draft.copy(jumpIds = it) }, hosts, controller.draft.id, controller.draft.protocol)
        HostEditorPage.STARTUP -> StartupDetail(controller, snippets)
        HostEditorPage.SECURITY -> SecurityDetail(controller, keys)
        HostEditorPage.ADVANCED -> AdvancedDetail(controller, defaults)
        HostEditorPage.MAIN -> Unit
    }
}

@Composable
private fun ConnectionDetail(controller: HostEditorController, keys: List<StoredSshKey>) {
    val draft = controller.draft
    MangoPreferenceGroup {
        HostEditorChoice(stringResource(R.string.ui_protocol), draft.protocol.label, draft.protocol,
            ConnectionProtocol.entries, { it.label }, { protocol ->
                val clear = protocol == ConnectionProtocol.MOSH && draft.jumpIds.isNotEmpty()
                controller.change(draft.copy(protocol = protocol, jumpIds = if (clear) emptyList() else draft.jumpIds), clear, mosh = true)
            }, "host_editor_protocol")
        HostEditorChoice(stringResource(R.string.ui_network_route), draft.route.label(), draft.route,
            ConnectionRoute.entries, { it.label() }, { route ->
                controller.draft = draft.copy(route = route, authentication = if (route == draft.route) draft.authentication else authenticationAfterRouteSelection(route, draft.authentication))
            }, "host_editor_route")
        if (draft.route != ConnectionRoute.TAILNET) {
            HostEditorChoice(stringResource(R.string.ui_authentication), draft.authentication.label(), draft.authentication,
                AuthenticationMethod.entries.filterNot { draft.route == ConnectionRoute.DIRECT && it == AuthenticationMethod.TAILSCALE_SSH },
                { it.label() }, { controller.draft = draft.copy(authentication = it) }, "host_editor_authentication")
            if (draft.authentication == AuthenticationMethod.PRIVATE_KEY) {
                fun keyName(id: String?) = keys.firstOrNull { it.id == id }?.label
                HostEditorChoice(stringResource(R.string.ui_shared_private_key), keyName(draft.keyId) ?: stringResource(R.string.host_editor_select_key),
                    draft.keyId, keys.map { it.id as String? }, { keyName(it).orEmpty() },
                    { controller.draft = draft.copy(keyId = it) }, "host_editor_key", searchable = true, error = keyName(draft.keyId) == null)
                if (keys.isEmpty()) Text(stringResource(R.string.ui_generate_or_import_a_private_key_on_the_keys_page_first),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
    when (draft.route) {
        ConnectionRoute.TAILNET -> HostEditorFootnote(stringResource(R.string.ui_tailnet_routing_reaches_the_target_through_the_device_s_enabled_tailscal))
        ConnectionRoute.TSNET -> HostEditorFootnote(stringResource(R.string.ui_embedded_tailscale_proxies_only_this_profile_s_ssh_and_mosh_traffic_tail))
        ConnectionRoute.DIRECT -> Unit
    }
    if (draft.protocol == ConnectionProtocol.MOSH) HostEditorFootnote(stringResource(R.string.ui_mosh_uses_a_gpl_3_0_or_later_native_client_its_source_and_license_are_in))
}

/** Kept outside the card and indented to align with the row text above it. */
@Composable
internal fun HostEditorFootnote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun StartupDetail(controller: HostEditorController, snippets: List<CommandSnippet>) {
    val draft = controller.draft
    val disabled = stringResource(R.string.ui_do_not_run)
    val missing = stringResource(R.string.host_editor_needs_attention)
    fun snippetName(id: String?) = if (id == null) disabled else snippets.firstOrNull { it.id == id }?.label ?: missing
    MangoPreferenceGroup {
        HostEditorChoice(stringResource(R.string.ui_run_after_connection), snippetName(draft.startupSnippetId), draft.startupSnippetId,
            listOf(null) + snippets.map { it.id }, { snippetName(it) }, { snippet ->
                controller.change(draft.copy(startupSnippetId = snippet, workspace = if (snippet != null) draft.workspace.copy(mode = WorkspaceMode.DISABLED) else draft.workspace),
                    confirm = snippet != null && draft.workspace.mode != WorkspaceMode.DISABLED)
            }, "host_editor_snippet", searchable = true)
        HostEditorChoice(stringResource(R.string.host_policy_workspace), draft.workspace.mode.label(), draft.workspace.mode,
            WorkspaceMode.entries, { it.label() }, { mode ->
                controller.change(draft.copy(workspace = draft.workspace.copy(mode = mode), startupSnippetId = if (mode != WorkspaceMode.DISABLED) null else draft.startupSnippetId),
                    confirm = mode != WorkspaceMode.DISABLED && draft.startupSnippetId != null)
            }, "host_editor_workspace")
    }
    when (draft.workspace.mode) {
        WorkspaceMode.CREATE, WorkspaceMode.CREATE_OR_ATTACH -> OutlinedTextField(draft.workspace.name,
            { controller.draft = draft.copy(workspace = draft.workspace.copy(name = it)) },
            label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true,
            isError = !draft.workspace.isValid(), modifier = Modifier.fillMaxWidth().testTag("host_editor_workspace_name"))
        WorkspaceMode.ATTACH -> OutlinedTextField(draft.workspace.sessionId,
            { controller.draft = draft.copy(workspace = draft.workspace.copy(sessionId = it)) },
            label = { Text(stringResource(R.string.workspace_id)) }, singleLine = true,
            isError = !draft.workspace.isValid(), modifier = Modifier.fillMaxWidth().testTag("host_editor_workspace_id"))
        WorkspaceMode.DISABLED -> Unit
    }
    HostEditorFootnote(stringResource(R.string.host_editor_startup_note))
}

@Composable
private fun SecurityDetail(controller: HostEditorController, keys: List<StoredSshKey>) {
    val draft = controller.draft
    MangoPreferenceGroup {
        if (draft.protocol == ConnectionProtocol.SSH) SettingsSwitchRow(stringResource(R.string.ui_enable_ssh_agent_forwarding),
            draft.agentForwarding, { controller.draft = draft.copy(agentForwarding = it) }, Modifier.testTag("host_editor_agent_enabled"))
        if (draft.protocol == ConnectionProtocol.SSH && draft.agentForwarding) {
            AgentAllowlist(draft.agentPolicy.allowedKeyIds, keys) { controller.draft = draft.copy(agentPolicy = draft.agentPolicy.copy(allowedKeyIds = it)) }
            SettingsSwitchRow(stringResource(R.string.host_policy_confirm_signature), draft.agentPolicy.confirmEachSignature,
                { controller.draft = draft.copy(agentPolicy = draft.agentPolicy.copy(confirmEachSignature = it)) })
            HostEditorChoice(stringResource(R.string.host_policy_agent_lifetime),
                if (draft.agentPolicy.authorizationSeconds == 0) stringResource(R.string.host_editor_this_connection) else draft.agentPolicy.authorizationSeconds.toString(),
                draft.agentPolicy.authorizationSeconds, (listOf(0, 30, 60, 300) + draft.agentPolicy.authorizationSeconds).distinct(),
                { if (it == 0) stringResource(R.string.host_editor_this_connection) else it.toString() },
                { controller.draft = draft.copy(agentPolicy = draft.agentPolicy.copy(authorizationSeconds = it)) }, "host_editor_agent_lifetime", compact = true)
        }
        val selected = stringResource(when (draft.reauthenticate) { null -> R.string.host_policy_inherit; true -> R.string.host_policy_enabled; false -> R.string.host_policy_disabled })
        HostEditorChoice(stringResource(R.string.host_policy_reauthenticate), selected, draft.reauthenticate, listOf(null, false, true),
            { stringResource(when (it) { null -> R.string.host_policy_inherit; true -> R.string.host_policy_enabled; false -> R.string.host_policy_disabled }) },
            { controller.draft = draft.copy(reauthenticate = it) }, "host_editor_reauthenticate")
    }
    if (draft.protocol == ConnectionProtocol.SSH && draft.agentForwarding) HostEditorFootnote(stringResource(R.string.host_policy_agent_scope_note))
}

@Composable
private fun AgentAllowlist(allowed: List<String>?, keys: List<StoredSshKey>, change: (List<String>?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    HostEditorRow(stringResource(R.string.host_policy_agent), if (allowed == null) stringResource(R.string.host_policy_selected_key)
        else stringResource(R.string.host_editor_key_count, allowed.size), { picking = true }, Modifier.testTag("host_editor_agent_keys"), dense = true)
    if (picking) {
        var selected by rememberSaveable { mutableStateOf(allowed) }
        var query by rememberSaveable { mutableStateOf("") }
        val missing = stringResource(R.string.host_editor_missing_key)
        val all = keys.map { it.id to it.label } + selected.orEmpty().filter { id -> keys.none { it.id == id } }.map { it to missing }
        AlertDialog(onDismissRequest = { picking = false }, title = { Text(stringResource(R.string.host_policy_agent)) }, text = {
            // Scroll the mode and search controls too: they must not squeeze the key list off a short screen.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("host_editor_agent_key_options"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected == null, role = Role.RadioButton) { selected = null }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == null, null)
                        Text(stringResource(R.string.host_policy_selected_key), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("host_editor_agent_custom")
                        .selectable(selected = selected != null, role = Role.RadioButton) { if (selected == null) selected = emptyList() }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected != null, null)
                        Text(stringResource(R.string.host_editor_custom_keys), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (selected != null) {
                    item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.common_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    items(all.filter { it.second.contains(query, true) }, key = { it.first }) { (id, label) ->
                        val checked = id in selected.orEmpty()
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = checked, role = Role.Checkbox) {
                                selected = if (checked) selected.orEmpty() - id else selected.orEmpty() + id
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, null)
                            Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { change(selected); picking = false }, modifier = Modifier.testTag("host_editor_agent_keys_apply")) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun AdvancedDetail(controller: HostEditorController, defaults: ConnectionPreferences) {
    val draft = controller.draft
    val value = draft.overrides
    HostEditorFootnote(stringResource(R.string.host_policy_snapshot))
    MangoPreferenceGroup {
        NumberOverride(R.string.host_policy_timeout, defaults.connectTimeoutSeconds, value.connectTimeoutSeconds, ConnectionPreferences.CONNECT_TIMEOUT_CHOICES, "timeout") {
            controller.draft = draft.copy(overrides = value.copy(connectTimeoutSeconds = it))
        }
        NumberOverride(R.string.host_policy_keepalive, defaults.keepaliveSeconds, value.keepaliveSeconds, ConnectionPreferences.KEEPALIVE_CHOICES, "keepalive") {
            controller.draft = draft.copy(overrides = value.copy(keepaliveSeconds = it))
        }
        NumberOverride(R.string.host_policy_background, defaults.backgroundKeepaliveMultiplier, value.backgroundMultiplier, ConnectionPreferences.BACKGROUND_KEEPALIVE_MULTIPLIER_CHOICES, "background") {
            controller.draft = draft.copy(overrides = value.copy(backgroundMultiplier = it))
        }
        HostEditorChoice(stringResource(R.string.host_editor_terminal),
            stringResource(if (value.terminalType == null) R.string.host_policy_inherited else R.string.host_policy_override),
            value.terminalType, listOf(null) + SshTerminalType.entries,
            { it?.termValue ?: stringResource(R.string.host_policy_inherit) },
            { controller.draft = draft.copy(overrides = value.copy(terminalType = it)) }, "host_editor_terminal", compact = true,
            valueLabel = (value.terminalType ?: defaults.sshTerminalType).termValue)
    }
}

@Composable
private fun NumberOverride(label: Int, default: Int, value: Int?, choices: List<Int>, tag: String, change: (Int?) -> Unit) {
    HostEditorChoice(stringResource(label), stringResource(if (value == null) R.string.host_policy_inherited else R.string.host_policy_override),
        value, listOf(null) + (choices + listOfNotNull(value)).distinct().sorted(),
        { it?.toString() ?: stringResource(R.string.host_policy_inherit) }, change, "host_editor_$tag", compact = true,
        valueLabel = "${value ?: default}")
}
