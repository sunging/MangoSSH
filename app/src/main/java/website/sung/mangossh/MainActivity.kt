package website.sung.mangossh

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import website.sung.mangossh.presentation.MangoSshApp
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.ui.theme.MangoSshTheme

/** Hosts the Compose UI and delegates biometric verification without retaining biometric data. */
class MainActivity : FragmentActivity() {
    private val mangoViewModel: MangoSshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangoSshTheme {
                MangoSshApp(
                    viewModel = mangoViewModel,
                    onRequestBiometricUnlock = ::requestBiometricUnlock,
                )
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) mangoViewModel.lockForBackground()
        super.onStop()
    }

    /** Requests a strong biometric only after the user explicitly chooses biometric app unlock. */
    private fun requestBiometricUnlock() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            mangoViewModel.reportUserMessage(getString(R.string.biometric_unavailable))
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    mangoViewModel.unlockWithBiometrics()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setNegativeButtonText(getString(R.string.biometric_prompt_use_pin))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}
