package website.sung.mangossh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Surface for every settings card, matching the host list's cards rather than
 * the filled-card default: a light container that stays a step above the page
 * without turning into a grey slab under a full screen of settings. Exposed so
 * the older hand-rolled settings cards can share one surface with the helpers
 * here instead of drifting a shade darker.
 */
@Composable
internal fun mangoCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * The card idiom used across the app's newer settings cards: 16.dp padding,
 * 10.dp spacing between items. Standardizes the mix of manual `Spacer`s and
 * `spacedBy` seen in older settings code.
 */
@Composable
internal fun MangoSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = mangoCardColors()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * Group heading inside a settings page or on the hub: titleSmall weight in the
 * accent color, following the platform settings idiom where the category label
 * is the only tinted text above an otherwise neutral card.
 */
@Composable
internal fun MangoSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A card that holds a stack of preference rows (see `MangoSettingsRows.kt`).
 * Unlike [MangoSettingsCard] it applies no horizontal padding: each row pads
 * itself so its click ripple spans the full card width, matching the list
 * idiom used by system settings screens.
 *
 * [verticalPadding] insets the stack from the card's rounded ends; pass `0.dp`
 * when a row paints its own background (a selected hub row, say) and must reach
 * the card edge.
 */
@Composable
internal fun MangoPreferenceGroup(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = mangoCardColors()) {
        Column(modifier = Modifier.padding(vertical = verticalPadding), content = content)
    }
}
