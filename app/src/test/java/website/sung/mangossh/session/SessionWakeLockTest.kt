package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWakeLockTest {
    @Test
    fun idleServiceDoesNotAcquireALock() {
        val fixture = Fixture()
        fixture.owner.update(hasSessions = false, foregroundOwned = false)
        fixture.owner.renew()
        fixture.owner.close()
        assertTrue(fixture.acquisitions.isEmpty())
        assertEquals(0, fixture.releases)
    }

    @Test
    fun tsnetOnlyForegroundOwnershipDoesNotKeepTheCpuAwake() {
        val fixture = Fixture()
        fixture.owner.update(hasSessions = false, foregroundOwned = true)
        fixture.owner.renew()
        assertTrue(fixture.acquisitions.isEmpty())
    }

    @Test
    fun sessionsWithoutForegroundOwnershipDoNotAcquireALock() {
        val fixture = Fixture()
        fixture.owner.update(hasSessions = true, foregroundOwned = false)
        fixture.owner.renew()
        assertFalse(fixture.held)
        assertTrue(fixture.acquisitions.isEmpty())
    }

    @Test
    fun firstForegroundOwnedSessionImmediatelyAcquiresABoundedLease() {
        val fixture = Fixture()
        fixture.open()
        assertTrue(fixture.held)
        assertEquals(listOf(SessionWakeLock.LEASE_TIMEOUT_MILLIS), fixture.acquisitions)
        assertTrue(SessionWakeLock.RENEW_INTERVAL_MILLIS > 0)
        assertTrue(SessionWakeLock.RENEW_INTERVAL_MILLIS < SessionWakeLock.LEASE_TIMEOUT_MILLIS)
    }

    @Test
    fun repeatedSnapshotsAndAdditionalSessionsDoNotAccumulateAcquisitions() {
        val fixture = Fixture()
        fixture.open()
        // Opening another session, changing phase, and closing one of two
        // sessions all leave the aggregate hasSessions value true.
        repeat(3) { fixture.open() }
        assertEquals(1, fixture.acquisitions.size)
        assertEquals(0, fixture.releases)
    }

    @Test
    fun longRunningSessionRenewsEvenWhenThePlatformLockIsStillHeld() {
        val fixture = Fixture()
        fixture.open()
        repeat(4) { fixture.owner.renew() }
        assertEquals(List(5) { SessionWakeLock.LEASE_TIMEOUT_MILLIS }, fixture.acquisitions)
        assertTrue(fixture.held)
    }

    @Test
    fun renewalRecoversAnExpiredPlatformLease() {
        val fixture = Fixture()
        fixture.open()
        fixture.held = false
        fixture.owner.renew()
        assertTrue(fixture.held)
        assertEquals(2, fixture.acquisitions.size)
    }

    @Test
    fun finalSessionReleasesEvenWhenTsnetKeepsTheServiceInTheForeground() {
        val fixture = Fixture()
        fixture.open()
        fixture.owner.update(hasSessions = false, foregroundOwned = true)
        fixture.owner.renew()
        assertFalse(fixture.held)
        assertEquals(1, fixture.releases)
        assertEquals(1, fixture.acquisitions.size)
    }

    @Test
    fun losingForegroundOwnershipReleasesImmediately() {
        val fixture = Fixture()
        fixture.open()
        fixture.owner.update(hasSessions = true, foregroundOwned = false)
        fixture.owner.renew()
        assertFalse(fixture.held)
        assertEquals(1, fixture.releases)
        assertEquals(1, fixture.acquisitions.size)
    }

    @Test
    fun destructionAndRepeatedCleanupReleaseOnlyOnceAndPreventRenewal() {
        val fixture = Fixture()
        fixture.open()
        fixture.owner.close()
        fixture.owner.close()
        fixture.owner.renew()
        assertFalse(fixture.held)
        assertEquals(1, fixture.releases)
        assertEquals(1, fixture.acquisitions.size)
    }

    @Test
    fun aNewSessionCanAcquireAfterThePreviousSessionWasClosed() {
        val fixture = Fixture()
        fixture.open()
        fixture.owner.close()
        fixture.open()
        assertTrue(fixture.held)
        assertEquals(2, fixture.acquisitions.size)
        assertEquals(1, fixture.releases)
    }

    @Test
    fun acquisitionFailureIsContainedAndTheNextRenewalRetries() {
        val fixture = Fixture()
        val expected = SecurityException("denied")
        fixture.acquireFailure = expected
        fixture.open()
        assertFalse(fixture.held)
        assertSame(expected, fixture.failures.single())
        fixture.acquireFailure = null
        fixture.owner.renew()
        assertTrue(fixture.held)
        assertEquals(2, fixture.acquisitions.size)
    }

    @Test
    fun releaseFailureDoesNotAllowLaterRenewalToReacquire() {
        val fixture = Fixture()
        val expected = IllegalStateException("already released")
        fixture.open()
        fixture.releaseFailure = expected
        fixture.owner.close()
        fixture.owner.renew()
        assertSame(expected, fixture.failures.single())
        assertEquals(1, fixture.acquisitions.size)
    }

    @Test
    fun failingDiagnosticsCannotEscapePowerManagement() {
        val owner = SessionWakeLock(
            acquire = { throw SecurityException("denied") },
            release = { throw IllegalStateException("already released") },
            onFailure = { throw IllegalStateException("diagnostics failed") },
        )
        owner.update(hasSessions = true, foregroundOwned = true)
        owner.renew()
        owner.close()
    }

    private class Fixture {
        val acquisitions = mutableListOf<Long>()
        val failures = mutableListOf<Throwable>()
        var releases = 0
        var held = false
        var acquireFailure: Exception? = null
        var releaseFailure: Exception? = null
        val owner = SessionWakeLock(
            acquire = { timeoutMillis ->
                acquisitions += timeoutMillis
                acquireFailure?.let { throw it }
                held = true
            },
            release = {
                releases += 1
                releaseFailure?.let { throw it }
                held = false
            },
            onFailure = { failures += it },
        )

        fun open() {
            owner.update(hasSessions = true, foregroundOwned = true)
        }
    }
}
