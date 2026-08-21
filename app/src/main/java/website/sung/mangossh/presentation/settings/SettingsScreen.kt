package website.sung.mangossh.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Badge
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.presentation.SecurityBanner
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.SettingsCategoryRow

/** Window width, in dp, at or above which Settings shows the hub and detail side by side. */
internal val SETTINGS_TWO_PANE_MIN_WIDTH = 600.dp

/**
 * Settings tab: a category hub that opens one detail page at a time.
 *
 * On compact windows the detail page replaces the hub and a back arrow (see
 * `MangoSshApp`'s top bar) returns to it. On windows at least
 * [SETTINGS_TWO_PANE_MIN_WIDTH] wide, both are shown side by side and the hub row for
 * the open category stays highlighted. Detail navigation is held on
 * [viewModel] (see [MangoSshViewModel.settingsDestination]) rather than local
 * Compose state, because the destination title and back affordance are
 * rendered by the shared top app bar in `MangoSshApp`, outside this
 * composable's own tree.
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
    val selectedDestination = destination
    val twoPane = LocalWindowInfo.current.containerDpSize.width >= SETTINGS_TWO_PANE_MIN_WIDTH

    BackHandler(enabled = !twoPane && selectedDestination != null) { viewModel.closeSettingsDestination() }

    if (twoPane) {
        Row(modifier = modifier.fillMaxSize()) {
            SettingsHub(
                state = state,
                selected = selectedDestination,
                onOpen = viewModel::openSettingsDestination,
                modifier = Modifier.width(320.dp).fillMaxHeight(),
            )
            VerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val visible = visibleDestinations(state)
                SettingsDetail(
                    destination = selectedDestination ?: visible.first(),
                    state = state,
                    portableExport = portableExport,
                    callbacks = callbacks,
                )
            }
        }
    } else if (selectedDestination == null) {
        SettingsHub(
            state = state,
            selected = null,
            onOpen = viewModel::openSettingsDestination,
            modifier = modifier,
        )
    } else {
        SettingsDetail(
            destination = selectedDestination,
            state = state,
            portableExport = portableExport,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

private fun visibleDestinations(state: SettingsScreenState): List<SettingsDestination> =
    SettingsDestination.entries.filter { it != SettingsDestination.UPDATES || state.update.supported }

@Composable
private fun SettingsHub(
    state: SettingsScreenState,
    selected: SettingsDestination?,
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val updateBadgeDescription = stringResource(R.string.app_update_badge_description)
    val sections = visibleDestinations(state).groupBy { it.section }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings_hub"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.vaultStatus !is VaultStatus.Ready) {
            item { SecurityBanner(state.vaultStatus) }
        }
        SettingsSection.entries.forEach { section ->
            val destinations = sections[section].orEmpty()
            if (destinations.isEmpty()) return@forEach
            item(key = section.name) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangoSectionHeader(
                        text = section.title(),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    // One card per section: rows inside sit flush, and the gap
                    // between cards is what separates one group from the next.
                    MangoPreferenceGroup(verticalPadding = 0.dp) {
                        destinations.forEach { destination ->
                            val badged = destination == SettingsDestination.UPDATES &&
                                state.update.hasPendingUpdate
                            SettingsCategoryRow(
                                icon = destination.icon(),
                                title = destination.title(),
                                summary = destination.summary(),
                                onClick = { onOpen(destination) },
                                modifier = Modifier.testTag("settings_category_${destination.name}"),
                                selected = destination == selected,
                                badge = if (badged) {
                                    {
                                        Badge(
                                            modifier = Modifier.semantics {
                                                contentDescription = updateBadgeDescription
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
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
        SettingsDestination.CONNECTION -> ConnectionSettingsPage(state.connection, callbacks.connection, pageModifier)
        SettingsDestination.SECURITY -> SecuritySettingsPage(state.security, callbacks.security, pageModifier)
        SettingsDestination.BACKUP -> BackupSettingsPage(state.backup, portableExport, callbacks.backup, pageModifier)
        SettingsDestination.SNIPPETS -> SnippetSettingsPage(state.snippets, callbacks.snippets, pageModifier)
        SettingsDestination.TSNET -> TsnetSettingsPage(state.tsnet, callbacks.tsnet, pageModifier)
        SettingsDestination.UPDATES -> UpdateSettingsPage(state.update, callbacks.update, pageModifier)
        SettingsDestination.ABOUT -> AboutSettingsPage(state.about, callbacks.about, pageModifier)
    }
}
