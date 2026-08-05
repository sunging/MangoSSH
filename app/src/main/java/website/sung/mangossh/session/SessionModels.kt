package website.sung.mangossh.session

import androidx.compose.runtime.Immutable
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.domain.ConnectionProtocol

/** Lifecycle visible to Compose for either an SSH shell or a native Mosh PTY. */
enum class TerminalSessionPhase {
    CONNECTING,
    VERIFYING_HOST_KEY,
    AUTHENTICATING,
    OPEN,
    FAILED,
    CLOSED,
}

/**
 * Why a live transport exists.
 *
 * A [FILE_TRANSFER] connection is authenticated exactly like a terminal one but
 * never opens a shell channel, so it has no emulator and must not be presented
 * as something the user can type into.
 */
enum class SessionKind {
    TERMINAL,
    FILE_TRANSFER,

    /** Carries port forwards only; it has no shell and is never shown as a terminal. */
    PORT_FORWARD,
}

/** Immutable terminal summary; it intentionally excludes credentials and command contents. */
@Immutable
data class TerminalSessionState(
    val id: String,
    val profileId: String,
    val title: String,
    val endpoint: String,
    val protocol: ConnectionProtocol,
    val phase: TerminalSessionPhase,
    val detail: String? = null,
    val kind: SessionKind = SessionKind.TERMINAL,
)

/** A transient terminal-originated clipboard request handled only by visible UI. */
@Immutable
data class TerminalClipboardCopy(
    val sessionId: String,
    val text: String,
)

/** Sanitized reason why a live transport was removed from the session runtime. */
enum class SessionEndReason {
    USER_REQUEST,
    REMOTE_EXIT,
    CONNECTION_LOST,
    CONNECTION_FAILED,
}

/**
 * One-shot lifecycle event for returning a visible terminal to the host list.
 *
 * [messageKind] is an optional fixed, application-owned category for failures
 * that need a more specific explanation than [reason].
 */
@Immutable
data class SessionEndedEvent(
    val sessionId: String,
    val reason: SessionEndReason,
    val messageKind: SessionEndMessageKind? = null,
)

/** Specific sanitized failure wording selected after a connection attempt ends. */
enum class SessionEndMessageKind {
    AUTHENTICATION_FAILED,
    MOSH_BOOTSTRAP_FAILED,
    MOSH_RUNTIME_MISSING,
    TSNET_ENROLLMENT_REQUIRED,
}

enum class PortForwardRuntimePhase {
    STARTING,
    ACTIVE,
    FAILED,
    STOPPED,
}

@Immutable
data class PortForwardRuntimeState(
    val runtimeId: String,
    val sessionId: String,
    val rule: PortForwardRule,
    val phase: PortForwardRuntimePhase,
    val detail: String? = null,
)

enum class ScpTransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class ScpTransferPhase {
    QUEUED,
    RUNNING,

    /** Stopped at a chunk boundary by the user; [ScpTransferState.transferredBytes] is the resume offset. */
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Whether a transfer moves a single file or a whole directory tree. */
enum class ScpTransferKind {
    FILE,
    DIRECTORY,
}

/**
 * Progress of one file transfer.
 *
 * [totalBytes] is only known for SFTP transfers started from the remote file
 * browser; the legacy SCP path cannot report progress, so both byte counters
 * stay at their defaults there.
 *
 * [displayName], [remotePath], and [currentItem] are user or server data: they
 * are rendered verbatim, never translated, and never logged.
 */
@Immutable
data class ScpTransferState(
    val id: String,
    val sessionId: String,
    val direction: ScpTransferDirection,
    val displayName: String,
    val remotePath: String,
    val phase: ScpTransferPhase,
    val kind: ScpTransferKind = ScpTransferKind.FILE,
    val detail: RemoteFileMessage? = null,
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
    /**
     * Download destination or upload source document, as a string so this model
     * stays free of Android types. Null for the legacy SCP path, which cannot
     * be resumed, retried, or opened.
     */
    val localUri: String? = null,
    /** Relative path of the entry a directory transfer is currently moving. */
    val currentItem: String? = null,
    val completedItems: Int = 0,
    val totalItems: Int? = null,
    /**
     * False once the connection that owns this transfer is gone. Resuming and
     * retrying need that connection, and the transfer list must never open a
     * new one, because host-key and authentication prompts are only rendered by
     * the remote file browser.
     */
    val controllable: Boolean = true,
)

/** True while the transfer is queued or moving bytes. */
val ScpTransferState.isActive: Boolean
    get() = phase == ScpTransferPhase.QUEUED || phase == ScpTransferPhase.RUNNING

/** True once the transfer reached a terminal phase and holds no connection. */
val ScpTransferState.isFinished: Boolean
    get() = phase == ScpTransferPhase.COMPLETED ||
        phase == ScpTransferPhase.FAILED ||
        phase == ScpTransferPhase.CANCELLED

val ScpTransferState.canPause: Boolean
    get() = isActive && controllable

val ScpTransferState.canResume: Boolean
    get() = phase == ScpTransferPhase.PAUSED && controllable

val ScpTransferState.canCancel: Boolean
    get() = isActive || phase == ScpTransferPhase.PAUSED

/** Retrying re-runs the original request from the start, so it needs the local document. */
val ScpTransferState.canRetry: Boolean
    get() = (phase == ScpTransferPhase.FAILED || phase == ScpTransferPhase.CANCELLED) &&
        controllable &&
        localUri != null

/** A finished download can be handed to another app through its destination document. */
val ScpTransferState.canOpenLocally: Boolean
    get() = phase == ScpTransferPhase.COMPLETED &&
        direction == ScpTransferDirection.DOWNLOAD &&
        localUri != null

/** A finished upload can be revisited by browsing the directory it landed in. */
val ScpTransferState.canOpenRemote: Boolean
    get() = phase == ScpTransferPhase.COMPLETED &&
        direction == ScpTransferDirection.UPLOAD &&
        controllable

@Immutable
data class ServerResourceSnapshot(
    val sessionId: String,
    val collectedAtEpochMillis: Long = System.currentTimeMillis(),
    val report: String,
)

sealed interface SessionPrompt {
    val requestId: String
    val sessionId: String

    @Immutable
    data class HostKeyVerification(
        override val requestId: String,
        override val sessionId: String,
        val hostname: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String,
        val isChanged: Boolean,
        val previousFingerprint: String?,
    ) : SessionPrompt

    @Immutable
    data class Authentication(
        override val requestId: String,
        override val sessionId: String,
        val title: SessionPromptText,
        val instruction: SessionPromptText?,
        val fields: List<AuthenticationField>,
    ) : SessionPrompt
}

@Immutable
data class AuthenticationField(
    val label: SessionPromptText,
    val echo: Boolean,
)

/** Distinguishes fixed application prompts from SSH server-provided wording. */
@Immutable
sealed interface SessionPromptText {
    data class App(
        val kind: SessionPromptTextKind,
        val argument: String? = null,
    ) : SessionPromptText

    data class Verbatim(val value: String) : SessionPromptText
}

/** Resource-independent identifiers for authentication wording owned by MangoSSH. */
enum class SessionPromptTextKind {
    PASSWORD_TITLE,
    PASSWORD_INSTRUCTION,
    PASSWORD_FIELD,
    UNLOCK_KEY_TITLE,
    KEY_PASSPHRASE_INSTRUCTION,
    KEY_PASSPHRASE_FIELD,
    TAILSCALE_LOGIN_TITLE,
    INTERACTIVE_LOGIN_TITLE,
}
