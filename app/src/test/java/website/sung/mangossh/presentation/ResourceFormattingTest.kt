package website.sung.mangossh.presentation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceFormattingTest {
    @Test fun capacitiesAndRatesUseBinaryUnitsAndUiLocale() {
        assertEquals("1 KiB", formatResourceBytes(1024.0, Locale.US))
        assertEquals("1.5 MiB", formatResourceBytes(1.5 * 1024 * 1024, Locale.US))
        assertEquals("1,5 MiB", formatResourceBytes(1.5 * 1024 * 1024, Locale.GERMANY))
        assertEquals("0 B", formatResourceBytes(0.0, Locale.US))
    }
}
