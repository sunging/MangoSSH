package website.sung.mangossh.session

import org.junit.Assert.assertThrows
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
}
