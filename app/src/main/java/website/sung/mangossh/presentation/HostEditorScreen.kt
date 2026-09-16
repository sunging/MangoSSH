package website.sung.mangossh.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.*
import website.sung.mangossh.ui.components.MangoPreferenceGroup

/** Phones use all available space; tablet panels bound line length and share the same navigation. */
@Composable
internal fun HostEditorDialog(hosts: List<ConnectionProfile>, defaults: ConnectionPreferences,
    initialHost: ConnectionProfile?, keys: List<StoredSshKey>, snippets: List<CommandSnippet>,
    onDismiss: () -> Unit, onSave: (ConnectionProfileDraft, EditorSaveOperation) -> Unit) {
    val save = remember { EditorSaveOperation(onDismiss) }
    DisposableEffect(save) { onDispose { save.dispose() } }
    val controller = rememberHostEditorController(initialHost)
    val tablet = LocalWindowInfo.current.containerDpSize.width >= 600.dp
    Dialog(onDismissRequest = { controller.back(onDismiss) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .padding(if (tablet) 24.dp else 0.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = if (tablet) 720.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxSize()
                .testTag(if (tablet) "host_editor_tablet" else "host_editor_phone"),
                shape = if (tablet) MaterialTheme.shapes.extraLarge else RectangleShape) {
                HostEditorScreen(controller, hosts, defaults, keys, snippets, onDismiss, { onSave(it, save) }, saveOperation = save)
            }
        }
    }
}

/** Only the main page can commit. Subpages mutate the same draft and preserve scroll on return. */
@Composable
internal fun HostEditorScreen(controller: HostEditorController, hosts: List<ConnectionProfile>,
    defaults: ConnectionPreferences, keys: List<StoredSshKey>, snippets: List<CommandSnippet>,
    onDismiss: () -> Unit, onSave: (ConnectionProfileDraft) -> Unit, modifier: Modifier = Modifier, saveOperation: EditorSaveOperation? = null) {
    val draft = controller.draft
    val invalid = draft.invalidPages(keys.map { it.id }.toSet(), snippets.map { it.id }.toSet(), hosts)
    val focus = LocalFocusManager.current
    val pages = rememberSaveableStateHolder()
    val main = controller.page == HostEditorPage.MAIN
    fun back() { focus.clearFocus(); controller.back(onDismiss) }
    BackHandler { back() }
    Column(modifier.fillMaxSize().testTag("host_editor")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }, modifier = Modifier.testTag("host_editor_back")) {
                Icon(if (main) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(if (main) R.string.common_cancel else R.string.host_editor_back))
            }
            Text(if (main) stringResource(if (draft.id == null) R.string.ui_new_server else R.string.ui_edit_server)
                else controller.page.title(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            pages.SaveableStateProvider(controller.page) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    .testTag("host_editor_scroll_${controller.page.name}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (main) {
                        HostEditorBasics(draft) { controller.draft = it }
                        MangoPreferenceGroup {
                            HostEditorPage.entries.filter { it != HostEditorPage.MAIN }.forEach { page ->
                                val problem = page in invalid
                                val summary = if (problem) stringResource(R.string.host_editor_needs_attention) else
                                    draft.summary(page, snippets)
                                HostEditorRow(page.title(), summary, {
                                    focus.clearFocus(); controller.page = page
                                }, Modifier.testTag("host_editor_open_${page.name}"), problem)
                            }
                        }
                    } else {
                        HostEditorDetails(controller, hosts, defaults, keys, snippets)
                        if (controller.page in invalid) Text(stringResource(R.string.host_editor_needs_attention),
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("host_editor_detail_error"))
                    }
                }
            }
        }
        if (main) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                saveOperation?.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (invalid.isNotEmpty()) {
                    val first = HostEditorPage.entries.first { it in invalid }
                    Text(stringResource(R.string.host_editor_save_blocked, first.title()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("host_editor_save_error"))
                }
                Button(onClick = { focus.clearFocus(); onSave(controller.draft.toProfileDraft()) },
                    enabled = invalid.isEmpty() && saveOperation?.busy != true, modifier = Modifier.fillMaxWidth().testTag("host_editor_save")) {
                    Text(stringResource(if (saveOperation?.busy == true) R.string.vault_saving else R.string.ui_save_profile))
                }
            }
        }
    }
    if (controller.confirmDiscard) AlertDialog(onDismissRequest = { controller.confirmDiscard = false },
        title = { Text(stringResource(R.string.editor_unsaved)) }, text = { Text(stringResource(R.string.host_editor_discard_detail)) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("host_editor_discard")) { Text(stringResource(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { controller.confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) } })
    controller.pendingDraft?.let { next ->
        AlertDialog(onDismissRequest = { controller.pendingDraft = null },
            title = { Text(stringResource(R.string.host_editor_confirm_change)) },
            text = { Text(stringResource(if (controller.confirmingMosh) R.string.host_editor_mosh_clear else R.string.host_editor_startup_replace)) },
            confirmButton = { TextButton(onClick = { controller.draft = next; controller.pendingDraft = null },
                modifier = Modifier.testTag("host_editor_confirm_change")) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { controller.pendingDraft = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun HostEditorBasics(draft: HostEditorDraft, change: (HostEditorDraft) -> Unit) {
    OutlinedTextField(draft.label, { change(draft.copy(label = it)) }, label = { Text(stringResource(R.string.ui_name_optional)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host_editor_label"))
    OutlinedTextField(draft.hostname, { change(draft.copy(hostname = it)) }, label = { Text(stringResource(R.string.ui_hostname_or_ip_address)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host_editor_hostname"), isError = draft.hostname.isBlank())
    val largeFont = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints {
        val stacked = maxWidth < 360.dp || largeFont
        val username: @Composable (Modifier) -> Unit = { fieldModifier ->
            OutlinedTextField(draft.username, { change(draft.copy(username = it)) }, label = { Text(stringResource(R.string.ui_username)) },
                singleLine = true, modifier = fieldModifier.testTag("host_editor_username"), isError = draft.username.isBlank())
        }
        val port: @Composable (Modifier) -> Unit = { fieldModifier ->
            OutlinedTextField(draft.portText, { change(draft.copy(portText = it.filter(Char::isDigit))) }, label = { Text(stringResource(R.string.ui_port)) },
                singleLine = true, modifier = fieldModifier.testTag("host_editor_port"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = draft.portText.toIntOrNull() !in 1..65535)
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { username(Modifier.fillMaxWidth()); port(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { username(Modifier.weight(1f)); port(Modifier.width(112.dp)) }
    }
}

@Composable
internal fun HostEditorPage.title(): String = stringResource(when (this) {
    HostEditorPage.MAIN -> R.string.host_editor_basic
    HostEditorPage.CONNECTION -> R.string.host_editor_connection
    HostEditorPage.JUMPS -> R.string.host_editor_jumps
    HostEditorPage.STARTUP -> R.string.host_editor_startup
    HostEditorPage.SECURITY -> R.string.host_editor_security
    HostEditorPage.ADVANCED -> R.string.host_editor_advanced
})

@Composable
private fun HostEditorDraft.summary(page: HostEditorPage, snippets: List<CommandSnippet>): String = when (page) {
    HostEditorPage.MAIN -> ""
    HostEditorPage.CONNECTION -> "${protocol.label} · ${route.label()} · ${if (route == ConnectionRoute.TAILNET) AuthenticationMethod.TAILSCALE_SSH.label() else authentication.label()}"
    HostEditorPage.JUMPS -> when {
        protocol == ConnectionProtocol.MOSH -> stringResource(R.string.host_editor_mosh_unavailable)
        jumpIds.isEmpty() -> stringResource(R.string.host_editor_not_configured)
        else -> stringResource(R.string.host_editor_jump_count, jumpIds.size)
    }
    HostEditorPage.STARTUP -> when {
        workspace.mode != WorkspaceMode.DISABLED -> "tmux · ${workspace.mode.label()} · ${if (workspace.mode == WorkspaceMode.ATTACH) workspace.sessionId else workspace.name}"
        startupSnippetId != null -> snippets.firstOrNull { it.id == startupSnippetId }?.label ?: stringResource(R.string.host_editor_needs_attention)
        else -> stringResource(R.string.ui_do_not_run)
    }
    HostEditorPage.SECURITY -> stringResource(R.string.host_editor_security_summary,
        stringResource(if (protocol == ConnectionProtocol.SSH && agentForwarding) R.string.host_policy_enabled else R.string.host_policy_disabled),
        stringResource(when (reauthenticate) { null -> R.string.host_policy_inherited; true -> R.string.host_policy_enabled; false -> R.string.host_policy_disabled }))
    HostEditorPage.ADVANCED -> listOf(overrides.connectTimeoutSeconds, overrides.keepaliveSeconds, overrides.backgroundMultiplier, overrides.terminalType)
        .count { it != null }.let { if (it == 0) stringResource(R.string.host_policy_inherit) else stringResource(R.string.host_editor_override_count, it) }
}

@Composable
internal fun WorkspaceMode.label() = stringResource(when (this) {
    WorkspaceMode.DISABLED -> R.string.host_policy_disabled
    WorkspaceMode.CREATE -> R.string.workspace_create
    WorkspaceMode.ATTACH -> R.string.workspace_attach
    WorkspaceMode.CREATE_OR_ATTACH -> R.string.workspace_create_or_attach
})
