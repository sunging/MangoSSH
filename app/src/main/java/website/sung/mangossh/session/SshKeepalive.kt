package website.sung.mangossh.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sends one-way SSH transport keepalives while the owning session is active.
 *
 * The callback is deliberately one-way: ConnectBot's response-waiting
 * `Connection.ping()` has no timeout and can block forever after a silent
 * network loss. An SSH_MSG_IGNORE packet still keeps TCP/NAT state active,
 * while an immediate write failure is reported through [onFailure].
 */
internal suspend fun runSshKeepaliveLoop(
    intervalMillis: Long,
    isSessionActive: () -> Boolean,
    sendKeepalive: () -> Unit,
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
            if (isSessionActive()) onFailure(error)
            return
        }
    }
}
