package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import kotlinx.coroutines.runBlocking
import website.sung.mangossh.session.ssh.SshFileAttributes
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Uses only explicit loopback fixtures and UUID-owned files; ownership tests require the disposable Linux runner. */
class RemoteMetadataInstrumentedTest {
    private fun fixture(argument: String, block: suspend (SshConnection) -> Unit) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString(argument)?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull(argument, port)
        assumeTrue(port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {})); block(connection)
        } finally { connection.close() }
    }
    @Test fun replacementPreservesRealOwnershipAndSpecialMode() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureOwnership") == "true")
        fixture("fixturePort") { connection ->
            val files = RemoteFileClient()
            val name = UUID.randomUUID().toString()
            BlockingOperation().use { control ->
                files.upload(connection, "old".byteInputStream(), "/", name, 0, 3, 1024, control) { _, _ -> }
                val client = connection.openFiles()
                try {
                    client.setstat("/$name", SshFileAttributes(uid = 12001, gid = 12002))
                    client.setstat("/$name", SshFileAttributes(permissions = 0x9ed))
                } finally { client.close() }
                val before = files.readEditable(connection, "/$name", control)
                files.saveEditable(connection, before, "new", null, false, control)
                val after = files.readEditable(connection, "/$name", control)
                assertEquals("new", after.text)
                assertEquals(12001, after.target.uid); assertEquals(12002, after.target.gid)
                assertEquals(0x9ed, after.target.permissions!! and 0xfff)
            }
        }
    }
    @Test fun rejectedMetadataLeavesOriginalContentsUnchanged() = fixture("fixtureMetadataPort") { connection ->
        val files = RemoteFileClient()
        val name = UUID.randomUUID().toString()
        BlockingOperation().use { control ->
            files.upload(connection, "old".byteInputStream(), "/", name, 0, 3, 1024, control) { _, _ -> }
            val before = files.readEditable(connection, "/$name", control)
            assertSuspendingThrows(MetadataPreservationException::class.java) { files.saveEditable(connection, before, "new", null, false, control) }
            val after = files.readEditable(connection, "/$name", control)
            assertEquals("old", after.text)
            assertTrue(before.target.matches(after.target))
        }
    }
}
