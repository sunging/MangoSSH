package website.sung.mangossh.session

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** A waiting sibling consumes no global slot; cancellation always returns acquired permits. */
internal class TransferSupervisor(maximumActive: Int = 2) {
    private val global = Semaphore(maximumActive)
    private val sessions = ConcurrentHashMap<String, Semaphore>()
    suspend fun <T> run(sessionId: String, action: suspend () -> T): T =
        sessions.getOrPut(sessionId) { Semaphore(1) }.withPermit { global.withPermit { action() } }
}
