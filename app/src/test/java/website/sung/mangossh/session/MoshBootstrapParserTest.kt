package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun acceptsPrivilegedUdpPortAdvertisedByServer() {
        assertEquals(123, MoshBootstrapParser.parse("MOSH CONNECT 123 AQIDBAUGBwgJCgsMDQ4PEA")?.port)
    }

    @Test
    fun selectsDefaultOrUsesFullOverrideVerbatim() {
        val defaultCommand = "mosh-server new -s -c 256 -l LANG=C.UTF-8"
        assertEquals(defaultCommand, moshBootstrapCommand(null, null))
        assertEquals(defaultCommand, moshBootstrapCommand(" \t ", null))
        for (command in listOf(
            "mosh-server new -s -l LANG=en_US.UTF-8",
            "authbind mosh-server new -p 123 -s -l LANG=en_US.UTF-8",
            "  '/path with spaces/mosh-server' new -s  ",
        )) {
            assertEquals(command, moshBootstrapCommand(command, null))
        }
    }

    @Test
    fun appendsTheQuotedWorkspaceTargetToTheSelectedCommand() {
        val suffix = " -- tmux attach-session -t '$4'"
        val custom = "authbind mosh-server new -p 123 -s -l LANG=en_US.UTF-8"
        assertEquals("mosh-server new -s -c 256 -l LANG=C.UTF-8" + suffix, moshBootstrapCommand(null, "$4"))
        assertEquals(custom + suffix, moshBootstrapCommand(custom, "$4"))
        assertThrows(IllegalArgumentException::class.java) { moshBootstrapCommand("mosh-server\n", null) }
    }
}
