package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in fixture tests: only an explicitly supplied loopback port is ever contacted. */
class RemoteSafetyInstrumentedTest {
    private fun fixture(block: suspend (SshConnection, Int) -> Unit) = runBlocking<Unit> {
        val port = InstrumentationRegistry.getArguments().getString("fixturePort")?.toIntOrNull()
        if (InstrumentationRegistry.getArguments().getString("requireFixtures") == "true") assertNotNull("Required SSH fixture port", port)
        assumeTrue("Run the disposable tools/ssh-test-fixture.py with adb reverse", port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            block(connection, port)
        } finally { connection.close() }
    }

    @Test fun verifiedTemporaryReplacesLongerTargetAndEditorDetectsSameSizeChange() = fixture { connection, _ ->
        val files = RemoteFileClient()
        BlockingOperation().use { control ->
            val name = UUID.randomUUID().toString()
            val path = "/$name"
            suspend fun put(bytes: ByteArray) = files.upload(connection, bytes.inputStream(), "/", name, 0L,
                bytes.size.toLong(), 1024L, control) { _, _ -> }
            put(ByteArray(200) { 65 })
            val target = files.inspectTarget(connection, path, control)
            assertTrue(target.atomicReplace)
            val token = UUID.randomUUID().toString()
            val temporary = files.reserveTemporary(connection, "/", token, control)
            val bytes = "short\r\n".toByteArray()
            files.upload(connection, bytes.inputStream(), "/", RemoteFilePaths.nameOf(temporary), 0,
                bytes.size.toLong(), 1024, control) { _, _ -> }
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), files.sha256(connection, temporary, control))
            files.commitTemporary(connection, temporary, path, target, false, control)
            val source = files.readEditable(connection, path, control)
            assertEquals("short\r\n", source.text)
            put("other\r\n".toByteArray())
            try { files.saveEditable(connection, source, "draft", null, false, control); fail("Conflict accepted") }
            catch (_: SourceChangedException) { }
            assertEquals("other\r\n", files.readEditable(connection, path, control).text)
            val cleanup = connection.openFiles()
            try { cleanup.remove(path) } finally { cleanup.close() }
        }
    }

    @Test fun stagedUploadStaysPrivateUntilANewFileIsCommitted() = fixture { connection, _ ->
        val files = RemoteFileClient()
        BlockingOperation().use { control ->
            val path = "/" + UUID.randomUUID().toString()
            val token = UUID.randomUUID().toString()
            val temporary = files.reserveTemporary(connection, "/", token, control)
            suspend fun mode(target: String): Int {
                val client = connection.openFiles()
                try { return client.lstat(target).permissions!! and 0xfff } finally { client.close() }
            }
            assertEquals(0x180, mode(temporary))
            val bytes = ByteArray(40_000) { 7 }
            // A first chunk stands in for a paused upload: the partial file must stay private.
            files.upload(connection, bytes.copyOf(32_768).inputStream(), "/", RemoteFilePaths.nameOf(temporary), 0,
                null, 1 shl 20, control) { _, _ -> }
            assertEquals(0x180, mode(temporary))
            files.upload(connection, bytes.inputStream(), "/", RemoteFilePaths.nameOf(temporary), 32_768,
                bytes.size.toLong(), 1 shl 20, control) { _, _ -> }
            assertEquals(0x180, mode(temporary))
            files.commitTemporary(connection, temporary, path, files.inspectTarget(connection, path, control), false, control)
            assertEquals(0x1a4, mode(path))
            val cleanup = connection.openFiles()
            try { cleanup.remove(path) } finally { cleanup.close() }
        }
    }

    @Test fun directTcpipJumpCarriesSftpAndClosingItPreservesFirstHop() = fixture { first, port ->
        val final = SshConnection("127.0.0.1", port)
        final.useJump(first)
        try {
            final.connect(10_000) { _, _ -> true }
            assertTrue(final.authenticate("fixture", object : SshCredentials {}))
            assertTrue(RemoteFileClient().resolveHome(final).startsWith("/"))
        } finally { final.close() }
        first.keepalive()
        assertTrue(RemoteFileClient().resolveHome(first).startsWith("/"))
    }

    @Test fun fourHopSftpAndRejectedNextHopReleaseWithoutClosingIndependentConnection() = fixture { first, port ->
        val carriers = mutableListOf<SshConnection>()
        try {
            repeat(4) {
                val next = SshConnection("127.0.0.1", port)
                next.useJump(carriers.lastOrNull() ?: first)
                carriers += next
                next.connect(10_000) { _, _ -> true }
                assertTrue(next.authenticate("fixture", object : SshCredentials {}))
            }
            assertTrue(RemoteFileClient().resolveHome(carriers.last()).startsWith("/"))
            val rejected = SshConnection("127.0.0.1", port + 1)
            rejected.useJump(carriers.last())
            try {
                assertSuspendingThrows(java.io.IOException::class.java) { rejected.connect(10_000) { _, _ -> true } }
            } finally { rejected.close() }
            carriers.last().keepalive()
        } finally { carriers.asReversed().forEach { it.close() } }
        first.keepalive()
    }

    @Test fun isolatedTmuxCreateListAndAttachSelection() = fixture { connection, _ ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureTmux") == "true")
        val workspace = website.sung.mangossh.domain.TmuxWorkspace(website.sung.mangossh.domain.WorkspaceMode.CREATE,
            name = "test-" + UUID.randomUUID().toString())
        val id = requireNotNull(TmuxWorkspaces.prepare(connection, workspace))
        assertTrue(TmuxWorkspaces.list(connection).any { it.id == id && it.name == workspace.name })
        assertEquals(id, TmuxWorkspaces.prepare(connection,
            website.sung.mangossh.domain.TmuxWorkspace(website.sung.mangossh.domain.WorkspaceMode.ATTACH, sessionId = id)))
        assertTrue(TmuxWorkspaces.attachCommand(id).endsWith("'$id'"))
    }

    @Test fun tmuxCreateOrAttachUsesExactNameAndReturnsTheSameSession() = fixture { connection, _ ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureTmux") == "true")
        val name = "test-" + UUID.randomUUID().toString()
        val workspace = website.sung.mangossh.domain.TmuxWorkspace(website.sung.mangossh.domain.WorkspaceMode.CREATE_OR_ATTACH, name)
        val longer = requireNotNull(TmuxWorkspaces.prepare(connection, workspace.copy(name = "$name-long")))
        val created = requireNotNull(TmuxWorkspaces.prepare(connection, workspace))
        assertNotEquals(longer, created)
        assertEquals(created, TmuxWorkspaces.prepare(connection, workspace))
        assertEquals(1, TmuxWorkspaces.list(connection).count { it.name == name })
        assertSuspendingThrows(WorkspaceUnavailableException::class.java) {
            TmuxWorkspaces.prepare(connection, workspace.copy(mode = website.sung.mangossh.domain.WorkspaceMode.CREATE))
        }
    }

    @Test fun nativeMoshReceivesOutputResizesAndReapsThroughSingleWriter() = fixture { connection, _ ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureMosh") == "true")
        val session = connection.openChannel()
        session.execute("fixture-mosh")
        val bootstrap = requireNotNull(BoundedProtocolReader.lines(session.stdout, 20, 4096, 32768, MoshBootstrapParser::parse))
        // Carry datagrams through the fixture channel because Windows/WSL does not forward localhost UDP.
        val udp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))
        val peer = java.util.concurrent.atomic.AtomicReference<java.net.SocketAddress>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        executor.submit {
            try {
                val stream = java.io.DataOutputStream(session.stdin)
                val buffer = ByteArray(65535)
                while (!udp.isClosed) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    peer.set(packet.socketAddress)
                    stream.writeInt(packet.length); stream.write(packet.data, 0, packet.length); stream.flush()
                }
            } catch (_: java.io.IOException) { }
        }
        executor.submit {
            try {
                val stream = java.io.DataInputStream(session.stdout)
                while (!udp.isClosed) {
                    val length = stream.readInt()
                    require(length in 1..65535)
                    val bytes = ByteArray(length); stream.readFully(bytes)
                    peer.get()?.let { udp.send(java.net.DatagramPacket(bytes, bytes.size, it)) }
                }
            } catch (_: java.io.IOException) { }
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(base.cacheDir, "native-mosh-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = directory
        }
        val process = MoshPtyProcess.start(context, "127.0.0.1", udp.localPort, bootstrap.key, 80, 24)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val writer = TerminalTransport(scope, process.output, {}, {})
        try {
            val received = executor.submit<Boolean> {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (bytes.size() < 65536) {
                    val count = try { process.input.read(buffer) } catch (_: java.io.IOException) { -1 }
                    if (count < 0) {
                        val output = bytes.toString("UTF-8").lowercase()
                        val categories = listOf("locale", "permission denied", "no such file", "SshConnection", "exiting", "closed", "terminal", "mosh-client", "usage", "key").filter(output::contains)
                        throw AssertionError("Native exit categories=$categories byteCount=${bytes.size()}")
                    }
                    bytes.write(buffer, 0, count)
                    if (bytes.toString("UTF-8").contains("MANGOSSH_NATIVE_FIXTURE_READY")) return@submit true
                }
                false
            }
            assertTrue("Native Mosh must receive the remote fixture marker", received.get(25, java.util.concurrent.TimeUnit.SECONDS))
            process.resize(100, 36)
            process.closeGracefully(writer::finish)
            executor.submit { process.awaitExit() }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            process.close()
            writer.close()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            udp.close()
            session.close()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun cancellingOwnedSftpDoesNotCloseSharedSsh() = fixture { connection, _ ->
        val session = connection.openChannel()
        val control = BlockingOperation()
        assertTrue(control.own(session))
        control.close()
        assertFalse(control.shouldContinue())
        connection.keepalive()
        assertTrue(RemoteFileClient().resolveHome(connection).startsWith("/"))
    }
}
