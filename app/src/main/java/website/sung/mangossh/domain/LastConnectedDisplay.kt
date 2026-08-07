package website.sung.mangossh.domain

/** How a host card should render [ConnectionProfile.lastConnectedAtEpochMillis]. */
enum class LastConnectedKind {
    /** The host has no recorded connection yet. */
    NEVER,

    /** Recent enough to read better as a relative span, e.g. "2 hours ago". */
    RELATIVE,

    /** Old enough that a relative span stops being useful; show a calendar date instead. */
    ABSOLUTE,
}

/** Relative time is shown for timestamps within this many milliseconds of [nowMillis]. */
const val LAST_CONNECTED_RELATIVE_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Chooses how to render a last-connected timestamp. A non-positive [epochMillis] means the host
 * has never been connected to. A timestamp in the future (e.g. after a clock rollback) is treated
 * as [LastConnectedKind.RELATIVE] rather than crashing a relative-time formatter.
 */
fun lastConnectedKind(epochMillis: Long, nowMillis: Long): LastConnectedKind = when {
    epochMillis <= 0L -> LastConnectedKind.NEVER
    nowMillis - epochMillis < LAST_CONNECTED_RELATIVE_WINDOW_MILLIS -> LastConnectedKind.RELATIVE
    else -> LastConnectedKind.ABSOLUTE
}
