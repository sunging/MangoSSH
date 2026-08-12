package website.sung.mangossh.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalDelKeyMode
import website.sung.mangossh.domain.TerminalRightAltMode

/**
 * Stores non-secret, device-local terminal emulator and input behavior.
 *
 * Excluded from the encrypted vault and portable backups: this describes the
 * local display and input handling, not a connection's behavior or
 * credentials.
 */
class TerminalBehaviorStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _behavior = MutableStateFlow(readBehavior())

    /** Observable current behavior for session creation and live terminal screens. */
    val behavior: StateFlow<TerminalBehavior> = _behavior.asStateFlow()

    /** Returns a normalized snapshot that session creation can apply before remote output arrives. */
    fun current(): TerminalBehavior = _behavior.value

    fun setScrollbackLines(lines: Int) = update { it.copy(scrollbackLines = lines) }

    fun setBoldAsBright(enabled: Boolean) = update { it.copy(boldAsBright = enabled) }

    fun setAutoDetectUrls(enabled: Boolean) = update { it.copy(autoDetectUrls = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOn = enabled) }

    fun setRightAltMode(mode: TerminalRightAltMode) = update { it.copy(rightAltMode = mode) }

    fun setDelKeyMode(mode: TerminalDelKeyMode) = update { it.copy(delKeyMode = mode) }

    fun setMaxPinchZoomScale(scale: Float) = update { it.copy(maxPinchZoomScale = scale) }

    private fun update(transform: (TerminalBehavior) -> TerminalBehavior) {
        val next = transform(_behavior.value).normalized()
        persist(next)
        _behavior.value = next
    }

    private fun readBehavior(): TerminalBehavior {
        val scrollbackLines = readInt(KEY_SCROLLBACK_LINES, TerminalBehavior.DEFAULT_SCROLLBACK_LINES)
        val boldAsBright = readBoolean(KEY_BOLD_AS_BRIGHT, true)
        val autoDetectUrls = readBoolean(KEY_AUTO_DETECT_URLS, true)
        val keepScreenOn = readBoolean(KEY_KEEP_SCREEN_ON, false)
        val rightAltMode = TerminalRightAltMode.fromPreference(readString(KEY_RIGHT_ALT_MODE))
            ?: TerminalRightAltMode.DEFAULT
        val delKeyMode = TerminalDelKeyMode.fromPreference(readString(KEY_DEL_KEY_MODE))
            ?: TerminalDelKeyMode.DEFAULT
        val maxPinchZoomScale = readFloat(KEY_MAX_PINCH_ZOOM_SCALE, TerminalBehavior.DEFAULT_MAX_PINCH_ZOOM_SCALE)
        return TerminalBehavior(
            scrollbackLines = scrollbackLines,
            boldAsBright = boldAsBright,
            autoDetectUrls = autoDetectUrls,
            keepScreenOn = keepScreenOn,
            rightAltMode = rightAltMode,
            delKeyMode = delKeyMode,
            maxPinchZoomScale = maxPinchZoomScale,
        ).normalized()
    }

    /** Treats type-mismatched or otherwise damaged preference values as absent. */
    private fun readString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readInt(key: String, defaultValue: Int): Int = runCatching {
        preferences.getInt(key, defaultValue)
    }.getOrDefault(defaultValue)

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readBoolean(key: String, defaultValue: Boolean): Boolean = runCatching {
        preferences.getBoolean(key, defaultValue)
    }.getOrDefault(defaultValue)

    /** Treats type-mismatched or otherwise damaged preference values as the supplied default. */
    private fun readFloat(key: String, defaultValue: Float): Float = runCatching {
        preferences.getFloat(key, defaultValue)
    }.getOrDefault(defaultValue)

    private fun persist(behavior: TerminalBehavior) {
        preferences.edit {
            putInt(KEY_SCROLLBACK_LINES, behavior.scrollbackLines)
            putBoolean(KEY_BOLD_AS_BRIGHT, behavior.boldAsBright)
            putBoolean(KEY_AUTO_DETECT_URLS, behavior.autoDetectUrls)
            putBoolean(KEY_KEEP_SCREEN_ON, behavior.keepScreenOn)
            putString(KEY_RIGHT_ALT_MODE, behavior.rightAltMode.preferenceValue)
            putString(KEY_DEL_KEY_MODE, behavior.delKeyMode.preferenceValue)
            putFloat(KEY_MAX_PINCH_ZOOM_SCALE, behavior.maxPinchZoomScale)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mangossh-terminal-behavior"
        const val KEY_SCROLLBACK_LINES = "scrollback_lines"
        const val KEY_BOLD_AS_BRIGHT = "bold_as_bright"
        const val KEY_AUTO_DETECT_URLS = "auto_detect_urls"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_RIGHT_ALT_MODE = "right_alt_mode"
        const val KEY_DEL_KEY_MODE = "del_key_mode"
        const val KEY_MAX_PINCH_ZOOM_SCALE = "max_pinch_zoom_scale"
    }
}
