package website.sung.mangossh.presentation

import androidx.compose.runtime.Composable

/** F-Droid owns updates, so this flavor deliberately renders no self-update card. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun UpdateSettingsCard(
    state: UpdateUiState,
    callbacks: UpdateCardCallbacks,
) = Unit
