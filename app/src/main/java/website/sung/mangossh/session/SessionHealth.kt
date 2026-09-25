package website.sung.mangossh.session

import androidx.compose.runtime.Immutable

/**
 * State of the SSH connection kept beside a Mosh session for files, forwards and
 * server resources. The Mosh terminal runs over UDP and is unaffected by it.
 */
enum class CompanionHealth {
    CONNECTED,
    /** Dropped; the next feature that needs it reconnects. */
    LOST,
    RECONNECTING,
    RECONNECT_FAILED,
}

/** The device's default network as the platform reports it. */
enum class NetworkHealth {
    AVAILABLE,
    /** No default network at all. */
    LOST,
    /** A network exists but this app may not use it (Data Saver, background restriction, lockdown VPN). */
    BLOCKED,
}

@Immutable
data class NetworkStatus(
    val health: NetworkHealth = NetworkHealth.AVAILABLE,
    /** The platform verified the network reaches the internet. */
    val validated: Boolean = true,
    val metered: Boolean = false,
)

/** Per-session health that the session phase alone does not show. Contains categories only. */
@Immutable
data class SessionHealth(
    /** Null for SSH sessions, which have no companion. */
    val companion: CompanionHealth? = null,
)

/**
 * The single condition most worth telling the user about a running session, so a
 * disconnect can be explained instead of just happening.
 */
enum class SessionAttention {
    NONE,
    NETWORK_LOST,
    NETWORK_BLOCKED,
    NETWORK_UNVALIDATED,
    /** The Mosh terminal still runs; only SSH-based features are unavailable. */
    COMPANION_LOST,
    COMPANION_RECONNECTING,
    COMPANION_RECONNECT_FAILED,
}

/** Network problems explain everything else, so they win over companion state. */
fun sessionAttention(phase: TerminalSessionPhase, health: SessionHealth?, network: NetworkStatus): SessionAttention {
    if (phase != TerminalSessionPhase.OPEN) return SessionAttention.NONE
    return when {
        network.health == NetworkHealth.LOST -> SessionAttention.NETWORK_LOST
        network.health == NetworkHealth.BLOCKED -> SessionAttention.NETWORK_BLOCKED
        health?.companion == CompanionHealth.RECONNECT_FAILED -> SessionAttention.COMPANION_RECONNECT_FAILED
        health?.companion == CompanionHealth.RECONNECTING -> SessionAttention.COMPANION_RECONNECTING
        health?.companion == CompanionHealth.LOST -> SessionAttention.COMPANION_LOST
        !network.validated -> SessionAttention.NETWORK_UNVALIDATED
        else -> SessionAttention.NONE
    }
}
