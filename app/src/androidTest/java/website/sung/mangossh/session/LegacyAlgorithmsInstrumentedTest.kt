package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials
import website.sung.mangossh.session.ssh.SshFailure

/** The disposable peer offers only explicitly opt-in SHA-1/CBC algorithms. */
class LegacyAlgorithmsInstrumentedTest {
    @Test fun compatibilityIsExplicitAndReversible() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixtureLegacyPort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        for (legacy in listOf(false, true, false)) {
            val connection = SshConnection("127.0.0.1", port!!, legacyAlgorithms = legacy)
            try {
                if (legacy) {
                    connection.connect(10_000) { _, _ -> true }
                    assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
                    assertTrue(RemoteFileClient().resolveHome(connection).startsWith("/"))
                    connection.keepalive()
                } else {
                    val failure = assertSuspendingThrows(SshFailure::class.java) {
                        connection.connect(10_000) { _, _ -> true }
                    }
                    assertEquals(SshFailure.Category.ALGORITHMS, failure.category)
                }
            } finally { connection.close() }
        }
    }
}
