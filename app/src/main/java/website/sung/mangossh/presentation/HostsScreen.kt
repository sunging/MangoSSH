package website.sung.mangossh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.session.SessionKind
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState

/**
 * Host list screen: a search-filtered, optionally manually-reorderable list of connection
 * profiles, preceded by the vault security banner and any live terminal sessions.
 *
 * [hosts] is already filtered and sorted by the caller; [hasAnyHost] reflects the unfiltered
 * count so an empty *search result* can be told apart from a genuinely empty host list.
 */
@Composable
internal fun HostsScreen(
    hosts: List<ConnectionProfile>,
    hasAnyHost: Boolean,
    reorderable: Boolean,
    sessions: List<TerminalSessionState>,
    vaultStatus: VaultStatus,
    onEditHost: (ConnectionProfile) -> Unit,
    onRemoveHost: (String) -> Unit,
    onConnectHost: (ConnectionProfile) -> Unit,
    onBrowseHostFiles: (ConnectionProfile) -> Unit,
    onOpenSession: (String) -> Unit,
    onDisconnectSession: (String) -> Unit,
    onReorderHosts: (List<String>) -> Unit,
    onMoveHost: (id: String, delta: Int) -> Unit,
    onMoveHostToTop: (String) -> Unit,
) {
    if (hosts.isEmpty() && sessions.isEmpty()) {
        if (hasAnyHost) {
            NoHostsMatchSearch()
        } else {
            EmptyHosts(vaultStatus = vaultStatus)
        }
        return
    }

    // Dragging mutates this local copy immediately for visible feedback; onDragEnd commits the
    // final order upstream. Resyncing only while not dragging keeps an in-flight drag from being
    // clobbered by the vault snapshot it is about to produce.
    val ordered = remember { mutableStateListOf<ConnectionProfile>().apply { addAll(hosts) } }
    val listState = rememberLazyListState()
    val hostIds = remember(hosts) { hosts.mapTo(HashSet()) { it.id } }
    val reorderState = rememberReorderableListState(
        listState = listState,
        isReorderableKey = { key -> key is String && key in hostIds },
        onMove = { from, to ->
            val fromIndex = from - headerItemCount(vaultStatus, sessions)
            val toIndex = to - headerItemCount(vaultStatus, sessions)
            if (fromIndex in ordered.indices && toIndex in ordered.indices) {
                ordered.add(toIndex, ordered.removeAt(fromIndex))
            }
        },
        onDragEnd = { onReorderHosts(ordered.map { it.id }) },
    )
    LaunchedEffect(hosts) {
        if (reorderState.draggingKey == null) {
            ordered.clear()
            ordered.addAll(hosts)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vaultStatus !is VaultStatus.Ready) {
            item(key = "security-banner") { SecurityBanner(vaultStatus) }
        }
        // Transfer-only and forwarding-only connections have no shell to open,
        // so they are owned by the file browser and the forwarding rules rather
        // than listed as sessions the user can enter.
        val visibleSessions = sessions.filter {
            it.phase != TerminalSessionPhase.CLOSED && it.kind == SessionKind.TERMINAL
        }
        if (visibleSessions.isNotEmpty()) {
            item(key = "active-sessions-header") {
                Text(stringResource(R.string.ui_active_sessions), style = MaterialTheme.typography.titleMedium)
            }
            items(visibleSessions, key = { it.id }) { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                session.endpoint + " · " + session.phase.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onOpenSession(session.id) }) { Text(stringResource(R.string.common_open)) }
                        TextButton(onClick = { onDisconnectSession(session.id) }) { Text(stringResource(R.string.common_close)) }
                    }
                }
            }
        }
        itemsIndexed(items = ordered, key = { _, host -> host.id }) { index, host ->
            HostCard(
                host = host,
                reorderable = reorderable,
                isFirst = index == 0,
                isLast = index == ordered.lastIndex,
                modifier = if (reorderable) {
                    Modifier
                        .zIndex(if (reorderState.draggingKey == host.id) 1f else 0f)
                        .graphicsLayer { translationY = reorderState.offsetForKey(host.id) }
                        .dragReorderable(reorderState, host.id)
                } else {
                    Modifier
                },
                onConnect = { onConnectHost(host) },
                onEdit = { onEditHost(host) },
                onRemove = { onRemoveHost(host.id) },
                onBrowseFiles = { onBrowseHostFiles(host) },
                onMoveUp = { onMoveHost(host.id, -1) },
                onMoveDown = { onMoveHost(host.id, 1) },
                onMoveToTop = { onMoveHostToTop(host.id) },
            )
        }
    }
}

/** Non-host items shown ahead of the hosts in the list, in lazy-list item count. */
private fun headerItemCount(vaultStatus: VaultStatus, sessions: List<TerminalSessionState>): Int {
    val securityBanner = if (vaultStatus !is VaultStatus.Ready) 1 else 0
    val visibleSessionCount = sessions.count {
        it.phase != TerminalSessionPhase.CLOSED && it.kind == SessionKind.TERMINAL
    }
    val sessionsSection = if (visibleSessionCount > 0) 1 + visibleSessionCount else 0
    return securityBanner + sessionsSection
}

@Composable
private fun EmptyHosts(vaultStatus: VaultStatus) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Dns,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.ui_no_servers_yet), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_add_an_ssh_mosh_or_tailnet_host_profile_keys_are_managed_and_shared_sepa),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vaultStatus is VaultStatus.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = vaultStatus.failureMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NoHostsMatchSearch() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.ui_no_hosts_match_search), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_no_hosts_match_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SecurityBanner(vaultStatus: VaultStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_security_first), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = vaultStatus.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * A host row: tapping anywhere on the card connects. Every other action — files, edit, manual
 * reordering, remove — lives behind the trailing overflow menu so the card stays compact.
 */
@Composable
private fun HostCard(
    host: ConnectionProfile,
    reorderable: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onBrowseFiles: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onConnect,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    host.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${host.username}@${host.endpoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${host.protocol.label} · ${host.route.label()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.ui_host_actions, host.label),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // Mosh replaces its SSH bootstrap with a UDP terminal and has no
                    // channel left for SFTP.
                    if (host.protocol == ConnectionProtocol.SSH) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_files)) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = { menuExpanded = false; onBrowseFiles() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    if (reorderable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_move_up)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                            enabled = !isFirst,
                            onClick = { menuExpanded = false; onMoveUp() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_move_down)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                            enabled = !isLast,
                            onClick = { menuExpanded = false; onMoveDown() },
                        )
                        if (!isFirst) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_move_to_top)) },
                                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                                onClick = { menuExpanded = false; onMoveToTop() },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_remove),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Remove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuExpanded = false; onRemove() },
                    )
                }
            }
        }
    }
}
