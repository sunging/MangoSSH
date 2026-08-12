package website.sung.mangossh.domain

/** How the right-alt (AltGr) key behaves in the terminal keyboard handler. */
enum class TerminalRightAltMode(val preferenceValue: String) {
    CHARACTER_MODIFIER("character_modifier"),
    META("meta"),
    ;

    companion object {
        val DEFAULT: TerminalRightAltMode = CHARACTER_MODIFIER

        fun fromPreference(value: String?): TerminalRightAltMode? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/** How the backspace/delete keys map to terminal characters. */
enum class TerminalDelKeyMode(val preferenceValue: String) {
    DELETE("delete"),
    BACKSPACE("backspace"),
    ;

    companion object {
        val DEFAULT: TerminalDelKeyMode = DELETE

        fun fromPreference(value: String?): TerminalDelKeyMode? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/**
 * User-selectable terminal emulator and input behavior.
 *
 * This is intentionally device-local UI state: it does not contain
 * credentials and is not part of an encrypted profile or portable vault
 * backup. Every default reproduces the app's prior hardcoded behavior, so
 * upgrading installs see no change: [scrollbackLines] matches termlib's old
 * fixed 1000-line buffer, [boldAsBright] and [autoDetectUrls] match the
 * factory defaults MangoSSH already relied on, and [maxPinchZoomScale]
 * matches termlib's old fixed 3x pinch-zoom ceiling.
 */
data class TerminalBehavior(
    val scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    val boldAsBright: Boolean = true,
    val autoDetectUrls: Boolean = true,
    val keepScreenOn: Boolean = false,
    val rightAltMode: TerminalRightAltMode = TerminalRightAltMode.DEFAULT,
    val delKeyMode: TerminalDelKeyMode = TerminalDelKeyMode.DEFAULT,
    val maxPinchZoomScale: Float = DEFAULT_MAX_PINCH_ZOOM_SCALE,
) {
    /**
     * Produces a safe persisted value when preferences are damaged or from a
     * newer app version. Out-of-range values return to their bundled default.
     */
    fun normalized(): TerminalBehavior = copy(
        scrollbackLines = scrollbackLines.takeIf { it in MIN_SCROLLBACK_LINES..MAX_SCROLLBACK_LINES }
            ?: DEFAULT_SCROLLBACK_LINES,
        maxPinchZoomScale = maxPinchZoomScale.takeIf { it in MIN_MAX_PINCH_ZOOM_SCALE..MAX_MAX_PINCH_ZOOM_SCALE }
            ?: DEFAULT_MAX_PINCH_ZOOM_SCALE,
    )

    companion object {
        const val MIN_SCROLLBACK_LINES = 200
        const val MAX_SCROLLBACK_LINES = 10_000
        const val DEFAULT_SCROLLBACK_LINES = 1_000

        /** Discrete choices offered by the UI; the store still accepts any in-range value. */
        val SCROLLBACK_CHOICES = listOf(200, 500, 1_000, 2_500, 5_000, 10_000)

        const val MIN_MAX_PINCH_ZOOM_SCALE = 1.5f
        const val MAX_MAX_PINCH_ZOOM_SCALE = 5f
        const val DEFAULT_MAX_PINCH_ZOOM_SCALE = 3f

        /** Discrete choices offered by the UI; the store still accepts any in-range value. */
        val PINCH_ZOOM_CHOICES = listOf(1.5f, 2f, 3f, 4f, 5f)
    }
}
