package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HostConnectionOverridesTest {
    @Test fun aHostWithoutAnOverrideFollowsTheCurrentGlobalMultiplier() {
        val overrides = HostConnectionOverrides()
        assertEquals(4, overrides.liveBackgroundMultiplier(ConnectionPreferences(backgroundKeepaliveMultiplier = 4)))
        // The same running session sees the new value once the global setting changes.
        assertEquals(2, overrides.liveBackgroundMultiplier(ConnectionPreferences(backgroundKeepaliveMultiplier = 2)))
    }

    @Test fun aHostOverrideWinsOverTheGlobalMultiplier() {
        val overrides = HostConnectionOverrides(backgroundMultiplier = 8)
        assertEquals(8, overrides.liveBackgroundMultiplier(ConnectionPreferences(backgroundKeepaliveMultiplier = 2)))
    }
}
