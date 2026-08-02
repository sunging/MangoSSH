package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.TerminalShortcutConfig

/** Stores the global, non-secret floating shortcut layout on this device only. */
class TerminalShortcutStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _config = MutableStateFlow(readConfig())

    /** Observable shortcut configuration used by settings and all live terminal screens. */
    val config: StateFlow<TerminalShortcutConfig> = _config.asStateFlow()

    /** Atomically validates and persists a complete ordered shortcut configuration. */
    fun save(config: TerminalShortcutConfig) {
        require(config.isValid()) { "Terminal shortcut configuration is invalid." }
        preferences.edit { putString(KEY_CONFIG, TerminalShortcutConfigCodec.encode(config)) }
        _config.value = config
    }

    /** Removes saved customization and restores the bundled default toolbar. */
    fun reset() {
        preferences.edit { remove(KEY_CONFIG) }
        _config.value = TerminalShortcutConfig.defaults()
    }

    private fun readConfig(): TerminalShortcutConfig {
        val encoded = runCatching { preferences.getString(KEY_CONFIG, null) }.getOrNull()
            ?: return TerminalShortcutConfig.defaults()
        return TerminalShortcutConfigCodec.decode(encoded) ?: TerminalShortcutConfig.defaults()
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-terminal-shortcuts"
        const val KEY_CONFIG = "config"
    }
}
