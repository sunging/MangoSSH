package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHealthTest {
    private val open = TerminalSessionPhase.OPEN

    @Test fun aHealthySessionNeedsNoAttention() {
        assertEquals(SessionAttention.NONE, sessionAttention(open, SessionHealth(CompanionHealth.CONNECTED), NetworkStatus()))
        assertEquals(SessionAttention.NONE, sessionAttention(open, null, NetworkStatus()))
    }

    @Test fun aLostNetworkExplainsALostCompanion() {
        val health = SessionHealth(CompanionHealth.LOST)
        assertEquals(SessionAttention.NETWORK_LOST, sessionAttention(open, health, NetworkStatus(NetworkHealth.LOST)))
        assertEquals(SessionAttention.NETWORK_BLOCKED, sessionAttention(open, health, NetworkStatus(NetworkHealth.BLOCKED)))
    }

    @Test fun companionStatesAreReportedForAMoshSessionOnAWorkingNetwork() {
        assertEquals(SessionAttention.COMPANION_LOST, sessionAttention(open, SessionHealth(CompanionHealth.LOST), NetworkStatus()))
        assertEquals(SessionAttention.COMPANION_RECONNECTING, sessionAttention(open, SessionHealth(CompanionHealth.RECONNECTING), NetworkStatus()))
        assertEquals(SessionAttention.COMPANION_RECONNECT_FAILED,
            sessionAttention(open, SessionHealth(CompanionHealth.RECONNECT_FAILED), NetworkStatus()))
    }

    @Test fun anUnvalidatedNetworkIsTheLeastSevereHint() {
        assertEquals(SessionAttention.NETWORK_UNVALIDATED, sessionAttention(open, null, NetworkStatus(validated = false)))
        assertEquals(SessionAttention.COMPANION_LOST,
            sessionAttention(open, SessionHealth(CompanionHealth.LOST), NetworkStatus(validated = false)))
    }

    @Test fun sessionsThatAreNotOpenReportNothing() {
        assertEquals(SessionAttention.NONE,
            sessionAttention(TerminalSessionPhase.CONNECTING, null, NetworkStatus(NetworkHealth.LOST)))
    }
}
