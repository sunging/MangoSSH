package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockDelayTest {
    @Test
    fun defaultIsImmediately() {
        assertEquals(AppLockDelay.IMMEDIATELY, AppLockDelay.DEFAULT)
    }

    @Test
    fun preferenceIdsResolveOnlyKnownDelays() {
        assertEquals(AppLockDelay.THIRTY_SECONDS, AppLockDelay.fromPreference("30s"))
        assertEquals(AppLockDelay.ONE_MINUTE, AppLockDelay.fromPreference("1m"))
        assertEquals(AppLockDelay.FIVE_MINUTES, AppLockDelay.fromPreference("5m"))
        assertNull(AppLockDelay.fromPreference("10m"))
        assertNull(AppLockDelay.fromPreference(null))
    }

    @Test
    fun immediatelyAlwaysLocksRegardlessOfElapsedTime() {
        assertTrue(shouldLockOnResume(AppLockDelay.IMMEDIATELY, backgroundedAtElapsedMillis = 0L, nowElapsedMillis = 0L))
        assertTrue(shouldLockOnResume(AppLockDelay.IMMEDIATELY, backgroundedAtElapsedMillis = 0L, nowElapsedMillis = 1_000_000L))
    }

    @Test
    fun onlyImmediateModeLocksAsSoonAsTheAppBackgrounds() {
        assertTrue(shouldLockWhenBackgrounded(AppLockDelay.IMMEDIATELY))
        assertFalse(shouldLockWhenBackgrounded(AppLockDelay.THIRTY_SECONDS))
        assertFalse(shouldLockWhenBackgrounded(AppLockDelay.ONE_MINUTE))
        assertFalse(shouldLockWhenBackgrounded(AppLockDelay.FIVE_MINUTES))
    }

    @Test
    fun missingTimestampAlwaysLocks() {
        assertTrue(shouldLockOnResume(AppLockDelay.FIVE_MINUTES, backgroundedAtElapsedMillis = null, nowElapsedMillis = 0L))
    }

    @Test
    fun backwardsClockLocks() {
        assertTrue(shouldLockOnResume(AppLockDelay.FIVE_MINUTES, backgroundedAtElapsedMillis = 10_000L, nowElapsedMillis = 5_000L))
    }

    @Test
    fun withinDelayDoesNotLock() {
        assertFalse(
            shouldLockOnResume(
                AppLockDelay.ONE_MINUTE,
                backgroundedAtElapsedMillis = 0L,
                nowElapsedMillis = AppLockDelay.ONE_MINUTE.delayMillis - 1,
            ),
        )
    }

    @Test
    fun exactlyAtDelayLocks() {
        assertTrue(
            shouldLockOnResume(
                AppLockDelay.ONE_MINUTE,
                backgroundedAtElapsedMillis = 0L,
                nowElapsedMillis = AppLockDelay.ONE_MINUTE.delayMillis,
            ),
        )
    }

    @Test
    fun pastDelayLocks() {
        assertTrue(
            shouldLockOnResume(
                AppLockDelay.ONE_MINUTE,
                backgroundedAtElapsedMillis = 0L,
                nowElapsedMillis = AppLockDelay.ONE_MINUTE.delayMillis + 1,
            ),
        )
    }
}
