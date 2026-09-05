package website.sung.mangossh.session

/**
 * Owns a renewable, bounded CPU wake-lock lease for foreground-owned sessions.
 *
 * All calls are confined to the service's main dispatcher. Foreground service
 * priority alone does not keep the CPU running when the screen turns off, so
 * SSH keepalives and the embedded transports need this separate lease. An idle
 * tsnet foreground service is deliberately not enough to require a wake lock.
 *
 * The platform adapter must use a non-reference-counted lock: renewing extends
 * the same lease rather than accumulating acquisitions. A finite timeout bounds
 * battery use if the renewal coroutine stops unexpectedly. This does not bypass
 * Doze network restrictions or recover a socket after the process is killed.
 */
internal class SessionWakeLock(
    private val acquire: (timeoutMillis: Long) -> Unit,
    private val release: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var required = false

    /** Applies a complete work snapshot, not a per-session reference increment. */
    fun update(hasSessions: Boolean, foregroundOwned: Boolean) {
        val nextRequired = hasSessions && foregroundOwned
        if (nextRequired == required) return
        required = nextRequired
        if (required) {
            renew()
        } else {
            releaseSafely()
        }
    }

    /**
     * Acquires or extends the lease only while live sessions still require it.
     * Called immediately on the first session and periodically by the service.
     * Retrying also recovers from a failed acquisition or an expired lease.
     */
    fun renew() {
        if (!required) return
        try {
            acquire(LEASE_TIMEOUT_MILLIS)
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    /** Stops renewal eligibility before releasing; repeated cleanup is harmless. */
    fun close() {
        update(hasSessions = false, foregroundOwned = false)
    }

    private fun releaseSafely() {
        try {
            release()
        } catch (error: Exception) {
            // A platform timeout can race release. Never let power management
            // failure skip the rest of session/service teardown.
            reportFailure(error)
        }
    }

    private fun reportFailure(error: Throwable) {
        // Diagnostics are best effort too; a rejected lock must not kill every
        // transport or break the foreground-service launch contract.
        runCatching { onFailure(error) }
    }

    companion object {
        const val LEASE_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val RENEW_INTERVAL_MILLIS = 5 * 60 * 1_000L
    }
}
