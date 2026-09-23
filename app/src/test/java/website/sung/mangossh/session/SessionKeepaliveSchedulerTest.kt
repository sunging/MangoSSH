package website.sung.mangossh.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepaliveSchedulerTest {
    @Test
    fun backgroundIntervalScalesAndClamps() {
        assertEquals(120_000L, SessionKeepaliveScheduler.backgroundInterval(30_000L, 4))
        assertEquals(60_000L, SessionKeepaliveScheduler.backgroundInterval(15_000L, 4))
        // Floor: never poll more often than once a minute in the background.
        assertEquals(60_000L, SessionKeepaliveScheduler.backgroundInterval(10_000L, 4))
        assertEquals(60_000L, SessionKeepaliveScheduler.backgroundInterval(30_000L, 1))
        // Ceiling: keep the NAT/binding alive even if the user picked a long interval.
        assertEquals(300_000L, SessionKeepaliveScheduler.backgroundInterval(300_000L, 4))
        assertEquals(240_000L, SessionKeepaliveScheduler.backgroundInterval(30_000L, 8))
    }

    @Test
    fun backgroundMultiplierIsReadOnEachWait() = runBlocking {
        var multiplier = 4
        var scheduledDelay: Long? = null
        val scheduler = SessionKeepaliveScheduler(
            appForeground = MutableStateFlow(false),
            alarm = KeepaliveAlarm { delayMillis, onFire ->
                scheduledDelay = delayMillis
                onFire()
                val cancel: () -> Unit = {}
                cancel
            },
            backgroundMultiplier = { multiplier },
            // This test measures multiplier changes, not wall-clock scheduling latency.
            now = { 0L },
        )

        scheduler.waitForNextKeepalive(30_000L)
        assertEquals(120_000L, scheduledDelay)

        multiplier = 8
        scheduler.waitForNextKeepalive(30_000L)
        assertEquals(240_000L, scheduledDelay)
    }

    @Test
    fun foregroundUsesThePlainWaitAtTheConfiguredInterval() = runBlocking {
        val waited = mutableListOf<Long>()
        val scheduler = SessionKeepaliveScheduler(
            appForeground = MutableStateFlow(true),
            alarm = KeepaliveAlarm { _, _ -> error("alarm must not be used in the foreground") },
            foregroundWait = { waited += it },
        )

        scheduler.waitForNextKeepalive(30_000L)

        assertEquals(listOf(30_000L), waited)
    }

    @Test
    fun backgroundWaitsForTheAlarmThenReturns() = runBlocking {
        var scheduledDelay: Long? = null
        var fire: (() -> Unit)? = null
        var cancelled = false
        val scheduler = SessionKeepaliveScheduler(
            appForeground = MutableStateFlow(false),
            alarm = KeepaliveAlarm { delayMillis, onFire ->
                scheduledDelay = delayMillis
                fire = onFire
                { cancelled = true }
            },
            now = { 0L },
        )

        val job = launch { scheduler.waitForNextKeepalive(30_000L) }
        while (fire == null) yield()

        assertEquals(120_000L, scheduledDelay)
        assertTrue(job.isActive)

        checkNotNull(fire).invoke()
        withTimeout(1_000) { job.join() }
        assertTrue(cancelled)
    }

    @Test
    fun backgroundReturnsEarlyWhenTheAppComesBackToForeground() = runBlocking {
        val foreground = MutableStateFlow(false)
        var scheduled = false
        var cancelled = false
        val scheduler = SessionKeepaliveScheduler(
            appForeground = foreground,
            alarm = KeepaliveAlarm { _, _ ->
                scheduled = true
                { cancelled = true }
            },
        )

        val job = launch { scheduler.waitForNextKeepalive(30_000L) }
        while (!scheduled) yield()
        assertTrue(job.isActive)

        foreground.value = true
        withTimeout(1_000) { job.join() }
        assertTrue(cancelled)
    }

    @Test fun foregroundToBackgroundRegistersImmediatelyUsingOriginalDeadline() = runBlocking {
        val foreground = MutableStateFlow(true)
        var now = 0L
        var registered: Long? = null
        var cancelled = false
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scheduler = SessionKeepaliveScheduler(foreground, KeepaliveAlarm { delay, _ ->
            registered = delay
            { cancelled = true }
        }, foregroundWait = { waiting.complete(Unit); kotlinx.coroutines.awaitCancellation() }, now = { now })
        val job = launch { scheduler.waitForNextKeepalive(30_000) }
        waiting.await()
        now = 5_000
        foreground.value = false
        withTimeout(1_000) { while (registered == null) yield() }
        assertEquals(115_000L, registered)
        foreground.value = true
        withTimeout(1_000) { job.join() }
        assertTrue(cancelled)
    }

    @Test fun cancellationUnregistersBackgroundAlarmAndLateCallbackIsHarmless() = runBlocking {
        var fire: (() -> Unit)? = null
        var cancellations = 0
        val scheduler = SessionKeepaliveScheduler(MutableStateFlow(false), KeepaliveAlarm { _, callback ->
            fire = callback
            { cancellations++ }
        })
        val job = launch { scheduler.waitForNextKeepalive(30_000) }
        withTimeout(1_000) { while (fire == null) yield() }
        job.cancel(); job.join(); fire!!.invoke()
        assertEquals(1, cancellations)
    }
}
