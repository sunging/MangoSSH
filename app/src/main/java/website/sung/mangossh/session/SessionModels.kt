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
 * [userMessage] is optional fixed, application-owned wording for failures
 * that need a more specific explanation than [reason]. It must never contain
 * host, credential, command, or protocol-library error data.
 */
@Immutable
data class SessionEndedEvent(
    val sessionId: String,
    val reason: SessionEndReason,
    val userMessage: String? = null,
)

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
    COMPLETED,
    FAILED,
}

/**
 * Progress of one file transfer.
 *
 * [totalBytes] is only known for SFTP transfers started from the remote file
 * browser; the legacy SCP path cannot report progress, so both byte counters
 * stay at their defaults there.
 */
@Immutable
data class ScpTransferState(
    val id: String,
    val sessionId: String,
    val direction: ScpTransferDirection,
    val displayName: String,
    val remotePath: String,
    val phase: ScpTransferPhase,
    val detail: String? = null,
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
)

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
        val title: String,
        val instruction: String?,
        val fields: List<AuthenticationField>,
    ) : SessionPrompt
}

@Immutable
data class AuthenticationField(
    val label: String,
    val echo: Boolean,
)
