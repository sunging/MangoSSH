package website.sung.mangossh.session

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.connectbot.sshlib.transport.KtorTcpTransportFactory
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportFactory
import website.sung.mangossh.data.settings.*
import website.sung.mangossh.data.vault.*
import website.sung.mangossh.domain.*
import website.sung.mangossh.presentation.AppLockCoordinator
import website.sung.mangossh.security.*
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.tsnet.EmbeddedTsnetManager

/** Real SSH hops and SOCKS traffic; all profiles, PINs, aliases and servers are disposable. */
class JumpSocksLockInstrumentedTest {
    private class Fixture(val controller: SshSessionController, val profile: ConnectionProfile,
        val lock: AppLockCoordinator, val pin: String, val connections: List<SshConnection>,
        val transports: List<Transport>, val trusts: List<String>, val echoPort: Int) {
        val rule = PortForwardRule(UUID.randomUUID().toString(), profile.id, PortForwardType.DYNAMIC,
            bindPort = ServerSocket(0).use { it.localPort })

        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun idle() { withTimeout(15_000) { lock.busy.first { !it } } }
        suspend fun approve() { main { lock.verifySensitiveAction(pin) }; idle() }
        fun start(preferred: String? = null) {
            if (!lock.authorizeSensitive(profile.requireReauthentication) { start(preferred) }) return
            if (preferred == null) controller.startPortForwardOnNewConnection(profile, rule)
            else controller.startPortForward(preferred, rule)
        }
        suspend fun active() = try {
            withTimeout(25_000) {
                controller.portForwards.first { states -> states.any { it.rule.id == rule.id && it.phase == PortForwardRuntimePhase.ACTIVE } }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("Synthetic forward timed out: network=${controller.network.value.health}, " +
                "states=${controller.portForwards.value.map { it.phase }}, sessions=${controller.sessions.value.map { it.phase }}, " +
                "prompts=${controller.prompts.value.map { it.javaClass.simpleName }}, locked=${lock.locked.value}")
        }
        fun client(domain: Boolean = false, destination: Int = echoPort, accepted: Boolean = true): Socket {
            val socket = Socket("127.0.0.1", rule.bindPort)
            try {
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                val address = if (domain) "localhost".encodeToByteArray().let { byteArrayOf(3, it.size.toByte()) + it }
                    else byteArrayOf(1, 127, 0, 0, 1)
                output.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((destination shr 8).toByte(), destination.toByte()))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                val reply = input.readUnsignedByte()
                if (!accepted) { assertNotEquals(0, reply); return socket }
                assertEquals(0, reply)
                input.readUnsignedByte()
                val length = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Invalid synthetic SOCKS response") }
                input.readFully(ByteArray(length + 2))
                return socket
            } catch (failure: Throwable) { socket.close(); throw failure }
        }
        fun echo(socket: Socket) {
            val bytes = ByteArray(1024).also(SecureRandom()::nextBytes)
            socket.getOutputStream().write(bytes)
            val result = ByteArray(bytes.size)
            DataInputStream(socket.getInputStream()).readFully(result)
            // Never include payloads in assertion diagnostics.
            assertTrue(bytes.contentEquals(result))
        }
        suspend fun stopAndVerifyClosed() {
            controller.stopPortForward("", rule.id)
            withTimeout(15_000) {
                controller.portForwards.first { states -> states.any { it.rule.id == rule.id && it.phase == PortForwardRuntimePhase.STOPPED } }
                while (runCatching { Socket("127.0.0.1", rule.bindPort).use {} }.isSuccess) delay(30)
            }
        }
    }

    private fun fixture(hops: Int = 0, rejectTrust: Int = 0, cancelHopAuthentication: Boolean = false,
        block: suspend Fixture.() -> Unit) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        val echoPort = args.getString("fixtureEchoPort")?.toIntOrNull()
        val jumpPort = args.getString("fixtureJumpPort")?.toIntOrNull()
        val secondPort = args.getString("fixtureJump2Port")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") {
            assertNotNull(port); assertNotNull(echoPort)
            if (hops > 0) assertNotNull(jumpPort)
            if (hops > 1) assertNotNull(secondPort)
        }
        assumeTrue(port != null && echoPort != null && (hops == 0 || jumpPort != null) && (hops < 2 || secondPort != null))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "jump-socks-lock-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val preferences = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
        }
        val vault = VaultRepository(context, AndroidKeystoreVault(context, prefix))
        vault.open()
        val host = args.getString("fixtureHost") ?: "127.0.0.1"
        val route = (1..hops).map { "hop-$it" }
        val profile = ConnectionProfile(UUID.randomUUID().toString(), "Disposable target",
            hostname = if (hops == 0) host else "127.0.0.1", port = port!!, username = "fixture", jumpProfileIds = route)
        for ((index, id) in route.withIndex()) assertTrue(vault.upsertProfile(profile.copy(id = id,
            label = "Disposable hop", hostname = if (index == 0) host else "127.0.0.1",
            port = if (index == 0) jumpPort!! else secondPort!!, jumpProfileIds = emptyList(),
            username = if (cancelHopAuthentication && index == hops - 1) "fixture-otp" else "fixture")).isSuccess)
        assertTrue(vault.upsertProfile(profile).isSuccess)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val access = AppAccessState(false)
        val lock = AppLockCoordinator(AppLockStore(context), access, scope, {}, {})
        val pin = CharArray(6) { ('0'.code + SecureRandom().nextInt(10)).toChar() }.concatToString()
        withContext(Dispatchers.Main) { lock.configurePin(pin) }
        withTimeout(15_000) { lock.busy.first { !it } }
        val connections = CopyOnWriteArrayList<SshConnection>()
        val transports = CopyOnWriteArrayList<Transport>()
        val trusts = CopyOnWriteArrayList<String>()
        val controller = SshSessionController(context, vault, embeddedTsnetManager = EmbeddedTsnetManager(context),
            terminalAppearanceStore = TerminalAppearanceStore(context), terminalBehaviorStore = TerminalBehaviorStore(context),
            connectionPreferencesStore = ConnectionPreferencesStore(context), appForegroundState = AppForegroundState.fixed(false),
            accessState = access, foregroundStarter = {}, connectionFactory = { target ->
                SshConnection(target.hostname, target.port, customTransport = if (hops == 0) TransportFactory {
                    KtorTcpTransportFactory(target.hostname, target.port).create().also(transports::add)
                } else null).also(connections::add)
            })
        val prompts = launch(Dispatchers.IO) {
            val seen = mutableSetOf<String>()
            controller.prompts.collect { values -> values.forEach { prompt ->
                if (seen.add(prompt.requestId)) when (prompt) {
                    is SessionPrompt.HostKeyVerification -> {
                        trusts += prompt.requestId
                        controller.respondToPrompt(prompt.requestId, if (trusts.size == rejectTrust) null else listOf("trust"))
                    }
                    is SessionPrompt.Authentication -> if (cancelHopAuthentication) controller.respondToPrompt(prompt.requestId, null)
                    else -> Unit
                }
            } }
        }
        val recovery = launch(Dispatchers.Main) {
            fun authorize(request: ForwardAuthorizationRequest) {
                if (controller.forwardAuthorization.value.none { it.id == request.id }) return
                if (lock.authorizeSensitive(profile.requireReauthentication,
                        onCancelled = { controller.respondToForwardAuthorization(request.id, false) }) { authorize(request) })
                    controller.respondToForwardAuthorization(request.id, true)
            }
            var seen: String? = null
            controller.forwardAuthorization.collect { values -> values.firstOrNull()?.let { if (seen != it.id) { seen = it.id; authorize(it) } } }
        }
        try {
            val fixture = Fixture(controller, profile, lock, pin, connections, transports, trusts, echoPort!!)
            assertTrue(vault.upsertPortForward(fixture.rule).isSuccess)
            withTimeout(90_000) { fixture.block() }
        }
        finally {
            withContext(Dispatchers.Main) { lock.cancelReauthentication() }
            controller.clear(); prompts.cancelAndJoin(); recovery.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            connections.forEach { it.close() }
            preferences.forEach { name ->
                base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                base.deleteSharedPreferences(name)
            }
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(prefix) }
        }
    }

    @Test fun singleJumpSocksRequiresPinAndKeepsEstablishedTrafficDuringLock() = fixture(hops = 1) {
        main { lock.setReauthentication(ReauthenticationMode.EVERY_ACTION); start() }
        assertTrue(lock.reauthenticationPending.value)
        assertTrue(connections.isEmpty())
        val wrong = pin.map { if (it == '9') '0' else it + 1 }.joinToString("")
        main { lock.verifySensitiveAction(wrong) }; idle()
        assertTrue(connections.isEmpty())
        approve(); active()
        assertEquals(2, trusts.size)
        client().use { socket ->
            echo(socket)
            main { lock.lock() }
            echo(socket)
            main { lock.unlockWithPin(pin) }; idle()
        }
        client(domain = true).use(::echo)
        client(destination = 1, accepted = false).close()
        client().use(::echo)
        stopAndVerifyClosed()
    }

    @Test fun twoJumpTerminalSharesItsCarrierWithSocksAndCleansEveryHop() = fixture(hops = 2) {
        val id = controller.connect(profile)
        withTimeout(25_000) { controller.sessions.first { states -> states.any { it.id == id && it.phase == TerminalSessionPhase.OPEN } } }
        assertEquals(3, trusts.size)
        assertEquals(3, connections.size)
        main { lock.setReauthentication(ReauthenticationMode.EVERY_ACTION); start(id) }
        approve(); active()
        assertEquals(id, controller.portForwards.value.single().sessionId)
        assertEquals(3, connections.size)
        client(domain = true).use(::echo)
        stopAndVerifyClosed()
        controller.close(id)
        withTimeout(15_000) { controller.sessions.first { it.isEmpty() } }
        assertTrue(connections.all { runCatching { it.keepalive() }.isFailure })
    }

    @Test fun cancellingSecondHopAuthenticationClosesTheEntireRoute() = fixture(hops = 2, cancelHopAuthentication = true) {
        controller.connect(profile)
        withTimeout(25_000) { controller.sessions.first { it.isEmpty() } }
        assertEquals(2, trusts.size)
        assertTrue(connections.all { runCatching { it.keepalive() }.isFailure })
    }

    @Test fun rejectingIntermediateHostKeyClosesTheEntireRoute() = fixture(hops = 2, rejectTrust = 2) {
        controller.connect(profile)
        withTimeout(25_000) { controller.sessions.first { it.isEmpty() } }
        assertEquals(2, trusts.size)
        assertTrue(connections.all { runCatching { it.keepalive() }.isFailure })
    }

    @Test fun standaloneSocksRecoveryWaitsForUnlockAndFreshPinApproval() = fixture {
        main { lock.setReauthentication(ReauthenticationMode.EVERY_ACTION); start() }
        approve(); active(); client().use(::echo)
        val runtimeId = controller.portForwards.value.single().runtimeId
        main { lock.lock() }
        transports.single().close()
        withTimeout(25_000) { controller.portForwards.first { states -> states.any { it.phase == PortForwardRuntimePhase.WAITING_FOR_OPERATION } } }
        assertEquals(1, connections.size)
        assertTrue(controller.forwardAuthorization.value.isEmpty())
        main { lock.unlockWithPin(pin) }; idle()
        withTimeout(15_000) { lock.reauthenticationPending.first { it } }
        assertEquals(1, connections.size)
        approve(); active()
        assertEquals(runtimeId, controller.portForwards.value.single().runtimeId)
        assertEquals(2, connections.size)
        client(domain = true).use(::echo)
        stopAndVerifyClosed()
    }

    @Test fun cancellingRecoveryPinDoesNotReopenTheCarrier() = fixture {
        main { lock.setReauthentication(ReauthenticationMode.EVERY_ACTION); start() }
        approve(); active()
        transports.single().close()
        withTimeout(25_000) { lock.reauthenticationPending.first { it } }
        main { lock.cancelReauthentication() }
        withTimeout(15_000) { controller.portForwards.first { states -> states.any { it.phase == PortForwardRuntimePhase.FAILED } } }
        assertEquals(1, connections.size)
        assertTrue(controller.forwardAuthorization.value.isEmpty())
    }
}
