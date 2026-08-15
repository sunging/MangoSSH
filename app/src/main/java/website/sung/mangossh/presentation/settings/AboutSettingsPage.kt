package website.sung.mangossh.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.data.update.GitHubReleaseClient
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.MangoSectionHeader
import website.sung.mangossh.ui.components.MangoSettingsCard
import website.sung.mangossh.ui.components.SettingsActionRow

/** Public releases page for this project; distinct from [MangoSshViewModel.releasePageUrl], which is null until a check has run. */
internal fun projectReleasesUrl(): String = "https://github.com/${GitHubReleaseClient.REPO_SLUG}/releases"

/** About detail page: installed version, release page link, and bundled open-source licenses. */
@Composable
internal fun AboutSettingsPage(
    state: AboutSettingsState,
    callbacks: AboutSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    var openNotice by remember { mutableStateOf<ThirdPartyNotice?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MangoPreferenceGroup(modifier = Modifier.testTag("about_identity_card")) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("MangoSSH", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_about_version, state.versionName, state.versionCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsActionRow(
                    title = "GitHub",
                    actionLabel = stringResource(R.string.settings_about_release_page),
                    onAction = callbacks.onOpenReleasePage,
                    modifier = Modifier.testTag("settings_about_release_page"),
                )
            }
        }
        item {
            MangoSettingsCard(modifier = Modifier.testTag("about_mosh_gpl_card")) {
                Text(stringResource(R.string.settings_about_mosh_gpl_notice), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            // Section intro for the per-library cards below: a header and its
            // one-line explanation, not a card of its own.
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MangoSectionHeader(text = stringResource(R.string.settings_about_licenses_title))
                Text(
                    stringResource(R.string.settings_about_licenses_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(thirdPartyNotices, key = { it.name }) { notice ->
            MangoPreferenceGroup(modifier = Modifier.testTag("about_license_${notice.name}")) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(notice.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        notice.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (notice.licenseAsset == null) {
                        Text(notice.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (notice.licenseAsset != null) {
                    SettingsActionRow(
                        title = notice.url,
                        actionLabel = stringResource(R.string.settings_about_view_license_text),
                        onAction = { openNotice = notice },
                    )
                }
            }
        }
    }

    openNotice?.let { notice ->
        LicenseTextDialog(notice = notice, onDismiss = { openNotice = null })
    }
}

/**
 * Loads a bundled license file and shows it verbatim.
 *
 * Reads the asset off the main thread (AGENTS.md forbids blocking I/O in a
 * composable) and renders the server- and vendor-owned text through
 * [SelectionContainer] rather than [stringResource], since it must never be
 * translated or altered before rendering.
 */
@Composable
private fun LicenseTextDialog(notice: ThirdPartyNotice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var licenseText by remember(notice) { mutableStateOf<String?>(null) }
    var loadFailed by remember(notice) { mutableStateOf(false) }

    LaunchedEffect(notice) {
        val asset = notice.licenseAsset
        if (asset == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull()
        }
        if (text != null) licenseText = text else loadFailed = true
    }

    AlertDialog(
        modifier = Modifier.testTag("about_license_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_license_dialog_title, notice.name, notice.license)) },
        text = {
            val text = licenseText
            if (text != null) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            } else if (loadFailed) {
                Text(stringResource(R.string.settings_about_license_unavailable))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
