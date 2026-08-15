package website.sung.mangossh.presentation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import website.sung.mangossh.R
import website.sung.mangossh.domain.AppRelease
import website.sung.mangossh.ui.components.mangoCardColors

/** Settings card for checking, downloading, verifying, and installing GitHub releases. */
@Composable
internal fun UpdateSettingsCard(
    state: UpdateUiState,
    callbacks: UpdateCardCallbacks,
) {
    val context = LocalContext.current
    var showReleaseNotes by rememberSaveable { mutableStateOf(false) }
    var permissionUnavailable by rememberSaveable { mutableStateOf(false) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The unknown-sources settings screen always returns RESULT_CANCELED,
        // regardless of the user's choice, so the permission itself must be
        // re-queried rather than branching on the result code.
        if (context.packageManager.canRequestPackageInstalls()) callbacks.onInstall()
    }

    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) {
            callbacks.onInstall()
            return
        }
        val scoped = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        runCatching { installPermissionLauncher.launch(scoped) }
            .recoverCatching {
                // Some OEM builds have no activity for the package-scoped variant.
                installPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            .onFailure { permissionUnavailable = true }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_update_card"),
        colors = mangoCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                // No title here: the page this card fills is already titled
                // "Updates" in the top bar.
                if (state.installedVersion.isNotBlank()) {
                    Text(
                        stringResource(R.string.app_update_installed_version, state.installedVersion),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                stringResource(R.string.app_update_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(R.string.app_update_automatic_check), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.app_update_automatic_check_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.automaticCheckEnabled,
                    onCheckedChange = callbacks.onSetAutomaticCheck,
                    modifier = Modifier.testTag("app_update_auto_check_switch"),
                )
            }

            UpdatePhaseContent(
                phase = state.phase,
                onCheckNow = callbacks.onCheckNow,
                onDownload = callbacks.onDownload,
                onCancelDownload = callbacks.onCancelDownload,
                onInstall = ::requestInstall,
                onDismissNotice = callbacks.onDismissNotice,
                onShowReleaseNotes = { showReleaseNotes = true },
                onOpenReleasePage = callbacks.onOpenReleasePage,
            )

            if (permissionUnavailable) {
                Text(
                    stringResource(R.string.app_update_permission_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    val releaseForNotes = state.phase.releaseOrNull()
    if (showReleaseNotes && releaseForNotes != null) {
        ReleaseNotesDialog(
            release = releaseForNotes,
            onDismiss = { showReleaseNotes = false },
            onOpenReleasePage = callbacks.onOpenReleasePage,
        )
    }
}

@Composable
private fun UpdatePhaseContent(
    phase: UpdatePhase,
    onCheckNow: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismissNotice: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val context = LocalContext.current
    when (phase) {
        UpdatePhase.Idle -> {
            OutlinedButton(onClick = onCheckNow, modifier = Modifier.testTag("app_update_check_now")) {
                Text(stringResource(R.string.app_update_check_now))
            }
        }

        UpdatePhase.Checking -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("app_update_progress"))
                Text(stringResource(R.string.app_update_checking), style = MaterialTheme.typography.bodySmall)
            }
        }

        UpdatePhase.UpToDate -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                StatusHeadline(
                    icon = Icons.Outlined.CheckCircle,
                    text = stringResource(R.string.app_update_up_to_date),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCheckNow, modifier = Modifier.testTag("app_update_check_now")) {
                Text(stringResource(R.string.app_update_check_now))
            }
        }

        is UpdatePhase.Available -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.primaryContainer) {
                StatusHeadline(
                    icon = Icons.Outlined.CloudDownload,
                    text = stringResource(R.string.app_update_available, phase.release.version.toString()),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, modifier = Modifier.testTag("app_update_download")) {
                        Text(stringResource(R.string.app_update_download))
                    }
                    TextButton(onClick = onShowReleaseNotes, modifier = Modifier.testTag("app_update_release_notes")) {
                        Text(stringResource(R.string.app_update_release_notes))
                    }
                }
            }
            TextButton(
                onClick = onDismissNotice,
                modifier = Modifier.testTag("app_update_dismiss"),
            ) {
                Text(stringResource(R.string.app_update_dismiss))
            }
        }

        is UpdatePhase.Downloading -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                if (phase.totalBytes > 0) {
                    val fraction = (phase.downloadedBytes.toFloat() / phase.totalBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().testTag("app_update_progress"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.app_update_downloading, (fraction * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${formatByteSize(context, phase.downloadedBytes)} / ${formatByteSize(context, phase.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("app_update_progress"))
                    Text(stringResource(R.string.app_update_downloading_indeterminate), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancelDownload, modifier = Modifier.testTag("app_update_cancel_download")) {
                    Text(stringResource(R.string.app_update_cancel))
                }
            }
        }

        is UpdatePhase.Verifying -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("app_update_progress"))
                Text(stringResource(R.string.app_update_verifying), style = MaterialTheme.typography.bodySmall)
            }
        }

        is UpdatePhase.ReadyToInstall -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.primaryContainer) {
                StatusHeadline(
                    icon = Icons.Outlined.CheckCircle,
                    text = stringResource(R.string.app_update_ready),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, modifier = Modifier.testTag("app_update_install")) {
                        Text(stringResource(R.string.app_update_install))
                    }
                    TextButton(onClick = onShowReleaseNotes, modifier = Modifier.testTag("app_update_release_notes")) {
                        Text(stringResource(R.string.app_update_release_notes))
                    }
                }
            }
        }

        is UpdatePhase.Failed -> {
            UpdateStatusSurface(color = MaterialTheme.colorScheme.errorContainer) {
                StatusHeadline(
                    icon = Icons.Outlined.ErrorOutline,
                    text = phase.message.asString(),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phase.release != null) {
                        Button(onClick = onDownload, modifier = Modifier.testTag("app_update_retry")) {
                            Text(stringResource(R.string.app_update_retry))
                        }
                    } else {
                        OutlinedButton(onClick = onCheckNow, modifier = Modifier.testTag("app_update_retry")) {
                            Text(stringResource(R.string.app_update_retry))
                        }
                    }
                    if (phase.release?.htmlUrl != null) {
                        TextButton(
                            onClick = onOpenReleasePage,
                            modifier = Modifier.testTag("app_update_open_release_page"),
                        ) {
                            Text(stringResource(R.string.app_update_open_release_page))
                        }
                    }
                }
            }
        }
    }
}

/** Tonal container that visually separates a live update state from the static settings above it. */
@Composable
private fun UpdateStatusSurface(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .testTag("app_update_status"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusHeadline(icon: ImageVector, text: String, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
    }
}

/**
 * Shows the server-owned release title and notes verbatim, never through
 * `stringResource` — release-please generates this text and it must never be
 * translated or altered before rendering.
 */
@Composable
private fun ReleaseNotesDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("app_update_release_notes_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(release.title) },
        text = {
            SelectionContainer {
                Text(
                    text = release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_update_release_notes_close)) }
        },
        dismissButton = if (release.htmlUrl != null) {
            {
                TextButton(
                    onClick = onOpenReleasePage,
                    modifier = Modifier.testTag("app_update_release_notes_open_release_page"),
                ) {
                    Text(stringResource(R.string.app_update_open_release_page))
                }
            }
        } else {
            null
        },
    )
}

private fun UpdatePhase.releaseOrNull(): AppRelease? = when (this) {
    is UpdatePhase.Available -> release
    is UpdatePhase.Downloading -> release
    is UpdatePhase.Verifying -> release
    is UpdatePhase.ReadyToInstall -> release
    is UpdatePhase.Failed -> release
    UpdatePhase.Idle, UpdatePhase.Checking, UpdatePhase.UpToDate -> null
}
