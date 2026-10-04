package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.tsnet.EmbeddedTsnetControlServer
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus
import website.sung.mangossh.ui.components.MangoSettingsCard

/**
 * Controls enrollment into MangoSSH's embedded, process-scoped Tailscale (tsnet) node.
 *
 * [controlUrl] is the server of the stored identity or pending enrollment. It
 * is editable only while no registration exists; sign-in callbacks receive
 * the normalized value, "" meaning Tailscale's own control plane.
 */
@Composable
internal fun EmbeddedTsnetCard(
    status: EmbeddedTsnetStatus,
    onBeginBrowserEnrollment: (controlUrl: String) -> Unit,
    onBeginAuthKeyEnrollment: (authKey: CharArray, controlUrl: String) -> Unit,
    onLogout: () -> Unit,
    controlUrl: String = "",
) {
    var showAuthKeyDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    // A server URL is not a secret, so the draft may survive recreation.
    var controlUrlDraft by rememberSaveable(controlUrl) { mutableStateOf(controlUrl) }
    val normalizedControlUrl = EmbeddedTsnetControlServer.normalize(controlUrlDraft)
    val statusText = if (!status.identityResolved) {
        stringResource(R.string.embedded_tsnet_status_checking)
    } else when (status.phase) {
        EmbeddedTsnetPhase.UNENROLLED -> stringResource(R.string.embedded_tsnet_status_unenrolled)
        EmbeddedTsnetPhase.STARTING -> stringResource(R.string.embedded_tsnet_status_starting)
        EmbeddedTsnetPhase.WAITING_FOR_LOGIN ->
            stringResource(R.string.embedded_tsnet_status_waiting_login)
        EmbeddedTsnetPhase.WAITING_FOR_APPROVAL ->
            stringResource(R.string.embedded_tsnet_status_waiting_approval)
        EmbeddedTsnetPhase.READY_IDLE -> stringResource(R.string.embedded_tsnet_status_ready_idle)
        // With no session, only the visible device list keeps the node up.
        EmbeddedTsnetPhase.ACTIVE -> if (status.activeSessions == 0) {
            stringResource(R.string.embedded_tsnet_status_connected)
        } else {
            pluralStringResource(
                R.plurals.embedded_tsnet_status_active,
                status.activeSessions,
                status.activeSessions,
            )
        }
        EmbeddedTsnetPhase.FAILED -> stringResource(R.string.embedded_tsnet_status_failed)
    }
    // Until the stored identity is read, UNENROLLED is only a placeholder:
    // offering sign-in then would flash for an already signed-in node.
    val canStartEnrollment = status.identityResolved && (
        status.phase == EmbeddedTsnetPhase.UNENROLLED ||
            status.phase == EmbeddedTsnetPhase.FAILED ||
            status.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN
        )
    val canLogout = status.phase == EmbeddedTsnetPhase.READY_IDLE ||
        status.phase == EmbeddedTsnetPhase.ACTIVE
    // A registered identity stays with its server; changing it needs a logout.
    val canChooseServer = canStartEnrollment && status.authKeyAllowed

    // No in-card heading: the settings page this card fills is already titled
    // "Embedded Tailscale" in the top bar.
    MangoSettingsCard(modifier = Modifier.testTag("embedded_tsnet_card")) {
        Text(
            stringResource(R.string.embedded_tsnet_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            statusText,
            modifier = Modifier.testTag("embedded_tsnet_status"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (canChooseServer) {
            OutlinedTextField(
                value = controlUrlDraft,
                onValueChange = { controlUrlDraft = it.take(EmbeddedTsnetControlServer.MAX_LENGTH) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("embedded_tsnet_control_url"),
                label = { Text(stringResource(R.string.embedded_tsnet_control_server_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (normalizedControlUrl == null) {
                                R.string.embedded_tsnet_control_server_invalid
                            } else {
                                R.string.embedded_tsnet_control_server_hint
                            },
                        ),
                    )
                },
                isError = normalizedControlUrl == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
        } else if (controlUrl.isNotEmpty()) {
            Text(
                stringResource(R.string.embedded_tsnet_control_server_current, controlUrl),
                modifier = Modifier.testTag("embedded_tsnet_control_url_current"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canStartEnrollment) {
            // A registered node re-authenticates with its own server, so only
            // a fresh enrollment depends on the draft being valid.
            val enrollmentServer = if (canChooseServer) normalizedControlUrl else controlUrl
            Button(
                onClick = { enrollmentServer?.let(onBeginBrowserEnrollment) },
                enabled = enrollmentServer != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("embedded_tsnet_browser_login"),
            ) {
                Text(stringResource(R.string.embedded_tsnet_browser_login))
            }
            if (status.authKeyAllowed) {
                OutlinedButton(
                    onClick = { showAuthKeyDialog = true },
                    enabled = enrollmentServer != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("embedded_tsnet_auth_key_login"),
                ) {
                    Text(stringResource(R.string.embedded_tsnet_auth_key_login))
                }
            }
        }
        if (canLogout) {
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                enabled = status.activeSessions == 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("embedded_tsnet_logout"),
            ) {
                Text(stringResource(R.string.embedded_tsnet_logout))
            }
        }
    }

    if (showAuthKeyDialog) {
        EmbeddedTsnetAuthKeyDialog(
            onDismiss = { showAuthKeyDialog = false },
            onConfirm = { key ->
                showAuthKeyDialog = false
                val server = EmbeddedTsnetControlServer.normalize(controlUrlDraft)
                if (server == null) key.fill('\u0000') else onBeginAuthKeyEnrollment(key, server)
            },
        )
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.embedded_tsnet_logout_title)) },
            text = { Text(stringResource(R.string.embedded_tsnet_logout_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                ) {
                    Text(stringResource(R.string.embedded_tsnet_logout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.embedded_tsnet_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmbeddedTsnetAuthKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    // Deliberately not rememberSaveable: activity recreation must discard the
    // one-shot credential rather than place it in SavedState.
    var authKey by remember { mutableStateOf("") }

    fun clearAndDismiss() {
        authKey = ""
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::clearAndDismiss,
        title = { Text(stringResource(R.string.embedded_tsnet_auth_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.embedded_tsnet_auth_key_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it },
                    modifier = Modifier.testTag("embedded_tsnet_auth_key_input"),
                    label = { Text(stringResource(R.string.embedded_tsnet_auth_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = authKey.isNotBlank(),
                modifier = Modifier.testTag("embedded_tsnet_auth_key_confirm"),
                onClick = {
                    val transient = authKey.toCharArray()
                    authKey = ""
                    onConfirm(transient)
                },
            ) {
                Text(stringResource(R.string.embedded_tsnet_auth_key_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = ::clearAndDismiss) {
                Text(stringResource(R.string.embedded_tsnet_cancel))
            }
        },
    )
}
