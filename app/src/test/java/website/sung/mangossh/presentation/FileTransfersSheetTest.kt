package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.session.ScpTransferDirection
import website.sung.mangossh.session.ScpTransferPhase
import website.sung.mangossh.session.ScpTransferState

/**
 * Verifies the aggregate progress shown by the host list transfer indicator.
 *
 * Only transfers still moving bytes may contribute: a finished record would
 * otherwise pin the indicator at the fraction it ended on.
 */
class FileTransfersSheetTest {
    @Test
    fun reportsNoProgressWithoutMeasurableTransfers() {
        assertNull(aggregateTransferProgress(emptyList()))
        assertNull(aggregateTransferProgress(listOf(transfer(transferred = 100L, total = null))))
        assertNull(aggregateTransferProgress(listOf(transfer(transferred = 100L, total = 0L))))
    }

    @Test
    fun reportsTheFractionOfOneTransfer() {
        val progress = aggregateTransferProgress(listOf(transfer(transferred = 50L, total = 100L)))

        assertEquals(0.5f, progress!!, 0.0001f)
    }

    @Test
    fun sumsBytesAcrossActiveTransfers() {
        val progress = aggregateTransferProgress(
            listOf(
                transfer(transferred = 50L, total = 100L),
                transfer(transferred = 100L, total = 300L),
            ),
        )

        assertEquals(0.375f, progress!!, 0.0001f)
    }

    @Test
    fun ignoresTransfersThatAreNoLongerRunning() {
        val progress = aggregateTransferProgress(
            listOf(
                transfer(transferred = 50L, total = 100L),
                transfer(transferred = 900L, total = 900L, phase = ScpTransferPhase.COMPLETED),
                transfer(transferred = 10L, total = 800L, phase = ScpTransferPhase.PAUSED),
                transfer(transferred = 0L, total = 400L, phase = ScpTransferPhase.CANCELLED),
            ),
        )

        assertEquals(0.5f, progress!!, 0.0001f)
    }

    @Test
    fun clampsCountersThatOvershootTheirTotal() {
        val progress = aggregateTransferProgress(listOf(transfer(transferred = 300L, total = 100L)))

        assertEquals(1f, progress!!, 0.0001f)
    }

    private fun transfer(
        transferred: Long,
        total: Long?,
        phase: ScpTransferPhase = ScpTransferPhase.RUNNING,
    ) = ScpTransferState(
        id = "transfer-$transferred-$total-$phase",
        sessionId = "session",
        direction = ScpTransferDirection.DOWNLOAD,
        displayName = "notes.txt",
        remotePath = "/home/sung/notes.txt",
        phase = phase,
        transferredBytes = transferred,
        totalBytes = total,
    )
}
