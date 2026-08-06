package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.HostSortMode

/**
 * Stores the non-secret, device-local host list sort preference.
 *
 * Excluded from the encrypted vault and portable backups: this describes how a device displays
 * hosts, not a connection's behavior or credentials.
 */
class HostListPreferencesStore(context: Context) {
    private val store = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _sortMode = MutableStateFlow(readSortMode())

    /** Observable sort mode shared by the host list and its top bar sort menu. */
    val sortMode: StateFlow<HostSortMode> = _sortMode.asStateFlow()

    fun setSortMode(mode: HostSortMode) {
        store.edit { putString(KEY_SORT_MODE, mode.name) }
        _sortMode.value = mode
    }

    /** Treats a missing or unrecognized stored value as the default manual order. */
    private fun readSortMode(): HostSortMode = runCatching {
        val stored = store.getString(KEY_SORT_MODE, null) ?: return HostSortMode.MANUAL
        HostSortMode.entries.firstOrNull { it.name == stored } ?: HostSortMode.MANUAL
    }.getOrDefault(HostSortMode.MANUAL)

    private companion object {
        const val PREFERENCES_NAME = "mangossh-host-list"
        const val KEY_SORT_MODE = "sort_mode"
    }
}
