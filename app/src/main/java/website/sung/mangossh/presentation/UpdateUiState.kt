package website.sung.mangossh.presentation

import androidx.compose.runtime.Immutable
import website.sung.mangossh.domain.AppRelease

/** Immutable self-update state rendered by the settings card and the navigation badge. */
@Immutable
data class UpdateUiState(
    /** False when a store owns updates for this install; the whole section is hidden. */
    val supported: Boolean = false,
    val automaticCheckEnabled: Boolean = true,
    val installedVersion: String = "",
    val phase: UpdatePhase = UpdatePhase.Idle,
) {
    /** True while an update the user has not dismissed is waiting for action. */
    val hasPendingUpdate: Boolean
        get() = supported && (phase is UpdatePhase.Available || phase is UpdatePhase.ReadyToInstall)
}

/** One step of the self-update flow, driving what [UpdateSettingsCard] renders. */
@Immutable
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val release: AppRelease) : UpdatePhase
    data class Downloading(val release: AppRelease, val downloadedBytes: Long, val totalBytes: Long) : UpdatePhase
    data class Verifying(val release: AppRelease) : UpdatePhase
    data class ReadyToInstall(val release: AppRelease) : UpdatePhase

    /** [release] is retained when known so retry can resume without re-checking. */
    data class Failed(val message: UiText, val release: AppRelease?) : UpdatePhase
}

/**
 * Update actions handed to [UpdateSettingsCard].
 *
 * Bundled rather than added as individual `SettingsScreen` parameters because
 * that signature already carries thirty. Must be constructed inside
 * `remember(viewModel) { ... }` at the call site so the card stays skippable.
 */
@Immutable
internal data class UpdateCardCallbacks(
    val onCheckNow: () -> Unit,
    val onDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onInstall: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onSetAutomaticCheck: (Boolean) -> Unit,
    val onOpenReleasePage: () -> Unit,
)
