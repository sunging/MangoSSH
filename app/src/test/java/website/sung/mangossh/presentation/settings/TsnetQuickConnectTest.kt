package website.sung.mangossh.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute
import website.sung.mangossh.session.tsnet.TsnetDevice

class TsnetQuickConnectTest {
    private val lab = TsnetDevice(
        id = "n1",
        hostName = "lab-server",
        dnsName = "lab.example.ts.net",
        os = "linux",
        addresses = listOf("100.64.0.2"),
        online = true,
        lastSeenEpochMillis = 0,
        tailscaleSshEnabled = true,
    )

    @Test
    fun findsOnlySavedEmbeddedTailscaleHostsForTheDevice() {
        val embedded = profile("a", "lab", ConnectionRoute.TSNET, "root")
        val system = profile("b", "lab.example.ts.net", ConnectionRoute.TAILNET, "admin")
        val other = profile("c", "laptop", ConnectionRoute.TSNET, "me")

        assertEquals(listOf(embedded), savedTsnetHostsFor(lab, listOf(system, embedded, other)))
        assertEquals("admin", suggestedTsnetUsername(lab, listOf(system, embedded, other)))
        assertEquals("", suggestedTsnetUsername(lab, listOf(other)))
    }

    @Test
    fun defaultsToTailscaleSshOnlyWhereTheDeviceOffersIt() {
        assertEquals(AuthenticationMethod.TAILSCALE_SSH, defaultTsnetAuthentication(lab))
        assertEquals(
            AuthenticationMethod.PASSWORD,
            defaultTsnetAuthentication(lab.copy(tailscaleSshEnabled = false)),
        )
    }

    @Test
    fun quickConnectProfileTargetsMagicDnsThroughTheEmbeddedNode() {
        val profile = tsnetQuickConnectProfile(
            device = lab,
            username = " root ",
            protocol = ConnectionProtocol.MOSH,
            authentication = AuthenticationMethod.TAILSCALE_SSH,
            keyId = "ignored",
        )

        assertEquals("lab", profile.label)
        assertEquals("lab.example.ts.net", profile.hostname)
        assertEquals(22, profile.port)
        assertEquals("root", profile.username)
        assertEquals(ConnectionProtocol.MOSH, profile.protocol)
        assertEquals(ConnectionRoute.TSNET, profile.route)
        assertEquals(AuthenticationMethod.TAILSCALE_SSH, profile.authentication)
        assertNull(profile.keyId)
    }

    @Test
    fun quickConnectProfileKeepsKeyOnlyForKeyAuthentication() {
        val profile = tsnetQuickConnectProfile(
            device = lab.copy(dnsName = ""),
            username = "root",
            protocol = ConnectionProtocol.SSH,
            authentication = AuthenticationMethod.PRIVATE_KEY,
            keyId = "key-1",
        )

        assertEquals("100.64.0.2", profile.hostname)
        assertEquals("key-1", profile.keyId)
    }

    private fun profile(id: String, hostname: String, route: ConnectionRoute, username: String) =
        ConnectionProfile(id = id, label = id, hostname = hostname, username = username, route = route)
}
