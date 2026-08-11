package website.sung.mangossh.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import website.sung.mangossh.presentation.AppLanguage
import website.sung.mangossh.presentation.MangoSshViewModel
import website.sung.mangossh.presentation.UpdateCardCallbacks

/**
 * Builds every Settings callback bundle from the shared [MangoSshViewModel].
 *
 * Kept in one place so `SettingsScreen` takes a single [SettingsCallbacks]
 * argument instead of the individually-wired callbacks each card used to
 * receive directly from `MangoSshApp`. [onRemoveSnippet] is the one callback
 * that cannot be wired straight to the view model: snippet removal shares
 * `MangoSshApp`'s destructive-confirmation dialog with hosts, keys, and port
 * forwards, so that lambda is still supplied by the caller.
 */
@Composable
internal fun rememberSettingsCallbacks(
    viewModel: MangoSshViewModel,
    onSetAppLanguage: (AppLanguage) -> Unit,
    onRemoveSnippet: (String) -> Unit,
): SettingsCallbacks {
    val context = LocalContext.current
    return remember(viewModel, onSetAppLanguage, onRemoveSnippet) {
        SettingsCallbacks(
            appearance = AppearanceSettingsCallbacks(
                onSetLanguage = onSetAppLanguage,
            ),
            terminal = TerminalSettingsCallbacks(
                onSetFont = viewModel::setTerminalFont,
                onSetFontSize = viewModel::setTerminalFontSize,
                onSetTheme = viewModel::setTerminalTheme,
                onSetCustomColors = viewModel::setTerminalCustomColors,
                onResetAppearance = viewModel::resetTerminalAppearance,
            ),
            shortcuts = ShortcutSettingsCallbacks(
                onSaveShortcuts = viewModel::saveTerminalShortcuts,
            ),
            security = SecuritySettingsCallbacks(
                onConfigurePin = viewModel::configureAppPin,
                onClearAppLock = viewModel::clearAppLock,
                onSetBiometricEnabled = viewModel::setBiometricUnlockEnabled,
                onLockNow = viewModel::lockForBackground,
            ),
            backup = BackupSettingsCallbacks(
                onSaveWebDav = viewModel::saveWebDavConfig,
                onClearWebDav = viewModel::clearWebDavConfig,
                onPrepareExport = viewModel::preparePortableExport,
                onConsumeExport = viewModel::consumePortableExport,
                onImport = viewModel::importPortable,
                onUpload = viewModel::uploadWebDav,
                onDownloadAndImport = viewModel::downloadWebDavAndImport,
            ),
            snippets = SnippetSettingsCallbacks(
                onSaveSnippet = viewModel::saveSnippet,
                onRemoveSnippet = onRemoveSnippet,
            ),
            tsnet = TsnetSettingsCallbacks(
                onBeginBrowserEnrollment = viewModel::beginEmbeddedTsnetBrowserEnrollment,
                onBeginAuthKeyEnrollment = viewModel::beginEmbeddedTsnetAuthKeyEnrollment,
                onLogout = viewModel::logoutEmbeddedTsnet,
            ),
            update = UpdateCardCallbacks(
                onCheckNow = viewModel::checkForUpdates,
                onDownload = viewModel::downloadUpdate,
                onCancelDownload = viewModel::cancelUpdateDownload,
                onInstall = {
                    val uri = viewModel.readyInstallUri()
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            viewModel.reportInstallHandoffFailed()
                        }
                    }
                },
                onDismissNotice = viewModel::dismissUpdateNotice,
                onSetAutomaticCheck = viewModel::setAutomaticUpdateCheckEnabled,
                onOpenReleasePage = {
                    val uri = runCatching { viewModel.releasePageUrl()?.toUri() }.getOrNull()
                    if (uri?.scheme != "https" || uri.host != "github.com") {
                        viewModel.reportReleasePageOpenFailed()
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }.onFailure { viewModel.reportReleasePageOpenFailed() }
                    }
                },
            ),
        )
    }
}
