package website.sung.mangossh.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.AppLockDelay
import website.sung.mangossh.security.AppAccessState
import website.sung.mangossh.security.AppLockStore
import website.sung.mangossh.security.ReauthenticationMode

/** Real PIN derivation, isolated preferences and controlled clocks; never reads the installed app PIN. */
class AppLockCoordinatorInstrumentedTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext() { checkNotNull(tasks.poll()).run() }
        val pending get() = tasks.isNotEmpty()
    }

    private class Fixture(val access: AppAccessState, val lock: AppLockCoordinator,
        val pin: String, val wrong: String, val now: AtomicLong, val messages: MutableList<UiText>,
        val recreateLocked: () -> AppLockCoordinator) {
        suspend fun idle() { withTimeout(15_000) { lock.busy.first { !it } } }
        suspend fun configure() { lock.configurePin(pin); idle() }
        fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        }
    }

    private fun isolated(dispatcher: CoroutineDispatcher = Dispatchers.Default, block: suspend Fixture.() -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lock-coordinator-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val pin = CharArray(6) { ('0'.code + SecureRandom().nextInt(10)).toChar() }.concatToString()
        val wrong = pin.map { if (it == '9') '0' else it + 1 }.joinToString("")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val now = AtomicLong(10_000)
        val store = AppLockStore(context, now::get)
        val access = AppAccessState(false)
        val messages = mutableListOf<UiText>()
        val lock = AppLockCoordinator(store, access, scope, {}, { messages.add(it) }, dispatcher)
        val fixture = Fixture(access, lock, pin, wrong, now, messages) {
            AppLockCoordinator(AppLockStore(context, now::get), AppAccessState(true), scope, {}, {}, dispatcher)
        }
        try { withContext(Dispatchers.Main) { fixture.block() } }
        finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            // commit waits for older apply writes, which otherwise can recreate a deleted file.
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(name)
        }
    }

    @Test fun documentRoundTripPreservesAccessAndOrdinaryBackgroundLocksAgain() = isolated {
        configure()
        repeat(2) {
            val generation = access.generation
            val token = lock.documents.begin()
            lock.noteBackgrounded(100)
            lock.evaluateAutoLock(1_000_000)
            assertFalse(lock.locked.value)
            assertEquals(generation, access.generation)
            assertTrue(lock.documents.finish(token))
        }
        lock.noteBackgrounded(1_000_001)
        assertTrue(lock.locked.value)
    }

    @Test fun allGracePeriodsUseTheBoundaryAndIgnoreConfigurationOnlyResume() = isolated {
        configure()
        for (delay in AppLockDelay.entries) {
            lock.setAutoLockDelay(delay)
            lock.evaluateAutoLock(50)
            assertFalse(lock.locked.value)
            lock.noteBackgrounded(100)
            if (delay != AppLockDelay.IMMEDIATELY) {
                assertFalse(lock.locked.value)
                lock.evaluateAutoLock(100 + delay.delayMillis - 1)
                assertFalse(lock.locked.value)
                lock.noteBackgrounded(200)
                lock.evaluateAutoLock(200 + delay.delayMillis)
            }
            assertTrue(lock.locked.value)
            lock.unlockWithPin(pin); idle()
            assertFalse(lock.locked.value)
        }
    }

    @Test fun cancellationAndLaunchFailureDoNotLeaveAnExemption() = isolated {
        configure()
        lock.documents.finish(lock.documents.begin())
        lock.documents.abandon(lock.documents.begin())
        lock.noteBackgrounded(10)
        assertTrue(lock.locked.value)
    }

    @Test fun screenOffAndExplicitLockRevokeLateResultsEvenAfterUnlock() = isolated {
        configure()
        for (screenOff in listOf(true, false)) {
            val token = lock.documents.begin()
            if (screenOff) lock.screenOff() else lock.lock()
            assertTrue(lock.locked.value)
            lock.unlockWithPin(pin); idle()
            assertFalse(lock.documents.finish(token))
        }
    }

    @Test fun changingAndRemovingPinResetsConfiguration() = isolated {
        configure()
        lock.setBiometricEnabled(true)
        lock.setAutoLockDelay(AppLockDelay.FIVE_MINUTES)
        lock.configurePin(wrong); idle()
        lock.lock()
        lock.unlockWithPin(pin); idle(); assertTrue(lock.locked.value)
        lock.unlockWithPin(wrong); idle(); assertFalse(lock.locked.value)
        lock.clear()
        assertFalse(lock.configuration.value.pinConfigured)
        assertFalse(lock.configuration.value.biometricEnabled)
        assertEquals(AppLockDelay.IMMEDIATELY, lock.configuration.value.autoLockDelay)
        assertEquals(ReauthenticationMode.DISABLED, lock.configuration.value.reauthentication)
    }

    @Test fun biometricAndDelayRequirePinAndInvalidPinCannotEnableLock() = isolated {
        lock.setBiometricEnabled(true)
        lock.setAutoLockDelay(AppLockDelay.ONE_MINUTE)
        assertFalse(lock.configuration.value.biometricEnabled)
        lock.configurePin(pin.take(3)); idle()
        assertFalse(lock.configuration.value.pinConfigured)
        lock.configurePin(pin.repeat(3)); idle()
        assertFalse(lock.configuration.value.pinConfigured)
        lock.configurePin(pin + "x"); idle()
        assertFalse(lock.configuration.value.pinConfigured)
    }

    @Test fun sensitiveActionsRequireCurrentPinAndLockClearsTheApprovalWindow() = isolated {
        configure()
        lock.setReauthentication(ReauthenticationMode.FIVE_MINUTES)
        var performed = 0
        fun attempt() { if (lock.authorizeSensitive { attempt() }) performed++ }
        attempt(); assertEquals(0, performed)
        lock.verifySensitiveAction(wrong); idle(); assertEquals(0, performed)
        lock.verifySensitiveAction(pin); idle(); assertEquals(1, performed)
        attempt(); assertEquals(2, performed)
        lock.lock(); lock.unlockWithPin(pin); idle()
        attempt(); assertEquals(2, performed)
        lock.cancelReauthentication(); assertFalse(lock.reauthenticationPending.value)
    }

    @Test fun changingPinAfterReauthenticationUsesTheNewVerifier() = isolated {
        configure()
        lock.setReauthentication(ReauthenticationMode.EVERY_ACTION)
        lock.configurePin(wrong)
        assertTrue(lock.reauthenticationPending.value)
        lock.verifySensitiveAction(pin); idle()
        assertFalse(lock.reauthenticationPending.value)
        lock.lock()
        lock.unlockWithPin(pin); idle()
        assertTrue(lock.locked.value)
        lock.unlockWithPin(wrong); idle()
        assertFalse(lock.locked.value)
    }

    @Test fun persistedCooldownFollowsTheVisiblePromptAndWallClock() = isolated {
        configure()
        messages.clear()
        lock.setReauthentication(ReauthenticationMode.EVERY_ACTION)
        lock.evaluateAutoLock()
        assertFalse(lock.authorizeSensitive { error("Incorrect PIN approved the action") })
        repeat(4) {
            lock.verifySensitiveAction(wrong); idle()
            assertEquals(PinVerificationState(incorrect = true), lock.pinVerification.value)
            lock.dismissPinError()
            assertEquals(PinVerificationState(), lock.pinVerification.value)
        }
        lock.verifySensitiveAction(wrong); idle()
        assertEquals(PinVerificationState(cooldownSeconds = 30), lock.pinVerification.value)
        lock.dismissPinError()
        assertEquals(30, lock.pinVerification.value.cooldownSeconds)
        lock.cancelReauthentication()
        assertEquals(PinVerificationState(), lock.pinVerification.value)
        assertFalse(lock.authorizeSensitive { error("Blocked PIN approved the action") })
        assertEquals(30, lock.pinVerification.value.cooldownSeconds)
        lock.lock()
        lock.noteBackgrounded()

        now.addAndGet(28_500)
        delay(1_100)
        assertEquals(30, lock.pinVerification.value.cooldownSeconds)
        assertEquals(2, recreateLocked().pinVerification.value.cooldownSeconds)
        lock.evaluateAutoLock()
        assertEquals(2, lock.pinVerification.value.cooldownSeconds)
        now.addAndGet(500)
        withTimeout(5_000) { lock.pinVerification.first { it.cooldownSeconds == 1 } }
        lock.unlockWithPin(pin)
        assertTrue(lock.locked.value)
        assertFalse(lock.pinVerification.value.busy)

        now.addAndGet(1_000)
        withTimeout(5_000) { lock.pinVerification.first { it.cooldownSeconds == 0 } }
        assertEquals(PinVerificationState(), lock.pinVerification.value)
        lock.unlockWithPin(pin); idle()
        assertFalse(lock.locked.value)
        assertTrue(messages.isEmpty())
    }

    @Test fun cancellingSensitiveActionRejectsItsSlowPinResult() {
        val dispatcher = PausedDispatcher()
        isolated(dispatcher) {
            lock.configurePin(pin)
            assertTrue(dispatcher.pending); dispatcher.runNext(); idle()
            lock.setReauthentication(ReauthenticationMode.EVERY_ACTION)
            var performed = 0
            val action: () -> Unit = { performed++ }
            lock.authorizeSensitive(action = action)
            lock.verifySensitiveAction(pin)
            lock.cancelReauthentication()
            lock.authorizeSensitive(action = action)
            dispatcher.runNext(); idle()
            assertEquals(0, performed)
            assertTrue(lock.reauthenticationPending.value)
        }
    }

    @Test fun verificationAndPinWritesRejectOtherSettingsChangesWhileAutoLockStillRuns() {
        val dispatcher = PausedDispatcher()
        isolated(dispatcher) {
            lock.configurePin(pin); dispatcher.runNext(); idle()
            lock.setReauthentication(ReauthenticationMode.EVERY_ACTION)
            lock.configurePin(wrong)
            lock.verifySensitiveAction(pin)
            assertTrue(lock.busy.value)
            lock.clear()
            lock.setBiometricEnabled(true)
            lock.setAutoLockDelay(AppLockDelay.FIVE_MINUTES)
            lock.setReauthentication(ReauthenticationMode.DISABLED)
            dispatcher.runNext()
            withTimeout(5_000) { while (!dispatcher.pending) yield() }

            assertTrue(lock.busy.value)
            lock.clear()
            lock.setBiometricEnabled(true)
            lock.setAutoLockDelay(AppLockDelay.FIVE_MINUTES)
            lock.noteBackgrounded()
            assertTrue(lock.locked.value)
            dispatcher.runNext(); idle()
            assertTrue(lock.locked.value)
            assertFalse(lock.configuration.value.biometricEnabled)
            assertEquals(AppLockDelay.IMMEDIATELY, lock.configuration.value.autoLockDelay)
            assertEquals(ReauthenticationMode.EVERY_ACTION, lock.configuration.value.reauthentication)

            lock.unlockWithPin(wrong); dispatcher.runNext(); idle()
            assertFalse(lock.locked.value)
        }
    }

    @Test fun aSecondLockRejectsAnInFlightPinUnlockAndDuplicateSubmission() {
        val dispatcher = PausedDispatcher()
        isolated(dispatcher) {
            lock.configurePin(pin); dispatcher.runNext(); idle()
            lock.lock()
            lock.unlockWithPin(pin)
            lock.unlockWithPin(pin)
            lock.lock()
            dispatcher.runNext(); idle()
            assertTrue(lock.locked.value)
            assertFalse(dispatcher.pending)
            lock.unlockWithPin(wrong)
            lock.lock()
            dispatcher.runNext(); idle()
            assertEquals(PinVerificationState(), lock.pinVerification.value)
        }
    }

    @Test fun firstPinFinishingInBackgroundCannotLeaveTheAppUnlocked() {
        val dispatcher = PausedDispatcher()
        isolated(dispatcher) {
            lock.configurePin(pin)
            lock.noteBackgrounded(100)
            dispatcher.runNext(); idle()
            assertTrue(lock.configuration.value.pinConfigured)
            assertTrue(lock.locked.value)
        }
    }

    @Test fun biometricCallbacksRequireTheCurrentOriginalCipherAndDoNotGrantSensitiveActions() = isolated {
        configure(); lock.setBiometricEnabled(true)
        lock.setReauthentication(ReauthenticationMode.EVERY_ACTION)
        lock.lock()
        val missing = lock.beginBiometricUnlock(cipher())!!
        assertFalse(lock.completeBiometricUnlock(missing, null))
        val substituted = lock.beginBiometricUnlock(cipher())!!
        assertFalse(lock.completeBiometricUnlock(substituted, cipher()))
        val cancelled = lock.beginBiometricUnlock(cipher())!!
        lock.cancelBiometricUnlock(cancelled)
        assertFalse(lock.completeBiometricUnlock(cancelled, cancelled.cipher))
        val stale = lock.beginBiometricUnlock(cipher())!!
        lock.lock()
        val current = lock.beginBiometricUnlock(cipher())!!
        assertFalse(lock.completeBiometricUnlock(stale, stale.cipher))
        // This test supplies an ordinary cipher to cover only the application state machine.
        // Separate device tests use an auth-bound Keystore cipher and the real system prompt.
        assertTrue(lock.completeBiometricUnlock(current, current.cipher))
        var performed = false
        assertFalse(lock.authorizeSensitive { performed = true })
        assertFalse(performed)
    }

    @Test fun disabledBiometricsAndInvalidCipherNeverUnlock() = isolated {
        configure(); lock.lock()
        assertNull(lock.beginBiometricUnlock(cipher()))
        lock.setBiometricEnabled(true)
        val uninitialized = Cipher.getInstance("AES/GCM/NoPadding")
        val request = lock.beginBiometricUnlock(uninitialized)!!
        assertFalse(lock.completeBiometricUnlock(request, uninitialized))
        assertTrue(lock.locked.value)
        val disabled = lock.beginBiometricUnlock(cipher())!!
        lock.setBiometricEnabled(false)
        assertFalse(lock.completeBiometricUnlock(disabled, disabled.cipher))
        assertTrue(lock.locked.value)
    }
}
