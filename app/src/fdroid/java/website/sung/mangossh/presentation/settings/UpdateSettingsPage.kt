package website.sung.mangossh.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import website.sung.mangossh.presentation.UpdateCardCallbacks
import website.sung.mangossh.presentation.UpdateUiState

/** Unreachable empty page retained only to keep the common navigation exhaustively typed. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun UpdateSettingsPage(
    state: UpdateUiState,
    callbacks: UpdateCardCallbacks,
    modifier: Modifier = Modifier,
) = Unit
