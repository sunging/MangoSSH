package website.sung.mangossh.domain

/**
 * Terminal type MangoSSH requests over SSH's `pty-req` channel.
 *
 * Mosh is deliberately excluded: its bundled terminfo asset ships only the
 * `xterm-256color` entry, so Mosh sessions always request that value
 * regardless of this preference.
 */
enum class SshTerminalType(val preferenceValue: String, val termValue: String) {
    XTERM_256COLOR("xterm-256color", "xterm-256color"),
    XTERM("xterm", "xterm"),
    SCREEN_256COLOR("screen-256color", "screen-256color"),
    VT100("vt100", "vt100"),
    ;

    companion object {
        val DEFAULT: SshTerminalType = XTERM_256COLOR

        fun fromPreference(value: String?): SshTerminalType? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/**
 * User-selectable global defaults for new SSH/Mosh connections.
 *
 * This is intentionally device-local UI state, not part of an encrypted
 * profile: a future per-profile override belongs on [ConnectionProfile] in
 * the encrypted vault. These fields only ever change the *transport*
 * defaults new connections use going forward — never a saved profile's
 * hostname, credentials, or other identity.
 */
data class ConnectionPreferences(
    /** Seconds between SSH transport keepalives. `0` disables them entirely. */
    val keepaliveSeconds: Int = DEFAULT_KEEPALIVE_SECONDS,
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    /** SSH only; see [SshTerminalType]. */
    val sshTerminalType: SshTerminalType = SshTerminalType.DEFAULT,
) {
    /**
     * Produces a safe persisted value when preferences are damaged or from a
     * newer app version. Out-of-range values return to their bundled
     * default; `0` (disabled) is a valid keepalive value and is preserved.
     */
    fun normalized(): ConnectionPreferences = copy(
        keepaliveSeconds = keepaliveSeconds.takeIf { it == 0 || it in MIN_KEEPALIVE_SECONDS..MAX_KEEPALIVE_SECONDS }
            ?: DEFAULT_KEEPALIVE_SECONDS,
        connectTimeoutSeconds = connectTimeoutSeconds.takeIf { it in MIN_CONNECT_TIMEOUT_SECONDS..MAX_CONNECT_TIMEOUT_SECONDS }
            ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
    )

    companion object {
        const val MIN_KEEPALIVE_SECONDS = 10
        const val MAX_KEEPALIVE_SECONDS = 300
        const val DEFAULT_KEEPALIVE_SECONDS = 30

        /** Discrete choices offered by the UI; `0` means "off". The store still accepts any in-range value. */
        val KEEPALIVE_CHOICES = listOf(0, 15, 30, 60, 120, 300)

        const val MIN_CONNECT_TIMEOUT_SECONDS = 5
        const val MAX_CONNECT_TIMEOUT_SECONDS = 120
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10

        /** Discrete choices offered by the UI; the store still accepts any in-range value. */
        val CONNECT_TIMEOUT_CHOICES = listOf(5, 10, 20, 30, 60, 120)
    }
}
