package website.sung.mangossh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            mangoViewModel.reportUserMessage(getString(R.string.notification_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSessionIntent(intent)
        enableEdgeToEdge()
        setContent {
            MangoSshTheme {
                MangoSshApp(
                    viewModel = mangoViewModel,
                    onRequestBiometricUnlock = ::requestBiometricUnlock,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSessionIntent(intent)
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

    /** Requests the Android 13+ notification grant only as a result of a connect action. */
    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Routes foreground notification destinations without exposing session identifiers in logs. */
    private fun handleSessionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_SESSION -> intent.getStringExtra(EXTRA_SESSION_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(mangoViewModel::requestOpenSession)

            ACTION_OPEN_SESSIONS -> mangoViewModel.requestOpenSessions()
        }
    }

    companion object {
        const val ACTION_OPEN_SESSION = "website.sung.mangossh.action.OPEN_SESSION"
        const val ACTION_OPEN_SESSIONS = "website.sung.mangossh.action.OPEN_SESSIONS"
        const val EXTRA_SESSION_ID = "website.sung.mangossh.extra.SESSION_ID"

        /** Creates an explicit, reusable intent for one live session notification. */
        fun sessionIntent(context: android.content.Context, sessionId: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}
