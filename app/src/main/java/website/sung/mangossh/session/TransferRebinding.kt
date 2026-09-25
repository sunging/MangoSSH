package website.sung.mangossh.session

import website.sung.mangossh.domain.ConnectionRoute

/**
 * The server a transfer belongs to, captured when it was queued.
 *
 * A transfer interrupted by a lost connection may continue on a later session only
 * when every field matches, including the host key that session verified. Its staged
 * `.part` file, its approved overwrite target and the ownership checked for it exist
 * on exactly one server; a profile edited to point elsewhere, or a changed host key,
 * must never receive the rest of the bytes.
 */
internal data class TransferHostIdentity(
    val profileId: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val route: ConnectionRoute,
    val jumpProfileIds: List<String>,
    /** Algorithm and SHA-256 fingerprint of the target's verified host key. */
    val hostKey: String,
)

/** State changes for transfers whose session ends and, later, reconnects. */
internal object TransferRebinding {
    /**
     * The transfer after its session ended.
     *
     * With a known [TransferHostIdentity] ([rebindable]) an interrupted transfer becomes
     * a paused one waiting for a reconnect, keeping the contiguous [exactBytes] it had
     * confirmed. A transfer that was committing is never resumable: its destination may
     * be half replaced, so it fails as before. Nothing restarts on its own.
     *
     * A lost network usually surfaces first as an I/O error in the transfer itself, so
     * the transfer has already failed by the time its session is reported closed. When
     * [failedOnConnection] says it failed that way, it is treated like one still running.
     */
    fun detach(
        state: ScpTransferState,
        exactBytes: Long,
        rebindable: Boolean,
        failedOnConnection: Boolean = false,
    ): ScpTransferState = when {
        state.phase == ScpTransferPhase.COMMITTING -> state.copy(
            phase = ScpTransferPhase.FAILED,
            detail = RemoteFileMessage.CommitFailure,
            currentItem = null,
            controllable = false,
        )
        (state.isActive || state.phase == ScpTransferPhase.FAILED && failedOnConnection) && rebindable -> state.copy(
            phase = ScpTransferPhase.PAUSED,
            transferredBytes = exactBytes,
            detail = RemoteFileMessage.TransferSessionClosed,
            currentItem = null,
            controllable = false,
            awaitingReconnect = true,
        )
        state.isActive -> state.copy(
            phase = ScpTransferPhase.FAILED,
            detail = RemoteFileMessage.TransferSessionClosed,
            currentItem = null,
            controllable = false,
        )
        else -> state.copy(controllable = false, awaitingReconnect = rebindable)
    }

    /**
     * Whether a run failure could be the connection going away: a generic I/O failure,
     * not a refusal, a missing file, a changed source or a local document problem, and
     * never while committing. It only matters if the session then ends as well.
     */
    fun mayBeConnectionLoss(message: RemoteFileMessage, committing: Boolean): Boolean = !committing &&
        (message == RemoteFileMessage.IoFailure || message == RemoteFileMessage.Failure(RemoteFileFailure.IO_FAILURE))

    /** The transfer once a session to the same server is open again under [sessionId]. */
    fun reattach(state: ScpTransferState, sessionId: String): ScpTransferState = state.copy(
        sessionId = sessionId,
        controllable = true,
        awaitingReconnect = false,
        detail = if (state.detail == RemoteFileMessage.TransferSessionClosed) null else state.detail,
    )
}
