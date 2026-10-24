package website.sung.mangossh.session

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshCredentials

/** Explicit disposable loopback fixtures only; no vault, user profiles, or persistent files. */
class ServerResourcesInstrumentedTest {
    private fun fixture(argument: String = "fixtureResourcesPort", block: suspend (SshConnection) -> Unit) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString(argument)?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull("Required resource fixture", port)
        assumeTrue(port != null)
        val connection = SshConnection(args.getString("fixtureResourceHost") ?: "127.0.0.1", port!!)
        try {
            connection.connect(10_000) { _, _ -> true }
            assertTrue(connection.authenticate("fixture", object : SshCredentials {}))
            block(connection)
        } finally { connection.close() }
    }

    @Test fun realPlatformCommandsProduceStructuredMetricsAndRates() = fixture { connection ->
        val args = InstrumentationRegistry.getArguments()
        val collector = ServerResourceCollector()
        val first = collector.collect(connection)
        assertEquals(args.getString("fixtureResourcePlatform"), first.platform.name)
        assertFalse(first.system.isNullOrBlank())
        val restricted = args.getString("fixtureResourceRestricted") == "true"
        if (restricted) {
            assertNull(first.cpu)
            assertNull(first.memory)
            assertNull(first.swap)
            assertNull(first.disks)
        } else {
            assertNotNull("CPU counter", first.cpu)
            assertNotNull("Memory", first.memory)
            assertTrue(first.memory!!.totalBytes > 0)
            assertNotNull("Local filesystems", first.disks)
            assertTrue("Readable local filesystem capacity", first.disks!!.any { it.capacity.totalBytes > 0 })
            assertTrue(first.logicalCpuCount!! > 0)
        }
        assertNotNull("Network counters", first.networks)
        if (restricted) first.networks!!.forEach { assertNull(it.isVirtual) }
        else assertTrue("Interface classification", first.networks!!.any { it.isVirtual != null })
        delay(1200)
        val next = collector.collect(connection).snapshot(first, System.currentTimeMillis())
        if (!restricted) assertTrue(next.cpuPercent?.let { it in 0.0..100.0 } == true)
        next.networks!!.forEach { network ->
            assertTrue(network.receivedBytesPerSecond?.let { it >= 0 } == true)
            assertTrue(network.sentBytesPerSecond?.let { it >= 0 } == true)
        }
    }

    @Test fun cancellationOfBlockedExecLeavesTheSharedCarrierUsable() = fixture("fixtureResourceStallPort") { connection ->
        coroutineScope {
            val collector = ServerResourceCollector()
            val query = async(Dispatchers.IO) { collector.collect(connection) }
            delay(300)
            withTimeout(3000) { query.cancelAndJoin() }
            // Opening another channel proves cancellation did not close the shared transport.
            withTimeout(3000) { connection.openChannel().close() }
        }
    }

    @Test fun transportLossReleasesABlockedCollection() = fixture("fixtureResourceStallPort") { connection ->
        coroutineScope {
            val query = async(Dispatchers.IO) { runCatching { ServerResourceCollector().collect(connection) } }
            delay(300)
            connection.close()
            assertTrue(withTimeout(3000) { query.await() }.isFailure)
        }
    }
}
