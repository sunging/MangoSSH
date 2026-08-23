package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import website.sung.mangossh.presentation.UpdateCardCallbacks
import website.sung.mangossh.presentation.UpdateSettingsCard
import website.sung.mangossh.presentation.UpdateUiState

/** Updates detail page for the GitHub distribution. */
@Composable
internal fun UpdateSettingsPage(
    state: UpdateUiState,
    callbacks: UpdateCardCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { UpdateSettingsCard(state = state, callbacks = callbacks) }
    }
}
