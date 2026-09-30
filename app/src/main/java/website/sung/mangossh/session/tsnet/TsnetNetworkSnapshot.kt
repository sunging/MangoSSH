package website.sung.mangossh.session.tsnet

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** This app node as the tailnet sees it. */
@Immutable
internal data class TsnetSelf(
    val hostName: String,
    val dnsName: String,
    val addresses: List<String>,
) {
    /** MagicDNS short name when known, otherwise the requested hostname. */
    val displayName: String get() = dnsName.substringBefore('.').ifBlank { hostName }
}

/**
 * One tailnet peer as reported by the bridge's allow-listed snapshot.
 *
 * Names and addresses are tailnet data supplied by other nodes: they may be
 * displayed verbatim but must never be logged.
 */
@Immutable
internal data class TsnetDevice(
    val id: String,
    val hostName: String,
    val dnsName: String,
    val os: String,
    val addresses: List<String>,
    val online: Boolean,
    val lastSeenEpochMillis: Long,
    val tailscaleSshEnabled: Boolean,
) {
    /** MagicDNS short name, falling back to the peer's own hostname or first address. */
    val displayName: String
        get() = dnsName.substringBefore('.').ifBlank { hostName }.ifBlank { addresses.firstOrNull().orEmpty() }

    /** Target used for a new profile: the MagicDNS name resolves through the embedded node. */
    val connectHost: String get() = dnsName.ifBlank { addresses.firstOrNull().orEmpty() }

    /** Whether a saved profile hostname names this device by MagicDNS name, short name, hostname, or address. */
    fun isNamedBy(profileHostname: String): Boolean {
        val candidate = profileHostname.trim().trimEnd('.').lowercase()
        if (candidate.isEmpty()) return false
        val names = buildList {
            if (dnsName.isNotBlank()) {
                add(dnsName.lowercase())
                add(dnsName.substringBefore('.').lowercase())
            }
            if (hostName.isNotBlank()) add(hostName.lowercase())
            addresses.forEach { add(it.lowercase()) }
        }
        return candidate in names
    }
}

@Immutable
internal data class TsnetNetworkSnapshot(
    val self: TsnetSelf,
    val devices: List<TsnetDevice>,
)

/** Fixed failure for a malformed or oversized bridge snapshot; carries no payload text. */
internal class TsnetSnapshotFormatException : Exception()

/** Parses the bridge's network snapshot JSON into bounded, display-ordered values. */
internal object TsnetNetworkSnapshotCodec {
    private const val MAX_DEVICES = 4_096
    private const val MAX_ADDRESSES = 16
    private const val MAX_FIELD_CHARS = 255

    fun decode(json: String): TsnetNetworkSnapshot = try {
        val root = JSONObject(json)
        val self = root.getJSONObject("self")
        val peers = root.getJSONArray("peers")
        if (peers.length() > MAX_DEVICES) throw TsnetSnapshotFormatException()
        TsnetNetworkSnapshot(
            self = TsnetSelf(
                hostName = self.boundedString("hostName"),
                dnsName = self.boundedString("dnsName"),
                addresses = self.addresses(),
            ),
            devices = (0 until peers.length())
                .map { index -> peers.getJSONObject(index).toDevice() }
                .sortedWith(DISPLAY_ORDER),
        )
    } catch (error: TsnetSnapshotFormatException) {
        throw error
    } catch (_: Exception) {
        throw TsnetSnapshotFormatException()
    }

    /** Online first, then Tailscale SSH targets, then by name. */
    private val DISPLAY_ORDER = compareByDescending<TsnetDevice> { it.online }
        .thenByDescending { it.tailscaleSshEnabled }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        .thenBy { it.id }

    private fun JSONObject.toDevice() = TsnetDevice(
        id = boundedString("id"),
        hostName = boundedString("hostName"),
        dnsName = boundedString("dnsName"),
        os = boundedString("os"),
        addresses = addresses(),
        online = optBoolean("online", false),
        lastSeenEpochMillis = optLong("lastSeenUnixMs", 0L).coerceAtLeast(0L),
        tailscaleSshEnabled = optBoolean("sshEnabled", false),
    )

    private fun JSONObject.boundedString(name: String): String {
        val value = optString(name, "")
        if (value.length > MAX_FIELD_CHARS) throw TsnetSnapshotFormatException()
        return value
    }

    private fun JSONObject.addresses(): List<String> {
        val values = optJSONArray("ips") ?: JSONArray()
        if (values.length() > MAX_ADDRESSES) throw TsnetSnapshotFormatException()
        return (0 until values.length()).map { index ->
            values.getString(index).also { if (it.length > MAX_FIELD_CHARS) throw TsnetSnapshotFormatException() }
        }
    }
}
