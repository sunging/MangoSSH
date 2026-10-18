package website.sung.mangossh.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ServerResourceMonitorTest {
    private val identity = Any()
    private fun sample(name: String = "Example") = ServerResourceSample(identity, ServerResourcePlatform.LINUX, system = name)

    @Test fun automaticSamplingRepeatsOnlyWhileWatched() = runBlocking {
        val calls = AtomicInteger()
        val monitor = ServerResourceMonitor(this, { _, manual -> assertFalse(manual); sample(calls.incrementAndGet().toString()) }, intervalMillis = 20)
        try {
            monitor.start("session")
            withTimeout(3000) { monitor.states.first { it["session"]?.snapshot?.system == "2" } }
            monitor.stop("session")
            val stoppedAt = calls.get()
            delay(80)
            assertEquals(stoppedAt, calls.get())
        } finally { monitor.stop("session", remove = true) }
    }

    @Test fun repeatedRefreshesCoalesceAndStoppingCancelsTheSampler() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val monitor = ServerResourceMonitor(this, { _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); sample() })
        try {
            monitor.start("session")
            withTimeout(3000) { entered.await() }
            repeat(20) { monitor.refresh("session"); monitor.start("session") }
            release.complete(Unit)
            withTimeout(3000) { monitor.states.first { it["session"]?.snapshot != null && it["session"]?.isRefreshing == false } }
            assertEquals(1, calls.get())
            monitor.stop("session")
            monitor.refresh("session")
            delay(30)
            assertEquals(1, calls.get())
        } finally { monitor.stop("session", remove = true) }
    }

    @Test fun failuresKeepTheLastSuccessfulSnapshotAndTimestamp() = runBlocking {
        var calls = 0
        val monitor = ServerResourceMonitor(this, { _, _ -> if (++calls == 1) sample() else throw IOExceptionForTest() }, now = { 123L })
        try {
            monitor.start("session")
            withTimeout(3000) { monitor.states.first { it["session"]?.snapshot != null && it["session"]?.isRefreshing == false } }
            monitor.refresh("session")
            val state = withTimeout(3000) { monitor.states.first { it["session"]?.failure != null } }.getValue("session")
            assertEquals(123L, state.snapshot!!.collectedAtEpochMillis)
            assertEquals(ServerResourceFailure.QUERY, state.failure)
        } finally { monitor.stop("session", remove = true) }
    }

    @Test fun lostCompanionPausesPollingUntilAnExplicitRetry() = runBlocking {
        val modes = mutableListOf<Boolean>()
        val monitor = ServerResourceMonitor(this, { _, manual ->
            modes += manual
            if (!manual) throw ResourceQueryException(ServerResourceFailure.CONNECTION)
            sample()
        }, intervalMillis = 20)
        try {
            monitor.start("session")
            withTimeout(3000) { monitor.states.first { it["session"]?.failure == ServerResourceFailure.CONNECTION } }
            delay(80)
            assertEquals(listOf(false), modes)
            monitor.refresh("session")
            withTimeout(3000) { monitor.states.first { it["session"]?.snapshot != null } }
            assertEquals(listOf(false, true), modes)
        } finally { monitor.stop("session", remove = true) }
    }

    @Test fun deadlineStopsCollectionAndShowsTimeout() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val monitor = ServerResourceMonitor(this, { _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } }, timeoutMillis = 30)
        try {
            monitor.start("session")
            withTimeout(3000) { cancelled.await(); monitor.states.first { it["session"]?.failure == ServerResourceFailure.TIMEOUT } }
        } finally { monitor.stop("session", remove = true) }
    }

    @Test fun lateResultsCannotOverwriteANewWatchOrRepopulateRemovedSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val monitor = ServerResourceMonitor(this, { _, _ ->
            if (++calls == 1) withContext(NonCancellable) { entered.complete(Unit); release.await(); sample("old") }
            else sample("new")
        })
        try {
            monitor.start("session")
            withTimeout(3000) { entered.await() }
            monitor.stop("session", remove = true)
            monitor.start("session")
            // Even a second close/open while the old channel is closing must share its gate.
            monitor.stop("session")
            monitor.start("session")
            delay(30)
            assertEquals(1, calls)
            assertNull(monitor.states.value["session"]?.snapshot)
            release.complete(Unit)
            withTimeout(3000) { monitor.states.first { it["session"]?.snapshot?.system == "new" } }
            assertEquals("new", monitor.states.value["session"]!!.snapshot!!.system)
            monitor.stop("session", remove = true)
            assertFalse(monitor.states.value.containsKey("session"))
        } finally { release.complete(Unit); monitor.stop("session", remove = true) }
    }

    private class IOExceptionForTest : Exception()
}
