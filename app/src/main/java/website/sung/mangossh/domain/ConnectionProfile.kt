package website.sung.mangossh.domain

import androidx.compose.runtime.Immutable
import java.util.UUID

enum class ConnectionProtocol(val label: String) {
    SSH("SSH"),
    MOSH("Mosh"),
}

enum class ConnectionRoute(val label: String) {
    DIRECT("直接连接"),
    TAILNET("Tailnet"),
}

enum class AuthenticationMethod(val label: String) {
    PRIVATE_KEY("私钥"),
    PASSWORD("密码"),
    KEYBOARD_INTERACTIVE("交互式 / OTP"),
    TAILSCALE_SSH("Tailscale SSH"),
}

@Immutable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val route: ConnectionRoute = ConnectionRoute.DIRECT,
    val authentication: AuthenticationMethod = AuthenticationMethod.PRIVATE_KEY,
    val keyId: String? = null,
    val startupSnippetId: String? = null,
    val agentForwarding: Boolean = false,
    val favorite: Boolean = false,
) {
    val endpoint: String
        get() = if (port == 22) hostname else "$hostname:$port"
}

@Immutable
data class ConnectionProfileDraft(
    val id: String? = null,
    val label: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val protocol: ConnectionProtocol,
    val route: ConnectionRoute,
    val authentication: AuthenticationMethod = AuthenticationMethod.PRIVATE_KEY,
    val keyId: String? = null,
    val startupSnippetId: String? = null,
    val agentForwarding: Boolean = false,
    val favorite: Boolean = false,
) {
    fun isValid(): Boolean = hostname.isNotBlank() && username.isNotBlank() && port in 1..65535

    fun toProfile(): ConnectionProfile = ConnectionProfile(
        id = id ?: UUID.randomUUID().toString(),
        label = label.ifBlank { hostname.trim() },
        hostname = hostname.trim(),
        port = port,
        username = username.trim(),
        protocol = protocol,
        route = route,
        authentication = if (route == ConnectionRoute.TAILNET) {
            AuthenticationMethod.TAILSCALE_SSH
        } else {
            authentication
        },
        keyId = keyId,
        startupSnippetId = startupSnippetId,
        agentForwarding = agentForwarding,
        favorite = favorite,
    )
}
