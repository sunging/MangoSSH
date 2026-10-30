package website.sung.mangossh

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.presentation.LockRegressionActivity

/** Only the task-owned enrolled emulator runs these tests; the host drives its virtual sensor. */
class BiometricSensorInstrumentedTest {
    @get:Rule(order = 0) val environment = BiometricTestEnvironment.rule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<LockRegressionActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val model get() = compose.activity.model
    private fun text(id: Int) = instrumentation.targetContext.getString(id)

    @Before fun requireEnrolledDisposableEmulator() {
        compose.waitUntil(30_000) { model.ready.value }
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.from(compose.activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
        compose.runOnUiThread { model.lock.lock() }
    }

    @After fun cleanup() { compose.runOnUiThread { model.dispose() } }

    private fun prompt() {
        compose.onNodeWithText(text(R.string.ui_use_biometrics)).performClick()
        compose.waitUntil(15_000) { model.lock.biometricRequest != null }
    }

    private fun requestTouch(phase: String) {
        instrumentation.sendStatus(1, Bundle().apply { putString("biometricPhase", phase) })
    }

    @Test fun systemFingerprintAuthorizesTheOriginalKeystoreOperation() {
        prompt()
        requestTouch("success")
        compose.waitUntil(30_000) { !model.lock.locked.value }
        assertNull(model.lock.biometricRequest)
        compose.runOnUiThread { model.lock.noteBackgrounded() }
        assertTrue(model.lock.locked.value)
    }

    @Test fun rotationRebindsThePendingSystemPrompt() {
        prompt()
        val request = model.lock.biometricRequest
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { model.lock.biometricRequest === request }
        requestTouch("rotation")
        compose.waitUntil(30_000) { !model.lock.locked.value }
    }

    @Test fun rejectedFingerprintCanRetryWithoutGrantingAccess() {
        prompt()
        val request = checkNotNull(model.lock.biometricRequest)
        requestTouch("reject")
        compose.waitUntil(20_000) { request.failedAttempts > 0 }
        assertTrue(model.lock.locked.value)
        assertSame(request, model.lock.biometricRequest)
        requestTouch("retry")
        compose.waitUntil(30_000) { !model.lock.locked.value }
    }
}
