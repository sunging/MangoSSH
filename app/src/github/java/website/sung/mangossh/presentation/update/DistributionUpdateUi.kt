package website.sung.mangossh.presentation.update

import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R

/** Accessibility label for the GitHub distribution's update badges. */
@Composable
internal fun distributionUpdateBadgeDescription(): String =
    stringResource(R.string.app_update_badge_description)

@StringRes
internal fun distributionUpdateTitleResource(): Int = R.string.settings_category_updates_title

@StringRes
internal fun distributionUpdateSummaryResource(): Int = R.string.settings_category_updates_summary
