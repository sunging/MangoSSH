package website.sung.mangossh.session.tsnet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tsnetbridge.StateStore
import tsnetbridge.StatusListener
import website.sung.mangossh.data.tsnet.EmbeddedTsnetStateStore

@RunWith(AndroidJUnit4::class)
class EmbeddedTsnetManagerInstrumentedTest {
    private val directories = mutableListOf<java.io.File>()
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()
    private fun isolatedScope() = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).also { scopes += it }
    private fun isolatedContext(): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(base.cacheDir, "tsnet-manager-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        directories += directory
        return object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
    }
    @org.junit.After fun removeTestDirectories() = runBlocking {
        scopes.forEach { scope -> scope.coroutineContext[kotlinx.coroutines.Job]?.let { it.cancel(); it.join() } }
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun concurrentLeasesShareOneBackendAndFinalCloseStopsIt() = runBlocking {
        val state = FakeStateStore(enrolled = true)
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = state,
            backendFactory = factory,
            foregroundStarter = {},
        )

        val firstRequest = async { manager.acquire() }
        val secondRequest = async { manager.acquire() }
        val first = firstRequest.await()
        val second = secondRequest.await()
        assertEquals(1, factory.created.get())
        assertEquals(2, manager.status.value.activeSessions)

        first.close()
        withTimeout(5_000) { manager.status.first { it.activeSessions == 1 } }
        second.close()
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }
        assertEquals(1, factory.closed.get())
    }

    @Test
    fun enrolledIdentitySurvivesTransientNeedsLoginDuringRestart() = runBlocking {
        val state = FakeStateStore(enrolled = true)
        val factory = FakeBackendFactory(transientLoginBeforeRunning = true)
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = state,
            backendFactory = factory,
            foregroundStarter = {},
        )

        val lease = manager.acquire()

        assertEquals(EmbeddedTsnetPhase.ACTIVE, manager.status.value.phase)
        assertEquals(1, factory.created.get())
        assertEquals(0, factory.closed.get())
        lease.close()
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }
        withTimeout(5_000) {
            while (factory.closed.get() == 0) delay(10)
        }
        assertEquals(1, factory.closed.get())
    }

    @Test
    fun authInputIsClearedAndNeverWrittenToStateStore() = runBlocking {
        val state = FakeStateStore(enrolled = false)
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = state,
            backendFactory = factory,
            foregroundStarter = {},
        )
        val input = charArrayOf('x', 'y')

        manager.beginAuthKeyEnrollment(input)
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }

        assertTrue(input.all { it == '\u0000' })
        assertTrue(factory.authKeyWasNonEmpty.get())
        assertEquals(setOf("__marker"), state.values.keys)
    }

    @Test
    fun controlServerIsBoundToTheIdentityAndClearedOnLogout() = runBlocking {
        val state = FakeStateStore(enrolled = false)
        // Keys a half-finished enrollment presented to the default server.
        state.values["_machinekey"] = byteArrayOf(7)
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = state,
            backendFactory = factory,
            foregroundStarter = {},
        )

        manager.beginBrowserEnrollment("https://Headscale.example.com/")
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }
        assertEquals(listOf("https://headscale.example.com"), factory.controlUrls)
        assertEquals("https://headscale.example.com", manager.controlUrl.value)
        assertFalse(state.values.containsKey("_machinekey"))

        // Re-authenticating a registered node never moves it to another server.
        manager.beginBrowserEnrollment("https://other.example.com")
        assertEquals("https://headscale.example.com", factory.controlUrls.last())
        assertEquals("https://headscale.example.com", state.controlUrl())
        // Let the re-authenticated node report running and detach, as the UI
        // only offers logout once the node is idle or active.
        withTimeout(5_000) { while (factory.closed.get() != 2) delay(10) }
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }

        // Logout is offered on the settings page, which keeps the node up while visible.
        manager.setDeviceBrowsing(true)
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.ACTIVE } }
        manager.logout()
        assertEquals("", manager.controlUrl.value)
        assertEquals("", state.controlUrl())
    }

    @Test
    fun cleartextControlServerIsRefusedBeforeStarting() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = false),
            backendFactory = factory,
            foregroundStarter = {},
        )

        assertTrue(runCatching { manager.beginBrowserEnrollment("http://headscale.example.com") }.isFailure)
        assertEquals(0, factory.created.get())
    }

    @Test
    fun enrollmentRequiresForegroundBeforeServiceLaunch() = runBlocking {
        lateinit var manager: EmbeddedTsnetManager
        var phaseAtServiceLaunch: EmbeddedTsnetPhase? = null
        manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = false),
            backendFactory = FakeBackendFactory(),
            foregroundStarter = { phaseAtServiceLaunch = manager.status.value.phase },
        )

        manager.beginBrowserEnrollment()

        assertEquals(EmbeddedTsnetPhase.STARTING, phaseAtServiceLaunch)
    }

    @Test
    fun enrollmentRollsBackWhenForegroundServiceCannotStart() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = false),
            backendFactory = factory,
            foregroundStarter = { throw IllegalStateException() },
        )

        val failure = runCatching { manager.beginBrowserEnrollment() }

        assertTrue(failure.isFailure)
        assertEquals(EmbeddedTsnetPhase.FAILED, manager.status.value.phase)
        assertEquals(0, factory.created.get())
        assertFalse(manager.foregroundRequired.value)
    }

    @Test
    fun foregroundPromotionFailureStopsEnrollmentRuntime() = runBlocking {
        val factory = FakeBackendFactory(remainWaitingForLogin = true)
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = false),
            backendFactory = factory,
            foregroundStarter = {},
        )
        manager.beginBrowserEnrollment()
        withTimeout(5_000) {
            manager.status.first { it.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN }
        }

        manager.onForegroundServiceUnavailable()

        withTimeout(5_000) {
            manager.status.first { it.phase == EmbeddedTsnetPhase.FAILED }
        }
        withTimeout(5_000) {
            while (factory.closed.get() != 1) delay(10)
        }
        assertFalse(manager.foregroundRequired.value)
    }

    @Test
    fun deviceBrowsingRunsEnrolledNodeWithoutForegroundAndStopsAfterRelease() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = true),
            backendFactory = factory,
            foregroundStarter = { error("Browsing must not start the foreground service") },
        )

        manager.setDeviceBrowsing(true)
        val network = withTimeout(5_000) { manager.network.first { it != null } }

        assertEquals(listOf("lab"), network!!.devices.map { it.displayName })
        assertEquals(EmbeddedTsnetPhase.ACTIVE, manager.status.value.phase)
        assertEquals(0, manager.status.value.activeSessions)
        assertFalse(manager.foregroundRequired.value)

        manager.setDeviceBrowsing(false)
        withTimeout(10_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }
        assertEquals(null, manager.network.value)
        // The detached backend is closed just after the status changes, outside the lock.
        withTimeout(5_000) { while (factory.closed.get() == 0) delay(10) }
        assertEquals(1, factory.closed.get())
    }

    @Test
    fun browsingAgainWithinTheGraceDelayKeepsTheSameNode() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = true),
            backendFactory = factory,
            foregroundStarter = {},
        )
        manager.setDeviceBrowsing(true)
        withTimeout(5_000) { manager.network.first { it != null } }

        manager.setDeviceBrowsing(false)
        val lease = manager.acquire()
        manager.setDeviceBrowsing(true)
        lease.close()
        withTimeout(5_000) { manager.status.first { it.activeSessions == 0 } }
        delay(6_000)

        assertEquals(EmbeddedTsnetPhase.ACTIVE, manager.status.value.phase)
        assertEquals(1, factory.created.get())
        assertEquals(0, factory.closed.get())
    }

    @Test
    fun statusStaysUnresolvedUntilTheStoredIdentityIsRead() = runBlocking {
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = true),
            backendFactory = FakeBackendFactory(),
            foregroundStarter = {},
        )

        val resolved = withTimeout(5_000) { manager.status.first { it.identityResolved } }

        assertEquals(EmbeddedTsnetPhase.READY_IDLE, resolved.phase)
    }

    @Test
    fun registeredNodeLoadingItsStateNeverLooksSignedOut() = runBlocking {
        val factory = FakeBackendFactory(transientLoginBeforeRunning = true)
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = true),
            backendFactory = factory,
            foregroundStarter = {},
        )
        withTimeout(5_000) { manager.status.first { it.identityResolved } }
        val phases = java.util.concurrent.CopyOnWriteArrayList<EmbeddedTsnetPhase>()
        val recorder = launch(kotlinx.coroutines.Dispatchers.IO) { manager.status.collect { phases += it.phase } }

        manager.setDeviceBrowsing(true)
        withTimeout(5_000) { manager.network.first { it != null } }
        manager.setDeviceBrowsing(false)
        withTimeout(10_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.READY_IDLE } }
        recorder.cancel()

        assertTrue(EmbeddedTsnetPhase.STARTING in phases)
        assertFalse(EmbeddedTsnetPhase.WAITING_FOR_LOGIN in phases)
        assertFalse(EmbeddedTsnetPhase.UNENROLLED in phases)
    }

    @Test
    fun registeredNodeAskingForAuthorizationStillWaitsForLoginWithoutOpeningBrowser() = runBlocking {
        val factory = FakeBackendFactory(
            remainWaitingForLogin = true,
            loginUrl = "https://login.tailscale.com/a/test",
        )
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = true),
            backendFactory = factory,
            foregroundStarter = {},
        )
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = launch(kotlinx.coroutines.Dispatchers.IO) { manager.authorizationUrls.collect { urls += it } }

        manager.setDeviceBrowsing(true)
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.WAITING_FOR_LOGIN } }
        delay(200)
        recorder.cancel()

        assertTrue("Browsing must not open a browser", urls.isEmpty())
        assertFalse(manager.foregroundRequired.value)
    }

    @Test
    fun browsingNeverStartsAnUnenrolledNode() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = isolatedContext(),
            scope = isolatedScope(),
            stateStore = FakeStateStore(enrolled = false),
            backendFactory = factory,
            foregroundStarter = {},
        )

        manager.setDeviceBrowsing(true)
        delay(500)

        assertEquals(0, factory.created.get())
        assertEquals(EmbeddedTsnetPhase.UNENROLLED, manager.status.value.phase)
    }

    @Test
    fun androidNetworkSnapshotUsesPlatformInterfaces() {
        val context = isolatedContext()
        val snapshot = JSONObject(AndroidTsnetNetworkStateSource(context).snapshotJson())
        val interfaces = snapshot.getJSONArray("interfaces")

        assertTrue(interfaces.length() > 0)
        val first = interfaces.getJSONObject(0)
        assertFalse(first.getString("name").isBlank())
        assertTrue(first.has("addrs"))
        assertTrue(snapshot.has("defaultRoute"))
        assertTrue(snapshot.has("defaultGateway"))
    }

    @Test fun oldBackendFailureAndReleaseCannotRetireNewLease() = runBlocking {
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(context = isolatedContext(), scope = isolatedScope(),
            stateStore = FakeStateStore(true), backendFactory = factory, foregroundStarter = {})
        val old = manager.acquire()
        factory.listeners[0].onStatus("failed", "")
        withTimeout(5_000) { old.invalidated.await() }
        withTimeout(5_000) { manager.status.first { it.phase == EmbeddedTsnetPhase.FAILED } }
        val fresh = manager.acquire()
        old.close()
        old.close()
        factory.listeners[0].onStatus("stopped", "")
        val sibling = manager.acquire()
        assertEquals(2, manager.status.value.activeSessions)
        assertFalse(fresh.invalidated.isCompleted)
        fresh.close()
        sibling.close()
        withTimeout(5_000) { while (factory.closed.get() != 2) delay(10) }
        assertEquals(0, manager.status.value.activeSessions)
    }

    private class FakeStateStore(enrolled: Boolean) : EmbeddedTsnetStateStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        init {
            if (enrolled) values["__marker"] = byteArrayOf(1)
        }

        override fun readState(key: String): ByteArray = values[key]?.copyOf() ?: ByteArray(0)

        override fun writeState(key: String, value: ByteArray) {
            values[key] = value.copyOf()
        }

        override fun nodeName(): String = "mangossh-android-00000000"

        override fun hasEnrolledIdentity(): Boolean = values.containsKey("__marker")

        override fun markEnrolled() {
            values["__marker"] = byteArrayOf(1)
        }

        override fun controlUrl(): String = values["__control_url"]?.decodeToString().orEmpty()

        override fun setControlUrl(value: String) {
            if (value.isEmpty()) values.remove("__control_url") else values["__control_url"] = value.encodeToByteArray()
        }

        override fun clearIdentity() {
            values.clear()
        }
    }

    private class FakeBackendFactory(
        private val transientLoginBeforeRunning: Boolean = false,
        private val remainWaitingForLogin: Boolean = false,
        private val loginUrl: String = "",
    ) : EmbeddedTsnetBackendFactory {
        val listeners = java.util.concurrent.CopyOnWriteArrayList<StatusListener>()
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val authKeyWasNonEmpty = AtomicBoolean()
        val controlUrls = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun create(
            stateDirectory: String,
            hostname: String,
            controlUrl: String,
            store: StateStore,
            listener: StatusListener,
        ): EmbeddedTsnetBackend {
            listeners += listener
            controlUrls += controlUrl
            created.incrementAndGet()
            return object : EmbeddedTsnetBackend {
                override fun start(authKey: String) {
                    authKeyWasNonEmpty.set(authKey.isNotEmpty())
                    if (remainWaitingForLogin) {
                        listener.onStatus("needs_login", loginUrl)
                        return
                    }
                    if (transientLoginBeforeRunning) {
                        listener.onStatus("needs_login", "")
                        Thread.sleep(100)
                    }
                    listener.onStatus("running", "")
                }

                override fun socksAddress(): String = "127.0.0.1:1"

                override fun socksSecret(): String = "runtime-only"

                override fun startUdpRelay(host: String, port: Int): EmbeddedTsnetUdpRelay =
                    object : EmbeddedTsnetUdpRelay {
                        override val localPort: Int = 1
                        override fun close() = Unit
                    }

                override fun networkSnapshotJson(): String =
                    """{"self":{"hostName":"$hostname","dnsName":"$hostname.example.ts.net","ips":["100.64.0.1"]},""" +
                        """"peers":[{"id":"n1","hostName":"lab","dnsName":"lab.example.ts.net","os":"linux",""" +
                        """"ips":["100.64.0.2"],"online":true,"lastSeenUnixMs":0,"sshEnabled":true}]}"""

                override fun logout() {
                    listener.onStatus("stopped", "")
                }

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }
    }
}
