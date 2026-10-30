package website.sung.mangossh

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import website.sung.mangossh.presentation.LockRegressionActivity

/** Tests framework failure handling and real auth-bound keys without ever touching a user's fingerprint. */
class BiometricUnlockInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<LockRegressionActivity>()

    @Test fun unavailableHardwareEnrollmentAndLockoutKeepPinAvailable() {
        val model = compose.activity.model
        compose.waitUntil(30_000) { model.ready.value }
        try {
            for (availability in listOf(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)) {
                var reported = 0
                compose.runOnUiThread {
                    model.lock.lock()
                    BiometricUnlockPrompt(compose.activity, model.lock, { reported++ }, model.biometricKey,
                        availability = { availability }).authenticate()
                    assertEquals(1, reported)
                    assertNull(model.lock.biometricRequest)
                    assertTrue(model.lock.locked.value)
                    model.lock.unlockWithPin(model.pin)
                }
                compose.waitUntil(15_000) { !model.lock.busy.value }
                assertFalse(model.lock.locked.value)
            }
        } finally { compose.runOnUiThread { model.dispose() } }
    }

    @Test fun aForgedSuccessCannotExerciseTheKeystoreCipher() {
        val model = compose.activity.model
        compose.waitUntil(30_000) { model.ready.value }
        try {
            compose.runOnUiThread {
                model.lock.lock()
                val cipher = runCatching { model.biometricKey.encryptCipher() }.getOrNull()
                // An unenrolled emulator must fail key creation; an enrolled phone must reject
                // doFinal without a fresh sensor authorization. Neither may unlock the app.
                if (cipher != null) {
                    val request = checkNotNull(model.lock.beginBiometricUnlock(cipher))
                    assertFalse(model.lock.completeBiometricUnlock(request, cipher))
                }
                assertTrue(model.lock.locked.value)
            }
        } finally { compose.runOnUiThread { model.dispose() } }
    }

    @Test fun invalidatedKeysAndTerminalPromptErrorsKeepTheAppLocked() {
        val model = compose.activity.model
        compose.waitUntil(30_000) { model.ready.value }
        try {
            compose.runOnUiThread {
                model.lock.lock()
                var reset = 0
                var reported = 0
                BiometricUnlockPrompt(compose.activity, model.lock, { reported++ }, model.biometricKey,
                    availability = { BiometricManager.BIOMETRIC_SUCCESS },
                    createCipher = { throw android.security.keystore.KeyPermanentlyInvalidatedException() },
                    resetKey = { reset++ }).authenticate()
                assertEquals(1, reset); assertEquals(1, reported)
                assertTrue(model.lock.locked.value)
                for (error in listOf(BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").generateKey())
                    }
                    val request = checkNotNull(model.lock.beginBiometricUnlock(cipher))
                    val adapter = BiometricUnlockPrompt(compose.activity, model.lock, {}, model.biometricKey)
                    adapter.authenticationError(request, error)
                    assertNull(model.lock.biometricRequest)
                    assertTrue(model.lock.locked.value)
                }
            }
        } finally { compose.runOnUiThread { model.dispose() } }
    }

    @Test fun systemPromptCancellationFallsBackToPinWithoutUnlocking() {
        val model = compose.activity.model
        compose.waitUntil(30_000) { model.ready.value }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        try {
            compose.runOnUiThread { model.lock.lock() }
            compose.onNodeWithText(instrumentation.targetContext.getString(R.string.ui_use_biometrics)).performClick()
            if (BiometricManager.from(compose.activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
                compose.waitUntil(15_000) {
                    instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                        try {
                            val nodes = root.findAccessibilityNodeInfosByText(instrumentation.targetContext.getString(R.string.biometric_prompt_use_pin))
                            try { nodes.any { it.isEnabled && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } }
                            finally { nodes.forEach { it.recycle() } }
                        } finally { root.recycle() }
                    } ?: false
                }
                compose.waitUntil(15_000) { model.lock.biometricRequest == null }
            }
            assertTrue(model.lock.locked.value)
            compose.onNode(hasSetTextAction()).performTextInput(model.pin)
            compose.onNodeWithText(instrumentation.targetContext.getString(R.string.ui_unlock_with_pin)).performClick()
            compose.waitUntil(15_000) { !model.lock.locked.value }
        } finally { compose.runOnUiThread { model.dispose() } }
    }
}
