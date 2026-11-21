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

    @Test fun theRemoteLimitIncludesBomAndConvertedLineEndings() {
        val source = source("a\r\n", bom = true)
        val exact = "a".repeat(MAX_REMOTE_PREVIEW_BYTES - 5) + "\n"
        assertEquals(MAX_REMOTE_PREVIEW_BYTES, source.encodedSize(exact))
        assertEquals(MAX_REMOTE_PREVIEW_BYTES, source.encode(exact).size)
        assertEquals(MAX_REMOTE_PREVIEW_BYTES + 1, source.encodedSize(exact + "b"))
        val rejected = assertThrows(RemoteFileException::class.java) { source.encode(exact + "b") }
        assertEquals(RemoteFileFailure.TOO_LARGE, rejected.failure)
    }
}
