package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun defaultsFollowSystemWithDynamicColorEnabled() {
        val preferences = AppThemePreferences()

        assertEquals(AppThemeMode.SYSTEM, preferences.mode)
        assertTrue(preferences.dynamicColorEnabled)
    }

    @Test
    fun preferenceIdsResolveOnlyKnownModes() {
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromPreference("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromPreference("dark"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromPreference("system"))
        assertNull(AppThemeMode.fromPreference("auto"))
        assertNull(AppThemeMode.fromPreference(null))
    }

    @Test
    fun resolveDarkThemeFollowsSystemOnlyInSystemMode() {
        assertTrue(resolveDarkTheme(AppThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(AppThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun resolveDarkThemeIgnoresSystemInForcedModes() {
        assertFalse(resolveDarkTheme(AppThemeMode.LIGHT, systemDark = true))
        assertFalse(resolveDarkTheme(AppThemeMode.LIGHT, systemDark = false))
        assertTrue(resolveDarkTheme(AppThemeMode.DARK, systemDark = true))
        assertTrue(resolveDarkTheme(AppThemeMode.DARK, systemDark = false))
    }
}
