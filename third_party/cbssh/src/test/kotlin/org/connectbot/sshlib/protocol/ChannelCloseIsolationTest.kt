package org.connectbot.sshlib.protocol

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local cancellation keeps protocol identity alive but discards crossed inbound packets. */
class ChannelCloseIsolationTest {
    @Test fun inFlightPacketsAfterLocalCloseDoNotTouchReleasedStreams() = runTest {
        val lifecycle = SshChannelStateMachine(SshChannelState.OPEN)
        var closes = 0
        assertTrue(lifecycle.sendClose { closes++ })
        assertEquals(SshChannelState.CLOSE_SENT, lifecycle.state)
        assertTrue(lifecycle.receiveData { error("Closed stream received data") })
        assertTrue(lifecycle.receiveWindowAdjust { error("Closed stream window changed") })
        assertTrue(lifecycle.receiveEof { error("Closed stream received EOF") })
        assertTrue(lifecycle.receiveRequest { error("Closed stream received request") })
        assertTrue(lifecycle.receiveClose { closes++ })
        assertEquals(SshChannelState.CLOSED, lifecycle.state)
        assertEquals(2, closes)
    }
}
