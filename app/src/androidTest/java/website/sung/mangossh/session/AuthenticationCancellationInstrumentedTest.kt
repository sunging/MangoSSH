package website.sung.mangossh.session

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.settings.ConnectionPreferencesStore
import website.sung.mangossh.data.settings.TerminalAppearanceStore
import website.sung.mangossh.data.settings.TerminalBehaviorStore
import website.sung.mangossh.data.vault.AndroidKeystoreVault
import website.sung.mangossh.data.vault.VaultMutationResult
import website.sung.mangossh.data.vault.VaultRepository
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.presentation.SessionPromptDialog
import website.sung.mangossh.presentation.label
import website.sung.mangossh.session.ssh.SshConnection
import website.sung.mangossh.session.ssh.SshFailure
import website.sung.mangossh.session.tsnet.EmbeddedTsnetManager

/** Uses the production prompt and controller with a disposable peer and isolated vault/preferences. */
class AuthenticationCancellationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Fixture(val controller: SshSessionController, val profile: ConnectionProfile,
        val connections: List<Pair<String, SshConnection>>, val events: List<SessionEndedEvent>)

    private fun fixture(protocol: ConnectionProtocol = ConnectionProtocol.SSH, username: String = "fixture-otp",
        jump: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull()
        if (args.getString("requireFixtures") == "true") assertNotNull(port)
        assumeTrue(port != null)
        val host = args.getString("fixtureHost") ?: "127.0.0.1"
        val prefix = "authentication-cancellation-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val preferences = ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
        }
        val vault = VaultRepository(context, AndroidKeystoreVault(context, prefix))
        vault.open()
        val profile = ConnectionProfile(id = "synthetic", label = "Disposable authentication", hostname = host, port = port!!,
            username = if (jump) "fixture" else username, protocol = protocol, jumpProfileIds = if (jump) listOf("hop") else emptyList())
        if (jump) assertEquals(VaultMutationResult.Success, vault.upsertProfile(profile.copy(id = "hop", username = username, jumpProfileIds = emptyList())))
        assertEquals(VaultMutationResult.Success, vault.upsertProfile(profile))
        val connections = CopyOnWriteArrayList<Pair<String, SshConnection>>()
        val events = CopyOnWriteArrayList<SessionEndedEvent>()
        val controller = SshSessionController(context, vault, embeddedTsnetManager = EmbeddedTsnetManager(context),
            terminalAppearanceStore = TerminalAppearanceStore(context), terminalBehaviorStore = TerminalBehaviorStore(context),
            connectionPreferencesStore = ConnectionPreferencesStore(context), appForegroundState = AppForegroundState.fixed(false),
            foregroundStarter = {}, connectionFactory = { profile ->
                SshConnection(profile.hostname, profile.port, profile.legacySshAlgorithms).also { connections += profile.id to it }
            })
        val recorder = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { controller.sessionEndedEvents.collect { events += it } }
        val trust = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            controller.prompts.collect { prompts -> prompts.filterIsInstance<SessionPrompt.HostKeyVerification>().forEach {
                controller.respondToPrompt(it.requestId, listOf("trust"))
            } }
        }
        compose.setContent { MaterialTheme {
            val sessions by controller.sessions.collectAsState()
            val prompts by controller.prompts.collectAsState()
            Column { sessions.forEach { Text(it.phase.label(), Modifier.testTag("phase_${it.id}")) } }
            prompts.filterIsInstance<SessionPrompt.Authentication>().firstOrNull()?.let { prompt ->
                SessionPromptDialog(prompt) { controller.respondToPrompt(prompt.requestId, it) }
            }
        } }
        try { withTimeout(40_000) { Fixture(controller, profile, connections, events).block() } }
        finally {
            val pending = controller.sessions.value.map { it.id }.toSet()
            controller.clear()
            withTimeout(5_000) {
                controller.sessions.first { it.isEmpty() }
                while (!events.map { it.sessionId }.containsAll(pending)) delay(10)
            }
            trust.cancelAndJoin()
            recorder.cancelAndJoin()
            connections.forEach { it.second.close() }
            preferences.forEach(base::deleteSharedPreferences)
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(prefix) }
        }
    }

    private suspend fun Fixture.prompt(sessionId: String, previous: String? = null): SessionPrompt.Authentication = withTimeout(15_000) {
        controller.prompts.first { prompts -> prompts.any { it is SessionPrompt.Authentication && it.sessionId == sessionId && it.requestId != previous } }
            .filterIsInstance<SessionPrompt.Authentication>().single { it.sessionId == sessionId && it.requestId != previous }
    }

    private suspend fun Fixture.cancel(sessionId: String, prompt: SessionPrompt.Authentication) {
        compose.onNodeWithTag("authentication_prompt_cancel").assertIsDisplayed().performClick()
        withTimeout(5_000) {
            controller.sessions.first { states -> states.none { it.id == sessionId } }
            while (events.none { it.sessionId == sessionId }) delay(10)
        }
        assertTrue(controller.prompts.value.none { it.sessionId == sessionId })
        val ended = events.single { it.sessionId == sessionId }
        assertEquals(SessionEndReason.USER_REQUEST, ended.reason)
        assertNull(ended.messageKind)
        compose.onNodeWithTag("phase_$sessionId").assertDoesNotExist()
        compose.onNodeWithTag("authentication_prompt_cancel").assertDoesNotExist()
        val connection = connections.single { it.first == profile.id }.second
        assertTrue("Cancelled setup must close its transport", runCatching { connection.openChannel() }.exceptionOrNull() is SshFailure)
        // A late dialog callback or close action cannot recreate the session or emit a second end event.
        controller.respondToPrompt(prompt.requestId, null)
        controller.close(sessionId)
        delay(100)
        assertEquals(1, events.count { it.sessionId == sessionId })
        assertEquals(1, connections.count { it.first == profile.id })
        assertTrue(controller.prompts.value.none { it.sessionId == sessionId })
    }

    @Test fun cancellingInteractiveDialogWithdrawsTheAuthenticatingTerminal() = fixture {
        val id = controller.connect(profile)
        val prompt = prompt(id)
        assertEquals(1, prompt.fields.size)
        compose.onNodeWithTag("phase_$id").assertTextEquals(base.getString(R.string.ui_authenticating))
        cancel(id, prompt)
    }

    @Test fun cancellingInteractiveFallbackAfterARejectedPasswordEndsTheSession() = fixture(username = "fixture-mixed") {
        val id = controller.connect(profile)
        val password = prompt(id)
        assertEquals(2, password.fields.size)
        compose.onNodeWithTag("authentication_prompt_field_0").performTextInput(UUID.randomUUID().toString())
        compose.onNodeWithTag("authentication_prompt_submit").performClick()
        val interactive = prompt(id, password.requestId)
        assertEquals(1, interactive.fields.size)
        cancel(id, interactive)
    }

    @Test fun cancellingPasswordDoesNotFallBackToInteractiveOrLeaveAConnectingSession() = fixture(username = "fixture-mixed") {
        val id = controller.connect(profile)
        val password = prompt(id)
        assertEquals(2, password.fields.size)
        cancel(id, password)
    }

    @Test fun cancellingMoshBootstrapAuthenticationWithdrawsTheSession() = fixture(protocol = ConnectionProtocol.MOSH) {
        val id = controller.connect(profile)
        cancel(id, prompt(id))
    }

    @Test fun cancellingFileAuthenticationWithdrawsTheSession() = fixture {
        val id = controller.connectForFileTransfer(profile)
        cancel(id, prompt(id))
    }

    @Test fun cancellingForwardAuthenticationWithdrawsTheSession() = fixture {
        val id = controller.connectForPortForward(profile)
        cancel(id, prompt(id))
    }

    @Test fun cancellingJumpAuthenticationWithdrawsTheWholeRoute() = fixture(jump = true) {
        val id = controller.connect(profile)
        cancel(id, prompt(id))
    }

    @Test fun cancellingOneAuthenticationPreservesAnotherOpenSession() = fixture {
        val other = profile.copy(id = "other", username = "fixture")
        val otherId = controller.connect(other)
        withTimeout(15_000) { controller.sessions.first { states -> states.any { it.id == otherId && it.phase == TerminalSessionPhase.OPEN } } }
        val id = controller.connect(profile)
        cancel(id, prompt(id))
        assertEquals(TerminalSessionPhase.OPEN, controller.sessions.value.single().phase)
        assertEquals(otherId, controller.sessions.value.single().id)
        connections.single { it.first == other.id }.second.keepalive()
        assertTrue(events.none { it.sessionId == otherId })
    }
}
