package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalAppearanceColorParsingTest {
    @Test
    fun parsesOnlyOpaqueRgbHashNotation() {
        assertEquals(0xFF1A2B3C.toInt(), parseTerminalOpaqueArgb("#1a2B3c"))
        assertNull(parseTerminalOpaqueArgb("1A2B3C"))
        assertNull(parseTerminalOpaqueArgb("#1A2B3C44"))
        assertNull(parseTerminalOpaqueArgb("#1A2B3"))
        assertNull(parseTerminalOpaqueArgb("#nothex"))
    }

    @Test
    fun formatsArgbAsSixDigitRgbHashNotation() {
        assertEquals("#1A2B3C", terminalArgbToHex(0xFF1A2B3C.toInt()))
        assertEquals("#000000", terminalArgbToHex(0x00000000))
    }
}
