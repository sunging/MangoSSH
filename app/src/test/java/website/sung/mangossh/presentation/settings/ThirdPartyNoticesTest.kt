package website.sung.mangossh.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ThirdPartyNoticesTest {
    @Test
    fun tailscaleLicenseAndDependencyNoticesAreViewable() {
        val tailscale = thirdPartyNotices.firstOrNull { it.name == "Tailscale tsnet" }
        val dependencies = thirdPartyNotices.firstOrNull { it.name == "Tailscale tsnet dependency notices" }

        assertNotNull(tailscale)
        assertNotNull(dependencies)
        assertEquals("licenses/tailscale-BSD-3-Clause.txt", tailscale?.licenseAsset)
        assertEquals("licenses/tsnet-third-party-notices.txt", dependencies?.licenseAsset)
    }

    @Test
    fun releasePageUsesTheCanonicalRepository() {
        assertEquals("https://github.com/sunging/MangoSSH/releases", projectReleasesUrl())
    }
}
