package website.sung.mangossh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A Keystore-backed key that exists only to bind the app-unlock biometric prompt to a real
 * cryptographic operation.
 *
 * A [android.hardware.biometrics.BiometricPrompt] success callback carries no proof that a
 * biometric check actually happened; instrumentation tooling can invoke it directly. Requiring
 * the callback to use a live [Cipher] from a key that Android Keystore only unlocks after a
 * fresh biometric match closes that gap, since the cipher operation itself is authorized by the
 * hardware-backed keystore rather than by application code.
 */
internal object BiometricGateKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "website.sung.mangossh.biometric-gate-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** A fresh encrypt [Cipher] that Android will only let a caller use right after a live biometric check. */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    /** Drops the gate key so a fresh one is generated after biometric enrollment invalidates it. */
    fun reset() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                .build(),
        )
        return generator.generateKey()
    }
}
