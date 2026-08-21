package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Verifies that a failed native stop cannot skip later descriptor cleanup. */
class MoshCleanupTest {
    @Test
    fun runsEveryCleanupStepAfterAnEarlierFailure() {
        val expected = IllegalStateException("native stop failed")
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()

        runMoshCleanupSteps(
            steps = listOf(
                {
                    calls += "stop"
                    throw expected
                },
                { calls += "output" },
                { calls += "input" },
                { calls += "master" },
            ),
            onFailure = failures::add,
        )

        assertEquals(listOf("stop", "output", "input", "master"), calls)
        assertEquals(1, failures.size)
        assertSame(expected, failures.single())
    }

    @Test
    fun reportingFailureCannotSkipRemainingCleanup() {
        val calls = mutableListOf<String>()

        runMoshCleanupSteps(
            steps = listOf(
                { throw IllegalStateException("stop") },
                { calls += "closed" },
            ),
            onFailure = { throw IllegalStateException("logger") },
        )

        assertEquals(listOf("closed"), calls)
    }
}
