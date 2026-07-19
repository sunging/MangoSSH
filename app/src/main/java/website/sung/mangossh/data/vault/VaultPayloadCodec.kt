package website.sung.mangossh.data.vault

import org.json.JSONArray
import org.json.JSONObject
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.ConnectionRoute

/** Versioned JSON payload kept inside the authenticated encrypted vault. */
internal object VaultPayloadCodec {
    fun encode(snapshot: VaultSnapshot): ByteArray {
        val profiles = JSONArray().apply {
            snapshot.profiles.forEach { profile ->
                put(
                    JSONObject().apply {
                        put("id", profile.id)
                        put("label", profile.label)
                        put("hostname", profile.hostname)
                        put("port", profile.port)
                        put("username", profile.username)
                        put("protocol", profile.protocol.name)
                        put("route", profile.route.name)
                        put("authentication", profile.authentication.name)
                        put("keyId", profile.keyId ?: JSONObject.NULL)
                        put("startupSnippetId", profile.startupSnippetId ?: JSONObject.NULL)
                        put("agentForwarding", profile.agentForwarding)
                        put("favorite", profile.favorite)
                    },
                )
            }
        }
        val keys = JSONArray().apply {
            snapshot.keys.forEach { key ->
                put(
                    JSONObject().apply {
                        put("id", key.id)
                        put("label", key.label)
                        put("algorithm", key.algorithm)
                        put("publicKey", key.publicKey)
                        put("fingerprint", key.fingerprint)
                        put("privateKeyPem", key.privateKeyPem)
                        put("requiresPassphrase", key.requiresPassphrase)
                        put("createdAtEpochMillis", key.createdAtEpochMillis)
                    },
                )
            }
        }
        val knownHosts = JSONArray().apply {
            snapshot.knownHosts.forEach { hostKey ->
                put(
                    JSONObject().apply {
                        put("hostname", hostKey.hostname)
                        put("port", hostKey.port)
                        put("algorithm", hostKey.algorithm)
                        put("keyBlobBase64", hostKey.keyBlobBase64)
                        put("fingerprint", hostKey.fingerprint)
                        put("trustedAtEpochMillis", hostKey.trustedAtEpochMillis)
                    },
                )
            }
        }
        val snippets = JSONArray().apply {
            snapshot.snippets.forEach { snippet ->
                put(
                    JSONObject().apply {
                        put("id", snippet.id)
                        put("label", snippet.label)
                        put("script", snippet.script)
                        put("appendNewline", snippet.appendNewline)
                    },
                )
            }
        }
        val portForwards = JSONArray().apply {
            snapshot.portForwards.forEach { forward ->
                put(
                    JSONObject().apply {
                        put("id", forward.id)
                        put("profileId", forward.profileId)
                        put("type", forward.type.name)
                        put("bindHost", forward.bindHost)
                        put("bindPort", forward.bindPort)
                        put("destinationHost", forward.destinationHost ?: JSONObject.NULL)
                        put("destinationPort", forward.destinationPort ?: JSONObject.NULL)
                        put("startOnConnect", forward.startOnConnect)
                    },
                )
            }
        }
        return JSONObject()
            .put("schemaVersion", snapshot.schemaVersion)
            .put("profiles", profiles)
            .put("keys", keys)
            .put("knownHosts", knownHosts)
            .put("snippets", snippets)
            .put("portForwards", portForwards)
            .put(
                "webDavConfig",
                snapshot.webDavConfig?.let { config ->
                    JSONObject().apply {
                        put("endpoint", config.endpoint)
                        put("username", config.username)
                        put("password", config.password)
                        put("remoteFileName", config.remoteFileName)
                    }
                } ?: JSONObject.NULL,
            )
            .toString()
            .encodeToByteArray()
    }

    fun decode(bytes: ByteArray): VaultSnapshot {
        val root = JSONObject(bytes.decodeToString())
        val schemaVersion = root.optInt("schemaVersion", 0)
        require(schemaVersion in 1..VaultSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported vault schema version: $schemaVersion"
        }
        val profiles = root.optJSONArray("profiles")?.toProfiles().orEmpty()
        val keys = root.optJSONArray("keys")?.toKeys().orEmpty()
        val knownHosts = root.optJSONArray("knownHosts")?.toKnownHosts().orEmpty()
        val snippets = root.optJSONArray("snippets")?.toSnippets().orEmpty()
        val portForwards = root.optJSONArray("portForwards")?.toPortForwards().orEmpty()
        val webDavConfig = root.optJSONObject("webDavConfig")?.toWebDavConfig()
        return VaultSnapshot(
            schemaVersion = VaultSnapshot.CURRENT_SCHEMA_VERSION,
            profiles = profiles,
            keys = keys,
            knownHosts = knownHosts,
            snippets = snippets,
            portForwards = portForwards,
            webDavConfig = webDavConfig,
        )
    }

    private fun JSONArray.toProfiles(): List<ConnectionProfile> = buildList {
        repeat(length()) { index ->
            val value = getJSONObject(index)
            add(
                ConnectionProfile(
                    id = value.getString("id"),
                    label = value.getString("label"),
                    hostname = value.getString("hostname"),
                    port = value.getInt("port"),
                    username = value.getString("username"),
                    protocol = value.enumOrDefault("protocol", ConnectionProtocol.SSH),
                    route = value.enumOrDefault("route", ConnectionRoute.DIRECT),
                    authentication = value.enumOrDefault(
                        "authentication",
                        AuthenticationMethod.PRIVATE_KEY,
                    ),
                    keyId = value.optionalString("keyId"),
                    startupSnippetId = value.optionalString("startupSnippetId"),
                    agentForwarding = value.optBoolean("agentForwarding", false),
                    favorite = value.optBoolean("favorite", false),
                ),
            )
        }
    }

    private fun JSONArray.toKeys(): List<StoredSshKey> = buildList {
        repeat(length()) { index ->
            val value = getJSONObject(index)
            add(
                StoredSshKey(
                    id = value.getString("id"),
                    label = value.getString("label"),
                    algorithm = value.getString("algorithm"),
                    publicKey = value.getString("publicKey"),
                    fingerprint = value.getString("fingerprint"),
                    privateKeyPem = value.optString("privateKeyPem"),
                    requiresPassphrase = value.optBoolean("requiresPassphrase", false),
                    createdAtEpochMillis = value.optLong("createdAtEpochMillis", 0L),
                ),
            )
        }
    }

    private fun JSONArray.toKnownHosts(): List<TrustedHostKey> = buildList {
        repeat(length()) { index ->
            val value = getJSONObject(index)
            add(
                TrustedHostKey(
                    hostname = value.getString("hostname"),
                    port = value.getInt("port"),
                    algorithm = value.getString("algorithm"),
                    keyBlobBase64 = value.getString("keyBlobBase64"),
                    fingerprint = value.getString("fingerprint"),
                    trustedAtEpochMillis = value.optLong("trustedAtEpochMillis", 0L),
                ),
            )
        }
    }

    private fun JSONArray.toSnippets(): List<CommandSnippet> = buildList {
        repeat(length()) { index ->
            val value = getJSONObject(index)
            add(
                CommandSnippet(
                    id = value.getString("id"),
                    label = value.getString("label"),
                    script = value.getString("script"),
                    appendNewline = value.optBoolean("appendNewline", true),
                ),
            )
        }
    }

    private fun JSONArray.toPortForwards(): List<PortForwardRule> = buildList {
        repeat(length()) { index ->
            val value = getJSONObject(index)
            add(
                PortForwardRule(
                    id = value.getString("id"),
                    profileId = value.getString("profileId"),
                    type = value.enumOrDefault("type", PortForwardType.LOCAL),
                    bindHost = value.optString("bindHost", "127.0.0.1"),
                    bindPort = value.getInt("bindPort"),
                    destinationHost = value.optionalString("destinationHost"),
                    destinationPort = if (value.isNull("destinationPort")) null else value.getInt("destinationPort"),
                    startOnConnect = value.optBoolean("startOnConnect", false),
                ),
            )
        }
    }

    private fun JSONObject.toWebDavConfig(): WebDavConfig = WebDavConfig(
        endpoint = getString("endpoint"),
        username = getString("username"),
        password = getString("password"),
        remoteFileName = optString("remoteFileName", "mangossh-vault.mssh"),
    )

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private inline fun <reified T : Enum<T>> JSONObject.enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(getString(name)) }.getOrDefault(fallback)
}
