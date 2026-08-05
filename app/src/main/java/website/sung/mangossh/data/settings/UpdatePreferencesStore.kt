package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.UpdatePreferences

/**
 * Stores non-secret, device-local self-update preferences.
 *
 * Excluded from the encrypted vault and portable backups: this describes
 * local update-check bookkeeping, not a connection's behavior or credentials.
 */
class UpdatePreferencesStore(context: Context) {
    private val store = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _preferences = MutableStateFlow(read())

    /** Observable update preferences shared by the settings card and the update flow. */
    val preferences: StateFlow<UpdatePreferences> = _preferences.asStateFlow()

    fun current(): UpdatePreferences = _preferences.value

    fun setAutomaticCheckEnabled(enabled: Boolean) = update { it.copy(automaticCheckEnabled = enabled) }

    /** Recorded even when a check fails, so a persistently failing check cannot repeat within a day. */
    fun recordCheck(nowEpochMillis: Long) = update { it.copy(lastCheckedAtEpochMillis = nowEpochMillis) }

    fun dismissVersion(versionCode: Long) =
        update { it.copy(dismissedVersionCode = maxOf(versionCode, it.dismissedVersionCode)) }

    /** Clears the dismissal so an explicit manual check always reports its result. */
    fun clearDismissal() = update { it.copy(dismissedVersionCode = 0L) }

    private fun update(transform: (UpdatePreferences) -> UpdatePreferences) {
        val next = transform(_preferences.value)
        persist(next)
        _preferences.value = next
    }

    private fun read(): UpdatePreferences = UpdatePreferences(
        automaticCheckEnabled = readBoolean(KEY_AUTOMATIC_CHECK_ENABLED, true),
        lastCheckedAtEpochMillis = readLong(KEY_LAST_CHECKED_AT, 0L),
        dismissedVersionCode = readLong(KEY_DISMISSED_VERSION_CODE, 0L),
    )

    /** Treats a type-mismatched or otherwise damaged preference value as the supplied default. */
    private fun readBoolean(key: String, defaultValue: Boolean): Boolean = runCatching {
        store.getBoolean(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun readLong(key: String, defaultValue: Long): Long = runCatching {
        store.getLong(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun persist(preferences: UpdatePreferences) {
        store.edit {
            putBoolean(KEY_AUTOMATIC_CHECK_ENABLED, preferences.automaticCheckEnabled)
            putLong(KEY_LAST_CHECKED_AT, preferences.lastCheckedAtEpochMillis)
            putLong(KEY_DISMISSED_VERSION_CODE, preferences.dismissedVersionCode)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-app-updates"
        const val KEY_AUTOMATIC_CHECK_ENABLED = "automatic_check_enabled"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
    }
}
