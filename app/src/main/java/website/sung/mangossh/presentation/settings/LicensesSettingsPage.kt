package website.sung.mangossh.presentation.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.ui.components.MangoPreferenceGroup

/** Compact attribution list; opening a notice leaves the list and its scroll state in place. */
@Composable
internal fun LicensesSettingsPage(modifier: Modifier = Modifier) {
    var openNoticeName by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.testTag("about_licenses_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_about_licenses_summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        items(thirdPartyNotices, key = { it.name }) { notice ->
            MangoPreferenceGroup(verticalPadding = 0.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("about_license_${notice.name}")
                        .clickable(role = Role.Button) { openNoticeName = notice.name }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(notice.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        notice.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    thirdPartyNotices.firstOrNull { it.name == openNoticeName }?.let { notice ->
        LicenseTextDialog(notice, onDismiss = { openNoticeName = null })
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
internal fun LicenseTextDialog(
    notice: ThirdPartyNotice,
    onDismiss: () -> Unit,
    loadText: suspend (Context, String) -> String? = ::loadBundledLicenseText,
) {
    val context = LocalContext.current
    var licenseText by remember(notice) { mutableStateOf<String?>(null) }
    var loadFailed by remember(notice) { mutableStateOf(false) }

    LaunchedEffect(notice, context, loadText) {
        val asset = notice.licenseAsset
        if (asset == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        val text = loadText(context, asset)
        if (text != null) licenseText = text else loadFailed = true
    }

    AlertDialog(
        modifier = Modifier.testTag("about_license_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_license_dialog_title, notice.name, notice.license)) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(notice.url, style = MaterialTheme.typography.bodySmall)
                    notice.descriptionResource?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    Text(
                        text = licenseText ?: stringResource(
                            if (loadFailed) R.string.settings_about_license_unavailable
                            else R.string.settings_about_license_loading,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("about_license_text"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/** Reads attribution verbatim off the UI thread; a missing asset becomes a visible error. */
private suspend fun loadBundledLicenseText(context: Context, asset: String): String? =
    withContext(Dispatchers.IO) {
        runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull()
    }
