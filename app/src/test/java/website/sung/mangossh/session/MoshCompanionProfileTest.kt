package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.data.vault.StoredAuthentication
import website.sung.mangossh.data.vault.VaultSnapshot
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute

class MoshCompanionProfileTest {
    private val original = ConnectionProfile(
        id = "profile",
        label = "test",
        hostname = "terminal.invalid",
        username = "test",
        protocol = ConnectionProtocol.MOSH,
        authenticationId = "original",
    )
    private val replacement = original.copy(authenticationId = "new")

    @Test fun theSameAccountCanRefreshOnlyItsCredentialReference() {
        val edited = replacement.copy(
            label = "edited",
            authentication = AuthenticationMethod.TAILSCALE_SSH,
            legacySshAlgorithms = true,
            requireReauthentication = false,
            agentForwarding = true,
        )
        assertEquals(replacement, moshCompanionProfile(original, snapshot(edited)))
    }

    @Test fun aChangedAccountEndpointRouteOrProtocolCannotSupplyCredentials() {
        listOf(
            replacement.copy(username = "different"),
            replacement.copy(hostname = "other.invalid"),
            replacement.copy(port = 2222),
            replacement.copy(route = ConnectionRoute.TSNET),
            replacement.copy(protocol = ConnectionProtocol.SSH),
            replacement.copy(jumpProfileIds = listOf("hop")),
        ).forEach { edited ->
            assertEquals(original, moshCompanionProfile(original, snapshot(edited)))
        }
    }

    @Test fun aRemovedOrClearedCredentialCannotComeBackFromTheOriginalSession() {
        assertNull(moshCompanionProfile(original, snapshot(original.copy(authenticationId = null))).authenticationId)
        val noOldCredential = snapshot(replacement.copy(username = "different")).copy(
            authentications = listOf(StoredAuthentication("new", "test")),
        )
        assertNull(moshCompanionProfile(original, noOldCredential).authenticationId)
        assertNull(moshCompanionProfile(original, VaultSnapshot()).authenticationId)
    }

    private fun snapshot(profile: ConnectionProfile) = VaultSnapshot(
        profiles = listOf(profile),
        authentications = listOf(StoredAuthentication("original", "test"), StoredAuthentication("new", "test")),
    )
}
