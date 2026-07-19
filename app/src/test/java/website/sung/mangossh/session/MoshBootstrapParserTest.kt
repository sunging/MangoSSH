package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies that only complete, non-secret-bearing Mosh bootstrap metadata is accepted. */
class MoshBootstrapParserTest {
    @Test
    fun parsesValidConnectLine() {
        val parsed = MoshBootstrapParser.parse("MOSH CONNECT 60042 AQIDBAUGBwgJCgsMDQ4PEA")

        requireNotNull(parsed)
        assertEquals(60042, parsed.port)
        assertEquals("MoshBootstrap(port=60042, key=<redacted>)", parsed.toString())
    }

    @Test
    fun rejectsInvalidPortAndKey() {
        assertNull(MoshBootstrapParser.parse("MOSH CONNECT 70000 AQIDBAUGBwgJCgsMDQ4PEA"))
        assertNull(MoshBootstrapParser.parse("MOSH CONNECT 60042 not-a-valid-key"))
        assertNull(MoshBootstrapParser.parse("Mosh CONNECT 60042 AQIDBAUGBwgJCgsMDQ4PEA"))
    }
}
