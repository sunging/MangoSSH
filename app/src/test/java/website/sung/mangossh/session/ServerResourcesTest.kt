package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test

class ServerResourcesTest {
    private val connection = Any()

    @Test fun linuxParsesBinaryCapacityReservedSpaceAndMountpointsWithSpaces() {
        val sample = ServerResourceParser.linux(linux(), connection)
        assertEquals("Example Linux", sample.system)
        assertEquals(2, sample.logicalCpuCount)
        assertEquals(600L, sample.cpu!!.total) // guest fields must not be counted twice.
        assertEquals(ResourceCapacity(8192 * 1024, 6144 * 1024, 2048 * 1024), sample.memory)
        assertEquals(0L, sample.swap!!.totalBytes)
        assertEquals("/data volume", sample.disks!!.single().name)
        assertEquals(ResourceCapacity(100L * 1024, 60L * 1024, 30L * 1024), sample.disks.single().capacity)
        assertEquals(listOf("eth0:2"), sample.networks!!.map { it.id })
    }

    @Test fun partialMetricsDoNotBecomeZeroOrEraseValidSystemData() {
        val sample = ServerResourceParser.linux(linux().replace("MemAvailable: 2048 kB", "Unused: 2048 kB")
            .replace("@@disk_status\n0", "@@disk_status\n1").replace("@@network_status\n0", "@@network_status\n1"), connection)
        assertNull(sample.memory)
        assertNull(sample.disks)
        assertNull(sample.networks)
        assertNotNull(sample.cpu)
        assertEquals("Example Linux", sample.system)
    }

    @Test fun busyBoxDiskRowsKeepLargeCountersAndMountNamesWithSpaces() {
        val rows = "/dev/example 104857600 52428800 47185920 53% /data volume\n" +
            "overlay                   209715200 100000000 100000000 50% /container data"
        val parsed = ServerResourceParser.linux(linux().replace("/dev/example 100 60 30 67% /data volume", rows), connection)
        assertEquals(listOf("/data volume", "/container data"), parsed.disks!!.map { it.name })
        assertEquals(ResourceCapacity(107374182400, 53687091200, 48318382080), parsed.disks.first().capacity)
    }

    @Test fun windowsHandlesCrLfBomAndExact64BitCounters() {
        val sample = ServerResourceParser.windows("\uFEFF" + windows().replace("\n", "\r\n"), connection)
        assertEquals(ServerResourcePlatform.WINDOWS, sample.platform)
        assertEquals(9007199254740993L, sample.networks!!.single().received)
        assertEquals("Ethernet example", sample.networks.single().name)
        assertEquals(ResourceCapacity(4096, 3072, 1024), sample.memory)
        assertEquals(0, sample.disks!!.size)
        assertNull(sample.swap)
    }

    @Test fun ratesUseRemoteElapsedTimeAndFirstSampleIsExplicitlySampling() {
        val old = sample()
        val first = old.snapshot(null, 1)
        assertTrue(first.cpuSampling)
        assertNull(first.cpuPercent)
        assertNull(first.networks!!.single().receivedBytesPerSecond)
        val next = old.copy(cpu = ResourceCpuCounter(200, 75), networkTimeSeconds = 15.0,
            networks = listOf(ResourceNetworkCounter("a", "Adapter", 6120, 11240, true)))
        val result = next.snapshot(old, 2)
        assertEquals(75.0, result.cpuPercent!!, 0.001)
        assertEquals(1024.0, result.networks!!.single().receivedBytesPerSecond!!, 0.001)
        assertEquals(2048.0, result.networks.single().sentBytesPerSecond!!, 0.001)
        assertEquals(true, result.networks.single().isVirtual)
    }

    @Test fun linuxInterfaceKindUsesKernelMetadataAndPreservesUnknownKinds() {
        for ((flag, expected) in listOf("1" to true, "0" to false, "unknown" to null, null to null)) {
            val input = if (flag == null) linux() else linux().replace("eth0\t2", "eth0\t2\t$flag")
            val network = ServerResourceParser.linux(input, connection).networks!!.single()
            assertEquals("eth0", network.name)
            assertEquals(expected, network.isVirtual)
        }
    }

    @Test fun windowsInterfaceKindIsOptionalAndOnlyAcceptsBooleans() {
        for ((flag, expected) in listOf("true" to true, "false" to false, "null" to null, "\"true\"" to null, null to null)) {
            val input = if (flag == null) windows() else windows().replace("\"received\":", "\"virtual\":$flag,\"received\":")
            val network = ServerResourceParser.windows(input, connection).networks!!.single()
            assertEquals("Ethernet example", network.name)
            assertEquals(expected, network.isVirtual)
        }
    }

    @Test fun replacementRebootRollbackAndInterfaceChangeResetBaseline() {
        val old = sample()
        listOf(old.copy(connectionIdentity = Any()), old.copy(uptimeSeconds = 1.0),
            old.copy(cpu = ResourceCpuCounter(10, 5), networks = listOf(ResourceNetworkCounter("a", "Adapter", 1, 1))),
            old.copy(networks = listOf(ResourceNetworkCounter("b", "Adapter", 5000, 6000))))
            .forEach { changed ->
                val snapshot = changed.snapshot(old, 2)
                assertNull(snapshot.cpuPercent)
                assertNull(snapshot.networks!!.single().receivedBytesPerSecond)
            }
    }

    @Test fun unavailableAndEmptyCollectionsRemainDifferent() {
        val denied = ServerResourceParser.windows(frame("""{"v":1,"platform":"windows","os":"Example Windows"}"""), connection)
        val empty = ServerResourceParser.windows(frame("""{"v":1,"platform":"windows","net":[],"disks":[],"swap":{"total":0,"available":0}}"""), connection)
        assertNull(denied.networks)
        assertNull(denied.swap)
        assertEquals(emptyList<ResourceNetworkCounter>(), empty.networks)
        assertEquals(0L, empty.swap!!.totalBytes)
    }

    @Test fun malformedOrTruncatedFramesNeverExposePeerTextInErrors() {
        for (text in listOf("untrusted response", frame("{"), windows().substringBefore(ServerResourceParser.END))) {
            val error = assertThrows(ResourceQueryException::class.java) { ServerResourceParser.windows(text, connection) }
            assertNull(error.message)
        }
    }

    @Test fun collectionsWithOnlyMissingCountersAreUnavailableRatherThanEmpty() {
        val windows = ServerResourceParser.windows(frame("""{"v":1,"platform":"windows","disks":[{"name":"C:","total":null,"available":null}],"net":[{"id":"a","name":"Adapter","received":null,"sent":"1"}]}"""), connection)
        assertNull(windows.disks)
        assertNull(windows.networks)
        val linux = ServerResourceParser.linux(linux().replace("eth0: 1000", "eth0: invalid"), connection)
        assertNull(linux.networks)
    }

    @Test fun invalidNumericValuesAreUnavailable() {
        val parsed = ServerResourceParser.windows(frame("""{"v":1,"platform":"windows","uptime":"NaN","memory":{"total":10,"available":20},"cpu":{"total":"-1","idle":2}}"""), connection)
        assertNull(parsed.memory)
        assertNull(parsed.cpu)
        assertNull(parsed.uptimeSeconds)
    }

    private fun sample() = ServerResourceSample(connection, ServerResourcePlatform.LINUX, uptimeSeconds = 100.0,
        cpu = ResourceCpuCounter(100, 50), networkTimeSeconds = 10.0,
        networks = listOf(ResourceNetworkCounter("a", "Adapter", 1000, 1000)))

    companion object {
        fun frame(body: String) = "${ServerResourceParser.START}\n$body\n${ServerResourceParser.END}\n"
        fun windows() = frame("""{"v":1,"platform":"windows","os":"Example Windows","cores":8,"uptime":123.5,"cpu":{"total":"1000","idle":"500"},"memory":{"total":4096,"available":1024},"disks":[],"netTime":10,"net":[{"id":"adapter-example","name":"Ethernet example","received":"9007199254740993","sent":"9007199254740994"}]}""")
        fun linux() = frame("""
            @@platform
            Linux
            @@os
            PRETTY_NAME="Example Linux"
            @@uptime
            100.5 200.0
            @@cpu
            cpu 100 0 100 300 100 0 0 0 20 5
            cpu0 50 0 50 150 50 0 0 0
            cpu1 50 0 50 150 50 0 0 0
            @@load
            0.10 0.20 0.30 1/20 1
            @@memory
            MemTotal: 8192 kB
            MemAvailable: 2048 kB
            SwapTotal: 0 kB
            SwapFree: 0 kB
            @@network
            eth0: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0
            lo: 9999 0 0 0 0 0 0 0 9999 0 0 0 0 0 0 0
            @@network_status
            0
            @@links
            eth0\t2
            @@links_status
            0
            @@disk
            Filesystem 1024-blocks Used Available Capacity Mounted on
            /dev/example 100 60 30 67% /data volume
            @@disk_status
            0
        """.trimIndent().replace("\\t", "\t"))
    }
}
