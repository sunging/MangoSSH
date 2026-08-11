package website.sung.mangossh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import website.sung.mangossh.domain.AppThemePreferences
import website.sung.mangossh.domain.resolveDarkTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF006E2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8CF7A8),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF4E6352),
    tertiary = Color(0xFF386568),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF70DB90),
    onPrimary = Color(0xFF003916),
    primaryContainer = Color(0xFF005222),
    onPrimaryContainer = Color(0xFF8CF7A8),
    secondary = Color(0xFFB5CCB8),
    tertiary = Color(0xFFA0CED1),
)

/**
 * Resolves the effective [MaterialTheme] color scheme for the given [preferences].
 *
 * Exposed separately from [MangoSshTheme] so `MainActivity` can compute the
 * same dark/light decision for its edge-to-edge system-bar styling without
 * duplicating [resolveDarkTheme]'s branches.
 */
@Composable
fun resolveAppDarkTheme(preferences: AppThemePreferences): Boolean =
    resolveDarkTheme(preferences.mode, isSystemInDarkTheme())

@Composable
fun MangoSshTheme(
    preferences: AppThemePreferences = AppThemePreferences(),
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveAppDarkTheme(preferences)
    val context = LocalContext.current
    val dynamicColorAvailable = preferences.dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColorAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColorAvailable -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
