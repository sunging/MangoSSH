package website.sung.mangossh.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePreferencesTest {
    private val oneDayMillis = UpdatePreferences.AUTO_CHECK_INTERVAL_MILLIS

    @Test
    fun neverCheckedAlwaysAllowsAnAutomaticCheck() {
        val preferences = UpdatePreferences(lastCheckedAtEpochMillis = 0L)

        assertTrue(preferences.shouldAutoCheck(nowEpochMillis = 1_000L))
    }

    @Test
    fun withinTheIntervalDoesNotAllowAnotherCheck() {
        val preferences = UpdatePreferences(lastCheckedAtEpochMillis = 10_000L)

        assertFalse(preferences.shouldAutoCheck(nowEpochMillis = 10_000L + oneDayMillis - 1))
    }

    @Test
    fun atExactlyOneDayAllowsAnotherCheck() {
        val preferences = UpdatePreferences(lastCheckedAtEpochMillis = 10_000L)

        assertTrue(preferences.shouldAutoCheck(nowEpochMillis = 10_000L + oneDayMillis))
    }

    @Test
    fun afterOneDayAllowsAnotherCheck() {
        val preferences = UpdatePreferences(lastCheckedAtEpochMillis = 10_000L)

        assertTrue(preferences.shouldAutoCheck(nowEpochMillis = 10_000L + oneDayMillis + 1))
    }

    @Test
    fun disabledNeverAllowsAnAutomaticCheckRegardlessOfElapsedTime() {
        val preferences = UpdatePreferences(automaticCheckEnabled = false, lastCheckedAtEpochMillis = 0L)

        assertFalse(preferences.shouldAutoCheck(nowEpochMillis = 10_000_000_000L))
    }

    @Test
    fun aClockMovedBackwardsReenablesTheCheckImmediately() {
        val preferences = UpdatePreferences(lastCheckedAtEpochMillis = 10_000_000L)

        assertTrue(preferences.shouldAutoCheck(nowEpochMillis = 10_000L))
    }

    @Test
    fun isDismissedComparesAgainstTheDismissedVersionCode() {
        val preferences = UpdatePreferences(dismissedVersionCode = 1_004_002L)

        assertTrue(preferences.isDismissed(versionCode = 1_004_001L))
        assertTrue(preferences.isDismissed(versionCode = 1_004_002L))
        assertFalse(preferences.isDismissed(versionCode = 1_004_003L))
    }
}
