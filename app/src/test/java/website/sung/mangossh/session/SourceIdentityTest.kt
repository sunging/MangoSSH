package website.sung.mangossh.session

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdentityTest {
    @Test fun sameLengthChangedFileCannotResume() {
        assertThrows(SourceChangedException::class.java) {
            SourceIdentity("synthetic", 100, 1).requireMatches(SourceIdentity("synthetic", 100, 2), true)
        }
    }
    @Test fun missingMetadataCannotAuthorizeSkippingOrAppend() {
        val source = SourceIdentity("synthetic", 100, null)
        source.requireMatches(source)
        assertThrows(SourceChangedException::class.java) { source.requireMatches(source, true) }
    }

    @Test fun mismatchDiagnosticsDistinguishMetadataWithoutRetainingSourceValues() {
        val source = SourceIdentity("synthetic", 100, 1)
        for ((current, reason) in listOf(
            SourceIdentity("other", 100, 1) to SourceChangeReason.LOCATOR,
            SourceIdentity("synthetic", 101, 1) to SourceChangeReason.SIZE,
            SourceIdentity("synthetic", 100, 2) to SourceChangeReason.MODIFIED,
        )) {
            val error = assertThrows(SourceChangedException::class.java) { source.requireMatches(current) }
            assertEquals(reason, error.reason)
            assertEquals(null, error.message)
            assertEquals(null, error.cause)
        }
        val incomplete = SourceIdentity("synthetic", null, null)
        assertEquals(SourceChangeReason.INCOMPLETE,
            assertThrows(SourceChangedException::class.java) { incomplete.requireMatches(incomplete, true) }.reason)
    }
}
