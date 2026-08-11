package website.sung.mangossh.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.presentation.SecurityBanner

/**
 * Settings tab: a category hub that opens one full-screen detail page at a
 * time within the same tab. Detail navigation is held on [viewModel] (see
 * [MangoSshViewModel.settingsDestination]) rather than local Compose state,
 * because the destination title and back affordance are rendered by the
 * shared top app bar in `MangoSshApp`, outside this composable's tree.
 */
@Composable
internal fun SettingsScreen(
    viewModel: MangoSshViewModel,
    state: SettingsScreenState,
    portableExport: ByteArray?,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val destination by viewModel.settingsDestination.collectAsStateWithLifecycle()
    val currentDestination = destination

    BackHandler(enabled = currentDestination != null) { viewModel.closeSettingsDestination() }

    if (currentDestination == null) {
        SettingsHub(
            state = state,
            onOpen = viewModel::openSettingsDestination,
            modifier = modifier,
        )
    } else {
        SettingsDetail(
            destination = currentDestination,
            state = state,
            portableExport = portableExport,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsHub(
    state: SettingsScreenState,
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = SettingsDestination.entries.filter {
        it != SettingsDestination.UPDATES || state.update.supported
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings_hub"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.vaultStatus !is VaultStatus.Ready) {
            item { SecurityBanner(state.vaultStatus) }
        }
        items(destinations, key = { it.name }) { destination ->
            SettingsHubRow(
                destination = destination,
                showBadge = destination == SettingsDestination.UPDATES && state.update.hasPendingUpdate,
                onClick = { onOpen(destination) },
            )
        }
    }
}

@Composable
private fun SettingsHubRow(
    destination: SettingsDestination,
    showBadge: Boolean,
    onClick: () -> Unit,
) {
    val updateBadgeDescription = stringResource(R.string.app_update_badge_description)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_category_${destination.name}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = destination.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(destination.title(), style = MaterialTheme.typography.titleMedium)
                Text(
                    destination.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (showBadge) {
                Badge(modifier = Modifier.semantics { contentDescription = updateBadgeDescription })
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsDetail(
    destination: SettingsDestination,
    state: SettingsScreenState,
    portableExport: ByteArray?,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val pageModifier = modifier
        .fillMaxSize()
        .testTag("settings_page_${destination.name}")
    when (destination) {
        SettingsDestination.APPEARANCE -> AppearanceSettingsPage(state.appearance, callbacks.appearance, pageModifier)
        SettingsDestination.TERMINAL -> TerminalSettingsPage(state.terminal, callbacks.terminal, pageModifier)
        SettingsDestination.SHORTCUTS -> ShortcutSettingsPage(state.shortcuts, callbacks.shortcuts, pageModifier)
        SettingsDestination.SECURITY -> SecuritySettingsPage(state.security, callbacks.security, pageModifier)
        SettingsDestination.BACKUP -> BackupSettingsPage(state.backup, portableExport, callbacks.backup, pageModifier)
        SettingsDestination.SNIPPETS -> SnippetSettingsPage(state.snippets, callbacks.snippets, pageModifier)
        SettingsDestination.TSNET -> TsnetSettingsPage(state.tsnet, callbacks.tsnet, pageModifier)
        SettingsDestination.UPDATES -> UpdateSettingsPage(state.update, callbacks.update, pageModifier)
    }
}
