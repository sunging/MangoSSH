package website.sung.mangossh.presentation

import androidx.compose.runtime.Immutable
import website.sung.mangossh.session.RemoteFileEntry
import website.sung.mangossh.session.RemoteLaunchRequest
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
    /** Where the session's account starts, resolved once when the browser opens. */
    val homePath: String? = null,
    val entries: List<RemoteFileEntry> = emptyList(),
    val truncated: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: UiText? = null,
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
    /** A file being prepared for another app, shown with progress and a cancel action. */
    val opening: RemoteOpenUiState? = null,
    /** A prepared file the screen must hand to another app once, then consume. */
    val pendingLaunch: RemoteLaunchRequest? = null,
)

/**
 * Progress of preparing [path] for another app: a whole-file download into the
 * private cache, or, when [streaming], only the metadata lookup for a stream.
 */
@Immutable
data class RemoteOpenUiState(
    val path: String,
    val streaming: Boolean = false,
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
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
    val errorMessage: UiText? = null,
)
