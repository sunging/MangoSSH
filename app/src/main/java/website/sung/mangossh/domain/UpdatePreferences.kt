package website.sung.mangossh.domain

/**
 * Device-local, non-secret update-check preferences.
 *
 * This is intentionally excluded from the encrypted vault and portable
 * backups: it describes local update-check bookkeeping, not a connection's
 * behavior or credentials.
 */
data class UpdatePreferences(
    val automaticCheckEnabled: Boolean = true,
    val lastCheckedAtEpochMillis: Long = 0L,
    val dismissedVersionCode: Long = 0L,
) {
    /**
     * True when a background check may run.
     *
     * A wall clock that has moved backwards re-enables the check immediately
     * rather than locking it out until the stale timestamp is passed. A
     * monotonic clock cannot back this throttle because it resets at boot.
     */
    fun shouldAutoCheck(nowEpochMillis: Long): Boolean {
        if (!automaticCheckEnabled) return false
        if (lastCheckedAtEpochMillis <= 0L) return true
        val elapsed = nowEpochMillis - lastCheckedAtEpochMillis
        return elapsed !in 0 until AUTO_CHECK_INTERVAL_MILLIS
    }

    /** True when the user already dismissed this version or an equal/newer one. */
    fun isDismissed(versionCode: Long): Boolean = versionCode <= dismissedVersionCode

    companion object {
        const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
