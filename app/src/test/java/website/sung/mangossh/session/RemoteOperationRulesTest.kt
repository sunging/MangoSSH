package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test

class RemoteOperationRulesTest {
    @Test fun windowsRejectsAliasesSeparatorsStreamsAndReservedNames() {
        listOf("CON", "nul.txt", "COM1.log", "LPT³", "x.", "x ", "a:b", "a\\b", "a?b", "a\u0001b", "..", "a/b").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { RemoteOperationRules.requireName(it, RemotePlatform.WINDOWS) }
        }
        assertEquals("My file (2).txt", RemoteOperationRules.requireName("My file (2).txt", RemotePlatform.WINDOWS))
    }
    @Test fun posixPreservesLegalPunctuationAndCase() {
        listOf("CON", "a:b", "a\\b", "x.", "x ", "a?b", "a\$b;c'd").forEach {
            assertEquals(it, RemoteOperationRules.requireName(it, RemotePlatform.POSIX))
        }
    }
    @Test fun autoDoesNotGuessPosixFromUnixLookingPaths() {
        assertEquals(RemotePlatform.AUTO, RemotePlatform.AUTO.resolve("/home/user"))
        assertEquals(RemotePlatform.WINDOWS, RemotePlatform.AUTO.resolve("/C:/Users"))
        assertEquals(RemotePlatform.POSIX, RemotePlatform.POSIX.resolve("/C:/data"))
    }
    @Test fun containmentIsComponentAwareAndPlatformAware() {
        assertFalse(RemoteOperationRules.contains("/a", "/ab", RemotePlatform.POSIX))
        assertTrue(RemoteOperationRules.contains("/C:/A", "/c:/a/b", RemotePlatform.WINDOWS))
        assertFalse(RemoteOperationRules.contains("/a", "/A/b", RemotePlatform.POSIX))
        listOf("/", "/C:/", "/c:", "/folder/..").forEach {
            assertThrows(IllegalArgumentException::class.java) { RemoteOperationRules.requireMutable(it) }
        }
    }
    @Test fun permissionsPreserveSpecialBitsPerItemUnlessExplicitlyChanged() {
        val change = RemotePermissionChange(420, 493)
        assertEquals(0x800 or 420, change.apply(0x800 or 511, false))
        assertEquals(0x200 or 493, change.apply(0x200 or 448, true))
        assertEquals(420, change.copy(specialBits = 0).apply(0xe00 or 511, false))
        assertEquals(0x400 or 493, change.copy(specialBits = 2).apply(0, true))
        assertEquals(493, RemoteOperationRules.mode("755"))
        listOf("75", "888", "10000", " 644", "-1").forEach { assertNull(RemoteOperationRules.mode(it)) }
    }
}
