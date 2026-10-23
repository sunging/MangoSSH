package website.sung.mangossh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R
import website.sung.mangossh.session.RemoteOperationKind

private enum class RemoteBrowserAction(val label: Int, val icon: ImageVector) {
    HOME(R.string.ui_home_directory, Icons.Outlined.Home),
    UPLOAD(R.string.ui_upload_to_this_directory, Icons.Outlined.Upload),
    TASKS(R.string.fm_tasks, Icons.AutoMirrored.Outlined.List),
}

/**
 * Keeps navigation and overflow reachable while reserving 96 dp for the server title.
 * Actions leave the row from right to left; the overflow uses the same callbacks
 * and availability rules as the visible buttons. Width changes also dismiss menus
 * so a picker cannot be launched from an obsolete upload anchor.
 */
@Composable
internal fun RemoteBrowserToolbar(
    state: RemoteBrowserUiState,
    busy: Boolean,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onTasks: () -> Unit,
    onAction: (RemoteManagementAction) -> Unit,
    onSelectAll: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("remote-browser-toolbar"),
    ) {
        val visibleCount = ((maxWidth - 192.dp) / 48.dp).toInt().coerceIn(0, RemoteBrowserAction.entries.size)
        val visible = RemoteBrowserAction.entries.take(visibleCount)
        val overflow = RemoteBrowserAction.entries.drop(visibleCount)
        var moreOpen by remember(state.sessionId, visibleCount) { mutableStateOf(false) }
        var uploadOpen by remember(state.sessionId, visibleCount) { mutableStateOf(false) }

        fun enabled(action: RemoteBrowserAction): Boolean = !state.isConnecting && when (action) {
            RemoteBrowserAction.HOME -> state.homePath != null && state.homePath != state.path
            else -> true
        }

        fun invoke(action: RemoteBrowserAction) {
            when (action) {
                RemoteBrowserAction.HOME -> onHome()
                RemoteBrowserAction.UPLOAD -> uploadOpen = true
                RemoteBrowserAction.TASKS -> onTasks()
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_close_the_file_browser))
            }
            Text(
                state.title,
                modifier = Modifier.weight(1f).testTag("remote-browser-title"),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            visible.forEach { action ->
                Box {
                    IconButton(onClick = { invoke(action) }, enabled = enabled(action), modifier = Modifier.size(48.dp)) {
                        Icon(action.icon, stringResource(action.label))
                    }
                    if (action == RemoteBrowserAction.UPLOAD) {
                        DropdownMenu(expanded = uploadOpen, onDismissRequest = { uploadOpen = false }) {
                            RemoteUploadMenuItems(
                                onFile = { uploadOpen = false; onUploadFile() },
                                onFolder = { uploadOpen = false; onUploadFolder() },
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { moreOpen = true },
                    enabled = !state.isConnecting,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.fm_more))
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    overflow.forEach { action ->
                        if (action == RemoteBrowserAction.UPLOAD) {
                            RemoteUploadMenuItems(
                                onFile = { moreOpen = false; onUploadFile() },
                                onFolder = { moreOpen = false; onUploadFolder() },
                                enabled = enabled(action),
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(action.label)) },
                                leadingIcon = { Icon(action.icon, contentDescription = null) },
                                enabled = enabled(action),
                                onClick = { moreOpen = false; invoke(action) },
                            )
                        }
                    }
                    if (overflow.isNotEmpty()) HorizontalDivider()
                    listOf(RemoteOperationKind.CREATE_FILE, RemoteOperationKind.CREATE_DIRECTORY).forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(remoteOperationLabel(kind)) },
                            onClick = { moreOpen = false; onAction(RemoteManagementAction(kind)) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fm_select_all)) },
                        onClick = { moreOpen = false; onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fm_platform)) },
                        enabled = !busy,
                        onClick = { moreOpen = false; onAction(RemoteManagementAction(null)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteUploadMenuItems(onFile: () -> Unit, onFolder: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(text = { Text(stringResource(R.string.ui_upload_file)) }, onClick = onFile, enabled = enabled)
    DropdownMenuItem(text = { Text(stringResource(R.string.ui_upload_folder)) }, onClick = onFolder, enabled = enabled)
}
