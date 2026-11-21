package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPasteTest {
    @Test fun requiresConfirmationAtAnyLineEndingOrMoreThan4096Utf8Bytes() {
        assertFalse(snapshot("a".repeat(4_096)).needsConfirmation)
        assertTrue(snapshot("a".repeat(4_097)).needsConfirmation)
        assertFalse(snapshot("\u00e9".repeat(2_048)).needsConfirmation)
        assertTrue(snapshot("\u00e9".repeat(2_048) + "b").needsConfirmation)
        assertEquals(4_096, snapshot("\ud83d\ude00".repeat(1_024)).utf8Bytes)
        listOf("a\nb", "a\rb", "a\r\nb").forEach {
            val snapshot = snapshot(it)
            assertTrue(snapshot.needsConfirmation)
            assertEquals(it, snapshot.text)
        }
    }

    @Test fun rejectsEscapeAndOtherControlBytesInExplicitPaste() {
        listOf('\u001b', '\u0000', '\u0008', '\u007f', '\u009b', '\ud800').forEach {
            assertNull(TerminalPasteSnapshot.fromClipboard("ordinary text" + it))
        }
    }

    @Test fun previewMakesInvisibleFormattingVisibleWithoutModifyingThePayload() {
        val text = "a\r\nb\u202e\tc"
        val snapshot = snapshot(text)
        assertEquals("a\\r\nb\\u202e\\tc", snapshot.preview())
        assertEquals(text, snapshot.text)
        assertTrue(snapshot("a".repeat(4_097)).previewWasShortened)
    }

    @Test fun aTargetExpiresWhenItsScreenOrLockGenerationChanges() {
        var generation: Long? = 14
        val target = TerminalPasteTarget(open = true) { generation }
        assertTrue(target.isCurrent(14))
        generation = null
        assertFalse(target.isCurrent(14))
        generation = 15
        assertFalse(target.isCurrent(14))
        assertTrue(target.isCurrent(15))
        target.invalidate()
        assertFalse(target.isCurrent(15))
        assertNull(TerminalPasteTarget(open = false) { 15 }.currentGeneration())
    }

    private fun snapshot(text: String) = requireNotNull(TerminalPasteSnapshot.fromClipboard(text))
}
