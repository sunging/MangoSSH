package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** An explicitly supplied second fixture advertises ordinary SFTP rename without the POSIX extension. */
class RemoteFallbackInstrumentedTest {
    @Test fun ordinaryRenameCannotOverwriteUntilDirectOverwriteIsApproved() = runBlocking<Unit> {
        val port = InstrumentationRegistry.getArguments().getString("fixtureFallbackPort")?.toIntOrNull()
        if (InstrumentationRegistry.getArguments().getString("requireFixtures") == "true") assertNotNull("Required SSH fixture port", port)
        assumeTrue(port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            val files = RemoteFileClient()
            BlockingOperation().use { control ->
                val name = UUID.randomUUID().toString()
                val path = "/$name"
                val old = "long original content"
                files.upload(connection, old.byteInputStream(), "/", name, 0, old.length.toLong(), 1024, control) { _, _ -> }
                val target = files.inspectTarget(connection, path, control)
                assertFalse(target.atomicReplace)
                val token = UUID.randomUUID().toString()
                val temporary = files.reserveTemporary(connection, "/", token, control)
                files.upload(connection, "new".byteInputStream(), "/", RemoteFilePaths.nameOf(temporary), 0, 3, 1024, control) { _, _ -> }
                assertSuspendingThrows(AtomicReplaceUnavailableException::class.java) {
                    files.commitTemporary(connection, temporary, path, target, false, control)
                }
                assertEquals(old, files.readEditable(connection, path, control).text)
                files.commitTemporary(connection, temporary, path, target, true, control)
                assertEquals("new", files.readEditable(connection, path, control).text)
                val source = files.readEditable(connection, path, control)
                assertSuspendingThrows(AtomicReplaceUnavailableException::class.java) {
                    files.saveEditable(connection, source, "draft", null, false, control)
                }
                files.saveEditable(connection, source, "draft", "$name-copy", false, control)
                assertEquals("new", files.readEditable(connection, path, control).text)
                assertEquals("draft", files.readEditable(connection, "/$name-copy", control).text)
            }
        } finally { connection.close() }
    }
}
