package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/** Writes only to random directories inside the disposable loopback fixture; never uses saved profiles. */
class RemoteOperationsInstrumentedTest {
    private fun fixture(windows: Boolean = false, block: suspend (SshConnection, RemoteFileClient, RemoteOperationBackend, String, BlockingOperation) -> Unit) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString(if (windows) "fixtureWindowsPort" else "fixturePort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull("Required isolated fixture", port)
        assumeTrue(port != null)
        val connection = SshConnection(args.getString("fixtureHost") ?: "127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            val files = RemoteFileClient()
            val platform = if (windows) RemotePlatform.WINDOWS else RemotePlatform.POSIX
            files.setPlatform(connection, platform)
            val root = RemoteFilePaths.join(files.resolveHome(connection), UUID.randomUUID().toString())
            files.mkdirIfMissing(connection, root)
            BlockingOperation().use { control -> block(connection, files, SftpRemoteOperationBackend(files, connection, platform, control), root, control) }
        } finally { connection.close() }
    }

    @Test fun createRenamePermissionsAndDeleteUseProtocolOperations() = fixture { _, _, backend, root, _ ->
        val path = "$root/file"
        backend.create(path, false)
        backend.chmod(checkNotNull(backend.node(path)), 384)
        assertEquals(384, backend.node(path)?.attributes?.permissions?.and(0xfff))
        backend.move(checkNotNull(backend.node(path)), "$root/renamed", null)
        assertNull(backend.node(path))
        backend.remove(checkNotNull(backend.node("$root/renamed")))
        backend.remove(checkNotNull(backend.node(root)))
        assertNull(backend.node(root))
    }
    @Test fun streamedCopyPreservesBytesModeAndTimestampAndCleansStage() = fixture { connection, files, backend, root, control ->
        val bytes = ByteArray(190_000) { (it % 251).toByte() }
        files.upload(connection, bytes.inputStream(), root, "source", 0, bytes.size.toLong(), bytes.size.toLong(), control) { _, _ -> }
        backend.chmod(checkNotNull(backend.node("$root/source")), 416)
        val source = checkNotNull(backend.node("$root/source"))
        var progress = 0L
        backend.copy(source, "$root/copy", null, null, false, { progress += it }, { it() })
        assertEquals(bytes.size.toLong(), progress)
        assertArrayEquals(files.sha256(connection, "$root/source", control), files.sha256(connection, "$root/copy", control))
        assertEquals(416, backend.node("$root/copy")?.attributes?.permissions?.and(0xfff))
        assertEquals(source.attributes.mtime, backend.node("$root/copy")?.attributes?.mtime)
        assertFalse(backend.children(checkNotNull(backend.node(root))).any { it.path.endsWith(".part") })
    }
    @Test fun copiedLinksKeepRawTargetAndDeletingLinkRetainsTarget() = fixture { connection, _, backend, root, _ ->
        backend.create("$root/target", false)
        connection.openFiles().use { it.symlink("target", "$root/link"); it.symlink("missing", "$root/broken") }
        val link = checkNotNull(backend.node("$root/link"))
        assertEquals(RemoteFileKind.SYMLINK, link.kind)
        backend.copy(link, "$root/copied-link", null, null, false, {}, { it() })
        assertEquals("target", backend.node("$root/copied-link")?.linkTarget)
        assertEquals("missing", backend.node("$root/broken")?.linkTarget)
        backend.remove(link)
        assertNotNull(backend.node("$root/target"))
    }
    @Test fun changedSourceIsRejectedBeforeDeleting() = fixture { _, _, backend, root, _ ->
        backend.create("$root/file", false)
        val original = checkNotNull(backend.node("$root/file"))
        backend.chmod(original, 384)
        try { backend.remove(original); fail("Changed source was deleted") } catch (_: SourceChangedException) { }
        assertNotNull(backend.node("$root/file"))
    }
    @Test fun windowsCopyUsesExplicitDirectReplacementAndRetainsTargetMetadata() = fixture(windows = true) { connection, files, backend, root, control ->
        files.upload(connection, "source".byteInputStream(), root, "source", 0, 6, 1024, control) { _, _ -> }
        files.upload(connection, "old target".byteInputStream(), root, "target", 0, 10, 1024, control) { _, _ -> }
        val target = checkNotNull(backend.node("$root/target"))
        assertFalse(backend.atomicReplace())
        try {
            backend.copy(checkNotNull(backend.node("$root/source")), "$root/target", target, backend.digest(target), false, {}, { it() })
            fail("Direct write without approval")
        } catch (_: AtomicReplaceUnavailableException) { }
        assertEquals("old target", files.readEditable(connection, "$root/target", control).text)
        backend.copy(checkNotNull(backend.node("$root/source")), "$root/target", target, backend.digest(target), true, {}, { it() })
        assertEquals("source", files.readEditable(connection, "$root/target", control).text)
        assertEquals(target.attributes.permissions, backend.node("$root/target")?.attributes?.permissions)
    }

    @Test fun windowsCreateRenameDeleteAndEditorPreserveBomAndCrLf() = fixture(windows = true) { connection, files, backend, root, control ->
        backend.create("$root/new-file", false)
        try { backend.create("$root/new-file", false); fail("Exclusive create accepted an existing name") }
        catch (error: RemoteOperationException) { assertEquals(RemoteOperationFailure.EXISTS, error.reason) }
        val bytes = "\uFEFForiginal\r\nnext\r\n".toByteArray(Charsets.UTF_8)
        files.upload(connection, bytes.inputStream(), root, "new-file", 0, bytes.size.toLong(), 1024, control) { _, _ -> }
        val source = files.readEditable(connection, "$root/new-file", control)
        assertTrue(source.hasBom)
        try { files.saveEditable(connection, source, "edited\nnext\n", null, false, control); fail("Unapproved direct edit accepted") }
        catch (_: AtomicReplaceUnavailableException) { }
        files.saveEditable(connection, source, "edited\nnext\n", null, true, control)
        val saved = files.readEditable(connection, "$root/new-file", control)
        assertTrue(saved.hasBom)
        assertEquals("edited\r\nnext\r\n", saved.text)
        backend.move(checkNotNull(backend.node("$root/new-file")), "$root/renamed", null)
        assertNull(backend.node("$root/new-file"))
        backend.remove(checkNotNull(backend.node("$root/renamed")))
        backend.remove(checkNotNull(backend.node(root)))
        assertNull(backend.node(root))
    }

    @Test fun windowsEditorRejectsConcurrentRemoteChangesAndPermissionWrites() = fixture(windows = true) { connection, files, backend, root, control ->
        files.upload(connection, "before".byteInputStream(), root, "file", 0, 6, 1024, control) { _, _ -> }
        val source = files.readEditable(connection, "$root/file", control)
        files.upload(connection, "remote".byteInputStream(), root, "file", 0, 6, 1024, control) { _, _ -> }
        try { files.saveEditable(connection, source, "local", null, true, control); fail("Concurrent content change was overwritten") }
        catch (_: SourceChangedException) { }
        assertEquals("remote", files.readEditable(connection, "$root/file", control).text)
        val before = checkNotNull(backend.node("$root/file"))
        try { backend.chmod(before, 384); fail("Windows POSIX permissions were changed") }
        catch (_: RemoteOperationException) { }
        assertEquals(before.attributes.permissions, backend.node("$root/file")?.attributes?.permissions)
        assertFalse(backend.children(checkNotNull(backend.node(root))).any { it.path.endsWith(".part") })
    }
}
