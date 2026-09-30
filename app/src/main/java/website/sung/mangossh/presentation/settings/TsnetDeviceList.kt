package website.sung.mangossh.presentation.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.presentation.label
import website.sung.mangossh.session.tsnet.TsnetDevice
import website.sung.mangossh.session.tsnet.TsnetSelf
import website.sung.mangossh.ui.components.MangoSettingsCard
import website.sung.mangossh.ui.components.SettingsChoiceRow
import website.sung.mangossh.ui.components.mangoCardColors

/** The app node's tailnet identity; names and addresses are selectable for copying. */
@Composable
internal fun TsnetSelfCard(self: TsnetSelf?, nodeName: String?) {
    val name = self?.displayName?.takeIf(String::isNotBlank) ?: nodeName ?: return
    MangoSettingsCard(modifier = Modifier.testTag("embedded_tsnet_self")) {
        Text(
            stringResource(R.string.embedded_tsnet_self_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    modifier = Modifier.testTag("embedded_tsnet_self_name"),
                    style = MaterialTheme.typography.titleMedium,
                )
                self?.dnsName?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                self?.addresses?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Placeholder shown while the node is joining or the first snapshot is pending, or when the tailnet is empty. */
@Composable
internal fun TsnetDevicesPlaceholder(loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("embedded_tsnet_devices_placeholder"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            stringResource(if (loading) R.string.embedded_tsnet_devices_loading else R.string.embedded_tsnet_devices_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One tailnet peer: name, platform, reachability, whether it runs Tailscale
 * SSH, and a connect action. Tapping the row always opens quick connect; the
 * button reuses the saved host when exactly one already targets the device.
 */
@Composable
internal fun TsnetDeviceRow(
    device: TsnetDevice,
    onOpenQuickConnect: () -> Unit,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("embedded_tsnet_device_${device.id}"),
        colors = mangoCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = device.online, onClick = onOpenQuickConnect)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnlineDot(device.online)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        device.os.takeIf(String::isNotBlank),
                        device.addresses.firstOrNull(),
                        reachabilityText(device),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TailscaleSshBadge(device.tailscaleSshEnabled, Modifier.testTag("embedded_tsnet_device_ssh_${device.id}"))
            }
            TextButton(
                onClick = onConnect,
                enabled = device.online,
                modifier = Modifier.testTag("embedded_tsnet_device_connect_${device.id}"),
            ) {
                Text(stringResource(R.string.common_connect))
            }
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    val color = if (online) Color(0xFF2E9D57) else MaterialTheme.colorScheme.outline
    Spacer(
        Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun reachabilityText(device: TsnetDevice): String = when {
    device.online -> stringResource(R.string.embedded_tsnet_device_online)
    device.lastSeenEpochMillis > 0 -> stringResource(
        R.string.embedded_tsnet_device_last_seen,
        DateUtils.getRelativeTimeSpanString(
            device.lastSeenEpochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString(),
    )
    else -> stringResource(R.string.embedded_tsnet_device_offline)
}

@Composable
private fun TailscaleSshBadge(enabled: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            stringResource(if (enabled) R.string.embedded_tsnet_device_ssh_on else R.string.embedded_tsnet_device_ssh_off),
            modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Connects to [device] without first saving a host: saved hosts that already
 * target it are offered first, then a minimal form builds an unsaved
 * Embedded Tailscale profile. Passwords and OTPs are asked at connect time,
 * exactly as for saved hosts, so the form never holds a secret.
 */
@Composable
internal fun TsnetQuickConnectDialog(
    device: TsnetDevice,
    hosts: List<ConnectionProfile>,
    keys: List<StoredSshKey>,
    onConnectSavedHost: (ConnectionProfile) -> Unit,
    onQuickConnect: (ConnectionProfile) -> Unit,
    onSaveAsHost: (ConnectionProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val saved = savedTsnetHostsFor(device, hosts)
    var username by rememberSaveable(device.id) { mutableStateOf(suggestedTsnetUsername(device, hosts)) }
    var protocol by rememberSaveable(device.id) { mutableStateOf(ConnectionProtocol.SSH) }
    var authentication by rememberSaveable(device.id) { mutableStateOf(defaultTsnetAuthentication(device)) }
    var keyId by rememberSaveable(device.id) { mutableStateOf(keys.singleOrNull()?.id) }
    val keyMissing = authentication == AuthenticationMethod.PRIVATE_KEY && keys.none { it.id == keyId }
    val formValid = username.isNotBlank() && !keyMissing

    fun draft() = tsnetQuickConnectProfile(device, username, protocol, authentication, keyId)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("embedded_tsnet_quick_connect"),
        title = { Text(stringResource(R.string.embedded_tsnet_quick_connect_title, device.displayName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (saved.isNotEmpty()) {
                    Text(
                        stringResource(R.string.embedded_tsnet_quick_connect_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    saved.forEach { host ->
                        OutlinedButton(
                            onClick = { onConnectSavedHost(host) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("embedded_tsnet_quick_connect_saved_${host.id}"),
                        ) {
                            Text(
                                "${host.label} · ${host.username}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                if (device.tailscaleSshEnabled) {
                    Text(
                        stringResource(R.string.embedded_tsnet_quick_connect_ssh_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("embedded_tsnet_quick_connect_username"),
                    label = { Text(stringResource(R.string.ui_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.ui_protocol),
                    options = ConnectionProtocol.entries,
                    selected = protocol,
                    optionLabel = { it.label },
                    onSelect = { protocol = it },
                    modifier = Modifier.testTag("embedded_tsnet_quick_connect_protocol"),
                )
                SettingsChoiceRow(
                    label = stringResource(R.string.ui_authentication),
                    options = AuthenticationMethod.entries,
                    selected = authentication,
                    optionLabel = { it.label() },
                    onSelect = { authentication = it },
                    modifier = Modifier.testTag("embedded_tsnet_quick_connect_authentication"),
                )
                if (authentication == AuthenticationMethod.PRIVATE_KEY) {
                    if (keys.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_generate_or_import_a_private_key_on_the_keys_page_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val keyLabels = keys.associate { it.id to it.label }
                        val selectKey = stringResource(R.string.host_editor_select_key)
                        SettingsChoiceRow(
                            label = stringResource(R.string.ui_shared_private_key),
                            options = keys.map { it.id as String? },
                            selected = keyId,
                            optionLabel = { id -> keyLabels[id] ?: selectKey },
                            onSelect = { keyId = it },
                        )
                    }
                }
                TextButton(
                    onClick = { onSaveAsHost(draft()) },
                    enabled = formValid,
                    modifier = Modifier.testTag("embedded_tsnet_quick_connect_save"),
                ) {
                    Text(stringResource(R.string.embedded_tsnet_quick_connect_save_host))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onQuickConnect(draft()) },
                enabled = formValid,
                modifier = Modifier.testTag("embedded_tsnet_quick_connect_confirm"),
            ) {
                Text(stringResource(R.string.common_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
