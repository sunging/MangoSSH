package website.sung.mangossh

import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.Cipher
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.presentation.AppLockCoordinator

/**
 * Activity-bound Android adapter for a process-local authentication request. Recreating it in
 * onCreate rebinds AndroidX's retained prompt without issuing a new authentication. Tests use
 * their own Keystore alias; production never accepts a callback without the original cipher.
 */
internal class BiometricUnlockPrompt(
    private val activity: FragmentActivity,
    private val lock: AppLockCoordinator,
    private val unavailable: () -> Unit,
    private val key: BiometricGateKey = BiometricGateKey(),
    private val availability: () -> Int = {
        BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    },
    private val createCipher: () -> Cipher = key::encryptCipher,
    private val resetKey: () -> Unit = key::reset,
) {
    private var prompt: BiometricPrompt? = lock.biometricRequest?.let(::bind)

    /** Starts strong, crypto-bound authentication only after an explicit request from the lock UI. */
    fun authenticate() {
        if (!lock.locked.value || !lock.configuration.value.biometricEnabled || lock.biometricRequest != null) return
        if (availability() != BiometricManager.BIOMETRIC_SUCCESS) { unavailable(); return }
        val cipher = try { createCipher() }
        catch (error: Exception) {
            if (error is KeyPermanentlyInvalidatedException) {
                runCatching { resetKey() }
                MangoLog.warn(MangoLogEvent.BIOMETRIC_UNLOCK_KEY_INVALIDATED)
            } else MangoLog.warn(MangoLogEvent.BIOMETRIC_UNLOCK_FAILED)
            unavailable()
            return
        }
        val request = lock.beginBiometricUnlock(cipher) ?: return
        val bound = bind(request)
        prompt = bound
        try {
            bound.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.app_name))
                .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
                .setNegativeButtonText(activity.getString(R.string.biometric_prompt_use_pin))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: RuntimeException) {
            lock.cancelBiometricUnlock(request)
            unavailable()
        }
    }

    /** Dismisses the platform prompt; the coordinator owns authorization and stale-result rejection. */
    fun cancel() { prompt?.cancelAuthentication() }

    /** Terminal errors discard only their own request; an older prompt cannot cancel a newer one. */
    internal fun authenticationError(request: AppLockCoordinator.BiometricRequest, errorCode: Int) {
        if (lock.biometricRequest !== request) return
        lock.cancelBiometricUnlock(request)
        if (errorCode !in listOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON)) unavailable()
    }

    private fun bind(request: AppLockCoordinator.BiometricRequest): BiometricPrompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (lock.biometricRequest !== request) return
                if (!lock.completeBiometricUnlock(request, result.cryptoObject?.cipher)) {
                    MangoLog.warn(MangoLogEvent.BIOMETRIC_UNLOCK_FAILED)
                    unavailable()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticationError(request, errorCode)
            }

            override fun onAuthenticationFailed() { lock.rejectBiometricAttempt(request) }
        },
    )
}
