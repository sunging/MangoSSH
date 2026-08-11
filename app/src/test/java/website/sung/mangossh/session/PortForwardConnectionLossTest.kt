package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Test
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType

/** Verifies that losing a Mosh companion affects only live forwards it carried. */
class PortForwardConnectionLossTest {
    @Test
    fun failsStartingAndActiveForwardsForOnlyTheDisconnectedSession() {
        val states = listOf(
            state("target-starting", "target", PortForwardRuntimePhase.STARTING),
            state("target-active", "target", PortForwardRuntimePhase.ACTIVE),
            state("target-stopped", "target", PortForwardRuntimePhase.STOPPED),
            state("other-active", "other", PortForwardRuntimePhase.ACTIVE),
        )

        val updated = failPortForwardsForSession(states, "target", "Companion disconnected")

        assertEquals(PortForwardRuntimePhase.FAILED, updated[0].phase)
        assertEquals("Companion disconnected", updated[0].detail)
        assertEquals(PortForwardRuntimePhase.FAILED, updated[1].phase)
        assertEquals("Companion disconnected", updated[1].detail)
        assertEquals(states[2], updated[2])
        assertEquals(states[3], updated[3])
    }

    private fun state(
        id: String,
        sessionId: String,
        phase: PortForwardRuntimePhase,
    ): PortForwardRuntimeState = PortForwardRuntimeState(
        runtimeId = id,
        sessionId = sessionId,
        rule = PortForwardRule(
            id = "rule-$id",
            profileId = "profile",
            type = PortForwardType.LOCAL,
            bindPort = 8022,
            destinationHost = "server.internal",
            destinationPort = 22,
        ),
        phase = phase,
    )
}
