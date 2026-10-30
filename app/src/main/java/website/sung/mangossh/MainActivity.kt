package website.sung.mangossh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import website.sung.mangossh.presentation.LocalDocumentPickerRequests
import website.sung.mangossh.presentation.AppLockActivityObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import website.sung.mangossh.presentation.AppLanguage
import website.sung.mangossh.presentation.MangoSshApp
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.session.SessionForegroundService
import website.sung.mangossh.ui.theme.MangoSshTheme
import website.sung.mangossh.ui.theme.resolveAppDarkTheme

/** Hosts the Compose UI and delegates biometric verification without retaining biometric data. */
class MainActivity : AppCompatActivity() {
    private val mangoViewModel: MangoSshViewModel by viewModels()
    private lateinit var biometricUnlockPrompt: BiometricUnlockPrompt
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
        biometricUnlockPrompt = BiometricUnlockPrompt(this, mangoViewModel.appLockCoordinator,
            unavailable = { mangoViewModel.reportUserMessage(R.string.biometric_unavailable) })
        lifecycle.addObserver(AppLockActivityObserver(this, mangoViewModel.appLockCoordinator, biometricUnlockPrompt::cancel))
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
                CompositionLocalProvider(LocalDocumentPickerRequests provides mangoViewModel.appLockCoordinator.documents) {
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
    }

    /**
     * Delivers a system Back key event straight to the activity.
     *
     * Back arrives as a KEYCODE_BACK key event below API 33, and also on API 33+ wherever
     * the platform falls back to synthesizing one (some OEM builds turn predictive Back off
     * per app). That event first travels through the Compose hierarchy, and Compose turns
     * an unconsumed Back into a focus Exit: each press moves focus out one level (the
     * terminal nests several focus targets) and consumes the down, so `onBackPressed` and
     * every `BackHandler` stay silent until focus has nowhere left to go. Skipping the view
     * hierarchy matches the regular API 33+ path, where Back never reaches views at all.
     * The IME still sees Back first, and dialogs and popups have their own windows; a
     * hardware Esc rewritten into a fallback Back still reaches `BackHandler`.
     *
     * Back is not handled here, only routed to `OnBackPressedDispatcher` through the
     * activity's standard key callbacks, so GestureBackNavigation does not apply.
     */
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            onUserInteraction()
            return event.dispatch(this, window.decorView.keyDispatcherState, this)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSessionIntent(intent)
    }

    private fun requestBiometricUnlock() = biometricUnlockPrompt.authenticate()

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
