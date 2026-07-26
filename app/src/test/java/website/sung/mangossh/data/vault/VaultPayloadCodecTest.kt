package website.sung.mangossh.data.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionRoute

class VaultPayloadCodecTest {
    @Test
    fun schemaFourRoundTripsEmbeddedTsnet() {
        val snapshot = VaultSnapshot(
            profiles = listOf(profile(ConnectionRoute.TSNET)),
        )

        val decoded = VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot))

        assertEquals(4, decoded.schemaVersion)
        assertEquals(ConnectionRoute.TSNET, decoded.profiles.single().route)
    }

    @Test
    fun legacyTailnetAndMissingRoutesRemainCompatible() {
        val tailnet = payloadObject(ConnectionRoute.TAILNET).apply { put("schemaVersion", 3) }
        assertEquals(
            ConnectionRoute.TAILNET,
            VaultPayloadCodec.decode(tailnet.toString().encodeToByteArray()).profiles.single().route,
        )

        val missing = payloadObject(ConnectionRoute.DIRECT).apply {
            put("schemaVersion", 3)
            getJSONArray("profiles").getJSONObject(0).remove("route")
        }
        assertEquals(
            ConnectionRoute.DIRECT,
            VaultPayloadCodec.decode(missing.toString().encodeToByteArray()).profiles.single().route,
        )
    }

    @Test
    fun explicitUnknownOrMissingSchemaFourRouteIsRejected() {
        val unknown = payloadObject(ConnectionRoute.DIRECT).apply {
            getJSONArray("profiles").getJSONObject(0).put("route", "FUTURE_ROUTE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultPayloadCodec.decode(unknown.toString().encodeToByteArray())
        }

        val missing = payloadObject(ConnectionRoute.DIRECT).apply {
            getJSONArray("profiles").getJSONObject(0).remove("route")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultPayloadCodec.decode(missing.toString().encodeToByteArray())
        }
    }

    private fun payloadObject(route: ConnectionRoute): JSONObject =
        JSONObject(VaultPayloadCodec.encode(VaultSnapshot(profiles = listOf(profile(route)))).decodeToString())

    private fun profile(route: ConnectionRoute) = ConnectionProfile(
        id = "profile",
        label = "Profile",
        hostname = "example.invalid",
        username = "user",
        route = route,
    )
}
