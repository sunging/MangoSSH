package website.sung.mangossh.session

import java.util.Base64

/**
 * Validated data emitted by `mosh-server new` over the short-lived bootstrap
 * SSH channel. The key is intentionally redacted by [toString] because it is
 * a bearer secret for the encrypted UDP session.
 */
internal data class MoshBootstrap(
    val port: Int,
    val key: CharArray,
) {
    override fun toString(): String = "MoshBootstrap(port=$port, key=<redacted>)"
}

/**
 * Parses the small, machine-readable protocol emitted by `mosh-server new`.
 *
 * A server can print banners before the `MOSH CONNECT` line, so callers feed
 * lines one at a time. No raw server output is included in failures or logs:
 * the output can contain a Mosh session key.
 */
internal object MoshBootstrapParser {
    private val connectLine = Regex("^MOSH CONNECT ([1-9][0-9]{0,4}) ([A-Za-z0-9+/=]+)$")

    /** Returns a bootstrap record only for an in-range UDP port and a Base64-shaped key. */
    fun parse(line: String): MoshBootstrap? {
        val match = connectLine.matchEntire(line.trim()) ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val key = match.groupValues[2]
        val keyBytes = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return null
        val validKeyLength = keyBytes.size == MOSH_KEY_BYTES
        keyBytes.fill(0)
        if (!validKeyLength) return null
        return MoshBootstrap(port = port, key = key.toCharArray())
    }

    private const val MOSH_KEY_BYTES = 16
}
