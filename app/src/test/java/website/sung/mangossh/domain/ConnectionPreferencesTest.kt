package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPreferencesTest {
    @Test
    fun defaultsReproduceThePriorHardcodedRuntimeValues() {
        val preferences = ConnectionPreferences()

        assertEquals(30, preferences.keepaliveSeconds)
        assertEquals(10, preferences.connectTimeoutSeconds)
        assertEquals(SshTerminalType.XTERM_256COLOR, preferences.sshTerminalType)
        assertEquals("xterm-256color", preferences.sshTerminalType.termValue)
    }

    @Test
    fun keepaliveZeroMeansDisabledAndSurvivesNormalization() {
        val disabled = ConnectionPreferences(keepaliveSeconds = 0).normalized()
        assertEquals(0, disabled.keepaliveSeconds)
    }

    @Test
    fun negativeOrTooSmallKeepaliveFallsBackToDefault() {
        val negative = ConnectionPreferences(keepaliveSeconds = -1).normalized()
        val tooSmall = ConnectionPreferences(keepaliveSeconds = ConnectionPreferences.MIN_KEEPALIVE_SECONDS - 1).normalized()

        assertEquals(ConnectionPreferences.DEFAULT_KEEPALIVE_SECONDS, negative.keepaliveSeconds)
        assertEquals(ConnectionPreferences.DEFAULT_KEEPALIVE_SECONDS, tooSmall.keepaliveSeconds)
    }

    @Test
    fun tooLargeKeepaliveFallsBackToDefault() {
        val tooLarge = ConnectionPreferences(keepaliveSeconds = ConnectionPreferences.MAX_KEEPALIVE_SECONDS + 1).normalized()
        assertEquals(ConnectionPreferences.DEFAULT_KEEPALIVE_SECONDS, tooLarge.keepaliveSeconds)
    }

    @Test
    fun boundaryKeepaliveValuesSurviveNormalization() {
        val min = ConnectionPreferences(keepaliveSeconds = ConnectionPreferences.MIN_KEEPALIVE_SECONDS).normalized()
        val max = ConnectionPreferences(keepaliveSeconds = ConnectionPreferences.MAX_KEEPALIVE_SECONDS).normalized()

        assertEquals(ConnectionPreferences.MIN_KEEPALIVE_SECONDS, min.keepaliveSeconds)
        assertEquals(ConnectionPreferences.MAX_KEEPALIVE_SECONDS, max.keepaliveSeconds)
    }

    @Test
    fun outOfRangeConnectTimeoutFallsBackToDefault() {
        val tooSmall = ConnectionPreferences(
            connectTimeoutSeconds = ConnectionPreferences.MIN_CONNECT_TIMEOUT_SECONDS - 1,
        ).normalized()
        val tooLarge = ConnectionPreferences(
            connectTimeoutSeconds = ConnectionPreferences.MAX_CONNECT_TIMEOUT_SECONDS + 1,
        ).normalized()

        assertEquals(ConnectionPreferences.DEFAULT_CONNECT_TIMEOUT_SECONDS, tooSmall.connectTimeoutSeconds)
        assertEquals(ConnectionPreferences.DEFAULT_CONNECT_TIMEOUT_SECONDS, tooLarge.connectTimeoutSeconds)
    }

    @Test
    fun sshTerminalTypePreferenceIdsResolveOnlyKnownValues() {
        assertEquals(SshTerminalType.VT100, SshTerminalType.fromPreference("vt100"))
        assertEquals(SshTerminalType.SCREEN_256COLOR, SshTerminalType.fromPreference("screen-256color"))
        assertNull(SshTerminalType.fromPreference("linux"))
        assertNull(SshTerminalType.fromPreference(null))
    }

    @Test
    fun everySshTerminalTypeHasANonBlankTermValue() {
        SshTerminalType.entries.forEach { type ->
            assertTrue(type.termValue.isNotBlank())
        }
    }
}
