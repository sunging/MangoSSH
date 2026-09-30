package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.choicesIncludingCurrent

/**
 * Stores the device-local memory limit for streaming remote files to other apps.
 *
 * Excluded from the encrypted vault and portable backups: how much memory a
 * phone can spare on streaming says nothing about a connection, and the right
 * value differs from one device to the next.
 */
class StreamingPreferencesStore(context: Context) {
    private val store = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _cacheLimitMebibytes = MutableStateFlow(readCacheLimit())

    /** Configured streaming cache limit in MiB, before any device ceiling applies. */
    val cacheLimitMebibytes: StateFlow<Int> = _cacheLimitMebibytes.asStateFlow()

    fun setCacheLimitMebibytes(value: Int) {
        val normalized = normalizeCacheLimit(value)
        store.edit { putInt(KEY_CACHE_LIMIT_MIB, normalized) }
        _cacheLimitMebibytes.value = normalized
    }

    private fun readCacheLimit(): Int = runCatching {
        normalizeCacheLimit(store.getInt(KEY_CACHE_LIMIT_MIB, DEFAULT_CACHE_LIMIT_MIB))
    }.getOrDefault(DEFAULT_CACHE_LIMIT_MIB)

    companion object {
        private const val PREFERENCES_NAME = "mangossh-streaming"
        private const val KEY_CACHE_LIMIT_MIB = "cache_limit_mib"

        const val DEFAULT_CACHE_LIMIT_MIB = 64
        const val MIN_CACHE_LIMIT_MIB = 16
        const val MAX_CACHE_LIMIT_MIB = 1024

        /** Discrete values offered by the settings picker. */
        val CACHE_LIMIT_CHOICES_MIB = listOf(16, 32, 64, 128, 256, 512, 1024)

        /** Picker options, keeping an in-range stored value that is not on the list. */
        fun cacheLimitChoices(current: Int): List<Int> =
            choicesIncludingCurrent(CACHE_LIMIT_CHOICES_MIB, current) { it in MIN_CACHE_LIMIT_MIB..MAX_CACHE_LIMIT_MIB }

        /** Out-of-range values, e.g. from a newer build, fall back to the default. */
        fun normalizeCacheLimit(value: Int): Int =
            if (value in MIN_CACHE_LIMIT_MIB..MAX_CACHE_LIMIT_MIB) value else DEFAULT_CACHE_LIMIT_MIB
    }
}
