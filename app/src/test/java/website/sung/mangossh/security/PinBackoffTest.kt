package website.sung.mangossh.security

import org.junit.Assert.*
import org.junit.Test

class PinBackoffTest {
    @Test fun fifthAttemptStartsCooldownWhichDoublesAndCaps() {
        var state = PinBackoff()
        repeat(4) { state = state.failed(1_000); assertEquals(0L, state.remainingMillis(1_000)) }
        state = state.failed(1_000)
        assertEquals(30_000L, state.remainingMillis(1_000))
        state = state.failed(31_000)
        assertEquals(60_000L, state.remainingMillis(31_000))
        repeat(30) { state = state.failed(state.blockedUntilMillis) }
        assertEquals(900_000L, state.remainingMillis(state.blockedUntilMillis - 900_000))
        assertEquals(0L, state.remainingMillis(state.blockedUntilMillis))
    }
}
