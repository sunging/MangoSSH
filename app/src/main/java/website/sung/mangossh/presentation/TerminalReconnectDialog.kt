package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalColorScheme
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.ui.theme.terminalChromeColorScheme

/**
 * Opens confirmation on entry to a closed terminal or a transition to CLOSED. Dismissal
 * survives recomposition; only another close or leaving and reopening the terminal asks again.
 * This requests UI only and never opens a transport.
 */
@Composable
internal fun rememberTerminalReconnectRequest(sessionId: String, phase: TerminalSessionPhase): MutableState<Boolean> {
    val requested = remember(sessionId) { mutableStateOf(phase == TerminalSessionPhase.CLOSED) }
    LaunchedEffect(sessionId, phase) { requested.value = phase == TerminalSessionPhase.CLOSED }
    return requested
}

/**
 * Recovery follows the terminal palette even when the app theme differs. Only an available
 * startup snippet can be replayed, with fresh consent on each opening; workspaces never replay it.
 * The failure comes from the retained, localized exit category, never raw peer exception text.
 */
@Composable
internal fun TerminalReconnectDialog(
    hasWorkspace: Boolean,
    hasStartupSnippet: Boolean,
    terminalColors: TerminalColorScheme,
    failure: UiText? = null,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val canRunSnippet = hasStartupSnippet && !hasWorkspace
    var allowStartupSnippet by remember(canRunSnippet) { mutableStateOf(false) }
    MaterialTheme(colorScheme = remember(terminalColors) { terminalChromeColorScheme(terminalColors) }) {
        AlertDialog(
            modifier = Modifier.testTag("terminal_reconnect_dialog"),
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.terminal_reconnect)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    failure?.let {
                        Text(it.asString(), modifier = Modifier.testTag("terminal_reconnect_failure"),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(stringResource(if (hasWorkspace) R.string.terminal_reconnect_workspace else R.string.terminal_reconnect_fresh))
                    if (canRunSnippet) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .testTag("terminal_reconnect_snippet")
                                    .toggleable(value = allowStartupSnippet, role = Role.Checkbox,
                                        onValueChange = { allowStartupSnippet = it })
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Checkbox(checked = allowStartupSnippet, onCheckedChange = null)
                                Text(stringResource(R.string.terminal_reconnect_snippet),
                                    modifier = Modifier.weight(1f).testTag("terminal_reconnect_snippet_label"),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onConfirm(canRunSnippet && allowStartupSnippet) },
                    modifier = Modifier.testTag("terminal_reconnect_confirm")) {
                    Text(stringResource(R.string.terminal_reconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("terminal_reconnect_cancel")) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
