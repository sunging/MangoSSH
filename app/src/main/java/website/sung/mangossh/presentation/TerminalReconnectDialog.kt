package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R

/** Each opening requires fresh consent to replay a snippet; a workspace never replays one. */
@Composable
internal fun TerminalReconnectDialog(
    hasWorkspace: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var allowStartupSnippet by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_reconnect)) },
        text = {
            Column {
                if (hasWorkspace) {
                    Text(stringResource(R.string.terminal_reconnect_workspace))
                } else {
                    Text(stringResource(R.string.terminal_reconnect_fresh))
                    Row {
                        Checkbox(allowStartupSnippet, { allowStartupSnippet = it },
                            Modifier.testTag("terminal_reconnect_snippet"))
                        Text(stringResource(R.string.terminal_reconnect_snippet))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(!hasWorkspace && allowStartupSnippet) },
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
