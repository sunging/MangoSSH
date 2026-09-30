package website.sung.mangossh

import android.app.Application
import android.content.Context
import website.sung.mangossh.core.CrashReporter
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.settings.AppThemeStore
import website.sung.mangossh.data.settings.ConnectionPreferencesStore
import website.sung.mangossh.data.settings.HostListPreferencesStore
import website.sung.mangossh.data.settings.StreamingPreferencesStore
import website.sung.mangossh.data.settings.TerminalAppearanceStore
import website.sung.mangossh.data.settings.TerminalBehaviorStore
import website.sung.mangossh.data.settings.TerminalShortcutStore
import website.sung.mangossh.data.settings.UpdatePreferencesStore
import website.sung.mangossh.data.update.installedAppInfo
import website.sung.mangossh.data.vault.VaultRepository
import website.sung.mangossh.session.AppForegroundState
import website.sung.mangossh.session.RemoteStreamCacheBudget
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
    /**
     * Process-wide foreground/background signal. Created on the main thread in
     * [onCreate] so the platform lifecycle observer is registered correctly,
     * then handed to session components that relax power usage in the
     * background.
     */
    lateinit var appForegroundState: AppForegroundState
        private set

    private val runtime = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MangoSessionRuntime(this, appForegroundState)
    }

    /** Shared live-session dependencies for the lifetime of this app process. */
    val sessionRuntime: MangoSessionRuntime by runtime

    /**
     * The runtime only if something already created it. A content provider
     * called after a process restart has nothing to serve and must not build
     * the vault and session engine just to say so.
     */
    internal val existingSessionRuntime: MangoSessionRuntime?
        get() = if (runtime.isInitialized()) runtime.value else null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        existingSessionRuntime?.remoteStreamBudget?.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        existingSessionRuntime?.remoteStreamBudget?.trim()
    }

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else can run: a crash during session setup
        // is exactly the case that is hardest to reproduce and most valuable to
        // record.
        CrashReporter.install(this)
        appForegroundState = AppForegroundState.create()
    }
}

/**
 * Groups the encrypted vault and live session engine used by Activities and
 * the foreground service.
 *
 * Keeping one instance prevents an Activity recreated from a notification tap
 * from creating a second controller for the same process.
 */
class MangoSessionRuntime(context: Context, val appForegroundState: AppForegroundState) {
    /** Device-bound encrypted persistence shared by all process-local screens. */
    val vault = VaultRepository(context.applicationContext)

    /** Key codec shared by vault operations and live authentication. */
    val keyManager = SshKeyManager()

    /** Device-local display preferences shared by live terminals and Compose screens. */
    val terminalAppearance = TerminalAppearanceStore(context.applicationContext)

    /** Device-local terminal emulator and input behavior shared by live terminals and Compose screens. */
    val terminalBehavior = TerminalBehaviorStore(context.applicationContext)

    /** Device-local app-wide theme mode and dynamic color preference. */
    val appTheme = AppThemeStore(context.applicationContext)

    /** Device-local floating shortcut layout shared by all terminal screens. */
    val terminalShortcuts = TerminalShortcutStore(context.applicationContext)

    /** Device-local self-update preferences shared by settings and the update flow. */
    val updatePreferences = UpdatePreferencesStore(context.applicationContext)

    /** Device-local host list sort preference shared by the host list and its top bar. */
    val hostListPreferences = HostListPreferencesStore(context.applicationContext)

    /** Device-local defaults for new SSH/Mosh connections, shared by the session controller and settings. */
    val connectionPreferences = ConnectionPreferencesStore(context.applicationContext)

    /** Encrypted, device-only store for unsaved remote editor drafts. */
    internal val remoteDrafts = website.sung.mangossh.data.drafts.RemoteDraftStore.create(context.applicationContext)

    /** Version identity of the running build, read from the installed package rather than BuildConfig. */
    val installedAppInfo = context.applicationContext.installedAppInfo()

    /** Process-wide outbound-only Tailnet node, started only for explicit TSNET work. */
    internal val embeddedTsnetManager = EmbeddedTsnetManager(context.applicationContext)

    /** Application lock state persists across activity recreation, like live sessions. */
    internal val accessState = website.sung.mangossh.security.AppAccessState(
        website.sung.mangossh.security.AppLockStore(context).configuration().pinConfigured,
    )

    /** Device-local memory limit for streaming remote files to other apps. */
    val streamingPreferences = StreamingPreferencesStore(context.applicationContext)

    /** Memory shared by every streamed remote file; follows [streamingPreferences]. */
    internal val remoteStreamBudget = RemoteStreamCacheBudget(
        configuredBytes = streamingPreferences.cacheLimitMebibytes.value * MEBIBYTE,
        ceilingBytes = RemoteStreamCacheBudget.deviceCeilingBytes(context.applicationContext),
    )

    /** The sole live transport owner for this app process. */
    val sessionController = SshSessionController(
        context.applicationContext,
        vault,
        keyManager,
        embeddedTsnetManager,
        terminalAppearance,
        terminalBehavior,
        connectionPreferences,
        appForegroundState,
        accessState,
        remoteStreamBudget,
    )

    /** Applies a new streaming cache limit to the live budget. */
    fun setStreamingCacheLimitMebibytes(value: Int) {
        streamingPreferences.setCacheLimitMebibytes(value)
        remoteStreamBudget.setConfiguredBytes(streamingPreferences.cacheLimitMebibytes.value * MEBIBYTE)
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024
    }
}
