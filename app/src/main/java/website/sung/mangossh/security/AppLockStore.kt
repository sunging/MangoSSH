package website.sung.mangossh.security

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import website.sung.mangossh.domain.AppLockDelay

/** Stores only a salted PBKDF2 verifier; the app PIN is never persisted. */
class AppLockStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun configuration(): AppLockConfiguration = AppLockConfiguration(
        pinConfigured = preferences.contains(KEY_PIN_HASH) && preferences.contains(KEY_PIN_SALT),
        biometricEnabled = preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false),
        autoLockDelay = AppLockDelay.fromPreference(readString(KEY_AUTO_LOCK_DELAY)) ?: AppLockDelay.DEFAULT,
    )

    fun setPin(pin: CharArray) {
        require(pin.size in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)) {
            "PIN is outside the supported length or contains a non-digit character."
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val verifier = derive(pin, salt)
        try {
            preferences.edit {
                putString(KEY_PIN_SALT, Base64.getEncoder().encodeToString(salt))
                putString(KEY_PIN_HASH, Base64.getEncoder().encodeToString(verifier))
            }
        } finally {
            verifier.fill(0)
            salt.fill(0)
        }
    }

    fun verifyPin(pin: CharArray): Boolean {
        val salt = preferences.getString(KEY_PIN_SALT, null)?.let(Base64.getDecoder()::decode) ?: return false
        val expected = preferences.getString(KEY_PIN_HASH, null)?.let(Base64.getDecoder()::decode) ?: return false
        val actual = derive(pin, salt)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            salt.fill(0)
            expected.fill(0)
            actual.fill(0)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        require(configuration().pinConfigured) { "An app PIN must be configured first." }
        preferences.edit { putBoolean(KEY_BIOMETRIC_ENABLED, enabled) }
    }

    /** Requires a PIN first, matching [setBiometricEnabled]: there is nothing to delay-guard otherwise. */
    fun setAutoLockDelay(delay: AppLockDelay) {
        require(configuration().pinConfigured) { "An app PIN must be configured first." }
        preferences.edit { putString(KEY_AUTO_LOCK_DELAY, delay.preferenceValue) }
    }

    /** Also resets the auto-lock delay to [AppLockDelay.DEFAULT], since it clears the whole preference file. */
    fun clear() {
        preferences.edit { clear() }
    }

    /** Treats type-mismatched or otherwise damaged preference values as absent. */
    private fun readString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** Shortest PIN accepted by the local app-lock verifier. */
        const val MIN_PIN_LENGTH = 4

        /** Longest PIN accepted by the local app-lock verifier. */
        const val MAX_PIN_LENGTH = 12

        private const val PREFERENCES_NAME = "mangossh-app-lock"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTO_LOCK_DELAY = "auto_lock_delay"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val PBKDF2_ITERATIONS = 310_000
    }
}

data class AppLockConfiguration(
    val pinConfigured: Boolean,
    val biometricEnabled: Boolean,
    val autoLockDelay: AppLockDelay = AppLockDelay.DEFAULT,
)
