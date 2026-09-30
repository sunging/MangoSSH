package website.sung.mangossh.session

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/**
 * Streams UUID-owned fixture files through real proxy descriptors. Uses only the
 * explicit loopback fixture and an in-memory cache; no app data is touched.
 */
class RemoteStreamInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(block: suspend (SshConnection, RemoteStreamRegistry) -> Unit) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull("fixturePort", port)
        assumeTrue(port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            val files = RemoteFileClient()
            val registry = RemoteStreamRegistry(
                budget = RemoteStreamCacheBudget(8L * 1024 * 1024, Long.MAX_VALUE, allocate = ByteBuffer::allocate),
                scope = scope,
                openReader = { target -> files.openReader(connection, target.identity) },
                onRevoked = {},
                onIdle = {},
            )
            block(connection, registry)
        } finally {
            scope.cancel()
            connection.close()
        }
    }

    @Test fun randomReadsThroughAProxyDescriptorMatchTheRemoteFile() = fixture { connection, registry ->
        val files = RemoteFileClient()
        val content = Random(11).nextBytes(3 * 1024 * 1024 + 777)
        val path = upload(files, connection, content)
        val identity = files.streamIdentity(connection, path)
        assertEquals(content.size.toLong(), identity.size)
        val target = registry.register("fixture", identity, "clip.mp4", "video/mp4")
        RemoteStreamProvider.openDescriptor(context, registry, target.token).use { descriptor ->
            assertEquals(content.size.toLong(), descriptor.statSize)
            val random = Random(12)
            repeat(40) {
                val offset = random.nextLong(0, content.size.toLong())
                val count = random.nextInt(1, 300_000)
                val expected = content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + count))
                assertArrayEquals(expected, readFully(descriptor, offset, count))
            }
            assertEquals(0, Os.pread(descriptor.fileDescriptor, ByteArray(16), 0, 16, content.size.toLong()))
        }
    }

    @Test fun aFileChangedAfterItWasOfferedFailsToRead() = fixture { connection, registry ->
        val files = RemoteFileClient()
        val path = upload(files, connection, Random(13).nextBytes(100_000))
        val target = registry.register("fixture", files.streamIdentity(connection, path), "a.bin", "application/octet-stream")
        BlockingOperation().use { control ->
            files.upload(connection, ByteArray(50_000).inputStream(), "/", path.removePrefix("/"), 0, 50_000, 1_000_000, control) { _, _ -> }
        }
        RemoteStreamProvider.openDescriptor(context, registry, target.token).use { descriptor ->
            try {
                Os.pread(descriptor.fileDescriptor, ByteArray(16), 0, 16, 0)
                fail("a changed file must not be served")
            } catch (_: ErrnoException) {
            } catch (_: IOException) {
            }
        }
    }

    @Test fun aWithdrawnStreamCannotBeOpened() = fixture { connection, registry ->
        val files = RemoteFileClient()
        val path = upload(files, connection, Random(14).nextBytes(10_000))
        val target = registry.register("fixture", files.streamIdentity(connection, path), "a.bin", "application/octet-stream")
        registry.onSessionEnded("fixture")
        try {
            RemoteStreamProvider.openDescriptor(context, registry, target.token).close()
            fail("a withdrawn token must not open")
        } catch (_: java.io.FileNotFoundException) {
        }
    }

    private suspend fun upload(files: RemoteFileClient, connection: SshConnection, content: ByteArray): String {
        val name = UUID.randomUUID().toString()
        BlockingOperation().use { control ->
            files.upload(connection, content.inputStream(), "/", name, 0, content.size.toLong(), content.size.toLong(), control) { _, _ -> }
        }
        return "/$name"
    }

    private fun readFully(descriptor: ParcelFileDescriptor, offset: Long, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = Os.pread(descriptor.fileDescriptor, buffer, filled, count - filled, offset + filled)
            if (read <= 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }
}
