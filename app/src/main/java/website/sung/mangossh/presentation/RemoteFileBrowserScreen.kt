package website.sung.mangossh.presentation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import website.sung.mangossh.session.RemoteOperationRequest
import website.sung.mangossh.session.RemoteOperationKind
import website.sung.mangossh.session.RemotePlatform
import android.text.format.Formatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

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
    onDownload: (sessionId: String, remotePath: String, destination: Uri) -> Unit,
    onDownloadDirectory: (sessionId: String, remotePath: String, destinationTree: Uri) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onUploadDirectory: (Uri, String) -> Unit,
    onDismissPreview: () -> Unit,
    onClose: () -> Unit,
    onEditText: (String) -> Unit = {},
    onOpenWithApp: (String) -> Unit = {},
    onStream: (String) -> Unit = {},
    onCancelOpen: () -> Unit = {},
    onLaunchConsumed: () -> Unit = {},
    onOperation: (RemoteOperationRequest) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onCopy: (List<String>, Boolean) -> Unit = { _, _ -> },
    onClearClipboard: () -> Unit = {},
    onPlatform: (RemotePlatform) -> Unit = {},
    onTasks: () -> Unit = {},
    operationBusy: Boolean = false,
) {
    val context = LocalContext.current
    var managementAction by remember(state.sessionId) { mutableStateOf<RemoteManagementAction?>(null) }
    val manage: (RemoteManagementAction) -> Unit = { action ->
        if (action.kind == RemoteOperationKind.DELETE) onOperation(RemoteOperationRequest(state.sessionId, RemoteOperationKind.DELETE, action.paths))
        else managementAction = action
    }
    // Starting another app needs this Activity, so the view model only prepares
    // the URI and the screen hands it over exactly once.
    LaunchedEffect(state.pendingLaunch) {
        val launch = state.pendingLaunch ?: return@LaunchedEffect
        onLaunchConsumed()
        launchViewIntent(context, launch.uri, launch.mimeType)
    }
    // The system picker can outlive this Activity (rotation, dark-mode switch), so the
    // request it answers is saved with the session that asked for it; the result is
    // then applied to that session only, never to whichever browser is open later.
    var pendingDownloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDownloadSession by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDirectoryPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDirectorySession by rememberSaveable { mutableStateOf<String?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        DownloadDocumentContract(),
    ) { uri ->
        val remotePath = pendingDownloadPath
        val sessionId = pendingDownloadSession
        pendingDownloadPath = null
        pendingDownloadSession = null
        if (uri != null && remotePath != null && sessionId != null) onDownload(sessionId, remotePath, uri)
    }
    // A directory download needs a folder to build its tree in, which only the
    // tree contract can grant.
    val directoryDownloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val remotePath = pendingDirectoryPath
        val sessionId = pendingDirectorySession
        pendingDirectoryPath = null
        pendingDirectorySession = null
        if (uri != null && remotePath != null && sessionId != null) onDownloadDirectory(sessionId, remotePath, uri)
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
        pendingDownloadSession = state.sessionId
        downloadLauncher.launch(RemoteFilePaths.nameOf(remotePath))
    }
    val startDirectoryDownload: (String) -> Unit = { remotePath ->
        pendingDirectoryPath = remotePath
        pendingDirectorySession = state.sessionId
        directoryDownloadLauncher.launch(null)
    }

    BackHandler {
        when {
            state.selectedPaths.isNotEmpty() -> onClearSelection()
            state.opening != null -> onCancelOpen()
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
            RemoteBrowserToolbar(
                state = state,
                busy = operationBusy,
                onClose = onClose,
                onHome = onHome,
                onUploadFile = { uploadLauncher.launch(arrayOf("*/*")) },
                onUploadFolder = { directoryUploadLauncher.launch(null) },
                onTasks = onTasks,
                onAction = manage,
                onSelectAll = onSelectAll,
            )

            if (!state.isConnecting) {
                RemoteManagementToolbar(state, operationBusy, manage, onCopy, onOperation, onSelectAll, onClearSelection, onClearClipboard)
                RemoteBreadcrumbs(path = state.path, onNavigate = onNavigate)
            }

            if (state.isConnecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                RemoteBrowserMessage(
                    message = stringResource(R.string.ui_opening_a_connection_for_file_transfer),
                    isError = false,
                    onRetry = null,
                )
            } else {
                val refreshLabel = stringResource(R.string.common_refresh)
                val refresh = { if (!state.isLoading) onRefresh() }
                // Empty and error states still need a full-height scrollable child:
                // nested scrolling is what makes a pull gesture reach Material's container.
                PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = refresh,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("remote-file-refresh"),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("remote-file-list").semantics {
                            customActions = if (state.isLoading) emptyList() else listOf(
                                CustomAccessibilityAction(refreshLabel) { onRefresh(); true },
                            )
                        },
                    ) {
                        when {
                            state.errorMessage != null -> item {
                                RemoteBrowserMessage(state.errorMessage.asString(), isError = true, onRetry = refresh)
                            }
                            !state.isLoading && state.entries.isEmpty() -> item {
                                RemoteBrowserMessage(stringResource(R.string.ui_this_directory_is_empty), isError = false, onRetry = null)
                            }
                            else -> {
                                if (state.truncated) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.remote_file_directory_truncated, MAX_REMOTE_DIRECTORY_ENTRIES),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                                items(state.entries, key = { it.path }) { entry ->
                                    RemoteFileRow(
                                        entry = entry,
                                        onOpen = { if (state.selectedPaths.isNotEmpty()) onToggleSelection(entry.path) else onOpenEntry(entry) },
                                        onSelect = { onToggleSelection(entry.path) },
                                        selected = entry.path in state.selectedPaths,
                                        selectionMode = state.selectedPaths.isNotEmpty(),
                                        onManage = manage,
                                        onCopy = onCopy,
                                        onEdit = { onEditText(entry.path) },
                                        onDownload = {
                                            if (entry.kind == RemoteFileKind.DIRECTORY) startDirectoryDownload(entry.path)
                                            else startDownload(entry.path)
                                        },
                                        onOpenWithApp = { onOpenWithApp(entry.path) },
                                        onStream = { onStream(entry.path) },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        state.preview?.let { preview ->
            RemoteFilePreviewLayer(
                preview = preview,
                onEdit = { onEditText(preview.path) },
                onDownload = { startDownload(preview.path) },
                onOpenWithApp = { onOpenWithApp(preview.path) },
                onStream = { onStream(preview.path) },
                onDismiss = onDismissPreview,
            )
        }

        state.opening?.let { opening -> RemoteOpenProgressDialog(opening, onCancel = onCancelOpen) }
        managementAction?.let { action -> RemoteManagementDialog(action, state, onPlatform, onOperation) { managementAction = null } }
    }
}

/**
 * Shown while a file is prepared for another app. A whole-file download shows
 * byte progress; preparing a stream only looks the file up, so it spins.
 */
@Composable
private fun RemoteOpenProgressDialog(opening: RemoteOpenUiState, onCancel: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("remote-open-progress"),
        title = {
            Text(
                stringResource(if (opening.streaming) R.string.remote_open_preparing_stream else R.string.remote_open_downloading),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = RemoteFilePaths.nameOf(opening.path),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                val total = opening.totalBytes
                if (opening.streaming || total == null || total <= 0L) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (opening.transferredBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${formatByteSize(context, opening.transferredBytes)} / ${formatByteSize(context, total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The two ways to hand a remote file to another app, offered wherever a file
 * can be acted on.
 */
@Composable
private fun OpenElsewhereMenuItems(onOpenWithApp: () -> Unit, onStream: () -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.remote_open_with_app)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
        onClick = {
            dismiss()
            onOpenWithApp()
        },
        modifier = Modifier.testTag("remote-open-with-app"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.remote_open_stream)) },
        leadingIcon = { Icon(Icons.Outlined.PlayCircle, contentDescription = null) },
        onClick = {
            dismiss()
            onStream()
        },
        modifier = Modifier.testTag("remote-open-stream"),
    )
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
    onOpenWithApp: () -> Unit,
    onStream: () -> Unit,
    onSelect: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onManage: (RemoteManagementAction) -> Unit,
    onCopy: (List<String>, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("remote-file-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) androidx.compose.material3.Checkbox(selected, { onSelect() })
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
        run {
            // A symlink may point at a directory; the actions then fail with a
            // "not a file" message instead of being hidden on a guess.
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("remote-file-actions")) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.remote_file_actions))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    RemoteEntryManagementMenuItems(entry, onManage, onCopy, onEdit) { showMenu = false }
                    if (entry.kind != RemoteFileKind.DIRECTORY) OpenElsewhereMenuItems(onOpenWithApp, onStream, dismiss = { showMenu = false })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_download)) },
                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDownload()
                        },
                    )
                }
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
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onOpenWithApp: () -> Unit,
    onStream: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showOpenMenu by remember { mutableStateOf(false) }
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
                if (preview.content?.truncated == false && !preview.isBinary) TextButton(onClick = onEdit) { Text(stringResource(R.string.editor_edit)) }
                Box {
                    IconButton(onClick = { showOpenMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.remote_open_elsewhere))
                    }
                    DropdownMenu(expanded = showOpenMenu, onDismissRequest = { showOpenMenu = false }) {
                        OpenElsewhereMenuItems(onOpenWithApp, onStream, dismiss = { showOpenMenu = false })
                    }
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

                preview.isBinary || content == null -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RemoteBrowserMessage(
                        message = stringResource(R.string.ui_this_file_is_not_text_and_cannot_be_previewed_you_can_download_it_instea),
                        isError = false,
                        onRetry = null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenWithApp) { Text(stringResource(R.string.remote_open_with_app)) }
                        TextButton(onClick = onStream) { Text(stringResource(R.string.remote_open_stream)) }
                    }
                }

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

private fun formatModifiedTime(context: android.content.Context, epochSeconds: Long): String =
    formatShortDateTime(context, epochSeconds * 1000L)

@Composable
private fun formatPreviewSize(totalSizeBytes: Long?): String =
    if (totalSizeBytes == null) {
        ""
    } else {
        val context = LocalContext.current
        stringResource(R.string.preview_full_size, formatByteSize(context, totalSizeBytes))
    }
