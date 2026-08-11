package website.sung.mangossh.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R

/**
 * One category shown on the Settings hub, each opening its own detail page.
 *
 * Grows as later commits add settings that need a home of their own
 * (`CONNECTION`, `ABOUT`); every existing entry keeps its ordinal position so
 * saved instance state never resolves a stale entry to the wrong category.
 */
internal enum class SettingsDestination {
    APPEARANCE,
    TERMINAL,
    SHORTCUTS,
    SECURITY,
    BACKUP,
    SNIPPETS,
    TSNET,
    UPDATES,
}

@Composable
internal fun SettingsDestination.title(): String = stringResource(
    when (this) {
        SettingsDestination.APPEARANCE -> R.string.settings_category_appearance_title
        SettingsDestination.TERMINAL -> R.string.settings_category_terminal_title
        SettingsDestination.SHORTCUTS -> R.string.settings_category_shortcuts_title
        SettingsDestination.SECURITY -> R.string.settings_category_security_title
        SettingsDestination.BACKUP -> R.string.settings_category_backup_title
        SettingsDestination.SNIPPETS -> R.string.settings_category_snippets_title
        SettingsDestination.TSNET -> R.string.settings_category_tsnet_title
        SettingsDestination.UPDATES -> R.string.settings_category_updates_title
    },
)

@Composable
internal fun SettingsDestination.summary(): String = stringResource(
    when (this) {
        SettingsDestination.APPEARANCE -> R.string.settings_category_appearance_summary
        SettingsDestination.TERMINAL -> R.string.settings_category_terminal_summary
        SettingsDestination.SHORTCUTS -> R.string.settings_category_shortcuts_summary
        SettingsDestination.SECURITY -> R.string.settings_category_security_summary
        SettingsDestination.BACKUP -> R.string.settings_category_backup_summary
        SettingsDestination.SNIPPETS -> R.string.settings_category_snippets_summary
        SettingsDestination.TSNET -> R.string.settings_category_tsnet_summary
        SettingsDestination.UPDATES -> R.string.settings_category_updates_summary
    },
)

internal fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.APPEARANCE -> Icons.Outlined.Palette
    SettingsDestination.TERMINAL -> Icons.Outlined.Terminal
    SettingsDestination.SHORTCUTS -> Icons.Outlined.Keyboard
    SettingsDestination.SECURITY -> Icons.Outlined.Lock
    SettingsDestination.BACKUP -> Icons.Outlined.Backup
    SettingsDestination.SNIPPETS -> Icons.Outlined.Code
    SettingsDestination.TSNET -> Icons.Outlined.Hub
    SettingsDestination.UPDATES -> Icons.Outlined.SystemUpdate
}
