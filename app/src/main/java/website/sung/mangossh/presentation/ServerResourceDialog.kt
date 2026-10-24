package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** A foreground-only resource watch; disposing the dialog releases sampling, not the terminal. */
@Composable
internal fun ServerResourceDialog(
    state: ServerResourceState,
    sessionId: String,
    connected: Boolean,
    onMonitoringChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ResourceMonitoringEffect(sessionId, connected, onMonitoringChange)
    val density = LocalDensity.current
    val clipboard = LocalClipboard.current
    var showVirtualNetworks by rememberSaveable(sessionId) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density, LocalClipboard provides clipboard) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = maxHeight * 0.92f)
                        .testTag("server_resources_dialog"),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                ) {
                    Box {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_server_resources), style = MaterialTheme.typography.headlineSmall)
                            SelectionContainer(Modifier.weight(1f, fill = false)) {
                                Column(Modifier.verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { ResourceStatus(state, connected) }
                                    state.snapshot?.let { snapshot ->
                                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                                            val columns = if (maxWidth / density.fontScale >= 600.dp) 2 else 1
                                            val primaryCards: List<@Composable () -> Unit> = listOf(
                                                { SystemResourceCard(snapshot) },
                                                { CapacityResourceCard(R.string.resource_memory, "resource_memory", snapshot.memory) },
                                                { CapacityResourceCard(if (snapshot.platform == ServerResourcePlatform.WINDOWS)
                                                    R.string.resource_page_file else R.string.resource_swap, "resource_swap", snapshot.swap, disabledWhenEmpty = true) },
                                            )
                                            val secondaryCards: List<@Composable () -> Unit> = listOf(
                                                { DiskResourceCard(snapshot.disks) },
                                                { NetworkResourceCard(snapshot.networks, showVirtualNetworks) { showVirtualNetworks = it } },
                                            )
                                            if (columns == 1) {
                                                Column(Modifier.testTag("resource_columns_1"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    (primaryCards + secondaryCards).forEach { it() }
                                                }
                                            } else {
                                                // Independent stacks keep short cards from inheriting the adjacent card's height.
                                                Row(Modifier.testTag("resource_columns_2"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    listOf(primaryCards, secondaryCards).forEach { cards ->
                                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            cards.forEach { it() }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onRefresh, enabled = connected && !state.isRefreshing,
                                    modifier = Modifier.testTag("resource_refresh")) { Text(stringResource(R.string.common_refresh)) }
                                TextButton(onClick = onDismiss, modifier = Modifier.testTag("resource_close")) {
                                    Text(stringResource(R.string.common_close))
                                }
                            }
                        }
                        // Refresh feedback must never change content height or the user's scroll position.
                        if (state.isRefreshing) {
                            LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("resource_loading"))
                        }
                    }
                }
            }
        }
    }
}

/** Each session key keeps its own callback so disposal cannot stop the newly selected session. */
@Composable
internal fun ResourceMonitoringEffect(sessionId: String, connected: Boolean, onChange: (Boolean) -> Unit) {
    key(sessionId) {
        val currentOnChange by rememberUpdatedState(onChange)
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner, connected) {
            var active = false
            fun sync() {
                val next = connected && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (active != next) { active = next; currentOnChange(next) }
            }
            val observer = LifecycleEventObserver { _, _ -> sync() }
            owner.lifecycle.addObserver(observer)
            sync()
            onDispose {
                owner.lifecycle.removeObserver(observer)
                if (active) currentOnChange(false)
            }
        }
    }
}

@Composable
private fun ResourceStatus(state: ServerResourceState, connected: Boolean) {
    if (state.isRefreshing && state.snapshot == null) Text(stringResource(R.string.ui_reading_resource_information_from_the_server))
    val failure = if (!connected) ServerResourceFailure.CONNECTION else state.failure
    failure?.let {
        Text(stringResource(when (it) {
            ServerResourceFailure.QUERY -> R.string.terminal_resource_query_failed
            ServerResourceFailure.TIMEOUT -> R.string.resource_timeout
            ServerResourceFailure.CONNECTION -> R.string.resource_connection_unavailable
            ServerResourceFailure.UNSUPPORTED -> R.string.resource_unsupported
        }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("resource_error"))
        if (state.snapshot != null) Text(stringResource(R.string.resource_stale), color = MaterialTheme.colorScheme.error)
    }
    state.snapshot?.let {
        val locale = LocalConfiguration.current.locales[0]
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(Date(it.collectedAtEpochMillis))
        Text(stringResource(R.string.resource_updated, time), style = MaterialTheme.typography.labelMedium)
    }
    if (connected && failure != ServerResourceFailure.CONNECTION && failure != ServerResourceFailure.UNSUPPORTED) {
        Text(stringResource(R.string.resource_auto_refresh), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ResourceCard(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SystemResourceCard(snapshot: ServerResourceSnapshot) {
    ResourceCard(stringResource(R.string.resource_system), "resource_system") {
        Text(snapshot.system ?: stringResource(if (snapshot.platform == ServerResourcePlatform.WINDOWS)
            R.string.resource_windows else R.string.resource_linux))
        snapshot.uptimeSeconds?.let { seconds ->
            Text(stringResource(R.string.resource_uptime, seconds / 86400, seconds / 3600 % 24, seconds / 60 % 60))
        } ?: UnavailableResource()
        HorizontalDivider()
        CpuResourceContent(snapshot)
    }
}

@Composable
private fun CpuResourceContent(snapshot: ServerResourceSnapshot) {
    Column(Modifier.testTag("resource_cpu"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.resource_cpu), style = MaterialTheme.typography.titleMedium)
            snapshot.logicalCpuCount?.let {
                Text(stringResource(R.string.resource_cpu_count, it), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            snapshot.cpuPercent != null -> UsageProgress((snapshot.cpuPercent / 100).toFloat())
            snapshot.cpuSampling -> Text(stringResource(R.string.resource_sampling))
            else -> UnavailableResource()
        }
        snapshot.loadAverage?.let { load ->
            val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 2 }
            Text(stringResource(R.string.resource_load, format.format(load[0]), format.format(load[1]), format.format(load[2])))
        }
    }
}

@Composable
private fun CapacityResourceCard(title: Int, tag: String, capacity: ResourceCapacity?, disabledWhenEmpty: Boolean = false) {
    ResourceCard(stringResource(title), tag) {
        if (capacity == null) UnavailableResource()
        else if (disabledWhenEmpty && capacity.totalBytes == 0L) Text(stringResource(R.string.resource_not_enabled))
        else CapacityResource(capacity)
    }
}

@Composable
private fun CapacityResource(capacity: ResourceCapacity) {
    UsageProgress(capacity.fraction)
    val locale = LocalConfiguration.current.locales[0]
    Text(stringResource(R.string.resource_used_total, formatResourceBytes(capacity.usedBytes.toDouble(), locale),
        formatResourceBytes(capacity.totalBytes.toDouble(), locale)))
    Text(stringResource(R.string.resource_available, formatResourceBytes(capacity.availableBytes.toDouble(), locale)),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun UsageProgress(fraction: Float) {
    Text(NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
        .format(fraction), style = MaterialTheme.typography.titleLarge)
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DiskResourceCard(disks: List<ResourceDisk>?) {
    ResourceCard(stringResource(R.string.resource_disks), "resource_disks") {
        if (disks == null) UnavailableResource()
        else if (disks.isEmpty()) Text(stringResource(R.string.resource_no_disks))
        else disks.forEachIndexed { index, disk ->
            if (index > 0) HorizontalDivider()
            Text(disk.name, style = MaterialTheme.typography.labelLarge)
            CapacityResource(disk.capacity)
        }
    }
}

@Composable
private fun NetworkResourceCard(networks: List<ResourceNetwork>?, showVirtual: Boolean, onShowVirtualChange: (Boolean) -> Unit) {
    ResourceCard(stringResource(R.string.resource_network), "resource_network") {
        val locale = LocalConfiguration.current.locales[0]
        DisableSelection {
            FilterChip(selected = showVirtual, onClick = { onShowVirtualChange(!showVirtual) },
                label = { Text(stringResource(R.string.resource_show_virtual_networks)) }, modifier = Modifier.testTag("resource_virtual_toggle"))
        }
        val visibleNetworks = networks?.filter { showVirtual || it.isVirtual != true }.orEmpty()
        if (networks == null) UnavailableResource()
        else if (networks.isEmpty()) Text(stringResource(R.string.resource_no_networks))
        else if (visibleNetworks.isEmpty()) Text(stringResource(R.string.resource_virtual_networks_hidden),
            modifier = Modifier.testTag("resource_virtual_hidden"))
        else visibleNetworks.forEachIndexed { index, network ->
            if (index > 0) HorizontalDivider()
            Text(network.name, style = MaterialTheme.typography.labelLarge)
            if (network.receivedBytesPerSecond == null || network.sentBytesPerSecond == null) {
                Text(stringResource(R.string.resource_sampling))
            } else {
                Text(stringResource(R.string.resource_received, formatResourceBytes(network.receivedBytesPerSecond, locale)))
                Text(stringResource(R.string.resource_sent, formatResourceBytes(network.sentBytesPerSecond, locale)))
            }
        }
    }
}

@Composable
private fun UnavailableResource() { Text(stringResource(R.string.resource_unavailable)) }

/** Formats byte counts and byte rates with the same binary units and the active UI locale. */
internal fun formatResourceBytes(bytes: Double, locale: Locale): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024; unit++ }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (unit == 0) 0 else 1 }
    return "${format.format(value)} ${units[unit]}"
}
