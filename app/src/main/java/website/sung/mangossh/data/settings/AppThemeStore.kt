package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.AppThemeMode
import website.sung.mangossh.domain.AppThemePreferences

/**
 * Stores non-secret, device-local app theme preferences.
 *
 * Excluded from the encrypted vault and portable backups: this describes the
 * local display, not a connection's behavior or credentials.
 */
class AppThemeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _preferences = MutableStateFlow(readPreferences())

    /** Observable current preferences for the app's root theme composable. */
    val preferences: StateFlow<AppThemePreferences> = _preferences.asStateFlow()

    /** Returns a snapshot that the first composition can apply before recomposition occurs. */
    fun current(): AppThemePreferences = _preferences.value

    fun setMode(mode: AppThemeMode) = update { it.copy(mode = mode) }

    fun setDynamicColorEnabled(enabled: Boolean) = update { it.copy(dynamicColorEnabled = enabled) }

    private fun update(transform: (AppThemePreferences) -> AppThemePreferences) {
        val next = transform(_preferences.value)
        persist(next)
        _preferences.value = next
    }

    private fun readPreferences(): AppThemePreferences {
        val mode = AppThemeMode.fromPreference(readString(KEY_MODE)) ?: AppThemeMode.DEFAULT
        val dynamicColorEnabled = readBoolean(KEY_DYNAMIC_COLOR_ENABLED, true)
        return AppThemePreferences(mode = mode, dynamicColorEnabled = dynamicColorEnabled)
    }

    /** Treats type-mismatched or otherwise damaged preference values as absent. */
    private fun readString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readBoolean(key: String, defaultValue: Boolean): Boolean = runCatching {
        preferences.getBoolean(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun persist(value: AppThemePreferences) {
        preferences.edit {
            putString(KEY_MODE, value.mode.preferenceValue)
            putBoolean(KEY_DYNAMIC_COLOR_ENABLED, value.dynamicColorEnabled)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-app-theme"
        const val KEY_MODE = "mode"
        const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
    }
}
