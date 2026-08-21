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
    fun keepaliveChoicesOfferAPersistedInRangeValueTheListOmits() {
        val choices = ConnectionPreferences.keepaliveChoices(45)

        assertTrue(choices.contains(45))
        assertTrue(choices.containsAll(ConnectionPreferences.KEEPALIVE_CHOICES))
        assertEquals(ConnectionPreferences.KEEPALIVE_CHOICES.size + 1, choices.size)
        assertEquals(choices.sorted(), choices)
    }

    @Test
    fun keepaliveChoicesStayUnchangedForValuesTheListAlreadyOffers() {
        ConnectionPreferences.KEEPALIVE_CHOICES.forEach { value ->
            assertEquals(ConnectionPreferences.KEEPALIVE_CHOICES, ConnectionPreferences.keepaliveChoices(value))
        }
    }

    @Test
    fun keepaliveChoicesOmitValuesNormalizationWouldReject() {
        val belowMinimum = ConnectionPreferences.MIN_KEEPALIVE_SECONDS - 1
        val aboveMaximum = ConnectionPreferences.MAX_KEEPALIVE_SECONDS + 1

        assertEquals(ConnectionPreferences.KEEPALIVE_CHOICES, ConnectionPreferences.keepaliveChoices(belowMinimum))
        assertEquals(ConnectionPreferences.KEEPALIVE_CHOICES, ConnectionPreferences.keepaliveChoices(aboveMaximum))
        assertEquals(ConnectionPreferences.KEEPALIVE_CHOICES, ConnectionPreferences.keepaliveChoices(-1))
    }

    @Test
    fun keepaliveChoicesOfferTheRangeBoundaries() {
        assertTrue(
            ConnectionPreferences.keepaliveChoices(ConnectionPreferences.MIN_KEEPALIVE_SECONDS)
                .contains(ConnectionPreferences.MIN_KEEPALIVE_SECONDS),
        )
        assertTrue(
            ConnectionPreferences.keepaliveChoices(ConnectionPreferences.MAX_KEEPALIVE_SECONDS)
                .contains(ConnectionPreferences.MAX_KEEPALIVE_SECONDS),
        )
    }

    @Test
    fun connectTimeoutChoicesOfferAPersistedInRangeValueTheListOmits() {
        val choices = ConnectionPreferences.connectTimeoutChoices(45)

        assertTrue(choices.contains(45))
        assertEquals(ConnectionPreferences.CONNECT_TIMEOUT_CHOICES.size + 1, choices.size)
        assertEquals(choices.sorted(), choices)
    }

    @Test
    fun connectTimeoutChoicesOmitValuesNormalizationWouldReject() {
        val belowMinimum = ConnectionPreferences.MIN_CONNECT_TIMEOUT_SECONDS - 1
        val aboveMaximum = ConnectionPreferences.MAX_CONNECT_TIMEOUT_SECONDS + 1

        assertEquals(
            ConnectionPreferences.CONNECT_TIMEOUT_CHOICES,
            ConnectionPreferences.connectTimeoutChoices(belowMinimum),
        )
        assertEquals(
            ConnectionPreferences.CONNECT_TIMEOUT_CHOICES,
            ConnectionPreferences.connectTimeoutChoices(aboveMaximum),
        )
    }

    @Test
    fun everyOfferedChoiceSurvivesNormalization() {
        (ConnectionPreferences.keepaliveChoices(45) + 0).forEach { seconds ->
            assertEquals(seconds, ConnectionPreferences(keepaliveSeconds = seconds).normalized().keepaliveSeconds)
        }
        ConnectionPreferences.connectTimeoutChoices(45).forEach { seconds ->
            assertEquals(
                seconds,
                ConnectionPreferences(connectTimeoutSeconds = seconds).normalized().connectTimeoutSeconds,
            )
        }
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
