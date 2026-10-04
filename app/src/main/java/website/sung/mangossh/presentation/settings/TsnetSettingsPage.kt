package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import website.sung.mangossh.R
import website.sung.mangossh.presentation.EmbeddedTsnetCard
import website.sung.mangossh.session.tsnet.EmbeddedTsnetBuildInfo
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.ui.components.MangoSectionHeader

/**
 * Tailscale (tsnet) detail page: embedded node enrollment, this node's
 * tailnet name, and the other devices on the tailnet with quick connect.
 *
 * While the page is resumed it holds the embedded node up so the device list
 * stays live; pausing or leaving releases that hold.
 */
@Composable
internal fun TsnetSettingsPage(
    state: TsnetSettingsState,
    callbacks: TsnetSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val onBrowsingChanged by rememberUpdatedState(callbacks.onBrowsingChanged)
    LifecycleResumeEffect(Unit) {
        onBrowsingChanged(true)
        onPauseOrDispose { onBrowsingChanged(false) }
    }

    val status = state.status
    val nodeRunning = status.phase == EmbeddedTsnetPhase.ACTIVE
    val showDevices = nodeRunning ||
        status.phase == EmbeddedTsnetPhase.STARTING ||
        status.phase == EmbeddedTsnetPhase.READY_IDLE
    // Only trust the snapshot while the node that produced it is running.
    val network = state.network.takeIf { nodeRunning }
    var quickConnectDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmbeddedTsnetCard(
                status = status,
                controlUrl = state.controlUrl,
                onBeginBrowserEnrollment = callbacks.onBeginBrowserEnrollment,
                onBeginAuthKeyEnrollment = callbacks.onBeginAuthKeyEnrollment,
                onLogout = callbacks.onLogout,
            )
        }
        if (status.phase != EmbeddedTsnetPhase.UNENROLLED) {
            item { TsnetSelfCard(self = network?.self, nodeName = state.nodeName) }
        }
        if (showDevices) {
            item {
                val online = network?.devices?.count { it.online }
                MangoSectionHeader(
                    if (online == null) {
                        stringResource(R.string.embedded_tsnet_devices_title)
                    } else {
                        pluralStringResource(R.plurals.embedded_tsnet_devices_title_online, online, online)
                    },
                )
            }
            when {
                network == null -> item { TsnetDevicesPlaceholder(loading = true) }
                network.devices.isEmpty() -> item { TsnetDevicesPlaceholder(loading = false) }
                else -> items(network.devices, key = { it.id }) { device ->
                    TsnetDeviceRow(
                        device = device,
                        onOpenQuickConnect = { quickConnectDeviceId = device.id },
                        onConnect = {
                            val saved = savedTsnetHostsFor(device, state.hosts).singleOrNull()
                            if (saved != null) callbacks.onConnectSavedHost(saved) else quickConnectDeviceId = device.id
                        },
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.embedded_tsnet_version, EmbeddedTsnetBuildInfo.TAILSCALE_VERSION),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("embedded_tsnet_version"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    val quickConnectDevice = network?.devices?.firstOrNull { it.id == quickConnectDeviceId }
    if (quickConnectDevice != null) {
        fun dismissThen(action: () -> Unit) {
            quickConnectDeviceId = null
            action()
        }
        TsnetQuickConnectDialog(
            device = quickConnectDevice,
            hosts = state.hosts,
            keys = state.keys,
            onConnectSavedHost = { dismissThen { callbacks.onConnectSavedHost(it) } },
            onQuickConnect = { dismissThen { callbacks.onQuickConnect(it) } },
            onSaveAsHost = { dismissThen { callbacks.onSaveAsHost(it) } },
            onDismiss = { quickConnectDeviceId = null },
        )
    }
}
