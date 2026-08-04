package website.sung.mangossh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies remote path arithmetic for the SFTP browser.
 *
 * These paths travel as SFTP protocol fields rather than through a remote
 * shell, so spaces and punctuation must survive intact.
 */
class RemoteFilePathsTest {
    @Test
    fun normalizesRelativeSegmentsAndSeparators() {
        assertEquals("/var/log", RemoteFilePaths.normalize("/var//log/"))
        assertEquals("/var", RemoteFilePaths.normalize("/var/log/.."))
        assertEquals("/var/log", RemoteFilePaths.normalize("/var/./log"))
        assertEquals("/", RemoteFilePaths.normalize(""))
        assertEquals("/", RemoteFilePaths.normalize("/.."))
    }

    @Test
    fun joinsUnderRootAndNestedDirectories() {
        assertEquals("/etc", RemoteFilePaths.join("/", "etc"))
        assertEquals("/etc/ssh", RemoteFilePaths.join("/etc", "ssh"))
        assertEquals("/etc/ssh", RemoteFilePaths.join("/etc/", "ssh"))
    }

    @Test
    fun keepsSpacesAndNonAsciiNames() {
        assertEquals("/home/sung/my notes.txt", RemoteFilePaths.join("/home/sung", "my notes.txt"))
        assertEquals("/home/sung/备份 (1).tar.gz", RemoteFilePaths.join("/home/sung", "备份 (1).tar.gz"))
        assertEquals("/tmp/a\$b;c'd", RemoteFilePaths.join("/tmp", "a\$b;c'd"))
    }

    @Test
    fun rejectsNamesThatAreNotSinglePathComponents() {
        assertThrows(IllegalArgumentException::class.java) { RemoteFilePaths.join("/tmp", "") }
        assertThrows(IllegalArgumentException::class.java) { RemoteFilePaths.join("/tmp", "..") }
        assertThrows(IllegalArgumentException::class.java) { RemoteFilePaths.join("/tmp", "a/b") }
        assertThrows(IllegalArgumentException::class.java) { RemoteFilePaths.join("/tmp", "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { RemoteFilePaths.join("/tmp", "a\u0000b") }
    }

    @Test
    fun resolvesParentAndNameIncludingRoot() {
        assertEquals("/etc", RemoteFilePaths.parentOf("/etc/ssh"))
        assertEquals("/", RemoteFilePaths.parentOf("/etc"))
        assertEquals("/", RemoteFilePaths.parentOf("/"))
        assertEquals("ssh", RemoteFilePaths.nameOf("/etc/ssh"))
        assertEquals("/", RemoteFilePaths.nameOf("/"))
    }

    @Test
    fun relativizesPathsInsideATransferRoot() {
        assertEquals("", RemoteFilePaths.relativize("/home/sung", "/home/sung"))
        assertEquals("notes.txt", RemoteFilePaths.relativize("/home/sung", "/home/sung/notes.txt"))
        assertEquals("a/b/c.txt", RemoteFilePaths.relativize("/home/sung/", "/home/sung/a/b/c.txt"))
        assertEquals("etc", RemoteFilePaths.relativize("/", "/etc"))
    }

    @Test
    fun rejectsPathsOutsideTheTransferRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFilePaths.relativize("/home/sung", "/home/sungx/notes.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFilePaths.relativize("/home/sung", "/etc/passwd")
        }
    }

    @Test
    fun resolvesRelativePathsUnderABase() {
        assertEquals("/home/sung", RemoteFilePaths.resolve("/home/sung", ""))
        assertEquals("/home/sung/a/b", RemoteFilePaths.resolve("/home/sung", "a/b"))
        assertEquals("/a", RemoteFilePaths.resolve("/", "a"))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFilePaths.resolve("/home/sung", "a/../../etc")
        }
    }

    @Test
    fun buildsBreadcrumbTrail() {
        assertEquals(listOf("/" to "/"), RemoteFilePaths.breadcrumbs("/"))
        assertEquals(
            listOf("/" to "/", "home" to "/home", "sung" to "/home/sung"),
            RemoteFilePaths.breadcrumbs("/home/sung"),
        )
    }

    @Test
    fun ordersDirectoriesFirstThenCaseInsensitiveNames() {
        val entries = listOf(
            entry("beta.txt", RemoteFileKind.FILE),
            entry("Alpha", RemoteFileKind.DIRECTORY),
            entry("alpha.txt", RemoteFileKind.FILE),
            entry("zeta", RemoteFileKind.DIRECTORY),
        )

        assertEquals(
            listOf("Alpha", "zeta", "alpha.txt", "beta.txt"),
            RemoteFilePaths.sortForDisplay(entries).map { it.name },
        )
    }

    private fun entry(name: String, kind: RemoteFileKind) = RemoteFileEntry(
        name = name,
        path = RemoteFilePaths.join("/tmp", name),
        kind = kind,
    )
}
