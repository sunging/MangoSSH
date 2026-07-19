package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun tailnetDraftUsesTailscaleAuthentication() {
        val profile = ConnectionProfileDraft(
            label = "lab",
            hostname = "lab.tailnet.ts.net",
            port = 22,
            username = "alice",
            protocol = ConnectionProtocol.SSH,
            route = ConnectionRoute.TAILNET,
        ).toProfile()

        assertEquals(AuthenticationMethod.TAILSCALE_SSH, profile.authentication)
        assertEquals("lab.tailnet.ts.net", profile.endpoint)
    }

    @Test
    fun invalidPortsAreRejectedBeforePersistence() {
        val draft = ConnectionProfileDraft(
            label = "invalid",
            hostname = "example.test",
            port = 65536,
            username = "root",
            protocol = ConnectionProtocol.SSH,
            route = ConnectionRoute.DIRECT,
        )

        assertFalse(draft.isValid())
        assertTrue(
            draft.copy(port = 22).isValid(),
        )
    }
}
