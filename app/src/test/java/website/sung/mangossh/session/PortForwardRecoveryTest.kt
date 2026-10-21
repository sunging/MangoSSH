package website.sung.mangossh.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType
import website.sung.mangossh.session.ssh.awaitOwnedSshOperation

/** No sockets or persisted profiles: exercises ownership and recovery scheduling. */
class PortForwardRecoveryTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val network = MutableStateFlow(NetworkStatus())
        val phases = mutableMapOf<String, PortForwardRuntimePhase>()
        val retry = Channel<Unit>(Channel.UNLIMITED)
        val delays = mutableListOf<Long>()
        var opens = 0
        var action: suspend (PortForwardRecovery.Intent) -> Unit = { intent ->
            opens++
            intent.sessionId = "carrier-$opens"
            phases[intent.runtimeId] = PortForwardRuntimePhase.ACTIVE
        }
        val recovery = PortForwardRecovery(scope, network, { action(it) }, { intent, phase, _ ->
            phases[intent.runtimeId] = phase
        }, { it is java.io.IOException }, { delays += it; retry.receive() }, { 0 })
        val rule = PortForwardRule("rule", "profile", PortForwardType.DYNAMIC, bindPort = 1080)
        fun close() { recovery.stopAll(); scope.cancel() }
    }

    @Test fun duplicateStartsAndConcurrentLossReportsShareOneRecovery() = runBlocking {
        val fixture = Fixture()
        try {
            val intent = fixture.recovery.start(fixture.rule)
            assertSame(intent, fixture.recovery.start(fixture.rule))
            assertEquals(1, fixture.opens)
            fixture.recovery.disconnected("carrier-1")
            fixture.recovery.disconnected("carrier-1")
            assertEquals(listOf(1_000L), fixture.delays)
            assertSame(intent, fixture.recovery.start(fixture.rule))
            fixture.retry.send(Unit)
            yield()
            assertEquals(2, fixture.opens)
            assertEquals("rule", intent.runtimeId)
            assertEquals("carrier-2", intent.sessionId)
        } finally { fixture.close() }
    }

    @Test fun losingAConnectionWhileOpeningRetriesWithoutDroppingTheIntent() = runBlocking {
        val fixture = Fixture()
        val connection = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val opening = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.action = { intent ->
            fixture.opens++
            intent.sessionId = "carrier-${fixture.opens}"
            if (fixture.opens == 2) awaitOwnedSshOperation(connection) {
                opening.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            fixture.phases[intent.runtimeId] = PortForwardRuntimePhase.ACTIVE
        }
        try {
            val intent = fixture.recovery.start(fixture.rule)
            fixture.recovery.disconnected(intent.sessionId)
            fixture.retry.send(Unit)
            opening.await()
            connection.cancel()
            yield()
            assertSame(intent, fixture.recovery.current(fixture.rule.id))
            assertEquals(PortForwardRuntimePhase.RECONNECTING, fixture.phases[intent.runtimeId])
            assertEquals(listOf(1_000L, 2_000L), fixture.delays)
            fixture.retry.send(Unit)
            yield()
            assertEquals(3, fixture.opens)
            assertEquals(PortForwardRuntimePhase.ACTIVE, fixture.phases[intent.runtimeId])
        } finally { connection.cancel(); fixture.close() }
    }

    @Test fun lateListenerShutdownCannotDisconnectTheReplacementAttempt() = runBlocking {
        val fixture = Fixture()
        try {
            val intent = fixture.recovery.start(fixture.rule)
            val oldListener = intent.lost
            fixture.recovery.disconnected(intent.sessionId)
            fixture.retry.send(Unit)
            yield()
            assertEquals(2, fixture.opens)
            val replacementListener = intent.lost
            assertNotSame(oldListener, replacementListener)
            // The old listener's close can finish after another detector already recovered.
            fixture.recovery.listenerLost(intent, oldListener)
            yield()
            assertEquals(listOf(1_000L), fixture.delays)
            assertEquals(PortForwardRuntimePhase.ACTIVE, fixture.phases[intent.runtimeId])
            assertFalse(replacementListener.isCompleted)
            fixture.recovery.listenerLost(intent, replacementListener)
            fixture.retry.send(Unit)
            yield()
            assertEquals(3, fixture.opens)
        } finally { fixture.close() }
    }

    @Test fun unavailableOrBlockedNetworksWaitButUnvalidatedLanIsAllowed() = runBlocking {
        val fixture = Fixture()
        fixture.network.value = NetworkStatus(NetworkHealth.LOST)
        try {
            fixture.recovery.start(fixture.rule)
            assertTrue(fixture.recovery.pending.value)
            assertTrue(fixture.recovery.opening.value.isEmpty())
            assertEquals(PortForwardRuntimePhase.WAITING_FOR_NETWORK, fixture.phases["rule"])
            fixture.network.value = NetworkStatus(NetworkHealth.BLOCKED)
            yield()
            assertEquals(0, fixture.opens)
            fixture.network.value = NetworkStatus(NetworkHealth.AVAILABLE, validated = false)
            yield()
            assertEquals(1, fixture.opens)
        } finally { fixture.close() }
    }

    @Test fun stoppingDuringBackoffCancelsRecoveryAndRejectsLatePublication() = runBlocking {
        val fixture = Fixture()
        try {
            val intent = fixture.recovery.start(fixture.rule)
            fixture.recovery.disconnected(intent.sessionId)
            assertTrue(fixture.recovery.opening.value.isEmpty())
            fixture.recovery.stop(fixture.rule.id)
            fixture.retry.send(Unit)
            yield()
            assertEquals(1, fixture.opens)
            assertFalse(fixture.recovery.pending.value)
            assertNull(fixture.recovery.whileCurrent(intent) { error("stale publication") })
            val replacement = fixture.recovery.start(fixture.rule)
            assertNotSame(intent, replacement)
            assertNull(fixture.recovery.whileCurrent(intent) { error("old generation") })
        } finally { fixture.close() }
    }

    @Test fun stoppingAnOpeningCannotPublishAfterCancellationEvenIfItIgnoresCancellation() = runBlocking {
        val fixture = Fixture()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var published = false
        fixture.action = { intent ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finish.await() }
            fixture.recovery.whileCurrent(intent) { published = true }
        }
        try {
            fixture.recovery.start(fixture.rule)
            assertEquals(setOf("rule"), fixture.recovery.opening.value)
            fixture.recovery.stop(fixture.rule.id)
            finish.complete(Unit)
            yield()
            assertFalse(published)
            assertTrue(fixture.recovery.opening.value.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun permanentFailuresReleaseOwnershipAndDoNotRetry() = runBlocking {
        val fixture = Fixture()
        fixture.action = { throw SecurityException("synthetic rejection") }
        try {
            fixture.recovery.start(fixture.rule)
            assertEquals(PortForwardRuntimePhase.FAILED, fixture.phases["rule"])
            assertFalse(fixture.recovery.pending.value)
            assertTrue(fixture.delays.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun terminalFailureIsPublishedAfterOwnershipIsReleased() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var recovery: PortForwardRecovery
        var pendingAtFailure: Boolean? = null
        var ownedAtFailure: Boolean? = null
        recovery = PortForwardRecovery(scope, MutableStateFlow(NetworkStatus()),
            { throw SecurityException("synthetic rejection") },
            { intent, phase, _ ->
                if (phase == PortForwardRuntimePhase.FAILED) {
                    pendingAtFailure = recovery.pending.value
                    ownedAtFailure = recovery.current(intent.rule.id) != null
                }
            }, { false })
        try {
            recovery.start(PortForwardRule("rule", "profile", PortForwardType.LOCAL, bindPort = 1080))
            assertEquals(false, pendingAtFailure)
            assertEquals(false, ownedAtFailure)
        } finally { scope.cancel() }
    }

    @Test fun retryDelayIsExponentialAndBounded() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (0..7).map { PortForwardRecovery.retryDelayMillis(it) })
    }

    @Test fun shuttingDownAllIntentsAlsoCancelsOfflineRules() = runBlocking {
        val fixture = Fixture()
        fixture.network.value = NetworkStatus(NetworkHealth.LOST)
        try {
            fixture.recovery.start(fixture.rule)
            fixture.recovery.start(fixture.rule.copy(id = "second"))
            assertEquals(2, fixture.recovery.stopAll().size)
            fixture.network.value = NetworkStatus()
            yield()
            assertEquals(0, fixture.opens)
            assertFalse(fixture.recovery.pending.value)
        } finally { fixture.close() }
    }
}
