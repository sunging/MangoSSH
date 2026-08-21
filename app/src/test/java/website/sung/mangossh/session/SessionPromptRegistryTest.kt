package website.sung.mangossh.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that a dying session can never strand a blocked prompt caller. */
class SessionPromptRegistryTest {
    @Test
    fun releasesAWaiterThatWasRegisteredButNeverPublished() {
        // This is the window the previous implementation missed: it cancelled by
        // walking the published prompt list, so a prompt registered between the
        // read and the clear kept its protocol thread parked until the prompt
        // timeout expired.
        val registry = SessionPromptRegistry()
        val waiter = registry.register("request", "session")

        assertEquals(1, registry.cancelSession("session"))
        assertTrue(waiter.isCompleted)
        assertNull(runBlocking { waiter.await() })
    }

    @Test
    fun deliversAnAnswerAndLeavesNothingForTeardown() {
        val registry = SessionPromptRegistry()
        val waiter = registry.register("request", "session")

        assertTrue(registry.complete("request", listOf("trust")))
        assertEquals(listOf("trust"), runBlocking { waiter.await() })
        assertEquals(0, registry.cancelSession("session"))
    }

    @Test
    fun ignoresASecondAnswerForTheSameRequest() {
        val registry = SessionPromptRegistry()
        val waiter = registry.register("request", "session")

        assertTrue(registry.complete("request", listOf("first")))
        assertFalse(registry.complete("request", listOf("second")))
        assertEquals(listOf("first"), runBlocking { waiter.await() })
    }

    @Test
    fun cancelsOnlyTheWaitersOfTheEndedSession() {
        val registry = SessionPromptRegistry()
        val target = registry.register("target-request", "target")
        val other = registry.register("other-request", "other")

        assertEquals(1, registry.cancelSession("target"))
        assertTrue(target.isCompleted)
        assertFalse(other.isCompleted)
    }

    @Test
    fun cancelAllReleasesEveryPendingWaiter() {
        val registry = SessionPromptRegistry()
        val first = registry.register("first", "session-a")
        val second = registry.register("second", "session-b")
        registry.complete("second", listOf("answered"))
        val third = registry.register("third", "session-b")

        assertEquals(2, registry.cancelAll())
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertTrue(third.isCompleted)
        assertNull(runBlocking { third.await() })
    }

    @Test
    fun releaseIsSafeForAnAlreadyAnsweredRequest() {
        val registry = SessionPromptRegistry()
        val waiter = registry.register("request", "session")
        registry.complete("request", listOf("trust"))

        registry.release("request")

        assertEquals(listOf("trust"), runBlocking { waiter.await() })
    }
}
