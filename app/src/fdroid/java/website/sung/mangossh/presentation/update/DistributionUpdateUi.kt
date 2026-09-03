package website.sung.mangossh.presentation.update

import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import website.sung.mangossh.R

/** F-Droid cannot show an update badge because its update destination is absent. */
@Composable
internal fun distributionUpdateBadgeDescription(): String = ""

/** Unreachable fallback used only to keep common Settings code resource-safe. */
@StringRes
internal fun distributionUpdateTitleResource(): Int = R.string.settings_category_about_title

/** Unreachable fallback used only to keep common Settings code resource-safe. */
@StringRes
internal fun distributionUpdateSummaryResource(): Int = R.string.settings_category_about_summary
