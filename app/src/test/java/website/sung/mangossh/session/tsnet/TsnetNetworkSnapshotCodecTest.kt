package website.sung.mangossh.session.tsnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TsnetNetworkSnapshotCodecTest {
    @Test
    fun decodesSelfAndOrdersOnlineSshDevicesFirst() {
        val snapshot = TsnetNetworkSnapshotCodec.decode(
            """
            {"self":{"hostName":"mangossh-android-abc","dnsName":"phone.example.ts.net","ips":["100.64.0.1"]},
             "peers":[
              {"id":"n1","hostName":"zeta","dnsName":"zeta.example.ts.net","os":"linux","ips":["100.64.0.2"],"online":true,"lastSeenUnixMs":0,"sshEnabled":false},
              {"id":"n2","hostName":"old","dnsName":"old.example.ts.net","os":"windows","ips":["100.64.0.3"],"online":false,"lastSeenUnixMs":1700000000000,"sshEnabled":true},
              {"id":"n3","hostName":"lab","dnsName":"lab.example.ts.net","os":"linux","ips":["100.64.0.4"],"online":true,"lastSeenUnixMs":0,"sshEnabled":true},
              {"id":"n4","hostName":"Alpha","dnsName":"","os":"macOS","ips":["100.64.0.5"],"online":true,"sshEnabled":false}
             ]}
            """.trimIndent(),
        )

        assertEquals("phone", snapshot.self.displayName)
        assertEquals(listOf("100.64.0.1"), snapshot.self.addresses)
        assertEquals(listOf("lab", "Alpha", "zeta", "old"), snapshot.devices.map { it.displayName })
        val old = snapshot.devices.last()
        assertEquals(1_700_000_000_000L, old.lastSeenEpochMillis)
        assertTrue(old.tailscaleSshEnabled)
        assertEquals("100.64.0.5", snapshot.devices[1].connectHost)
        assertEquals("lab.example.ts.net", snapshot.devices[0].connectHost)
    }

    @Test
    fun selfFallsBackToRequestedHostname() {
        val snapshot = TsnetNetworkSnapshotCodec.decode(
            """{"self":{"hostName":"mangossh-android-abc","dnsName":"","ips":[]},"peers":[]}""",
        )

        assertEquals("mangossh-android-abc", snapshot.self.displayName)
        assertTrue(snapshot.devices.isEmpty())
    }

    @Test
    fun matchesSavedHostnamesByAnyTailnetName() {
        val device = TsnetDevice(
            id = "n1",
            hostName = "lab-server",
            dnsName = "lab.example.ts.net",
            os = "linux",
            addresses = listOf("100.64.0.2", "fd7a:115c:a1e0::2"),
            online = true,
            lastSeenEpochMillis = 0,
            tailscaleSshEnabled = true,
        )

        assertTrue(device.isNamedBy("LAB.example.ts.net."))
        assertTrue(device.isNamedBy("lab"))
        assertTrue(device.isNamedBy("lab-server"))
        assertTrue(device.isNamedBy(" 100.64.0.2 "))
        assertTrue(device.isNamedBy("FD7A:115C:A1E0::2"))
        assertFalse(device.isNamedBy("lab.other.ts.net"))
        assertFalse(device.isNamedBy(""))
    }

    @Test
    fun rejectsMalformedOrOversizedPayloadWithoutEchoingIt() {
        val oversized = "x".repeat(256)
        listOf(
            "not json",
            """{"peers":[]}""",
            """{"self":{"hostName":"$oversized","dnsName":"","ips":[]},"peers":[]}""",
        ).forEach { payload ->
            val error = assertThrows(TsnetSnapshotFormatException::class.java) {
                TsnetNetworkSnapshotCodec.decode(payload)
            }
            assertEquals(null, error.message)
        }
    }
}
