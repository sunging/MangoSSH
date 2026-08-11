package website.sung.mangossh.presentation.settings

import androidx.compose.runtime.Immutable
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.data.vault.WebDavConfig
import website.sung.mangossh.domain.AppThemeMode
import website.sung.mangossh.domain.AppThemePreferences
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalThemeId
import website.sung.mangossh.presentation.AppLanguage
import website.sung.mangossh.presentation.UpdateCardCallbacks
import website.sung.mangossh.presentation.UpdateUiState
import website.sung.mangossh.security.AppLockConfiguration
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus

/** State shown on the Appearance detail page. */
@Immutable
internal data class AppearanceSettingsState(
    val language: AppLanguage,
    val themePreferences: AppThemePreferences,
    /** False below API 31, where Material You dynamic color does not exist. */
    val dynamicColorSupported: Boolean,
)

/** State shown on the Terminal detail page. */
@Immutable
internal data class TerminalSettingsState(
    val appearance: TerminalAppearance,
)

/** State shown on the Keys & shortcuts detail page. */
@Immutable
internal data class ShortcutSettingsState(
    val config: TerminalShortcutConfig,
)

/** State shown on the Security & lock detail page. */
@Immutable
internal data class SecuritySettingsState(
    val lock: AppLockConfiguration,
)

/** State shown on the Backup & sync detail page. */
@Immutable
internal data class BackupSettingsState(
    val vaultStatus: VaultStatus,
    val webDavConfig: WebDavConfig?,
)

/** State shown on the Snippets detail page. */
@Immutable
internal data class SnippetSettingsState(
    val snippets: List<CommandSnippet>,
)

/**
 * Everything the Settings hub and its detail pages need to render.
 *
 * A decrypted portable export is deliberately not a member here: it is a
 * one-shot payload consumed by a `LaunchedEffect`-driven SAF launcher on the
 * Backup page, and a raw `ByteArray` field would give this otherwise-stable
 * state class identity-based equality, defeating Compose's skipping. It stays
 * a separate parameter on [SettingsScreen] instead.
 */
@Immutable
internal data class SettingsScreenState(
    val vaultStatus: VaultStatus,
    val appearance: AppearanceSettingsState,
    val terminal: TerminalSettingsState,
    val shortcuts: ShortcutSettingsState,
    val security: SecuritySettingsState,
    val backup: BackupSettingsState,
    val snippets: SnippetSettingsState,
    val tsnet: EmbeddedTsnetStatus,
    val update: UpdateUiState,
)

@Immutable
internal data class AppearanceSettingsCallbacks(
    val onSetLanguage: (AppLanguage) -> Unit,
    val onSetThemeMode: (AppThemeMode) -> Unit,
    val onSetDynamicColorEnabled: (Boolean) -> Unit,
)

@Immutable
internal data class TerminalSettingsCallbacks(
    val onSetFont: (TerminalFont) -> Unit,
    val onSetFontSize: (Int) -> Unit,
    val onSetTheme: (TerminalThemeId) -> Unit,
    val onSetCustomColors: (TerminalCustomColors) -> Unit,
    val onResetAppearance: () -> Unit,
)

@Immutable
internal data class ShortcutSettingsCallbacks(
    val onSaveShortcuts: (TerminalShortcutConfig) -> Unit,
)

@Immutable
internal data class SecuritySettingsCallbacks(
    val onConfigurePin: (String) -> Unit,
    val onClearAppLock: () -> Unit,
    val onSetBiometricEnabled: (Boolean) -> Unit,
    val onLockNow: () -> Unit,
)

@Immutable
internal data class BackupSettingsCallbacks(
    val onSaveWebDav: (endpoint: String, username: String, password: String, remoteFileName: String) -> Unit,
    val onClearWebDav: () -> Unit,
    val onPrepareExport: (String) -> Unit,
    val onConsumeExport: () -> Unit,
    val onImport: (ByteArray, String) -> Unit,
    val onUpload: (String) -> Unit,
    val onDownloadAndImport: (String) -> Unit,
)

@Immutable
internal data class SnippetSettingsCallbacks(
    val onSaveSnippet: (id: String?, label: String, script: String, appendNewline: Boolean) -> Unit,
    val onRemoveSnippet: (String) -> Unit,
)

@Immutable
internal data class TsnetSettingsCallbacks(
    val onBeginBrowserEnrollment: () -> Unit,
    val onBeginAuthKeyEnrollment: (CharArray) -> Unit,
    val onLogout: () -> Unit,
)

/**
 * Bundles every Settings callback into one parameter.
 *
 * `SettingsScreen` used to receive these as individual lambdas alongside its
 * state — thirty-four parameters in total before this package existed. Each
 * nested bundle is handed only to the detail page that needs it.
 */
@Immutable
internal data class SettingsCallbacks(
    val appearance: AppearanceSettingsCallbacks,
    val terminal: TerminalSettingsCallbacks,
    val shortcuts: ShortcutSettingsCallbacks,
    val security: SecuritySettingsCallbacks,
    val backup: BackupSettingsCallbacks,
    val snippets: SnippetSettingsCallbacks,
    val tsnet: TsnetSettingsCallbacks,
    val update: UpdateCardCallbacks,
)
