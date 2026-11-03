package website.sung.mangossh.session

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test
    fun recognizesLocalRangesAndTheirBoundariesWithoutBlockingLoopbackOrPublicAddresses() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1", "169.254.0.1",
            "100.64.0.1", "100.127.255.254", "224.0.0.1", "255.255.255.255", "0.0.0.0",
            "fe80::1", "fc00::1", "fd00::1", "ff02::1").forEach {
            assertTrue(it, isLocalNetworkAddress(ip(it)))
        }
        listOf("127.0.0.1", "::1", "192.0.2.1", "172.15.255.254", "172.32.0.1",
            "100.63.255.254", "100.128.0.1", "2001:db8::1").forEach {
            assertFalse(it, isLocalNetworkAddress(ip(it)))
        }
    }

    @Test
    fun recognizesGlobalIpv6OnTheLocalInterfaceIncludingNonByteAlignedPrefixes() {
        val prefixes = listOf(LocalNetworkPrefix(ip("2001:db8:1:2::1"), 64))
        assertTrue(isLocalNetworkAddress(ip("2001:db8:1:2::abcd"), prefixes))
        assertFalse(isLocalNetworkAddress(ip("2001:db8:1:3::1"), prefixes))
        val narrow = LocalNetworkPrefix(ip("2001:db8::8000"), 113)
        assertTrue(narrow.contains(ip("2001:db8::ffff")))
        assertFalse(narrow.contains(ip("2001:db8::7fff")))
        assertFalse(narrow.contains(ip("192.0.2.1")))
    }

    @Test
    fun oldAndroidAndExistingGrantsNeverResolveOrPrompt() = runBlocking {
        for ((sdk, granted) in listOf(36 to false, 37 to true)) {
            LocalNetworkPermissionGate(sdk, { granted }, resolve = { error("Unexpected DNS") })
                .requireAccess("host.invalid") { error("Unexpected prompt") }
        }
    }

    @Test
    fun requestsMdnsPermissionBeforeTryingToResolveTheName() = runBlocking {
        var granted = false
        var prompts = 0
        val gate = LocalNetworkPermissionGate(37, { granted }, resolve = { error("mDNS before grant") })
        gate.requireAccess("server.LOCAL.") { prompts++; granted = true; true }
        assertEquals(1, prompts)
    }

    @Test
    fun resolvesOrdinaryDnsNamesAndRequestsOnlyForLanResults() = runBlocking {
        var prompts = 0
        val public = LocalNetworkPermissionGate(37, { false }, resolve = { listOf(ip("192.0.2.1")) })
        public.requireAccess("public.invalid") { error("Public destinations do not need this grant") }
        val local = LocalNetworkPermissionGate(37, { false }, resolve = {
            listOf(ip("192.0.2.1"), ip("192.168.0.1"))
        })
        assertThrows(LocalNetworkPermissionDeniedException::class.java) {
            runBlocking { local.requireAccess("lan.invalid") { prompts++; false } }
        }
        assertEquals(1, prompts)
    }

    @Test
    fun deniedOrStaleUiAnswersCannotAuthorizeTheConnection() = runBlocking {
        for (answer in listOf(false, true)) {
            val gate = LocalNetworkPermissionGate(37, { false }, resolve = { listOf(ip("10.0.0.1")) })
            val failure = assertThrows(LocalNetworkPermissionDeniedException::class.java) {
                runBlocking { gate.requireAccess("lan.invalid") { answer } }
            }
            assertNull(failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun rechecksRevocationOnTheNextAttemptAndAllowsRetryAfterSettingsGrant() = runBlocking {
        var granted = true
        var prompts = 0
        val gate = LocalNetworkPermissionGate(37, { granted }, resolve = { listOf(ip("10.0.0.1")) })
        gate.requireAccess("lan.invalid") { error("Already granted") }
        granted = false
        assertThrows(LocalNetworkPermissionDeniedException::class.java) {
            runBlocking { gate.requireAccess("lan.invalid") { prompts++; false } }
        }
        granted = true
        gate.requireAccess("lan.invalid") { error("Granted in Settings") }
        assertEquals(1, prompts)
    }

    @Test
    fun leavesDnsFailuresToTheTransportAndDoesNotPromptForLoopback() = runBlocking {
        LocalNetworkPermissionGate(37, { false }, resolve = { throw UnknownHostException() })
            .requireAccess("missing.invalid") { error("A grant cannot fix DNS failure") }
        LocalNetworkPermissionGate(37, { false }, resolve = { listOf(ip("127.0.0.1"), ip("::1")) },
            localPrefixes = { emptyList() })
            .requireAccess("localhost") { error("Loopback does not need this grant") }
    }

    @Test
    fun cancellingAnOperationWhileTheSystemPromptIsOpenDoesNotContinueTheConnection() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        var connected = false
        val gate = LocalNetworkPermissionGate(37, { false }, resolve = { listOf(ip("10.0.0.1")) })
        val operation = async {
            gate.requireAccess("lan.invalid") { requested.complete(Unit); answer.await() }
            connected = true
        }
        requested.await()
        operation.cancelAndJoin()
        answer.complete(true)
        assertTrue(operation.isCancelled)
        assertFalse(connected)
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
