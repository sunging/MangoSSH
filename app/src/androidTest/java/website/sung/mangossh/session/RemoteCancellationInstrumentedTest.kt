package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import com.trilead.ssh2.Connection
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
        val connection = Connection("127.0.0.1", port!!)
        val independent = Connection("127.0.0.1", port)
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (client in listOf(connection, independent)) {
                client.connect({ _, _, _, _ -> true }, 5000, 5000)
                assertTrue(client.authenticateWithNone("fixture"))
            }
            connection.requestRemotePortForwarding("127.0.0.1", 22500, "127.0.0.1", port)
            val result = CompletableDeferred<Throwable?>()
            closeForwardInBackground(cleanup, { connection.cancelRemotePortForwarding(22500) }, { result.complete(it) })
            assertFalse(result.isCompleted)
            // A different connection remains usable while the first peer withholds its response.
            assertNotNull(RemoteFileClient().list(independent, "fixture", "/", 20))
            assertNotNull(withTimeout(35_000) { result.await() })
        } finally { connection.abort(); independent.abort(); cleanup.cancel() }
    }
}
