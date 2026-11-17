package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWriteNamesTest {
    @Test fun validNamesAreNeverTrimmedOrTreatedAsPaths() {
        val name = " spaced file "
        assertEquals(name, RemoteWriteNames.requireName(name, RemotePlatform.POSIX))
        assertTrue(RemoteWriteNames.isValid(" ", RemotePlatform.POSIX))
        assertFalse(RemoteWriteNames.isValid(name, RemotePlatform.WINDOWS))
        assertFalse(RemoteWriteNames.isValid("prefix/file", RemotePlatform.POSIX))
        assertFalse(RemoteWriteNames.isValid("prefix\\file", RemotePlatform.WINDOWS))
    }

    @Test fun windowsManifestRejectsInvalidNestedComponentsAndCaseAliases() {
        val invalidPaths = listOf("valid/NUL.txt", "valid/CON .txt", "valid/child.", "valid/another:stream",
            "valid/trailing ", "valid/sub\\file", "valid//empty")
        invalidPaths.forEach { path ->
            val error = assertThrows(RemoteOperationException::class.java) {
                RemoteWriteNames.requireTree("root", listOf("valid"), sequenceOf(path), RemotePlatform.WINDOWS)
            }
            assertEquals(RemoteOperationFailure.INVALID_NAME, error.reason)
        }
        assertThrows(RemoteOperationException::class.java) {
            RemoteWriteNames.requireTree("root", listOf("Dir"), sequenceOf("dir"), RemotePlatform.WINDOWS)
        }
        RemoteWriteNames.requireTree("root", listOf("Dir"), sequenceOf("dir", "Dir/NUL.txt"), RemotePlatform.POSIX)
    }

    @Test fun componentLengthUsesTheTargetPlatformAndEncoding() {
        val multibyte = "\u00e9".repeat(128)
        assertFalse(RemoteWriteNames.isValid(multibyte, RemotePlatform.POSIX))
        assertTrue(RemoteWriteNames.isValid(multibyte, RemotePlatform.WINDOWS))
        assertFalse(RemoteWriteNames.isValid("\ud800", RemotePlatform.POSIX))
    }
}
