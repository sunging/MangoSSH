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

        assertEquals(VaultSnapshot.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(ConnectionRoute.TSNET, decoded.profiles.single().route)
    }

    @Test
    fun positionAndLastConnectedRoundTrip() {
        val snapshot = VaultSnapshot(
            profiles = listOf(
                profile(ConnectionRoute.DIRECT).copy(position = 3, lastConnectedAtEpochMillis = 1_700_000_000_000L),
            ),
        )

        val decoded = VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)).profiles.single()

        assertEquals(3, decoded.position)
        assertEquals(1_700_000_000_000L, decoded.lastConnectedAtEpochMillis)
    }

    @Test
    fun connectionCountRoundTrips() {
        val snapshot = VaultSnapshot(
            profiles = listOf(profile(ConnectionRoute.DIRECT).copy(connectionCount = 42)),
        )

        val decoded = VaultPayloadCodec.decode(VaultPayloadCodec.encode(snapshot)).profiles.single()

        assertEquals(42, decoded.connectionCount)
    }

    @Test
    fun payloadMissingConnectionCountDecodesToZero() {
        val payload = payloadObject(ConnectionRoute.DIRECT).apply {
            getJSONArray("profiles").getJSONObject(0).remove("connectionCount")
        }

        val decoded = VaultPayloadCodec.decode(payload.toString().encodeToByteArray()).profiles.single()

        assertEquals(0, decoded.connectionCount)
    }

    @Test
    fun schemaFivePreservesStoredPosition() {
        val payload = payloadObject(ConnectionRoute.DIRECT).apply {
            getJSONArray("profiles").getJSONObject(0).put("position", 7)
        }

        val decoded = VaultPayloadCodec.decode(payload.toString().encodeToByteArray()).profiles.single()

        assertEquals(7, decoded.position)
    }

    @Test
    fun schemaFourUpgradeReordersByLegacyFavoriteThenLabelDisplayOrder() {
        // Schema 4 had no persisted position; the host list was always shown favorite-first,
        // then alphabetically by label. Upgrading must reproduce that same visible order as the
        // new manual position, so a just-upgraded list looks unchanged to the user.
        val legacyProfiles = JSONObject().apply {
            put("schemaVersion", 4)
            put(
                "profiles",
                org.json.JSONArray().apply {
                    put(legacyProfileJson(id = "b", label = "Bravo", favorite = false))
                    put(legacyProfileJson(id = "a", label = "Alpha", favorite = true))
                    put(legacyProfileJson(id = "c", label = "Charlie", favorite = false))
                },
            )
            put("keys", org.json.JSONArray())
            put("knownHosts", org.json.JSONArray())
            put("snippets", org.json.JSONArray())
            put("portForwards", org.json.JSONArray())
            put("webDavConfig", JSONObject.NULL)
        }

        val decoded = VaultPayloadCodec.decode(legacyProfiles.toString().encodeToByteArray()).profiles
            .sortedBy { it.position }

        // Alpha is the favorite so it leads; Bravo/Charlie follow in label order.
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
        assertEquals(listOf(0, 1, 2), decoded.map { it.position })
    }

    private fun legacyProfileJson(id: String, label: String, favorite: Boolean) = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("hostname", "$id.invalid")
        put("port", 22)
        put("username", "user")
        put("protocol", "SSH")
        put("route", "DIRECT")
        put("authentication", "PRIVATE_KEY")
        put("keyId", JSONObject.NULL)
        put("startupSnippetId", JSONObject.NULL)
        put("agentForwarding", false)
        put("favorite", favorite)
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
