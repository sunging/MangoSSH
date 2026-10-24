package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/** Uses only a disposable Windows-stat fixture, or a confined real Windows SFTP subprocess. */
class WindowsSftpUploadInstrumentedTest {
    private fun fixture(block: suspend (SshConnection, RemoteFileClient, String) -> Unit) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixtureWindowsPort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull("Required Windows-stat fixture", port)
        assumeTrue(port != null)
        val host = args.getString("fixtureHost") ?: "127.0.0.1"
        val connection = SshConnection(host, port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            val files = RemoteFileClient()
            val root = files.resolveHome(connection)
            assertTrue(RemoteStagingIdentity.isWindowsPath(root))
            val directory = RemoteFilePaths.join(root, UUID.randomUUID().toString())
            files.mkdirIfMissing(connection, directory)
            block(connection, files, directory)
        } finally { connection.close() }
    }

    @Test fun windowsStageUploadResumeAndHashCheckWorkDespiteModeProjection() = fixture { connection, files, directory ->
        BlockingOperation().use { control ->
            val bytes = ByteArray(80_000) { (it % 251).toByte() }
            val token = UUID.randomUUID().toString()
            val temporary = files.reserveTemporary(connection, directory, token, control)
            val name = RemoteFilePaths.nameOf(temporary)
            files.upload(connection, bytes.copyOf(32_768).inputStream(), directory, name, 0, 32_768, 1 shl 20, control) { _, _ -> }
            files.upload(connection, bytes.inputStream(), directory, name, 20_000, bytes.size.toLong(), 1 shl 20, control) { _, _ -> }
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), files.sha256(connection, temporary, control))
            val destination = RemoteFilePaths.join(directory, "target")
            files.commitTemporary(connection, temporary, destination, files.inspectTarget(connection, destination, control), false, control)
            assertEquals(bytes.size.toLong(), files.identity(connection, destination).size)
            assertFalse(files.list(connection, "fixture", directory, 20).entries.any { it.name.endsWith(".part") })
        }
    }

    @Test fun windowsReplacementNeedsApprovalAndKeepsOriginalPathMetadata() = fixture { connection, files, directory ->
        BlockingOperation().use { control ->
            files.upload(connection, "original long content".byteInputStream(), directory, "target", 0, 21, 1024, control) { _, _ -> }
            val destination = RemoteFilePaths.join(directory, "target")
            val target = files.inspectTarget(connection, destination, control)
            assertFalse("Windows ACL preservation requires an in-place write", target.atomicReplace)
            val temporary = files.reserveTemporary(connection, directory, UUID.randomUUID().toString(), control)
            files.upload(connection, "new".byteInputStream(), directory, RemoteFilePaths.nameOf(temporary), 0, 3, 1024, control) { _, _ -> }
            try {
                files.commitTemporary(connection, temporary, destination, target, false, control)
                fail("Unapproved direct replacement accepted")
            } catch (_: AtomicReplaceUnavailableException) { }
            assertEquals("original long content", files.readEditable(connection, destination, control).text)
            files.commitTemporary(connection, temporary, destination, target, true, control)
            assertEquals("new", files.readEditable(connection, destination, control).text)
            val committed = files.inspectTarget(connection, destination, control)
            assertEquals(target.permissions, committed.permissions)
            assertEquals(target.uid, committed.uid)
            assertEquals(target.gid, committed.gid)
        }
    }
}
