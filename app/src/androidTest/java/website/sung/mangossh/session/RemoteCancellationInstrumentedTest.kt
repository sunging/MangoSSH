package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises a peer that accepts forwarding but never replies to cancellation within the protocol deadline. */
class RemoteCancellationInstrumentedTest {
    @Test fun unansweredCancellationDoesNotBlockCallerOrIndependentSession() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixtureCancelPort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val connection = SshConnection("127.0.0.1", port!!)
        val independent = SshConnection("127.0.0.1", port)
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (client in listOf(connection, independent)) {
                client.connect(10_000) { _, _ -> true }
                assertTrue(client.authenticate("fixture", object : SshCredentials {}))
            }
            val forward = connection.createRemotePortForwarder("127.0.0.1", 22500, "127.0.0.1", port)
            val result = CompletableDeferred<Throwable?>()
            closeForwardInBackground(cleanup, { forward.close() }, { result.complete(it) })
            // A different SshConnection remains usable while the first peer withholds its response.
            assertNotNull(RemoteFileClient().list(independent, "fixture", "/", 20))
            // Cancellation is a no-reply SSH request; shutdown must not wait for the peer.
            assertNull(withTimeout(2_000) { result.await() })
        } finally { connection.close(); independent.close(); cleanup.cancel() }
    }
}
