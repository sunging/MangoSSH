package website.sung.mangossh.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Tracks the blocked callers waiting for a host-key or authentication answer.
 *
 * The registry is the authority on which waiters exist, and it records the
 * owning session next to every waiter. Session teardown therefore cancels by
 * walking this map rather than the published prompt list: a prompt is
 * registered here strictly before it becomes visible to the UI, so cancelling
 * from the visible list would miss a waiter registered in between and leave a
 * protocol thread parked until its timeout.
 *
 * Every entry carries a one-shot [CompletableDeferred]; completing an already
 * completed or removed waiter is a no-op, so answering, cancelling, and session
 * teardown may race freely.
 */
internal class SessionPromptRegistry {
    private val waiters = ConcurrentHashMap<String, Waiter>()

    /**
     * Claims [requestId] for [sessionId] and returns the waiter its caller
     * should await. Call this before publishing the prompt to the UI.
     */
    fun register(requestId: String, sessionId: String): CompletableDeferred<List<String>?> {
        val waiter = Waiter(sessionId, CompletableDeferred())
        waiters[requestId] = waiter
        return waiter.answer
    }

    /**
     * Delivers a user answer, or `null` for an explicit dismissal.
     *
     * Returns whether this call was the one that resolved the prompt.
     */
    fun complete(requestId: String, values: List<String>?): Boolean =
        waiters.remove(requestId)?.answer?.complete(values) == true

    /** Releases the waiter for [requestId] without an answer, if it is still pending. */
    fun release(requestId: String) {
        waiters.remove(requestId)?.answer?.complete(null)
    }

    /**
     * Releases every waiter belonging to [sessionId] and returns how many were
     * still pending. Called from session teardown so a dead transport can never
     * hold a blocked protocol thread.
     */
    fun cancelSession(sessionId: String): Int {
        var released = 0
        waiters.entries
            .filter { (_, waiter) -> waiter.sessionId == sessionId }
            .forEach { (requestId, _) ->
                if (waiters.remove(requestId)?.answer?.complete(null) == true) released++
            }
        return released
    }

    /** Releases every waiter, for controller-wide shutdown. */
    fun cancelAll(): Int {
        var released = 0
        waiters.keys.toList().forEach { requestId ->
            if (waiters.remove(requestId)?.answer?.complete(null) == true) released++
        }
        return released
    }

    private class Waiter(
        val sessionId: String,
        val answer: CompletableDeferred<List<String>?>,
    )
}
