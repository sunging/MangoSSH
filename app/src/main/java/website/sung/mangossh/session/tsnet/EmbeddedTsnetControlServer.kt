package website.sung.mangossh.session.tsnet

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Coordination-server selection for the embedded node.
 *
 * An empty value means Tailscale's own control plane. Anything else is a
 * self-hosted server such as Headscale. Only https is accepted, so neither
 * the node's registration nor the browser sign-in can travel in cleartext.
 * The value is bound to the enrolled identity: it changes only while no
 * identity exists, because tsnet would otherwise move a registered node to a
 * different server.
 */
internal object EmbeddedTsnetControlServer {
    /** Host of the browser sign-in pages for Tailscale's own control plane. */
    private const val DEFAULT_LOGIN_HOST = "login.tailscale.com"

    /** Names tsnet treats as its default server; they normalize to "". */
    private val DEFAULT_SERVER_HOSTS = setOf("controlplane.tailscale.com", DEFAULT_LOGIN_HOST)

    private const val HTTPS_PORT = 443
    const val MAX_LENGTH = 2048

    /**
     * Returns the canonical form of [input]: "" for Tailscale's default, or an
     * https URL with a lowercase host, no default port and no trailing slash.
     * Returns null when [input] is not an acceptable server URL: not https,
     * no host, or carrying credentials, a query or a fragment.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > MAX_LENGTH) return null
        val uri = parse(trimmed) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val port = uri.port.takeUnless { it == HTTPS_PORT } ?: -1
        if (path.isEmpty() && port == -1 && host in DEFAULT_SERVER_HOSTS) return ""
        val portSuffix = if (port == -1) "" else ":$port"
        return "https://$host$portSuffix$path"
    }

    /**
     * True when [url] is a browser sign-in page served by [controlUrl]'s host,
     * or by Tailscale's login host when [controlUrl] is the default. Anything
     * else must not be opened: the URL comes from the network.
     */
    fun isAllowedAuthorizationUrl(url: String, controlUrl: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (controlUrl.isEmpty()) return host == DEFAULT_LOGIN_HOST && effectivePort(uri) == HTTPS_PORT
        val server = parse(controlUrl) ?: return false
        return host == server.host?.lowercase(Locale.ROOT) && effectivePort(uri) == effectivePort(server)
    }

    private fun effectivePort(uri: URI): Int = uri.port.takeUnless { it == -1 } ?: HTTPS_PORT

    private fun parse(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }
}
