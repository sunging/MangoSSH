package website.sung.mangossh.session.tsnet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    @Test
    fun concurrentLeasesShareOneBackendAndFinalCloseStopsIt() = runBlocking {
        val state = FakeStateStore(enrolled = true)
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
        assertEquals(1, factory.closed.get())
    }

    @Test
    fun authInputIsClearedAndNeverWrittenToStateStore() = runBlocking {
        val state = FakeStateStore(enrolled = false)
        val factory = FakeBackendFactory()
        val manager = EmbeddedTsnetManager(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
    fun enrollmentRequiresForegroundBeforeServiceLaunch() = runBlocking {
        lateinit var manager: EmbeddedTsnetManager
        var phaseAtServiceLaunch: EmbeddedTsnetPhase? = null
        manager = EmbeddedTsnetManager(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
    fun androidNetworkSnapshotUsesPlatformInterfaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = JSONObject(AndroidTsnetNetworkStateSource(context).snapshotJson())
        val interfaces = snapshot.getJSONArray("interfaces")

        assertTrue(interfaces.length() > 0)
        val first = interfaces.getJSONObject(0)
        assertFalse(first.getString("name").isBlank())
        assertTrue(first.has("addrs"))
        assertTrue(snapshot.has("defaultRoute"))
        assertTrue(snapshot.has("defaultGateway"))
    }

    private class FakeStateStore(enrolled: Boolean) : EmbeddedTsnetStateStore {
        val values = linkedMapOf<String, ByteArray>()

        init {
            if (enrolled) values["__marker"] = byteArrayOf(1)
        }

        override fun readState(key: String): ByteArray = values[key]?.copyOf() ?: ByteArray(0)

        override fun writeState(key: String, value: ByteArray) {
            values[key] = value.copyOf()
        }

        override fun nodeName(): String = "mangossh-android-00000000"

        override fun hasEnrolledIdentity(): Boolean = "__marker" in values

        override fun markEnrolled() {
            values["__marker"] = byteArrayOf(1)
        }

        override fun clearIdentity() {
            values.clear()
        }
    }

    private class FakeBackendFactory(
        private val transientLoginBeforeRunning: Boolean = false,
        private val remainWaitingForLogin: Boolean = false,
    ) : EmbeddedTsnetBackendFactory {
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val authKeyWasNonEmpty = AtomicBoolean()

        override fun create(
            stateDirectory: String,
            hostname: String,
            store: StateStore,
            listener: StatusListener,
        ): EmbeddedTsnetBackend {
            created.incrementAndGet()
            return object : EmbeddedTsnetBackend {
                override fun start(authKey: String) {
                    authKeyWasNonEmpty.set(authKey.isNotEmpty())
                    if (remainWaitingForLogin) {
                        listener.onStatus("needs_login", "")
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
