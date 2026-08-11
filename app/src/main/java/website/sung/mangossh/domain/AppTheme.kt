package website.sung.mangossh.domain

/** How the app's Material color scheme follows (or overrides) the device's light/dark setting. */
enum class AppThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        val DEFAULT: AppThemeMode = SYSTEM

        /** Resolves a persisted mode identifier while rejecting unknown or future values. */
        fun fromPreference(value: String?): AppThemeMode? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/**
 * User-selectable app-wide theme preferences.
 *
 * This is intentionally device-local UI state: it does not contain
 * credentials and is not part of an encrypted profile or portable vault
 * backup. [dynamicColorEnabled] defaults to `true` so upgrading installs see
 * no visual change, matching the dynamic-color-on-API-31+ behavior the app
 * had before this preference existed.
 */
data class AppThemePreferences(
    val mode: AppThemeMode = AppThemeMode.DEFAULT,
    val dynamicColorEnabled: Boolean = true,
)

/** Pure resolution of the effective dark flag, independent of Compose so it stays JVM-testable. */
fun resolveDarkTheme(mode: AppThemeMode, systemDark: Boolean): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> systemDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}
