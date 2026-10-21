package website.sung.mangossh.session

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import website.sung.mangossh.diagnostics.CiRegressionDiagnostics
import website.sung.mangossh.data.settings.*
import website.sung.mangossh.data.vault.*
import website.sung.mangossh.domain.*
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.tsnet.EmbeddedTsnetManager

/** All files, preferences and Keystore aliases are isolated; only a disposable loopback peer is used. */
class PortForwardLifecycleInstrumentedTest {
    private fun fixture(protocol: ConnectionProtocol = ConnectionProtocol.SSH, automaticTrust: Boolean = true,
        block: suspend CoroutineScope.(SshSessionController, ConnectionProfile, Int, MutableList<SshConnection>, VaultRepository) -> Unit) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        val echoPort = args.getString("fixtureEchoPort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") { assertNotNull(port); assertNotNull(echoPort) }
        assumeTrue(port != null && echoPort != null)
        if (protocol == ConnectionProtocol.MOSH) assumeTrue(args.getString("fixtureMosh") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "forward-lifecycle-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val preferences = ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
        }
        val vault = VaultRepository(context, AndroidKeystoreVault(context, prefix))
        vault.open()
        val profile = ConnectionProfile(id = "synthetic", label = "Loopback fixture", hostname = "127.0.0.1", port = port!!,
            username = "fixture", authentication = AuthenticationMethod.AUTOMATIC, protocol = protocol)
        assertEquals(VaultMutationResult.Success, vault.upsertProfile(profile))
        val connections = java.util.Collections.synchronizedList(mutableListOf<SshConnection>())
        val controller = SshSessionController(context, vault, embeddedTsnetManager = EmbeddedTsnetManager(context),
            terminalAppearanceStore = TerminalAppearanceStore(context), terminalBehaviorStore = TerminalBehaviorStore(context),
            connectionPreferencesStore = ConnectionPreferencesStore(context), appForegroundState = AppForegroundState.fixed(false),
            foregroundStarter = {}, connectionFactory = { SshConnection(it.hostname, it.port, it.legacySshAlgorithms).also(connections::add) })
        val prompts = launch(Dispatchers.IO) {
            controller.prompts.collect { prompts -> prompts.filterIsInstance<SessionPrompt.HostKeyVerification>().filter { automaticTrust }.forEach {
                controller.respondToPrompt(it.requestId, listOf("trust"))
            } }
        }
        try { withTimeout(75_000) { block(controller, profile, echoPort!!, connections, vault) } }
        finally {
            controller.clear()
            prompts.cancelAndJoin()
            withTimeout(5_000) { controller.sessions.first { it.isEmpty() } }
            connections.toList().forEach { it.close() }
            preferences.forEach(base::deleteSharedPreferences)
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(prefix) }
        }
    }

    private suspend fun active(controller: SshSessionController, id: String, stage: String = "activation"): PortForwardRuntimeState = try {
        withTimeout(15_000) { controller.portForwards.first { states -> states.any { it.rule.id == id && it.phase == PortForwardRuntimePhase.ACTIVE } }
            .first { it.rule.id == id } }
    } catch (timeout: TimeoutCancellationException) {
        CiRegressionDiagnostics.record("forward_timeout", JSONObject().put("stage", stage).put("rule", id)
            .put("states", JSONArray(controller.portForwards.value.map {
                // detail is a local resource selected by toSafeMessage(), never peer exception text.
                JSONObject().put("rule", it.rule.id).put("port", it.rule.bindPort)
                    .put("phase", it.phase.name).put("detail", it.detail)
            })))
        throw AssertionError("Synthetic rule $id at $stage: states=${controller.portForwards.value.map { "${it.rule.id}:${it.rule.bindPort}:${it.phase}:${it.detail}" }}, " +
            "session phases=${controller.sessions.value.map { it.phase }}", timeout)
    }

    private fun echo(socket: Socket, value: Int) {
        socket.soTimeout = 5_000
        socket.getOutputStream().write(value)
        assertEquals(value, socket.getInputStream().read())
    }

    private fun client(rule: PortForwardRule, echoPort: Int): Socket = Socket("127.0.0.1", rule.bindPort).also { socket ->
        socket.soTimeout = 5_000
        if (rule.type == PortForwardType.DYNAMIC) {
            val input = java.io.DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
            output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (echoPort shr 8).toByte(), echoPort.toByte()))
            assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
            input.readUnsignedByte()
            val length = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS reply") }
            input.readFully(ByteArray(length + 2))
        }
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }

    @Test fun closingSshTerminalKeepsExistingTcpClientsAndStoppingReleasesTheCarrier() = fixture { controller, profile, echoPort, connections, vault ->
        val listen = java.net.ServerSocket(0).use { it.localPort }
        val rule = PortForwardRule("local", profile.id, PortForwardType.LOCAL, bindPort = listen,
            destinationHost = "127.0.0.1", destinationPort = echoPort)
        vault.upsertPortForward(rule)
        val sessionId = controller.connect(profile)
        controller.sessions.first { it.any { state -> state.id == sessionId && state.phase == TerminalSessionPhase.OPEN } }
        controller.startPortForward(sessionId, rule)
        active(controller, rule.id)
        Socket("127.0.0.1", listen).use { socket ->
            echo(socket, 41)
            controller.forwardingWork.first { it.awake }
            controller.close(sessionId)
            val session = controller.sessions.value.single()
            assertEquals(SessionKind.PORT_FORWARD, session.kind)
            assertEquals(PortForwardCarrier.DEDICATED, controller.portForwards.value.single().carrier)
            echo(socket, 42)
            assertEquals(1, connections.size)
        }
        controller.forwardingWork.first { !it.awake }
        controller.stopPortForward(sessionId, rule.id)
        controller.sessions.first { it.isEmpty() }
        assertEquals(PortForwardRuntimePhase.STOPPED, controller.portForwards.value.single().phase)
    }

    @Test fun sharedDedicatedCarrierRecoversWithStableRuleIdsAndWaitsForAuthorization() = fixture { controller, profile, echoPort, connections, vault ->
        val rules = PortForwardType.entries.map { type -> PortForwardRule("shared-${type.name}", profile.id, type,
            bindPort = if (type == PortForwardType.REMOTE) 22354 else java.net.ServerSocket(0).use { it.localPort },
            destinationHost = "127.0.0.1", destinationPort = echoPort) }
        rules.forEach { vault.upsertPortForward(it); controller.startPortForwardOnNewConnection(profile, it) }
        CiRegressionDiagnostics.phase("forward-initial")
        rules.forEach { active(controller, it.id, "initial") }
        assertEquals(1, connections.size)
        rules.forEach { rule -> client(rule, echoPort).use { echo(it, 54) } }
        val ids = controller.portForwards.value.map { it.runtimeId }.toSet()
        connections.single().close()
        val request = controller.forwardAuthorization.first { it.isNotEmpty() }.single()
        assertFalse(controller.forwardingWork.first { !it.awake }.awake)
        assertEquals(1, connections.size)
        controller.respondToForwardAuthorization(request.id, true)
        CiRegressionDiagnostics.phase("forward-recovery")
        rules.forEach { active(controller, it.id, "recovery") }
        assertEquals(ids, controller.portForwards.value.map { it.runtimeId }.toSet())
        assertEquals(2, connections.size)
        rules.forEach { rule -> client(rule, echoPort).use { echo(it, 55) } }
        controller.stopPortForward("", rules.first().id)
        controller.portForwards.first { states -> states.first { it.rule.id == rules.first().id }.phase == PortForwardRuntimePhase.STOPPED }
        assertEquals(1, controller.sessions.value.size)
        controller.stopPortForwardsForProfile(profile.id)
        controller.sessions.first { it.isEmpty() }
    }

    @Test fun occupiedLocalAndProxyPortsFailWithoutRetrying() = fixture { controller, profile, echoPort, connections, vault ->
        java.net.ServerSocket(0).use { occupied ->
            for (type in listOf(PortForwardType.LOCAL, PortForwardType.DYNAMIC)) {
                val rule = PortForwardRule("occupied-${type.name}", profile.id, type, bindPort = occupied.localPort,
                    destinationHost = "127.0.0.1", destinationPort = echoPort)
                vault.upsertPortForward(rule)
                controller.startPortForwardOnNewConnection(profile, rule)
                controller.portForwards.first { states -> states.any { it.rule.id == rule.id && it.phase == PortForwardRuntimePhase.FAILED } }
                controller.sessions.first { it.isEmpty() }
            }
            assertFalse(controller.hasForwardIntents)
            assertEquals(2, connections.size)
        }
    }

    @Test fun occupiedRemotePortDoesNotStopTheExistingListener() = fixture { controller, profile, echoPort, connections, vault ->
        val first = PortForwardRule("remote-first", profile.id, PortForwardType.REMOTE, bindPort = 22354,
            destinationHost = "127.0.0.1", destinationPort = echoPort)
        val duplicate = first.copy(id = "remote-occupied")
        for (rule in listOf(first, duplicate)) {
            vault.upsertPortForward(rule)
            controller.startPortForwardOnNewConnection(profile, rule)
            if (rule == first) active(controller, rule.id)
            else controller.portForwards.first { states -> states.any { it.rule.id == rule.id && it.phase == PortForwardRuntimePhase.FAILED } }
        }
        assertEquals(1, connections.size)
        client(first, echoPort).use { echo(it, 57) }
        controller.stopPortForwardsForProfile(profile.id)
        controller.sessions.first { it.isEmpty() }
    }

    @Test fun hostConfirmationWaitsWithoutCpuLeaseAndCanBeCancelled() = fixture(automaticTrust = false) { controller, profile, echoPort, _, vault ->
        val rule = PortForwardRule("confirm", profile.id, PortForwardType.LOCAL,
            bindPort = java.net.ServerSocket(0).use { it.localPort }, destinationHost = "127.0.0.1", destinationPort = echoPort)
        vault.upsertPortForward(rule)
        controller.startPortForwardOnNewConnection(profile, rule)
        val prompt = controller.prompts.first { it.any { it is SessionPrompt.HostKeyVerification } }.single()
        controller.portForwards.first { it.single().phase == PortForwardRuntimePhase.WAITING_FOR_OPERATION }
        assertFalse(controller.forwardingWork.first { !it.awake }.awake)
        controller.respondToPrompt(prompt.requestId, null)
        controller.portForwards.first { it.single().phase == PortForwardRuntimePhase.FAILED }
        controller.sessions.first { it.isEmpty() }
        controller.forwardingWork.first { !it.pending }
        assertFalse(controller.hasForwardIntents)
    }

    @Test fun realNetworkLossWaitsAndStoppedRulesDoNotReturn() = fixture { controller, profile, echoPort, connections, vault ->
        // This opt-in test is only run on an audited idle emulator with both networks enabled.
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceNetworkFixture") == "true")
        val rule = PortForwardRule("network", profile.id, PortForwardType.LOCAL,
            bindPort = java.net.ServerSocket(0).use { it.localPort }, destinationHost = "127.0.0.1", destinationPort = echoPort)
        vault.upsertPortForward(rule)
        try {
            shell("svc wifi disable"); shell("svc data disable")
            withTimeout(15_000) { controller.network.first { it.health == NetworkHealth.LOST } }
            controller.startPortForwardOnNewConnection(profile, rule)
            controller.portForwards.first { it.single().phase == PortForwardRuntimePhase.WAITING_FOR_NETWORK }
            assertFalse(controller.forwardingWork.first { !it.awake }.awake)
            assertEquals(0, connections.size)
            shell("svc data enable"); shell("svc wifi enable")
            active(controller, rule.id)
            client(rule, echoPort).use { echo(it, 71) }
            controller.stopPortForward("", rule.id)
            controller.sessions.first { it.isEmpty() }
            shell("svc wifi disable"); shell("svc data disable")
            withTimeout(15_000) { controller.network.first { it.health == NetworkHealth.LOST } }
            controller.startPortForwardOnNewConnection(profile, rule)
            controller.portForwards.first { it.single().phase == PortForwardRuntimePhase.WAITING_FOR_NETWORK }
            controller.stopPortForward("", rule.id)
            controller.portForwards.first { it.single().phase == PortForwardRuntimePhase.STOPPED }
            shell("svc data enable"); shell("svc wifi enable")
            withTimeout(15_000) { controller.network.first { it.health == NetworkHealth.AVAILABLE } }
            delay(1_500)
            assertEquals(1, connections.size)
            assertFalse(controller.hasForwardIntents)
        } finally { shell("svc data enable"); shell("svc wifi enable") }
    }

    @Test fun backgroundClientsAcquireRealCpuLeaseAndIdleDozeReleasesIt() = fixture { controller, profile, echoPort, _, vault ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceIdleFixture") == "true")
        assertEquals("ACTIVE", shell("dumpsys deviceidle get deep"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MangoSSH:isolated-forward-test").apply { setReferenceCounted(false) }
        val lease = SessionWakeLock(platform::acquire, { if (platform.isHeld) platform.release() }, { throw AssertionError(it) })
        val observer = launch(Dispatchers.Main) { controller.forwardingWork.collect {
            lease.update(hasSessions = it.pending, foregroundOwned = true, uiForeground = false, activeForwards = it.awake)
        } }
        val rule = PortForwardRule("power", profile.id, PortForwardType.LOCAL,
            bindPort = java.net.ServerSocket(0).use { it.localPort }, destinationHost = "127.0.0.1", destinationPort = echoPort)
        vault.upsertPortForward(rule)
        try {
            controller.startPortForwardOnNewConnection(profile, rule)
            active(controller, rule.id)
            controller.forwardingWork.first { !it.awake }
            withContext(Dispatchers.Main) { assertFalse(platform.isHeld) }
            shell("input keyevent 223")
            client(rule, echoPort).use {
                echo(it, 81)
                controller.forwardingWork.first { it.awake }
                withContext(Dispatchers.Main) { assertTrue(platform.isHeld) }
            }
            controller.forwardingWork.first { !it.awake }
            withContext(Dispatchers.Main) { assertFalse(platform.isHeld) }
            shell("dumpsys deviceidle force-idle")
            assertEquals("IDLE", shell("dumpsys deviceidle get deep"))
            withContext(Dispatchers.Main) { assertFalse(platform.isHeld) }
            shell("dumpsys deviceidle unforce"); shell("input keyevent 224")
            withTimeout(15_000) { controller.network.first { it.health == NetworkHealth.AVAILABLE } }
            client(rule, echoPort).use { echo(it, 82) }
        } finally {
            shell("dumpsys deviceidle unforce"); shell("input keyevent 224")
            observer.cancelAndJoin()
            withContext(Dispatchers.Main) { lease.close() }
        }
    }

    @Test fun moshCompanionRecoveryAndTerminalDetachPreserveTheForwardOwner() = fixture(ConnectionProtocol.MOSH) { controller, profile, echoPort, connections, vault ->
        val listen = java.net.ServerSocket(0).use { it.localPort }
        val rule = PortForwardRule("mosh-local", profile.id, PortForwardType.LOCAL, bindPort = listen,
            destinationHost = "127.0.0.1", destinationPort = echoPort)
        vault.upsertPortForward(rule)
        val sessionId = controller.connect(profile)
        controller.sessions.first { it.any { state -> state.id == sessionId && state.phase == TerminalSessionPhase.OPEN } }
        controller.startPortForward(sessionId, rule)
        assertEquals(PortForwardCarrier.MOSH_COMPANION, active(controller, rule.id).carrier)
        connections.single().close()
        val request = controller.forwardAuthorization.first { it.isNotEmpty() }.single()
        assertEquals(ConnectionProtocol.MOSH, controller.sessions.value.single().protocol)
        assertEquals(TerminalSessionPhase.OPEN, controller.sessions.value.single().phase)
        controller.respondToForwardAuthorization(request.id, true)
        active(controller, rule.id)
        Socket("127.0.0.1", listen).use { socket ->
            echo(socket, 61)
            controller.close(sessionId)
            assertEquals(SessionKind.PORT_FORWARD, controller.sessions.value.single().kind)
            echo(socket, 62)
            assertEquals(2, connections.size)
        }
        controller.stopPortForward(sessionId, rule.id)
        controller.sessions.first { it.isEmpty() }
    }
}
