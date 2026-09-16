package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import website.sung.mangossh.presentation.EditorSaveOperation
import website.sung.mangossh.presentation.asString
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.MangoSettingsCard

/** Snippets detail page: post-connect commands that a host can run automatically. */
@Composable
internal fun SnippetSettingsPage(
    state: SnippetSettingsState,
    callbacks: SnippetSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var showSnippetEditor by rememberSaveable { mutableStateOf(false) }
    val snippets = state.snippets

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoSectionHeader(
                text = stringResource(R.string.ui_post_connect_snippets),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item {
            MangoSettingsCard {
                Text(
                    stringResource(R.string.ui_a_snippet_selected_in_the_host_editor_is_sent_automatically_when_the_she),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        editingSnippet = null
                        showSnippetEditor = true
                    },
                ) { Text(stringResource(R.string.ui_new_snippet_2)) }
            }
        }
        if (snippets.isEmpty()) {
            item {
                Text(stringResource(R.string.ui_no_snippets_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(snippets, key = { it.id }) { snippet ->
                MangoSettingsCard {
                    Text(snippet.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        snippet.script,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                editingSnippet = snippet
                                showSnippetEditor = true
                            },
                        ) { Text(stringResource(R.string.common_edit)) }
                        TextButton(onClick = { callbacks.onRemoveSnippet(snippet.id) }) { Text(stringResource(R.string.common_remove)) }
                    }
                }
            }
        }
    }

    if (showSnippetEditor) {
        CommandSnippetDialog(
            initial = editingSnippet,
            onDismiss = {
                showSnippetEditor = false
                editingSnippet = null
            },
            onSave = { label, script, appendNewline, save ->
                callbacks.onSaveSnippet(editingSnippet?.id, label, script, appendNewline, save)
            },
        )
    }
}

@Composable
private fun CommandSnippetDialog(
    initial: CommandSnippet?,
    onDismiss: () -> Unit,
    onSave: (label: String, script: String, appendNewline: Boolean, operation: EditorSaveOperation) -> Unit,
) {
    val save = remember { EditorSaveOperation(onDismiss) }
    DisposableEffect(save) { onDispose { save.dispose() } }
    var label by rememberSaveable(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var script by rememberSaveable(initial?.id) { mutableStateOf(initial?.script.orEmpty()) }
    var appendNewline by rememberSaveable(initial?.id) { mutableStateOf(initial?.appendNewline ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.ui_new_snippet) else stringResource(R.string.ui_edit_snippet)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                save.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ui_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text(stringResource(R.string.ui_shell_command_or_script)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = appendNewline, onCheckedChange = { appendNewline = it })
                    Text(stringResource(R.string.ui_append_newline_automatically))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), script, appendNewline, save) },
                enabled = !save.busy && label.isNotBlank() && script.isNotBlank(),
            ) { Text(stringResource(if (save.busy) R.string.vault_saving else R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
