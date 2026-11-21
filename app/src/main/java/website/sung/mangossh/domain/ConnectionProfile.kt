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
    AUTOMATIC,
    TAILSCALE_SSH,
}

/** Persisted connection settings; reusable credentials live separately in the encrypted vault. */
@Immutable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val route: ConnectionRoute = ConnectionRoute.DIRECT,
    val authentication: AuthenticationMethod = AuthenticationMethod.AUTOMATIC,
    val authenticationId: String? = null,
    val startupSnippetId: String? = null,
    val agentForwarding: Boolean = false,
    val overrides: HostConnectionOverrides = HostConnectionOverrides(),
    val agentPolicy: HostAgentPolicy = HostAgentPolicy(),
    val requireReauthentication: Boolean? = null,
    val workspace: TmuxWorkspace = TmuxWorkspace(),
    val jumpProfileIds: List<String> = emptyList(),
    val favorite: Boolean = false,
    val position: Int = 0,
    val lastConnectedAtEpochMillis: Long = 0L,
    val connectionCount: Int = 0,
    val legacySshAlgorithms: Boolean = false,
    val moshServerCommand: String? = null,
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
    val authentication: AuthenticationMethod = AuthenticationMethod.AUTOMATIC,
    val authenticationId: String? = null,
    val startupSnippetId: String? = null,
    val agentForwarding: Boolean = false,
    val overrides: HostConnectionOverrides = HostConnectionOverrides(),
    val agentPolicy: HostAgentPolicy = HostAgentPolicy(),
    val requireReauthentication: Boolean? = null,
    val workspace: TmuxWorkspace = TmuxWorkspace(),
    val jumpProfileIds: List<String> = emptyList(),
    val favorite: Boolean = false,
    val position: Int = 0,
    val lastConnectedAtEpochMillis: Long = 0L,
    val connectionCount: Int = 0,
    val legacySshAlgorithms: Boolean = false,
    val moshServerCommand: String? = null,
) {
    /** Verifies only local form constraints; network reachability is checked at connection time. */
    fun isValid(): Boolean = hostname.isNotBlank() && username.isNotBlank() && port in 1..65535 &&
        (protocol != ConnectionProtocol.MOSH || isValidMoshServerCommand(moshServerCommand))

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
        authenticationId = authenticationId,
        startupSnippetId = startupSnippetId,
        agentForwarding = agentForwarding,
        overrides = overrides,
        agentPolicy = agentPolicy,
        requireReauthentication = requireReauthentication,
        workspace = workspace,
        jumpProfileIds = jumpProfileIds,
        favorite = favorite,
        position = position,
        lastConnectedAtEpochMillis = lastConnectedAtEpochMillis,
        connectionCount = connectionCount,
        legacySshAlgorithms = legacySshAlgorithms,
        moshServerCommand = moshServerCommand?.takeIf { protocol == ConnectionProtocol.MOSH && it.isNotBlank() },
    )
}

internal const val MOSH_SERVER_COMMAND_PREFIX = "mosh-server new"
// Non-interactive SSH sessions can inherit an ASCII locale that mosh-server rejects.
internal const val DEFAULT_MOSH_SERVER_ARGUMENTS = "-s -c 256 -l LANG=C.UTF-8"
internal const val DEFAULT_MOSH_SERVER_COMMAND = "$MOSH_SERVER_COMMAND_PREFIX $DEFAULT_MOSH_SERVER_ARGUMENTS"

/** A Mosh override is one remote invocation; an absent or blank value selects the built-in command. */
internal fun isValidMoshServerCommand(command: String?): Boolean =
    command?.none { it == '\n' || it == '\r' || it == '\u0000' } != false
