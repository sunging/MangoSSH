package website.sung.mangossh.presentation

import androidx.compose.runtime.Immutable
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteTextPreview

/**
 * Everything the remote file browser renders for one SSH session.
 *
 * Paths, entry names, and preview text are remote-provided user data: they are
 * displayed verbatim, never translated, and never logged.
 */
@Immutable
data class RemoteBrowserUiState(
    val sessionId: String,
    val title: String,
    val path: String,
    val entries: List<RemoteFileEntry> = emptyList(),
    val truncated: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val preview: RemotePreviewUiState? = null,
    /** True while a transfer-only connection is still authenticating. */
    val isConnecting: Boolean = false,
    /**
     * True when the browser opened its own transfer-only connection and must
     * release it on close; false when it borrowed a running terminal session.
     */
    val ownsSession: Boolean = false,
    /** Set only for a browser-owned connection, to recognize a repeat request. */
    val profileId: String? = null,
)

/**
 * State of the read-only preview layer.
 *
 * [content] stays null while loading, when the file turned out to be binary, or
 * when the read failed; [isBinary] and [errorMessage] distinguish those cases.
 */
@Immutable
data class RemotePreviewUiState(
    val path: String,
    val isLoading: Boolean = true,
    val content: RemoteTextPreview? = null,
    val isBinary: Boolean = false,
    val errorMessage: String? = null,
)
