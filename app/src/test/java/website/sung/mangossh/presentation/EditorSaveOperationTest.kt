package website.sung.mangossh.presentation

import org.junit.Assert.*
import org.junit.Test

class EditorSaveOperationTest {
    @Test fun failureRetainsEditorAndAllowsRetryWhileSuccessClosesOnce() {
        var closed = 0
        val operation = EditorSaveOperation { closed++ }
        assertTrue(operation.begin()); assertFalse(operation.begin())
        operation.finish(UiText.Verbatim("synthetic failure"))
        assertEquals(0, closed); assertFalse(operation.busy); assertNotNull(operation.error)
        assertTrue(operation.begin()); operation.finish(null); operation.finish(null)
        assertEquals(1, closed)
    }
    @Test fun disposedOrCancelledEditorIgnoresLateCompletion() {
        var closed = 0
        val operation = EditorSaveOperation { closed++ }
        operation.begin(); operation.cancel(); operation.finish(null)
        operation.begin(); operation.dispose(); operation.finish(null)
        assertEquals(0, closed); assertFalse(operation.begin())
    }
}
