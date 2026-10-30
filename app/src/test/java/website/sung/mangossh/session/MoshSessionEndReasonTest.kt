package website.sung.mangossh.session

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that a Mosh session's end reason follows the child's exit status,
 * because the PTY read ends with EIO even when the remote shell exits cleanly.
 */
class MoshSessionEndReasonTest {
    @Test
    fun cleanClientExitIsARemoteExit() {
        assertEquals(SessionEndReason.REMOTE_EXIT, moshSessionEndReason(Result.success(0)))
    }

    @Test
    fun failingClientExitIsAConnectionLoss() {
        assertEquals(SessionEndReason.CONNECTION_LOST, moshSessionEndReason(Result.success(1)))
    }

    @Test
    fun signalledOrAlreadyReapedClientIsAConnectionLoss() {
        assertEquals(SessionEndReason.CONNECTION_LOST, moshSessionEndReason(Result.success(null)))
    }

    @Test
    fun failedReapIsAConnectionLoss() {
        assertEquals(
            SessionEndReason.CONNECTION_LOST,
            moshSessionEndReason(Result.failure(IOException("waitpid"))),
        )
    }
}
