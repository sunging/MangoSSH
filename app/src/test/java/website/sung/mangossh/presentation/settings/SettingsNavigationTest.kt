package website.sung.mangossh.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun licenseBackReturnsToAboutBeforeHub() {
        val destination = SettingsDestination.LICENSES
        assertEquals(SettingsDestination.ABOUT, destination.parent)
        assertNull(destination.parent?.parent)
        assertTrue(destination.showsBack(twoPane = false))
        assertTrue(SettingsDestination.ABOUT.showsBack(twoPane = false))
    }

    @Test
    fun tabletChildKeepsAboutSelectedAndOffersBack() {
        assertEquals(SettingsDestination.ABOUT, SettingsDestination.LICENSES.hubDestination)
        assertTrue(SettingsDestination.LICENSES.showsBack(twoPane = true))
        assertFalse(SettingsDestination.ABOUT.showsBack(twoPane = true))
        assertFalse(SettingsDestination.entries.filter { it.parent == null }.contains(SettingsDestination.LICENSES))
    }

    @Test
    fun existingCategoriesStillReturnDirectlyToHub() {
        SettingsDestination.entries.filter { it != SettingsDestination.LICENSES }.forEach {
            assertNull(it.parent)
            assertEquals(it, it.hubDestination)
            assertFalse(it.showsBack(twoPane = true))
        }
    }
}
