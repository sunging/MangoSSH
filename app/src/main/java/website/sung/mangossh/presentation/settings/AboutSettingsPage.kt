package website.sung.mangossh.presentation.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.sung.mangossh.R
import website.sung.mangossh.core.CrashReporter
import website.sung.mangossh.ui.components.MangoPreferenceGroup
import website.sung.mangossh.ui.components.SettingsCategoryRow

/** Public source repository, available in every distribution without coupling About to the self-updater. */
internal fun projectRepositoryUrl(): String = "https://github.com/sunging/MangoSSH"

/**
 * About detail page with a footer at the bottom of the available pane.
 *
 * The viewport is a minimum height, so short content leaves room above the footer while
 * large text or a short window can scroll the entire page without clipping either section.
 */
@Composable
internal fun AboutSettingsPage(
    state: AboutSettingsState,
    callbacks: AboutSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        report = withContext(Dispatchers.IO) { CrashReporter.lastReport(context) }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("about_identity_card"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.matchParentSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.matchParentSize())
                    }
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.settings_about_version, state.versionName, state.versionCode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                MangoPreferenceGroup {
                    SettingsCategoryRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.settings_about_repository),
                        summary = "GitHub",
                        onClick = callbacks.onOpenReleasePage,
                        modifier = Modifier.testTag("settings_about_release_page"),
                    )
                    SettingsCategoryRow(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.settings_about_licenses_title),
                        summary = null,
                        onClick = callbacks.onOpenLicenses,
                        modifier = Modifier.testTag("settings_about_licenses"),
                    )
                }
                report?.let { storedReport ->
                    CrashReportRow(storedReport, onCleared = { report = null })
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.settings_about_license_notice),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(
                        R.string.settings_about_build,
                        state.buildRevision.ifEmpty { stringResource(R.string.settings_about_build_unknown) },
                        state.distribution,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("about_build_identity"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Offers the sanitized record of the last crash, and nothing when there is none.
 *
 * A crash in the middle of connecting is hard for a user to reproduce on demand
 * and impossible to read back from Logcat afterwards, so the stored report is
 * made reachable without a cable. [CrashReporter] already excludes exception
 * messages, so the text here carries type names and stack frames only.
 */
@Composable
private fun CrashReportRow(storedReport: String, onCleared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showReport by remember { mutableStateOf(false) }
    MangoPreferenceGroup(modifier = Modifier.testTag("about_crash_report_card")) {
        SettingsCategoryRow(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.settings_about_crash_report_title),
            summary = null,
            onClick = { showReport = true },
            modifier = Modifier.testTag("settings_about_crash_report"),
        )
    }

    if (showReport) {
        AlertDialog(
            modifier = Modifier.testTag("about_crash_report_dialog"),
            onDismissRequest = { showReport = false },
            title = { Text(stringResource(R.string.settings_about_crash_report_title)) },
            text = {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            stringResource(R.string.settings_about_crash_report_summary),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(storedReport, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, storedReport)
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    },
                ) {
                    Text(stringResource(R.string.settings_about_crash_report_share))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { CrashReporter.clear(context) }
                            onCleared()
                            showReport = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_about_crash_report_clear))
                }
            },
        )
    }
}
