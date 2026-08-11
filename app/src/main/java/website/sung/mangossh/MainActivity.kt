package website.sung.mangossh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.presentation.AppLanguage
import website.sung.mangossh.presentation.MangoSshApp
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.session.SessionForegroundService
import website.sung.mangossh.ui.theme.MangoSshTheme
import website.sung.mangossh.ui.theme.resolveAppDarkTheme

/** Hosts the Compose UI and delegates biometric verification without retaining biometric data. */
class MainActivity : AppCompatActivity() {
    private val mangoViewModel: MangoSshViewModel by viewModels()
    private var pendingNotificationPermissionAction: (() -> Unit)? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            mangoViewModel.reportUserMessage(R.string.notification_permission_denied)
        }
        pendingNotificationPermissionAction
            ?.also { pendingNotificationPermissionAction = null }
            ?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSessionIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themePreferences by mangoViewModel.appTheme.collectAsStateWithLifecycle()
            val darkTheme = resolveAppDarkTheme(themePreferences)
            // enableEdgeToEdge() above already ran with system-only dark-mode detection, before
            // the persisted theme preference could be read. Re-running it here with an explicit
            // detectDarkMode keeps system-bar icon contrast correct when the app theme opposes
            // the device theme (e.g. Light forced on a Dark system).
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(NAVIGATION_BAR_LIGHT_SCRIM, NAVIGATION_BAR_DARK_SCRIM) { darkTheme },
                )
            }
            MangoSshTheme(preferences = themePreferences) {
                MangoSshApp(
                    viewModel = mangoViewModel,
                    onRequestBiometricUnlock = ::requestBiometricUnlock,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    selectedAppLanguage = AppLanguage.current(),
                    onSetAppLanguage = ::setAppLanguage,
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

    /**
     * Requests a strong biometric only after the user explicitly chooses biometric app unlock.
     *
     * The prompt is bound to [BiometricGateKey], a Keystore key that Android will only let the
     * app use immediately after a fresh biometric match. [onAuthenticationSucceeded] only calls
     * [MangoSshViewModel.unlockWithBiometrics] once that key is actually exercised, so the
     * callback cannot be forged by instrumentation tooling without a genuine sensor match.
     */
    private fun requestBiometricUnlock() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            mangoViewModel.reportUserMessage(R.string.biometric_unavailable)
            return
        }
        val cipher = try {
            BiometricGateKey.encryptCipher()
        } catch (error: KeyPermanentlyInvalidatedException) {
            BiometricGateKey.reset()
            MangoLog.warn(MangoLogEvent.BIOMETRIC_UNLOCK_KEY_INVALIDATED, error)
            mangoViewModel.reportUserMessage(R.string.biometric_unavailable)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val authenticatedCipher = result.cryptoObject?.cipher
                    val verified = authenticatedCipher != null && runCatching { authenticatedCipher.doFinal() }.isSuccess
                    if (verified) {
                        mangoViewModel.unlockWithBiometrics()
                    } else {
                        MangoLog.warn(MangoLogEvent.BIOMETRIC_UNLOCK_FAILED)
                        mangoViewModel.reportUserMessage(R.string.biometric_unavailable)
                    }
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
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * Requests notification access before starting a foreground session.
     *
     * Launching the service while Android is still presenting the runtime
     * permission window can delay service creation beyond the platform's
     * foreground deadline. The user-requested connection is therefore resumed
     * only after the permission result is delivered, including when access is
     * denied.
     */
    private fun requestNotificationPermission(onReady: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationPermissionAction = onReady
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onReady()
        }
    }

    /** Applies one supported app locale and refreshes existing foreground-session notifications. */
    private fun setAppLanguage(language: AppLanguage) {
        val runtime = (application as MangoSshApplication).sessionRuntime
        val hasForegroundWork = runtime.sessionController.sessions.value.isNotEmpty() ||
            runtime.embeddedTsnetManager.foregroundRequired.value
        AppCompatDelegate.setApplicationLocales(language.localeList())
        if (hasForegroundWork) SessionForegroundService.start(this)
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

        // Mirrors androidx.activity.EdgeToEdge's own default navigation-bar scrims, which are
        // internal, so the app-forced theme can reuse them instead of losing 3-button-nav
        // legibility on API 28 and below.
        private val NAVIGATION_BAR_LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val NAVIGATION_BAR_DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        /** Creates an explicit, reusable intent for one live session notification. */
        fun sessionIntent(context: android.content.Context, sessionId: String): Intent {
            val intent = Intent(context, MainActivity::class.java)
            intent.setPackage(context.packageName)
            intent.action = ACTION_OPEN_SESSION
            intent.putExtra(EXTRA_SESSION_ID, sessionId)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return intent
        }
    }
}
