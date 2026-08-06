package website.sung.mangossh.domain

import androidx.compose.runtime.Immutable
import java.util.UUID

/** Transport selected for a profile; Mosh begins with an SSH bootstrap then uses UDP. */
enum class ConnectionProtocol(val label: String) {
    SSH("SSH"),
    MOSH("Mosh"),
}

/** Network path used before protocol authentication. */
enum class ConnectionRoute {
    DIRECT,
    TAILNET,
    TSNET,
}

/** Credential exchange offered to the SSH server during connection setup. */
enum class AuthenticationMethod {
    PRIVATE_KEY,
    PASSWORD,
    KEYBOARD_INTERACTIVE,
    TAILSCALE_SSH,
}

/** Persisted non-secret connection settings; referenced keys live separately in the encrypted vault. */
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
    val position: Int = 0,
    val lastConnectedAtEpochMillis: Long = 0L,
) {
    val endpoint: String
        get() = if (port == 22) hostname else "$hostname:$port"
}

/** Editable profile form state, converted to a validated [ConnectionProfile] only when saved. */
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
    val position: Int = 0,
    val lastConnectedAtEpochMillis: Long = 0L,
) {
    /** Verifies only local form constraints; network reachability is checked at connection time. */
    fun isValid(): Boolean = hostname.isNotBlank() && username.isNotBlank() && port in 1..65535

    /**
     * Creates a persisted profile and keeps the legacy system Tailnet route
     * pinned to Tailscale SSH. Embedded TSNET profiles retain the explicitly
     * selected SSH authentication method.
     */
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
        position = position,
        lastConnectedAtEpochMillis = lastConnectedAtEpochMillis,
    )
}
