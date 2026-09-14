package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class EditableRemoteTextTest {
    private fun source(text: String, bom: Boolean = false): EditableRemoteText {
        val bytes = ((if (bom) "\uFEFF" else "") + text).toByteArray()
        val identity = SourceIdentity("/test", bytes.size.toLong(), 1L)
        return EditableRemoteText("/test", text, bom, if (text.contains("\r\n")) "\r\n" else "\n",
            identity, RemoteTarget(identity, 384, true), MessageDigest.getInstance("SHA-256").digest(bytes))
    }
    @Test fun unchangedMixedNewlinesAndBomAreByteExact() {
        val text = "one\r\ntwo\nthree\r\n"
        assertArrayEquals(("\uFEFF" + text).toByteArray(), source(text, true).encode(text))
    }
    @Test fun newLinesUseOriginalCrlfConvention() {
        assertEquals("one\r\ntwo\r\n", source("one\r\n").encode("one\ntwo\n").toString(Charsets.UTF_8))
    }
    @Test(expected = SourceChangedException::class) fun equalMetadataCannotHideContentConflict() {
        source("one").requireUnchanged(source("two"))
    }
}
