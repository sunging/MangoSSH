package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBehaviorTest {
    @Test
    fun defaultsReproduceThePriorHardcodedRuntimeValues() {
        val behavior = TerminalBehavior()

        assertEquals(1_000, behavior.scrollbackLines)
        assertTrue(behavior.boldAsBright)
        assertTrue(behavior.autoDetectUrls)
        assertEquals(3f, behavior.maxPinchZoomScale)
        assertEquals(TerminalRightAltMode.CHARACTER_MODIFIER, behavior.rightAltMode)
        assertEquals(TerminalDelKeyMode.DELETE, behavior.delKeyMode)
    }

    @Test
    fun outOfRangeScrollbackFallsBackToDefault() {
        val tooFew = TerminalBehavior(scrollbackLines = TerminalBehavior.MIN_SCROLLBACK_LINES - 1).normalized()
        val tooMany = TerminalBehavior(scrollbackLines = TerminalBehavior.MAX_SCROLLBACK_LINES + 1).normalized()

        assertEquals(TerminalBehavior.DEFAULT_SCROLLBACK_LINES, tooFew.scrollbackLines)
        assertEquals(TerminalBehavior.DEFAULT_SCROLLBACK_LINES, tooMany.scrollbackLines)
    }

    @Test
    fun boundaryScrollbackValuesSurviveNormalization() {
        val min = TerminalBehavior(scrollbackLines = TerminalBehavior.MIN_SCROLLBACK_LINES).normalized()
        val max = TerminalBehavior(scrollbackLines = TerminalBehavior.MAX_SCROLLBACK_LINES).normalized()

        assertEquals(TerminalBehavior.MIN_SCROLLBACK_LINES, min.scrollbackLines)
        assertEquals(TerminalBehavior.MAX_SCROLLBACK_LINES, max.scrollbackLines)
    }

    @Test
    fun outOfRangePinchZoomFallsBackToDefault() {
        val tooSmall = TerminalBehavior(
            maxPinchZoomScale = TerminalBehavior.MIN_MAX_PINCH_ZOOM_SCALE - 0.1f,
        ).normalized()
        val tooLarge = TerminalBehavior(
            maxPinchZoomScale = TerminalBehavior.MAX_MAX_PINCH_ZOOM_SCALE + 0.1f,
        ).normalized()

        assertEquals(TerminalBehavior.DEFAULT_MAX_PINCH_ZOOM_SCALE, tooSmall.maxPinchZoomScale)
        assertEquals(TerminalBehavior.DEFAULT_MAX_PINCH_ZOOM_SCALE, tooLarge.maxPinchZoomScale)
    }

    @Test
    fun rightAltAndDelKeyPreferenceIdsResolveOnlyKnownValues() {
        assertEquals(TerminalRightAltMode.META, TerminalRightAltMode.fromPreference("meta"))
        assertNull(TerminalRightAltMode.fromPreference("auto"))
        assertEquals(TerminalDelKeyMode.BACKSPACE, TerminalDelKeyMode.fromPreference("backspace"))
        assertNull(TerminalDelKeyMode.fromPreference("del"))
    }
}
