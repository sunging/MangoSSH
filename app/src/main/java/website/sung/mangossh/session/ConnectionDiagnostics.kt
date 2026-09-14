package website.sung.mangossh.session

import website.sung.mangossh.domain.ConnectionRoute

/** Allowlisted status only: never contains endpoint, profile, path, fingerprint, or terminal output. */
data class ConnectionDiagnostics(
    val route: ConnectionRoute,
    val phase: TerminalSessionPhase,
    val sshLastSentNanos: Long?,
    val sshLastReceivedNanos: Long?,
    val sshLastConfirmedNanos: Long?,
    val moshRunning: Boolean?,
    val companionConnected: Boolean?,
    val failure: SessionEndMessageKind? = null,
) {
    /** Exports elapsed timings and enum categories only; no application-owned user data can enter it. */
    fun export(nowNanos: Long = System.nanoTime()): String {
        fun age(value: Long?) = value?.let { ((nowNanos - it).coerceAtLeast(0) / 1_000_000).toString() } ?: "unknown"
        return listOf("format=MangoSSH-diagnostics-v1", "configuredRoute=${route.name}", "phase=${phase.name}",
            "sshSentAgeMillis=${age(sshLastSentNanos)}", "sshReceivedAgeMillis=${age(sshLastReceivedNanos)}",
            "sshConfirmedAgeMillis=${age(sshLastConfirmedNanos)}", "moshRunning=${moshRunning ?: "unknown"}",
            "companionConnected=${companionConnected ?: "unknown"}", "failure=${failure?.name ?: "unknown"}").joinToString("\n")
    }
}
