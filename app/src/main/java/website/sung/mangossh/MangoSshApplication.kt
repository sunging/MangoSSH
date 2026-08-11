package website.sung.mangossh

import android.app.Application
import android.content.Context
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.settings.AppThemeStore
import website.sung.mangossh.data.settings.HostListPreferencesStore
import website.sung.mangossh.data.settings.TerminalAppearanceStore
import website.sung.mangossh.data.settings.TerminalShortcutStore
import website.sung.mangossh.data.settings.UpdatePreferencesStore
import website.sung.mangossh.data.update.installedAppInfo
import website.sung.mangossh.data.update.selfUpdateSupported
import website.sung.mangossh.data.vault.VaultRepository
import website.sung.mangossh.session.SshSessionController
import website.sung.mangossh.session.tsnet.EmbeddedTsnetManager

/**
 * Application owner for resources that must outlive a single activity.
 *
 * A terminal session is user-initiated foreground work, so it must not be
 * coupled to an Activity or ViewModel that Android may recreate while the user
 * is looking at another app. The process still remains the persistence
 * boundary: Android process death intentionally ends live transports rather
 * than trying to reconnect them without user consent.
 */
class MangoSshApplication : Application() {
    /** Shared live-session dependencies for the lifetime of this app process. */
    val sessionRuntime: MangoSessionRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MangoSessionRuntime(this)
    }
}

/**
 * Groups the encrypted vault and live session engine used by Activities and
 * the foreground service.
 *
 * Keeping one instance prevents an Activity recreated from a notification tap
 * from creating a second controller for the same process.
 */
class MangoSessionRuntime(context: Context) {
    /** Device-bound encrypted persistence shared by all process-local screens. */
    val vault = VaultRepository(context.applicationContext)

    /** Key codec shared by vault operations and live authentication. */
    val keyManager = SshKeyManager()

    /** Device-local display preferences shared by live terminals and Compose screens. */
    val terminalAppearance = TerminalAppearanceStore(context.applicationContext)

    /** Device-local app-wide theme mode and dynamic color preference. */
    val appTheme = AppThemeStore(context.applicationContext)

    /** Device-local floating shortcut layout shared by all terminal screens. */
    val terminalShortcuts = TerminalShortcutStore(context.applicationContext)

    /** Device-local self-update preferences shared by settings and the update flow. */
    val updatePreferences = UpdatePreferencesStore(context.applicationContext)

    /** Device-local host list sort preference shared by the host list and its top bar. */
    val hostListPreferences = HostListPreferencesStore(context.applicationContext)

    /**
     * True when this install manages its own updates. Resolved lazily because
     * it issues a package-manager binder call the caller may want off the main
     * thread; `MangoSshViewModel` reads it for the first time from inside a
     * coroutine so the check happens off `Dispatchers.Main`.
     */
    val selfUpdateSupported: Boolean by lazy { context.applicationContext.selfUpdateSupported() }

    /** Version identity of the running build, read from the installed package rather than BuildConfig. */
    val installedAppInfo = context.applicationContext.installedAppInfo()

    /** Process-wide outbound-only Tailnet node, started only for explicit TSNET work. */
    internal val embeddedTsnetManager = EmbeddedTsnetManager(context.applicationContext)

    /** The sole live transport owner for this app process. */
    val sessionController = SshSessionController(
        context.applicationContext,
        vault,
        keyManager,
        embeddedTsnetManager,
        terminalAppearance,
    )
}
