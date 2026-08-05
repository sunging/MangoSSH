package website.sung.mangossh.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.ScpTransferKind
import website.sung.mangossh.session.ScpTransferPhase
import website.sung.mangossh.session.ScpTransferState
import website.sung.mangossh.session.canCancel
import website.sung.mangossh.session.canOpenLocally
import website.sung.mangossh.session.canOpenRemote
import website.sung.mangossh.session.canPause
import website.sung.mangossh.session.canResume
import website.sung.mangossh.session.canRetry
import website.sung.mangossh.session.isActive
import website.sung.mangossh.session.isFinished

/**
 * Combined progress of the transfers still moving bytes, or null when none of
 * them reported a total size.
 *
 * Only unfinished transfers count: a finished record would otherwise keep the
 * host list indicator pinned at whatever fraction it ended on.
 */
internal fun aggregateTransferProgress(transfers: List<ScpTransferState>): Float? {
    val measurable = transfers.filter { transfer ->
        transfer.isActive && (transfer.totalBytes ?: 0L) > 0L
    }
    if (measurable.isEmpty()) return null
    val total = measurable.sumOf { it.totalBytes ?: 0L }
    if (total <= 0L) return null
    val transferred = measurable.sumOf { it.transferredBytes }
    return (transferred.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * Host-list top bar entry point for file transfers.
 *
 * The ring around the icon carries aggregate progress while transfers run and
 * disappears once only history is left, so the action stays reachable without
 * suggesting work is still happening.
 */
@Composable
internal fun FileTransferStatusAction(
    transfers: List<ScpTransferState>,
    onClick: () -> Unit,
) {
    val activeCount = transfers.count { it.isActive }
    val progress = aggregateTransferProgress(transfers)
    BadgedBox(
        badge = {
            if (activeCount > 0) {
                Badge { Text(activeCount.toString()) }
            }
        },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.testTag("home-transfers-action")) {
            Box(contentAlignment = Alignment.Center) {
                if (activeCount > 0) {
                    if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Icon(
                    Icons.Outlined.SwapVert,
                    contentDescription = stringResource(R.string.ui_file_transfers),
                )
            }
        }
    }
}

/**
 * Detail view for every transfer this process still remembers.
 *
 * Names, remote paths, and the entry a directory transfer is on are user or
 * server data and are rendered verbatim; only the fixed chrome is localized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileTransfersSheet(
    transfers: List<ScpTransferState>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenRemoteDirectory: (ScpTransferState) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ui_file_transfers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onClearFinished,
                enabled = transfers.any { it.isFinished },
            ) {
                Text(stringResource(R.string.ui_clear_finished))
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(transfers.reversed(), key = { it.id }) { transfer ->
                TransferCard(
                    transfer = transfer,
                    onPause = { onPause(transfer.id) },
                    onResume = { onResume(transfer.id) },
                    onCancel = { onCancel(transfer.id) },
                    onRetry = { onRetry(transfer.id) },
                    onOpen = {
                        if (transfer.canOpenLocally) {
                            openDownloadedDocument(context, transfer)
                        } else {
                            onOpenRemoteDirectory(transfer)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: ScpTransferState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val upload = transfer.direction == ScpTransferDirection.UPLOAD
            val directory = transfer.kind == ScpTransferKind.DIRECTORY
            val label = when {
                upload && directory -> stringResource(R.string.ui_upload_folder)
                upload -> stringResource(R.string.ui_upload_file)
                directory -> stringResource(R.string.ui_download_folder)
                else -> stringResource(R.string.ui_download_file)
            }
            Text(
                text = "$label · ${transfer.displayName}",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = transfer.remotePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            // Byte counters are only reported by SFTP transfers started from
            // the remote file browser.
            val totalBytes = transfer.totalBytes
            if (totalBytes != null && totalBytes > 0L) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (transfer.transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatByteSize(context, transfer.transferredBytes)} / ${formatByteSize(context, totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val totalItems = transfer.totalItems
            if (transfer.kind == ScpTransferKind.DIRECTORY && totalItems != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${transfer.completedItems} / $totalItems",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            transfer.currentItem?.let { item ->
                Text(
                    text = item,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            val failed = transfer.phase == ScpTransferPhase.FAILED
            Text(
                text = listOfNotNull(transfer.phase.label(), transfer.detail?.toUiText()?.asString())
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (!transfer.controllable && !transfer.isFinished) {
                Text(
                    text = stringResource(R.string.remote_file_transfer_session_closed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (transfer.canPause) {
                    TransferAction(Icons.Outlined.Pause, stringResource(R.string.ui_pause), onPause)
                }
                if (transfer.phase == ScpTransferPhase.PAUSED) {
                    TransferAction(Icons.Outlined.PlayArrow, stringResource(R.string.transfer_resume), onResume, enabled = transfer.canResume)
                }
                if (transfer.canCancel) {
                    TransferAction(Icons.Outlined.Cancel, stringResource(R.string.ui_cancel_transfer), onCancel)
                }
                if (transfer.phase == ScpTransferPhase.FAILED || transfer.phase == ScpTransferPhase.CANCELLED) {
                    TransferAction(Icons.Outlined.Refresh, stringResource(R.string.common_retry), onRetry, enabled = transfer.canRetry)
                }
                if (transfer.canOpenLocally || transfer.canOpenRemote) {
                    TransferAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.common_open), onOpen)
                }
            }
        }
    }
}

@Composable
private fun TransferAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun ScpTransferPhase.label(): String = when (this) {
    ScpTransferPhase.QUEUED -> stringResource(R.string.ui_queued)
    ScpTransferPhase.RUNNING -> stringResource(R.string.ui_transferring)
    ScpTransferPhase.PAUSED -> stringResource(R.string.ui_paused)
    ScpTransferPhase.COMPLETED -> stringResource(R.string.ui_completed)
    ScpTransferPhase.FAILED -> stringResource(R.string.ui_failed)
    ScpTransferPhase.CANCELLED -> stringResource(R.string.ui_cancelled)
}

/**
 * Hands a finished download to whichever app can display it.
 *
 * The document is shared read-only for the life of the target activity; nothing
 * is copied out of the location the user picked.
 */
private fun openDownloadedDocument(context: Context, transfer: ScpTransferState) {
    val uri = transfer.localUri?.let(Uri::parse) ?: return
    val mimeType = if (transfer.kind == ScpTransferKind.DIRECTORY) {
        DocumentsContract.Document.MIME_TYPE_DIR
    } else {
        context.contentResolver.getType(uri) ?: FALLBACK_MIME_TYPE
    }
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, null))
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, R.string.remote_file_no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

private const val FALLBACK_MIME_TYPE = "*/*"
