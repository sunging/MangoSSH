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

@Composable
fun MangoSshTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
