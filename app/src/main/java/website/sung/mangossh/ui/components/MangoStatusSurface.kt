package website.sung.mangossh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tonal container for a live, transient state (checking, downloading,
 * failed) that needs to stand out from the static controls around it.
 *
 * Generalized from `UpdateSettingsCard`'s private `UpdateStatusSurface` so
 * other settings pages (e.g. connection status, tsnet enrollment) can adopt
 * the same visual language. The caller supplies a `testTag` through
 * [modifier] if one is needed, matching how `UpdateSettingsCard` still
 * applies `app_update_status`.
 */
@Composable
internal fun MangoStatusSurface(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
