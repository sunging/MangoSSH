package website.sung.mangossh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppReleaseTest {
    @Test
    fun apkAssetNameMatchesTheReleaseWorkflowNaming() {
        assertEquals("MangoSSH-v0.1.0.apk", apkAssetName("v0.1.0"))
    }

    @Test
    fun parsesTheExactTwoLineWorkflowOutput() {
        val text = "aaaa000000000000000000000000000000000000000000000000000000000000  MangoSSH-v0.1.0.apk\n" +
            "bbbb000000000000000000000000000000000000000000000000000000000000  MangoSSH-v0.1.0.aab\n"

        assertEquals(
            "aaaa000000000000000000000000000000000000000000000000000000000000",
            parseSha256Sums(text, "MangoSSH-v0.1.0.apk"),
        )
        assertEquals(
            "bbbb000000000000000000000000000000000000000000000000000000000000",
            parseSha256Sums(text, "MangoSSH-v0.1.0.aab"),
        )
    }

    @Test
    fun acceptsTheBinaryMarkerSeparator() {
        val digest = "c".repeat(64)
        val text = "$digest *MangoSSH-v0.1.0.apk\n"

        assertEquals(digest, parseSha256Sums(text, "MangoSSH-v0.1.0.apk"))
    }

    @Test
    fun toleratesCrlfLineEndings() {
        val digest = "d".repeat(64)
        val text = "$digest  MangoSSH-v0.1.0.apk\r\n"

        assertEquals(digest, parseSha256Sums(text, "MangoSSH-v0.1.0.apk"))
    }

    @Test
    fun normalizesUppercaseHexToLowercase() {
        val text = "AAAA000000000000000000000000000000000000000000000000000000000000  MangoSSH-v0.1.0.apk\n"

        assertEquals(
            "aaaa000000000000000000000000000000000000000000000000000000000000",
            parseSha256Sums(text, "MangoSSH-v0.1.0.apk"),
        )
    }

    @Test
    fun returnsNullForAMissingEntry() {
        val text = "e".repeat(64) + "  MangoSSH-v0.1.0.aab\n"

        assertNull(parseSha256Sums(text, "MangoSSH-v0.1.0.apk"))
    }

    @Test
    fun returnsNullForDuplicateEntriesNamingTheSameFile() {
        val text = "f".repeat(64) + "  MangoSSH-v0.1.0.apk\n" +
            "1".repeat(64) + "  MangoSSH-v0.1.0.apk\n"

        assertNull(parseSha256Sums(text, "MangoSSH-v0.1.0.apk"))
    }

    @Test
    fun returnsNullForGarbageInput() {
        assertNull(parseSha256Sums("not a checksum file", "MangoSSH-v0.1.0.apk"))
        assertNull(parseSha256Sums("", "MangoSSH-v0.1.0.apk"))
    }
}
