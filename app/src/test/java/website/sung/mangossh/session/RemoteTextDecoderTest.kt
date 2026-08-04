package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that only valid UTF-8 remote content is offered as a text preview. */
class RemoteTextDecoderTest {
    @Test
    fun decodesUtf8Text() {
        val bytes = "hello 世界\n".toByteArray(Charsets.UTF_8)

        val preview = RemoteTextDecoder.decode("/tmp/a.txt", bytes, truncated = false, totalSizeBytes = 12L)

        requireNotNull(preview)
        assertEquals("hello 世界\n", preview.text)
        assertFalse(preview.truncated)
        assertEquals(12L, preview.totalSizeBytes)
    }

    @Test
    fun rejectsContentWithNulBytes() {
        val bytes = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01)

        assertNull(RemoteTextDecoder.decode("/bin/ls", bytes, truncated = true, totalSizeBytes = 4096L))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = byteArrayOf(0x68, 0x69, 0xC3.toByte(), 0x28)

        assertNull(RemoteTextDecoder.decode("/tmp/a.bin", bytes, truncated = false, totalSizeBytes = 4L))
    }

    @Test
    fun dropsTrailingPartialSequenceOnlyWhenTruncated() {
        // "世" is E4 B8 96; a capped read can stop after its first two bytes.
        val bytes = "ab世".toByteArray(Charsets.UTF_8).copyOf(4)

        val truncatedPreview = RemoteTextDecoder.decode(
            path = "/tmp/a.txt",
            bytes = bytes,
            truncated = true,
            totalSizeBytes = 1_000L,
        )
        requireNotNull(truncatedPreview)
        assertEquals("ab", truncatedPreview.text)
        assertTrue(truncatedPreview.truncated)

        assertNull(RemoteTextDecoder.decode("/tmp/a.txt", bytes, truncated = false, totalSizeBytes = 4L))
    }
}
