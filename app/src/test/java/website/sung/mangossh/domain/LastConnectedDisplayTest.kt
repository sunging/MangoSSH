package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LastConnectedDisplayTest {
    @Test
    fun neverConnectedForNonPositiveTimestamps() {
        assertEquals(LastConnectedKind.NEVER, lastConnectedKind(0L, nowMillis = 1_000L))
        assertEquals(LastConnectedKind.NEVER, lastConnectedKind(-1L, nowMillis = 1_000L))
    }

    @Test
    fun relativeJustInsideTheSevenDayWindow() {
        val now = 10_000_000_000L
        val justInside = now - (LAST_CONNECTED_RELATIVE_WINDOW_MILLIS - 1)

        assertEquals(LastConnectedKind.RELATIVE, lastConnectedKind(justInside, now))
    }

    @Test
    fun absoluteAtAndBeyondTheSevenDayWindow() {
        val now = 10_000_000_000L
        val atBoundary = now - LAST_CONNECTED_RELATIVE_WINDOW_MILLIS
        val wellBeyond = now - LAST_CONNECTED_RELATIVE_WINDOW_MILLIS * 10

        assertEquals(LastConnectedKind.ABSOLUTE, lastConnectedKind(atBoundary, now))
        assertEquals(LastConnectedKind.ABSOLUTE, lastConnectedKind(wellBeyond, now))
    }

    @Test
    fun futureTimestampFromAClockRollbackIsTreatedAsRelative() {
        val now = 10_000_000_000L
        val future = now + 60_000L

        assertEquals(LastConnectedKind.RELATIVE, lastConnectedKind(future, now))
    }
}
