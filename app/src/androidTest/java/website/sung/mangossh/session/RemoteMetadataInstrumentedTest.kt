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
        val connection = SshConnection(args.getString("fixtureHost") ?: "127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {})); block(connection)
        } finally { connection.close() }
    }
    @Test fun uploadAndEditorSaveAsCommitWithVerifiedOwnerOnlyMode() = fixture("fixturePort") { connection ->
        val files = RemoteFileClient()
        val name = UUID.randomUUID().toString()
        val path = "/$name"
        val copyName = "$name-copy"
        BlockingOperation().use { control ->
            val token = UUID.randomUUID().toString()
            val stage = files.reserveTemporary(connection, "/", token, control)
            files.upload(connection, "sample".byteInputStream(), "/", RemoteFilePaths.nameOf(stage), 0, 6, 1024, control) { _, _ -> }
            files.commitTemporary(connection, stage, path, files.inspectTarget(connection, path, control), false, control)
            val source = files.readEditable(connection, path, control)
            assertEquals(0x180, source.target.permissions?.and(0xfff))
            files.saveEditable(connection, source, "changed", copyName, false, control)
            val saved = files.readEditable(connection, "/$copyName", control)
            assertEquals("changed", saved.text)
            assertEquals(0x180, saved.target.permissions?.and(0xfff))
            assertEquals("sample", files.readEditable(connection, path, control).text)
        }
    }

    @Test fun broadStageModeBlocksUploadBeforeWriting() = fixture("fixturePort") { connection ->
        val files = RemoteFileClient()
        BlockingOperation().use { control ->
            val stage = files.reserveTemporary(connection, "/", UUID.randomUUID().toString(), control)
            val client = connection.openFiles()
            try { client.setstat(stage, SshFileAttributes(permissions = 0x1a4)) } finally { client.close() }
            assertSuspendingThrows(MetadataPreservationException::class.java) {
                files.upload(connection, "sample".byteInputStream(), "/", RemoteFilePaths.nameOf(stage), 0, 6, 1024, control) { _, _ -> }
            }
            assertEquals(0L, files.identity(connection, stage, control).size)
        }
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
