package website.sung.mangossh.security

import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.domain.HostAgentPolicy

class AgentGrantTest {
    @Test fun lockUnlockRequiresNewApprovalEvenWhenNoSignatureArrivedWhileLocked() {
        val access = AppAccessState(false)
        val grant = AgentGrant(HostAgentPolicy(), access)
        assertTrue(grant.authorize { error("Default grant should not prompt") })
        access.setLocked(true)
        assertFalse(grant.authorize { error("Locked app must not prompt") })
        access.setLocked(false)
        assertFalse(grant.authorize { false })
        assertTrue(grant.authorize { true })
    }

    @Test fun lockDuringConfirmationRejectsThatApproval() {
        val access = AppAccessState(false)
        val grant = AgentGrant(HostAgentPolicy(confirmEachSignature = true), access)
        assertFalse(grant.authorize { access.setLocked(true); access.setLocked(false); true })
    }

    @Test fun expirationRequiresRenewal() {
        var now = 0L
        val grant = AgentGrant(HostAgentPolicy(authorizationSeconds = 30), AppAccessState(false)) { now }
        assertTrue(grant.authorize { false })
        now = 30_000_000_000L
        assertFalse(grant.authorize { false })
        assertTrue(grant.authorize { true })
    }
}
