package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test
import website.sung.mangossh.session.ssh.SshFileAttributes

class RemoteStagingIdentityTest {
    private val path = SshFileAttributes(size = 0, uid = 0, gid = 0, permissions = 0x8187, mtime = 123)
    private val windowsHandle = path.copy(permissions = 0x81b6)

    @Test fun windowsAclAndCrtModesDoNotImplySourceChange() {
        RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path, windowsHandle, path)
    }

    @Test fun explicitWindowsPolicyHandlesChrootPathsWithoutWeakeningPathChecks() {
        RemoteStagingIdentity.requireMatches("/home/temp.part", path, windowsHandle, path, windows = true)
        assertThrows(SourceChangedException::class.java) {
            RemoteStagingIdentity.requireMatches("/home/temp.part", path, windowsHandle, path.copy(size = 10), windows = true)
        }
    }

    @Test fun unixHandleModeChangesAreStillRejected() {
        assertThrows(SourceChangedException::class.java) {
            RemoteStagingIdentity.requireMatches("/tmp/temp.part", path, windowsHandle, path)
        }
    }

    @Test fun actualWindowsPathPermissionChangesAreRejected() {
        assertThrows(SourceChangedException::class.java) {
            RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path, windowsHandle, path.copy(permissions = 0x81a4))
        }
    }

    @Test fun symlinkPathsAndHandlesAreRejected() {
        val link = path.copy(permissions = 0xa1ff)
        for ((before, opened, after) in listOf(Triple(link, windowsHandle, path), Triple(path, link, path), Triple(path, windowsHandle, link))) {
            assertThrows(SourceChangedException::class.java) {
                RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", before, opened, after)
            }
        }
    }

    @Test fun directorySubstitutionIsRejected() {
        assertThrows(SourceChangedException::class.java) {
            RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path, path.copy(permissions = 0x41ed), path)
        }
    }

    @Test fun ownerGroupAndSizeChangesAreRejected() {
        for (opened in listOf(windowsHandle.copy(uid = 1), windowsHandle.copy(gid = 1), windowsHandle.copy(size = 7), windowsHandle.copy(mtime = 124))) {
            assertThrows(SourceChangedException::class.java) {
                RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path, opened, path)
            }
        }
        for (after in listOf(path.copy(uid = 1), path.copy(gid = 1), path.copy(size = 7), path.copy(mtime = 124))) {
            assertThrows(SourceChangedException::class.java) {
                RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path, windowsHandle, after)
            }
        }
    }

    @Test fun accessTimeChangesDoNotInvalidateTheOpen() {
        RemoteStagingIdentity.requireMatches("/C:/fixture/temp.part", path.copy(atime = 100), windowsHandle.copy(atime = 200), path.copy(atime = 300))
    }

    @Test fun unixMetadataStillMatchesExactly() {
        RemoteStagingIdentity.requireMatches("/tmp/temp.part", path, path, path)
    }

    @Test fun driveDetectionDoesNotMisclassifyUnixNames() {
        for (value in listOf("/C:/fixture", "/r:/Temp", "/D:", "/E:/")) assertTrue(RemoteStagingIdentity.isWindowsPath(value))
        for (value in listOf("/", "/var", "/code", "/C:file", "C:/fixture", "/tmp/C:/file", "/1:/file", "")) assertFalse(RemoteStagingIdentity.isWindowsPath(value))
    }
}
