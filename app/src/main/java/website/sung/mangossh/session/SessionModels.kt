package website.sung.mangossh.session

import androidx.compose.runtime.Immutable
import website.sung.mangossh.data.vault.PortForwardRule

enum class TerminalSessionPhase {
    CONNECTING,
    VERIFYING_HOST_KEY,
    AUTHENTICATING,
    OPEN,
    FAILED,
    CLOSED,
}

@Immutable
data class TerminalSessionState(
    val id: String,
    val profileId: String,
    val title: String,
    val endpoint: String,
    val phase: TerminalSessionPhase,
    val detail: String? = null,
)

enum class TerminalOutputSource {
    STDOUT,
    STDERR,
    LOCAL_NOTICE,
}

@Immutable
data class TerminalOutput(
    val sessionId: String,
    val bytes: ByteArray,
    val source: TerminalOutputSource,
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

@Immutable
data class ScpTransferState(
    val id: String,
    val sessionId: String,
    val direction: ScpTransferDirection,
    val displayName: String,
    val remotePath: String,
    val phase: ScpTransferPhase,
    val detail: String? = null,
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
