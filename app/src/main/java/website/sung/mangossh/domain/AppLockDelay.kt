package website.sung.mangossh.domain

/**
 * How long the app may stay in the background before returning to the
 * foreground requires the PIN or biometric prompt again.
 *
 * A non-default value trades some security for convenience: while the grace
 * window is open, app content remains visible in the recents thumbnail and
 * to anyone who picks up an unlocked device. [IMMEDIATELY] is the default
 * for that reason.
 */
enum class AppLockDelay(val preferenceValue: String, val delayMillis: Long) {
    IMMEDIATELY("immediately", 0L),
    THIRTY_SECONDS("30s", 30_000L),
    ONE_MINUTE("1m", 60_000L),
    FIVE_MINUTES("5m", 5 * 60_000L),
    ;

    companion object {
        val DEFAULT: AppLockDelay = IMMEDIATELY

        /** Resolves a persisted delay identifier while rejecting unknown or future values. */
        fun fromPreference(value: String?): AppLockDelay? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/**
 * Decides whether a returning foreground must re-authenticate.
 *
 * Uses a monotonic elapsed-realtime clock (see `SystemClock.elapsedRealtime`)
 * so a wall-clock change cannot extend the grace window. A missing or
 * backwards timestamp locks, since both indicate the backgrounding was never
 * recorded or the clock is not trustworthy.
 */
fun shouldLockOnResume(
    delay: AppLockDelay,
    backgroundedAtElapsedMillis: Long?,
    nowElapsedMillis: Long,
): Boolean {
    if (delay == AppLockDelay.IMMEDIATELY) return true
    val backgroundedAt = backgroundedAtElapsedMillis ?: return true
    if (nowElapsedMillis < backgroundedAt) return true
    return nowElapsedMillis - backgroundedAt >= delay.delayMillis
}
