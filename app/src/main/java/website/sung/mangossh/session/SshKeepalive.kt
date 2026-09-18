package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Runs response-checked keepalives; each adapter request has a total 15-second deadline. */
internal suspend fun runSshKeepaliveLoop(
    intervalMillis: Long,
    isSessionActive: () -> Boolean,
    sendKeepalive: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
    waitForNextKeepalive: suspend (Long) -> Unit = { delay(it) },
) {
    require(intervalMillis > 0) { "Keepalive interval must be positive" }

    while (isSessionActive()) {
        waitForNextKeepalive(intervalMillis)
        if (!isSessionActive()) return

        try {
            sendKeepalive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A concurrent user close can make the transport write fail after
            // the active check. The user-initiated close already owns cleanup.
            //
            // [onFailure] typically tears the session down, and this loop is the
            // body of a root coroutine, so a throw from it would have nothing
            // left to catch it.
            if (isSessionActive()) runCatching { onFailure(error) }
            return
        }
    }
}
