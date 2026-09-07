package website.sung.mangossh.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides how a keepalive loop waits between sends, trading battery for
 * connection liveness based on whether the app is in the foreground.
 *
 * - **Foreground:** a plain coroutine [delay]. The device is not dozing and the
 *   session wake lock (when held) keeps the CPU alive, so the user-configured
 *   interval is honoured exactly.
 * - **Background:** an [KeepaliveAlarm] (a `setAndAllowWhileIdle` alarm). Once
 *   the session wake lock is released for background power saving, a `delay`
 *   would silently stall for the whole Doze window and the connection would
 *   drop from a NAT or server timeout before the loop ever resumed. The alarm
 *   fires through Doze, at a deliberately longer interval, and the loop returns
 *   early if the app comes back to the foreground.
 *
 * Deep Doze throttles `setAndAllowWhileIdle` to roughly one alarm per app every
 * nine minutes. A long stationary background session can therefore still be
 * dropped by the far end; that surfaces through the loop's normal failure path.
 */
internal class SessionKeepaliveScheduler(
    private val appForeground: StateFlow<Boolean>,
    private val alarm: KeepaliveAlarm,
    /**
     * Supplies the current background interval multiple of the foreground
     * interval. Read on every background wait so a settings change takes effect
     * without reconnecting.
     */
    private val backgroundMultiplier: () -> Int = { DEFAULT_BACKGROUND_MULTIPLIER },
    private val foregroundWait: suspend (Long) -> Unit = { delay(it) },
) {
    /** Matches `runSshKeepaliveLoop`'s `waitForNextKeepalive` seam. */
    suspend fun waitForNextKeepalive(foregroundIntervalMillis: Long) {
        if (appForeground.value) {
            foregroundWait(foregroundIntervalMillis)
            return
        }

        val fired = CompletableDeferred<Unit>()
        val cancelAlarm = alarm.schedule(
            backgroundInterval(foregroundIntervalMillis, backgroundMultiplier()),
        ) {
            fired.complete(Unit)
        }
        try {
            coroutineScope {
                val resumeOnForeground = launch {
                    appForeground.first { it }
                    fired.complete(Unit)
                }
                fired.await()
                resumeOnForeground.cancel()
            }
        } finally {
            cancelAlarm()
        }
    }

    companion object {
        const val DEFAULT_BACKGROUND_MULTIPLIER = 4
        const val MIN_BACKGROUND_INTERVAL_MILLIS = 60_000L
        const val MAX_BACKGROUND_INTERVAL_MILLIS = 300_000L

        /**
         * Background wait for a [foregroundIntervalMillis] keepalive, stretched by
         * [multiplier] and clamped to the [MIN_BACKGROUND_INTERVAL_MILLIS]–
         * [MAX_BACKGROUND_INTERVAL_MILLIS] window. The floor keeps the app from
         * waking the radio more than once a minute in the background; the ceiling
         * keeps a NAT binding alive even at a long foreground interval.
         */
        fun backgroundInterval(foregroundIntervalMillis: Long, multiplier: Int): Long =
            (foregroundIntervalMillis * multiplier.coerceAtLeast(1))
                .coerceIn(MIN_BACKGROUND_INTERVAL_MILLIS, MAX_BACKGROUND_INTERVAL_MILLIS)
    }
}

/**
 * A one-shot wake-up that survives Doze, used to pace background keepalives.
 *
 * The platform implementation is [createKeepaliveAlarm]; tests substitute a fake.
 */
internal fun interface KeepaliveAlarm {
    /**
     * Arranges for [onFire] to run after about [delayMillis], waking the CPU if
     * the device is idle. [onFire] may run on any thread. Returns an idempotent
     * action that cancels the pending wake-up if it has not fired yet.
     */
    fun schedule(delayMillis: Long, onFire: () -> Unit): () -> Unit
}
