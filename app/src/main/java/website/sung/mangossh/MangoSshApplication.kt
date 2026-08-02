package website.sung.mangossh

import android.app.Application
import android.content.Context
import website.sung.mangossh.data.keys.SshKeyManager
import website.sung.mangossh.data.settings.TerminalAppearanceStore
import website.sung.mangossh.data.settings.TerminalShortcutStore
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

    /** Device-local floating shortcut layout shared by all terminal screens. */
    val terminalShortcuts = TerminalShortcutStore(context.applicationContext)

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
