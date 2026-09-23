package website.sung.mangossh.domain

/** Nullable overrides inherit app defaults; an effective snapshot is fixed when connecting. */
data class HostConnectionOverrides(
    val connectTimeoutSeconds: Int? = null,
    val keepaliveSeconds: Int? = null,
    val backgroundMultiplier: Int? = null,
    val terminalType: SshTerminalType? = null,
) : java.io.Serializable {
    fun resolve(defaults: ConnectionPreferences): ConnectionPreferences = defaults.copy(
        connectTimeoutSeconds = connectTimeoutSeconds ?: defaults.connectTimeoutSeconds,
        keepaliveSeconds = keepaliveSeconds ?: defaults.keepaliveSeconds,
        backgroundKeepaliveMultiplier = backgroundMultiplier ?: defaults.backgroundKeepaliveMultiplier,
        sshTerminalType = terminalType ?: defaults.sshTerminalType,
    )

    fun isValid(): Boolean = (connectTimeoutSeconds == null || connectTimeoutSeconds in 5..120) &&
        (keepaliveSeconds == null || keepaliveSeconds == 0 || keepaliveSeconds in 10..300) &&
        (backgroundMultiplier == null || backgroundMultiplier in 1..16)
}

/** Null allows only the selected login key; an explicit empty list authorizes no identities. */
data class HostAgentPolicy(
    val allowedKeyIds: List<String>? = null,
    val confirmEachSignature: Boolean = false,
    /** Zero lasts this connection; a positive value requires renewal after this many seconds. */
    val authorizationSeconds: Int = 0,
) : java.io.Serializable

/** Startup modes are mutually exclusive with a saved startup snippet. */
enum class WorkspaceMode { DISABLED, CREATE, ATTACH, CREATE_OR_ATTACH }

/** tmux identifiers are declarative and are never interpolated without validation and quoting. */
data class TmuxWorkspace(
    val mode: WorkspaceMode = WorkspaceMode.DISABLED,
    val name: String = "",
    val sessionId: String = "",
) : java.io.Serializable {
    fun isValid(): Boolean = when (mode) {
        WorkspaceMode.DISABLED -> true
        WorkspaceMode.CREATE, WorkspaceMode.CREATE_OR_ATTACH -> name.matches(Regex("[A-Za-z0-9_-]{1,80}"))
        WorkspaceMode.ATTACH -> sessionId.matches(Regex("\\$[0-9]{1,10}"))
    }
}
