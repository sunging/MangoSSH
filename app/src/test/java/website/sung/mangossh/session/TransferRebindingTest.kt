package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import website.sung.mangossh.domain.ConnectionRoute

class TransferRebindingTest {
    private val running = ScpTransferState("t", "old", ScpTransferDirection.UPLOAD, "a", "/", ScpTransferPhase.RUNNING,
        transferredBytes = 900, localUri = "content://a")

    @Test fun anInterruptedTransferWithAKnownServerPausesAtItsConfirmedOffset() {
        val detached = TransferRebinding.detach(running, exactBytes = 640, rebindable = true)
        assertEquals(ScpTransferPhase.PAUSED, detached.phase)
        assertEquals(640, detached.transferredBytes)
        assertTrue(detached.awaitingReconnect)
        assertFalse("nothing resumes before a reconnect", detached.canResume)
    }

    @Test fun reattachingMakesItResumableOnTheNewSessionWithoutStartingIt() {
        val back = TransferRebinding.reattach(TransferRebinding.detach(running, 640, true), "new")
        assertEquals("new", back.sessionId)
        assertEquals(ScpTransferPhase.PAUSED, back.phase)
        assertTrue(back.canResume)
        assertFalse(back.awaitingReconnect)
    }

    @Test fun anIoFailureJustBeforeTheSessionEndsIsTreatedAsAnInterruption() {
        val failed = running.copy(phase = ScpTransferPhase.FAILED, detail = RemoteFileMessage.Failure(RemoteFileFailure.IO_FAILURE))
        val detached = TransferRebinding.detach(failed, exactBytes = 640, rebindable = true, failedOnConnection = true)
        assertEquals(ScpTransferPhase.PAUSED, detached.phase)
        assertEquals(640, detached.transferredBytes)
        assertTrue(detached.awaitingReconnect)
        assertEquals(RemoteFileMessage.TransferSessionClosed, detached.detail)
    }

    @Test fun otherFailuresStayFailedWhenTheSessionEnds() {
        val refused = running.copy(phase = ScpTransferPhase.FAILED, detail = RemoteFileMessage.Failure(RemoteFileFailure.ACCESS_DENIED))
        assertEquals(ScpTransferPhase.FAILED, TransferRebinding.detach(refused, 640, true, failedOnConnection = false).phase)
        assertTrue(TransferRebinding.mayBeConnectionLoss(RemoteFileMessage.IoFailure, committing = false))
        assertTrue(TransferRebinding.mayBeConnectionLoss(RemoteFileMessage.Failure(RemoteFileFailure.IO_FAILURE), committing = false))
        assertFalse(TransferRebinding.mayBeConnectionLoss(RemoteFileMessage.IoFailure, committing = true))
        assertFalse(TransferRebinding.mayBeConnectionLoss(RemoteFileMessage.Failure(RemoteFileFailure.ACCESS_DENIED), committing = false))
        assertFalse(TransferRebinding.mayBeConnectionLoss(RemoteFileMessage.SourceChanged, committing = false))
    }

    @Test fun aCommittingTransferStillFails() {
        val detached = TransferRebinding.detach(running.copy(phase = ScpTransferPhase.COMMITTING), 900, true)
        assertEquals(ScpTransferPhase.FAILED, detached.phase)
        assertEquals(RemoteFileMessage.CommitFailure, detached.detail)
        assertFalse(detached.awaitingReconnect)
    }

    @Test fun anUnknownServerKeepsTheOldFailure() {
        val detached = TransferRebinding.detach(running, 640, rebindable = false)
        assertEquals(ScpTransferPhase.FAILED, detached.phase)
        assertFalse(detached.awaitingReconnect)
    }

    @Test fun aFailedTransferCanBeRetriedAfterReconnecting() {
        val failed = running.copy(phase = ScpTransferPhase.FAILED)
        val detached = TransferRebinding.detach(failed, 0, true)
        assertFalse(detached.canRetry)
        assertTrue(TransferRebinding.reattach(detached, "new").canRetry)
    }

    @Test fun aChangedHostKeyIsADifferentServer() {
        val identity = TransferHostIdentity("p", "h", 22, "u", ConnectionRoute.DIRECT, emptyList(), "ssh-ed25519 SHA256:a")
        assertNotEquals(identity, identity.copy(hostKey = "ssh-ed25519 SHA256:b"))
        assertNotEquals(identity, identity.copy(hostname = "other"))
    }
}
