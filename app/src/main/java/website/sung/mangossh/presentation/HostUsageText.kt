package website.sung.mangossh.presentation

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Date
import website.sung.mangossh.R
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.LastConnectedKind
import website.sung.mangossh.domain.lastConnectedKind

/**
 * Usage line shown under a host card, e.g. "3 connections · 2 hours ago" or "Never connected".
 * Timestamps within [website.sung.mangossh.domain.LAST_CONNECTED_RELATIVE_WINDOW_MILLIS] of now
 * render as a relative span; older ones render as a calendar date. The comparison is taken once
 * per composition rather than re-evaluated on a timer, so the wording only refreshes when the
 * host list itself recomposes (e.g. after a new connection).
 */
@Composable
internal fun hostUsageSummary(host: ConnectionProfile): String {
    val context = LocalContext.current
    if (host.connectionCount <= 0 && host.lastConnectedAtEpochMillis <= 0L) {
        return stringResource(R.string.ui_never_connected)
    }
    val now = System.currentTimeMillis()
    val count = pluralStringResource(R.plurals.ui_connection_count, host.connectionCount, host.connectionCount)
    val time = when (lastConnectedKind(host.lastConnectedAtEpochMillis, now)) {
        LastConnectedKind.NEVER -> stringResource(R.string.ui_never_connected)
        LastConnectedKind.RELATIVE -> DateUtils.getRelativeTimeSpanString(
            host.lastConnectedAtEpochMillis,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        LastConnectedKind.ABSOLUTE -> formatShortDate(context, host.lastConnectedAtEpochMillis)
    }
    return "$count · $time"
}

/** Locale-aware short calendar date, e.g. "8/7/26". */
internal fun formatShortDate(context: Context, epochMillis: Long): String {
    val locale = context.resources.configuration.locales[0]
    return DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(epochMillis))
}

/** Locale-aware short date and time, e.g. "8/7/26, 2:30 PM". */
internal fun formatShortDateTime(context: Context, epochMillis: Long): String {
    val locale = context.resources.configuration.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(epochMillis))
}
