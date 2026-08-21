package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.presentation.EmbeddedTsnetCard
import website.sung.mangossh.session.tsnet.EmbeddedTsnetBuildInfo
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus

/** Tailscale (tsnet) detail page: embedded, process-scoped node enrollment. */
@Composable
internal fun TsnetSettingsPage(
    status: EmbeddedTsnetStatus,
    callbacks: TsnetSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmbeddedTsnetCard(
                status = status,
                onBeginBrowserEnrollment = callbacks.onBeginBrowserEnrollment,
                onBeginAuthKeyEnrollment = callbacks.onBeginAuthKeyEnrollment,
                onLogout = callbacks.onLogout,
            )
        }
        item {
            Text(
                stringResource(R.string.embedded_tsnet_version, EmbeddedTsnetBuildInfo.TAILSCALE_VERSION),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("embedded_tsnet_version"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
