package website.sung.mangossh.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R

/**
 * One category shown on the Settings hub, each opening its own detail page.
 *
 * Held only in memory (see [MangoSshViewModel.settingsDestination]), never
 * serialized by ordinal or name, so entries can be added or reordered
 * freely.
 */
internal enum class SettingsDestination {
    APPEARANCE,
    TERMINAL,
    SHORTCUTS,
    CONNECTION,
    SECURITY,
    BACKUP,
    SNIPPETS,
    TSNET,
    UPDATES,
    ABOUT,
}

/**
 * The hub groups categories under these headings so ten equal-weight rows read
 * as four short lists. Declaration order is display order, and a destination
 * belongs to exactly one section (see [SettingsDestination.section]).
 */
internal enum class SettingsSection {
    PERSONALIZATION,
    CONNECTIVITY,
    DATA,
    SYSTEM,
}

internal val SettingsDestination.section: SettingsSection
    get() = when (this) {
        SettingsDestination.APPEARANCE,
        SettingsDestination.TERMINAL,
        SettingsDestination.SHORTCUTS,
        -> SettingsSection.PERSONALIZATION

        SettingsDestination.CONNECTION,
        SettingsDestination.TSNET,
        SettingsDestination.SNIPPETS,
        -> SettingsSection.CONNECTIVITY

        SettingsDestination.SECURITY,
        SettingsDestination.BACKUP,
        -> SettingsSection.DATA

        SettingsDestination.UPDATES,
        SettingsDestination.ABOUT,
        -> SettingsSection.SYSTEM
    }

@Composable
internal fun SettingsSection.title(): String = stringResource(
    when (this) {
        SettingsSection.PERSONALIZATION -> R.string.settings_section_personalization
        SettingsSection.CONNECTIVITY -> R.string.settings_section_connectivity
        SettingsSection.DATA -> R.string.settings_section_data
        SettingsSection.SYSTEM -> R.string.settings_section_system
    },
)

@Composable
internal fun SettingsDestination.title(): String = stringResource(
    when (this) {
        SettingsDestination.APPEARANCE -> R.string.settings_category_appearance_title
        SettingsDestination.TERMINAL -> R.string.settings_category_terminal_title
        SettingsDestination.SHORTCUTS -> R.string.settings_category_shortcuts_title
        SettingsDestination.CONNECTION -> R.string.settings_category_connection_title
        SettingsDestination.SECURITY -> R.string.settings_category_security_title
        SettingsDestination.BACKUP -> R.string.settings_category_backup_title
        SettingsDestination.SNIPPETS -> R.string.settings_category_snippets_title
        SettingsDestination.TSNET -> R.string.settings_category_tsnet_title
        SettingsDestination.UPDATES -> R.string.settings_category_updates_title
        SettingsDestination.ABOUT -> R.string.settings_category_about_title
    },
)

@Composable
internal fun SettingsDestination.summary(): String = stringResource(
    when (this) {
        SettingsDestination.APPEARANCE -> R.string.settings_category_appearance_summary
        SettingsDestination.TERMINAL -> R.string.settings_category_terminal_summary
        SettingsDestination.SHORTCUTS -> R.string.settings_category_shortcuts_summary
        SettingsDestination.CONNECTION -> R.string.settings_category_connection_summary
        SettingsDestination.SECURITY -> R.string.settings_category_security_summary
        SettingsDestination.BACKUP -> R.string.settings_category_backup_summary
        SettingsDestination.SNIPPETS -> R.string.settings_category_snippets_summary
        SettingsDestination.TSNET -> R.string.settings_category_tsnet_summary
        SettingsDestination.UPDATES -> R.string.settings_category_updates_summary
        SettingsDestination.ABOUT -> R.string.settings_category_about_summary
    },
)

internal fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.APPEARANCE -> Icons.Outlined.Palette
    SettingsDestination.TERMINAL -> Icons.Outlined.Terminal
    SettingsDestination.SHORTCUTS -> Icons.Outlined.Keyboard
    SettingsDestination.CONNECTION -> Icons.Outlined.SettingsEthernet
    SettingsDestination.SECURITY -> Icons.Outlined.Lock
    SettingsDestination.BACKUP -> Icons.Outlined.Backup
    SettingsDestination.SNIPPETS -> Icons.Outlined.Code
    SettingsDestination.TSNET -> Icons.Outlined.Hub
    SettingsDestination.UPDATES -> Icons.Outlined.SystemUpdate
    SettingsDestination.ABOUT -> Icons.Outlined.Info
}
