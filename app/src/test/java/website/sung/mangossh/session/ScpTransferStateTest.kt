package website.sung.mangossh.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies which controls a transfer offers in each phase.
 *
 * Resuming and retrying reopen work on the connection the transfer started on,
 * so both must stay unavailable once that connection is gone.
 */
class ScpTransferStateTest {
    @Test
    fun runningTransfersCanBePausedAndCancelledOnly() {
        val running = transfer(ScpTransferPhase.RUNNING)

        assertTrue(running.isActive)
        assertFalse(running.isFinished)
        assertTrue(running.canPause)
        assertTrue(running.canCancel)
        assertFalse(running.canResume)
        assertFalse(running.canRetry)
        assertFalse(running.canOpenLocally)
    }

    @Test
    fun pausedTransfersResumeOrCancel() {
        val paused = transfer(ScpTransferPhase.PAUSED)

        assertTrue(paused.canResume)
        assertTrue(paused.canCancel)
        assertFalse(paused.canPause)
        assertFalse(paused.canRetry)
    }

    @Test
    fun failedAndCancelledTransfersRetry() {
        assertTrue(transfer(ScpTransferPhase.FAILED).canRetry)
        assertTrue(transfer(ScpTransferPhase.CANCELLED).canRetry)
        assertFalse(transfer(ScpTransferPhase.COMPLETED).canRetry)
        assertFalse(transfer(ScpTransferPhase.FAILED).canCancel)
    }

    @Test
    fun aClosedConnectionDisablesResumeAndRetry() {
        val paused = transfer(ScpTransferPhase.PAUSED, controllable = false)
        val failed = transfer(ScpTransferPhase.FAILED, controllable = false)

        assertFalse(paused.canResume)
        assertFalse(failed.canRetry)
        // Cancelling only drops local bookkeeping, so it stays available.
        assertTrue(paused.canCancel)
    }

    @Test
    fun retryNeedsTheLocalDocumentItStartedFrom() {
        assertFalse(transfer(ScpTransferPhase.FAILED, localUri = null).canRetry)
    }

    @Test
    fun finishedDownloadsOpenLocallyAndUploadsOpenRemotely() {
        val download = transfer(ScpTransferPhase.COMPLETED)
        val upload = transfer(ScpTransferPhase.COMPLETED, direction = ScpTransferDirection.UPLOAD)

        assertTrue(download.canOpenLocally)
        assertFalse(download.canOpenRemote)
        assertTrue(upload.canOpenRemote)
        assertFalse(upload.canOpenLocally)
        // Browsing the remote directory again needs the connection back.
        assertFalse(upload.copy(controllable = false).canOpenRemote)
    }

    private fun transfer(
        phase: ScpTransferPhase,
        direction: ScpTransferDirection = ScpTransferDirection.DOWNLOAD,
        controllable: Boolean = true,
        localUri: String? = "content://documents/1",
    ) = ScpTransferState(
        id = "transfer",
        sessionId = "session",
        direction = direction,
        displayName = "notes.txt",
        remotePath = "/home/sung/notes.txt",
        phase = phase,
        localUri = localUri,
        controllable = controllable,
    )
}
