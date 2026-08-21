package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.ConnectionPreferences
import website.sung.mangossh.domain.SshTerminalType

/**
 * Stores non-secret, device-local defaults for new SSH/Mosh connections.
 *
 * Excluded from the encrypted vault and portable backups: these are global
 * transport defaults, not a saved profile's identity or credentials.
 */
class ConnectionPreferencesStore(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _preferences = MutableStateFlow(readPreferences())

    /** Observable current preferences for the connection settings page. */
    val preferences: StateFlow<ConnectionPreferences> = _preferences.asStateFlow()

    /** Returns a normalized snapshot that a new connection can apply immediately. */
    fun current(): ConnectionPreferences = _preferences.value

    fun setKeepaliveSeconds(seconds: Int) = update { it.copy(keepaliveSeconds = seconds) }

    fun setConnectTimeoutSeconds(seconds: Int) = update { it.copy(connectTimeoutSeconds = seconds) }

    fun setSshTerminalType(type: SshTerminalType) = update { it.copy(sshTerminalType = type) }

    private fun update(transform: (ConnectionPreferences) -> ConnectionPreferences) {
        val next = transform(_preferences.value).normalized()
        persist(next)
        _preferences.value = next
    }

    private fun readPreferences(): ConnectionPreferences {
        val keepaliveSeconds = readInt(KEY_KEEPALIVE_SECONDS, ConnectionPreferences.DEFAULT_KEEPALIVE_SECONDS)
        val connectTimeoutSeconds = readInt(KEY_CONNECT_TIMEOUT_SECONDS, ConnectionPreferences.DEFAULT_CONNECT_TIMEOUT_SECONDS)
        val sshTerminalType = SshTerminalType.fromPreference(readString(KEY_SSH_TERMINAL_TYPE)) ?: SshTerminalType.DEFAULT
        return ConnectionPreferences(
            keepaliveSeconds = keepaliveSeconds,
            connectTimeoutSeconds = connectTimeoutSeconds,
            sshTerminalType = sshTerminalType,
        ).normalized()
    }

    /** Treats type-mismatched or otherwise damaged preference values as absent. */
    private fun readString(key: String): String? = runCatching {
        sharedPreferences.getString(key, null)
    }.getOrNull()

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readInt(key: String, defaultValue: Int): Int = runCatching {
        sharedPreferences.getInt(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun persist(value: ConnectionPreferences) {
        sharedPreferences.edit {
            putInt(KEY_KEEPALIVE_SECONDS, value.keepaliveSeconds)
            putInt(KEY_CONNECT_TIMEOUT_SECONDS, value.connectTimeoutSeconds)
            putString(KEY_SSH_TERMINAL_TYPE, value.sshTerminalType.preferenceValue)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-connection"
        const val KEY_KEEPALIVE_SECONDS = "keepalive_seconds"
        const val KEY_CONNECT_TIMEOUT_SECONDS = "connect_timeout_seconds"
        const val KEY_SSH_TERMINAL_TYPE = "ssh_terminal_type"
    }
}
