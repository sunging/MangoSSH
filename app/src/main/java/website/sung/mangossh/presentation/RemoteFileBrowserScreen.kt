package website.sung.mangossh.presentation

import android.content.Context
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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import website.sung.mangossh.session.LocalDocumentTree
import website.sung.mangossh.session.MAX_REMOTE_DIRECTORY_ENTRIES
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteFileKind
import website.sung.mangossh.session.RemoteFilePaths
import java.text.DateFormat
import java.util.Date
import android.text.format.Formatter
import androidx.compose.ui.res.stringResource

/**
 * Full-screen SFTP file browser for one open SSH session.
 *
 * Every remote name, path, and preview body is server-provided user data and is
 * rendered verbatim; fixed chrome is resolved from Android string resources.
 * Directory reads happen in the view model, never in this composable.
 */
@Composable
fun RemoteFileBrowserScreen(
    state: RemoteBrowserUiState,
    onNavigate: (String) -> Unit,
    onHome: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenEntry: (RemoteFileEntry) -> Unit,
    onDownload: (String, Uri) -> Unit,
    onDownloadDirectory: (String, Uri) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onUploadDirectory: (Uri, String) -> Unit,
    onDismissPreview: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    var pendingDirectoryPath by remember { mutableStateOf<String?>(null) }
    var showUploadMenu by remember { mutableStateOf(false) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val remotePath = pendingDownloadPath
        pendingDownloadPath = null
        if (uri != null && remotePath != null) onDownload(remotePath, uri)
    }
    // A directory download needs a folder to build its tree in, which only the
    // tree contract can grant.
    val directoryDownloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val remotePath = pendingDirectoryPath
        pendingDirectoryPath = null
        if (uri != null && remotePath != null) onDownloadDirectory(remotePath, uri)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUpload(uri, uri.displayName(context))
    }
    val directoryUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onUploadDirectory(uri, uri.treeDisplayName(context))
    }
    val startDownload: (String) -> Unit = { remotePath ->
        pendingDownloadPath = remotePath
        downloadLauncher.launch(RemoteFilePaths.nameOf(remotePath))
    }
    val startDirectoryDownload: (String) -> Unit = { remotePath ->
        pendingDirectoryPath = remotePath
        directoryDownloadLauncher.launch(null)
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
                        contentDescription = stringResource(R.string.ui_close_the_file_browser),
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
                        text = if (state.isConnecting) stringResource(R.string.ui_connecting) else state.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                IconButton(
                    onClick = onHome,
                    enabled = !state.isConnecting &&
                        state.homePath != null &&
                        state.homePath != state.path,
                ) {
                    Icon(Icons.Outlined.Home, contentDescription = stringResource(R.string.ui_home_directory))
                }
                IconButton(
                    onClick = onUp,
                    enabled = !state.isConnecting && state.path != RemoteFilePaths.ROOT,
                ) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.ui_parent_directory))
                }
                Box {
                    IconButton(
                        onClick = { showUploadMenu = true },
                        enabled = !state.isConnecting,
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = stringResource(R.string.ui_upload_to_this_directory))
                    }
                    DropdownMenu(
                        expanded = showUploadMenu,
                        onDismissRequest = { showUploadMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_upload_file)) },
                            onClick = {
                                showUploadMenu = false
                                uploadLauncher.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_upload_folder)) },
                            onClick = {
                                showUploadMenu = false
                                directoryUploadLauncher.launch(null)
                            },
                        )
                    }
                }
                IconButton(onClick = onRefresh, enabled = !state.isConnecting) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh))
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
                    message = stringResource(R.string.ui_opening_a_connection_for_file_transfer),
                    isError = false,
                    onRetry = null,
                )

                state.errorMessage != null -> RemoteBrowserMessage(
                    message = state.errorMessage.asString(),
                    isError = true,
                    onRetry = onRefresh,
                )

                !state.isLoading && state.entries.isEmpty() -> RemoteBrowserMessage(
                    message = stringResource(R.string.ui_this_directory_is_empty),
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
                            onDownload = {
                                if (entry.kind == RemoteFileKind.DIRECTORY) {
                                    startDirectoryDownload(entry.path)
                                } else {
                                    startDownload(entry.path)
                                }
                            },
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

/**
 * Display name of a folder the user picked with the tree contract.
 *
 * A tree URI is not openable, so the name comes from the document it points at
 * rather than from [OpenableColumns].
 */
private fun Uri.treeDisplayName(context: Context): String = runCatching {
    LocalDocumentTree.displayName(context.contentResolver, LocalDocumentTree.rootOf(this))
}.getOrNull()?.takeIf(String::isNotBlank)
    ?: lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
    ?: "upload"

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
    val context = LocalContext.current
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
                entry.sizeBytes?.takeIf { entry.kind != RemoteFileKind.DIRECTORY }
                    ?.let { formatByteSize(context, it) },
                entry.modifiedEpochSeconds?.let { formatModifiedTime(context, it) },
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
        IconButton(onClick = onDownload) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = if (entry.kind == RemoteFileKind.DIRECTORY) {
                    stringResource(R.string.ui_download_folder)
                } else {
                    stringResource(R.string.common_download)
                },
            )
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
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
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.common_download))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ui_close_preview))
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
                    message = preview.errorMessage.asString(),
                    isError = true,
                    onRetry = null,
                )

                preview.isBinary || content == null -> RemoteBrowserMessage(
                    message = stringResource(R.string.ui_this_file_is_not_text_and_cannot_be_previewed_you_can_download_it_instea),
                    isError = false,
                    onRetry = null,
                )

                else -> Column(modifier = Modifier.weight(1f)) {
                    if (content.truncated) {
                        Text(
                            text = stringResource(R.string.ui_only_the_beginning_of_this_file_is_previewed) +
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
internal fun formatByteSize(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

private fun formatModifiedTime(context: android.content.Context, epochSeconds: Long): String {
    val locale = context.resources.configuration.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        .format(Date(epochSeconds * 1000L))
}

@Composable
private fun formatPreviewSize(totalSizeBytes: Long?): String =
    if (totalSizeBytes == null) {
        ""
    } else {
        val context = LocalContext.current
        stringResource(R.string.preview_full_size, formatByteSize(context, totalSizeBytes))
    }
