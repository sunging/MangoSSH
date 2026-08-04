package website.sung.mangossh.presentation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.MAX_REMOTE_DIRECTORY_ENTRIES
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind
import website.sung.mangossh.session.RemoteFilePaths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource

/**
 * Full-screen SFTP file browser for one open SSH session.
 *
 * Every remote name, path, and preview body is server-provided user data and is
 * rendered verbatim; only the fixed chrome goes through [localizedUiLiteral].
 * Directory reads happen in the view model, never in this composable.
 */
@Composable
fun RemoteFileBrowserScreen(
    state: RemoteBrowserUiState,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenEntry: (RemoteFileEntry) -> Unit,
    onDownload: (String, Uri) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onDismissPreview: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val remotePath = pendingDownloadPath
        pendingDownloadPath = null
        if (uri != null && remotePath != null) onDownload(remotePath, uri)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUpload(uri, uri.displayName(context))
    }
    val startDownload: (String) -> Unit = { remotePath ->
        pendingDownloadPath = remotePath
        downloadLauncher.launch(RemoteFilePaths.nameOf(remotePath))
    }

    BackHandler {
        when {
            state.preview != null -> onDismissPreview()
            state.path != RemoteFilePaths.ROOT -> onUp()
            else -> onClose()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("remote-file-browser"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = localizedUiLiteral("关闭文件浏览器"),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.isConnecting) localizedUiLiteral("正在连接…") else state.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                IconButton(
                    onClick = onUp,
                    enabled = !state.isConnecting && state.path != RemoteFilePaths.ROOT,
                ) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = localizedUiLiteral("上级目录"))
                }
                IconButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.isConnecting,
                ) {
                    Icon(Icons.Outlined.Upload, contentDescription = localizedUiLiteral("上传到当前目录"))
                }
                IconButton(onClick = onRefresh, enabled = !state.isConnecting) {
                    Icon(Icons.Outlined.Refresh, contentDescription = localizedUiLiteral("刷新"))
                }
            }

            if (!state.isConnecting) {
                RemoteBreadcrumbs(path = state.path, onNavigate = onNavigate)
            }

            if (state.isLoading || state.isConnecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                state.isConnecting -> RemoteBrowserMessage(
                    message = localizedUiLiteral("正在为文件传输建立连接…"),
                    isError = false,
                    onRetry = null,
                )

                state.errorMessage != null -> RemoteBrowserMessage(
                    message = state.errorMessage,
                    isError = true,
                    onRetry = onRefresh,
                )

                !state.isLoading && state.entries.isEmpty() -> RemoteBrowserMessage(
                    message = localizedUiLiteral("此目录为空。"),
                    isError = false,
                    onRetry = null,
                )

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    if (state.truncated) {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.remote_file_directory_truncated,
                                    MAX_REMOTE_DIRECTORY_ENTRIES,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(state.entries, key = { it.path }) { entry ->
                        RemoteFileRow(
                            entry = entry,
                            onOpen = { onOpenEntry(entry) },
                            onDownload = { startDownload(entry.path) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        state.preview?.let { preview ->
            RemoteFilePreviewLayer(
                preview = preview,
                onDownload = { startDownload(preview.path) },
                onDismiss = onDismissPreview,
            )
        }
    }
}

/** Clickable path trail so the user can jump several levels up at once. */
@Composable
private fun RemoteBreadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val crumbs = remember(path) { RemoteFilePaths.breadcrumbs(path) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (label, target) ->
            // The root crumb is itself "/", so the separator starts one later.
            if (index > 1) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onNavigate(target) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RemoteFileRow(
    entry: RemoteFileEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("remote-file-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (entry.kind) {
                RemoteFileKind.DIRECTORY -> Icons.Outlined.Folder
                RemoteFileKind.SYMLINK -> Icons.Outlined.Link
                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val details = listOfNotNull(
                entry.sizeBytes?.takeIf { entry.kind != RemoteFileKind.DIRECTORY }?.let(::formatByteSize),
                entry.modifiedEpochSeconds?.let(::formatModifiedTime),
                entry.permissions,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.kind != RemoteFileKind.DIRECTORY) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = localizedUiLiteral("下载"))
            }
        }
    }
}

@Composable
private fun RemoteBrowserMessage(message: String, isError: Boolean, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text(localizedUiLiteral("重试")) }
        }
    }
}

/**
 * Read-only preview layer.
 *
 * It covers the listing instead of using a dialog because remote text needs the
 * full screen height to be readable on a phone.
 */
@Composable
private fun RemoteFilePreviewLayer(
    preview: RemotePreviewUiState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("remote-file-preview"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = RemoteFilePaths.nameOf(preview.path),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        text = preview.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = localizedUiLiteral("下载"))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = localizedUiLiteral("关闭预览"))
                }
            }

            val content = preview.content
            when {
                preview.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                preview.errorMessage != null -> RemoteBrowserMessage(
                    message = preview.errorMessage,
                    isError = true,
                    onRetry = null,
                )

                preview.isBinary || content == null -> RemoteBrowserMessage(
                    message = localizedUiLiteral("此文件不是文本，无法预览。可以直接下载。"),
                    isError = false,
                    onRetry = null,
                )

                else -> Column(modifier = Modifier.weight(1f)) {
                    if (content.truncated) {
                        Text(
                            text = localizedUiLiteral("仅预览文件开头部分。") +
                                " " + formatPreviewSize(content.totalSizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = content.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                            softWrap = false,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Formats a byte count for listing subtitles and transfer progress. */
internal fun formatByteSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatModifiedTime(epochSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000L))

@Composable
private fun formatPreviewSize(totalSizeBytes: Long?): String =
    if (totalSizeBytes == null) "" else localizedUiLiteral("完整大小") + " " + formatByteSize(totalSizeBytes)
