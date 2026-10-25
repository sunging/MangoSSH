package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.AuthenticationSummary
import website.sung.mangossh.data.vault.VaultStatus
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile
import website.sung.mangossh.presentation.settings.TsnetQuickConnectDialog
import website.sung.mangossh.session.AuthenticationField
import website.sung.mangossh.session.SessionPrompt
import website.sung.mangossh.session.SessionPromptText
import website.sung.mangossh.session.SessionPromptTextKind
import website.sung.mangossh.session.tsnet.TsnetDevice

/** Synthetic metadata and runtime-generated input only; this suite performs no network or vault I/O. */
class AuthenticationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private val authentications = listOf(AuthenticationSummary("one", "First password"), AuthenticationSummary("two", "Second password"))
    private fun host(id: String = "one", authenticationId: String? = "one") = ConnectionProfile(id = id, label = "Host $id",
        hostname = "synthetic.invalid", username = "test", authentication = AuthenticationMethod.AUTOMATIC, authenticationId = authenticationId)

    @Test fun editingStartsEmptyAndRenamingKeepsThePassword() {
        var dismissed = false
        var saved = false
        compose.setContent { MaterialTheme {
            AuthenticationEditorDialog(authentications.first().copy(hasPassword = true), emptyList(), AuthenticationEditorActions(save = { request, operation ->
                assertEquals("one", request.id); assertEquals("Renamed", request.label)
                assertNull(request.password); assertFalse(request.clearPassword)
                saved = true; assertTrue(operation.begin()); operation.finish(null)
            })) { dismissed = true }
        } }
        assertEquals("", field("authentication_password_value"))
        assertTrue(compose.onNodeWithTag("authentication_password_value").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        compose.onNodeWithTag("authentication_name").performTextReplacement("Renamed")
        compose.onNodeWithTag("authentication_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved); assertTrue(dismissed) }
    }

    @Test fun emptyCredentialsAreValidAndInputKeepsItsWhitespaceWithTheKeyboard() {
        val input = " ${UUID.randomUUID()} "
        var saved: AuthenticationEditRequest? = null
        compose.setContent { MaterialTheme {
            AuthenticationEditorDialog(null, emptyList(), AuthenticationEditorActions(save = { request, _ -> saved = request })) {}
        } }
        compose.onNodeWithTag("authentication_save").assertIsNotEnabled()
        compose.onNodeWithTag("authentication_name").performTextInput("Ephemeral")
        compose.onNodeWithTag("authentication_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(saved!!.password); assertNull(saved!!.keyId) }
        compose.onNodeWithTag("authentication_password_value").performClick().performTextInput(input)
        compose.onNodeWithTag("authentication_save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(input, saved!!.password) }
    }

    @Test fun clearingASavedPasswordIsExplicit() {
        var saved: AuthenticationEditRequest? = null
        compose.setContent { MaterialTheme {
            AuthenticationEditorDialog(authentications.first().copy(hasPassword = true), emptyList(),
                AuthenticationEditorActions(save = { request, _ -> saved = request })) {}
        } }
        compose.onNodeWithTag("authentication_clear_password").assertIsOff().performScrollTo().performClick()
        compose.onNodeWithTag("authentication_password_value").assertIsNotEnabled()
        compose.onNodeWithTag("authentication_save").performClick()
        compose.runOnIdle { assertTrue(saved!!.clearPassword); assertNull(saved!!.password) }
    }

    @Test fun cancellingDisposesTheStagedEditorWithoutSubmittingSecrets() {
        var visible by mutableStateOf(true)
        var opened: String? = null
        val discarded = AtomicReference<String?>()
        compose.setContent { MaterialTheme {
            if (visible) AuthenticationEditorDialog(null, emptyList(), AuthenticationEditorActions(begin = { opened = it }, discard = { discarded.set(it) },
                save = { _, _ -> fail("Cancelled editors cannot save") })) { visible = false }
        } }
        compose.onNodeWithTag("authentication_password_value").performTextInput(UUID.randomUUID().toString())
        // This checks disposal, while separate interaction tests cover pointer
        // targeting. IME/inset animation must not redirect this activation.
        compose.onNodeWithTag("authentication_cancel").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle { assertFalse(visible) }
        // Cancel requests removal; DisposableEffect cleanup happens when that removal is applied.
        compose.waitUntil(timeoutMillis = 5_000) { discarded.get() != null }
        compose.onNodeWithTag("authentication_editor").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(opened); assertEquals(opened, discarded.get()) }
    }

    @Test fun cancellingWaitsForCompositionRemovalBeforeDiscardingTheStagedEditor() {
        var visible by mutableStateOf(true)
        val opened = AtomicReference<String?>()
        val discarded = AtomicReference<String?>()
        val discardCount = AtomicInteger()
        compose.setContent { MaterialTheme {
            if (visible) AuthenticationEditorDialog(null, emptyList(), AuthenticationEditorActions(
                begin = { opened.set(it) }, discard = { discarded.set(it); discardCount.incrementAndGet() },
                save = { _, _ -> fail("Cancelled editors cannot save") })) { visible = false }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("authentication_cancel").performClick()
            // Hold the removal frame: an idle UI thread alone does not prove onDispose ran.
            compose.runOnIdle { assertFalse(visible); assertNotNull(opened.get()); assertNull(discarded.get()) }
            compose.onNodeWithTag("authentication_editor").assertExists()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(timeoutMillis = 5_000) { discarded.get() != null }
            compose.onNodeWithTag("authentication_editor").assertDoesNotExist()
            assertEquals(opened.get(), discarded.get())
            assertEquals(1, discardCount.get())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun restoringRetainsTheNameButDiscardsPasswordsAndStagingTokens() {
        val restoration = StateRestorationTester(compose)
        val tokens = mutableListOf<String>()
        restoration.setContent { MaterialTheme { AuthenticationEditorDialog(null, emptyList(), AuthenticationEditorActions(begin = { tokens += it })) {} } }
        compose.onNodeWithTag("authentication_name").performTextInput("Ephemeral")
        compose.onNodeWithTag("authentication_password_value").performTextInput(UUID.randomUUID().toString())
        restoration.emulateSavedInstanceStateRestore()
        assertEquals("Ephemeral", field("authentication_name")); assertEquals("", field("authentication_password_value"))
        compose.onNodeWithTag("authentication_save").assertIsEnabled()
        compose.runOnIdle { assertTrue(tokens.distinct().size >= 2) }
    }

    @Test fun passwordAndPrivateKeyCanBeConfiguredTogetherAndTheKeyCanBeRemoved() {
        val key = website.sung.mangossh.data.vault.SshKeySummary("key", "Test key", "ssh-ed25519", "")
        var saved: AuthenticationEditRequest? = null
        compose.setContent { MaterialTheme {
            AuthenticationEditorDialog(authentications.first().copy(keyId = key.id, hasPassword = true), listOf(key),
                AuthenticationEditorActions(save = { request, _ -> saved = request })) {}
        } }
        compose.onNodeWithTag("authentication_password_value").performTextInput(UUID.randomUUID().toString())
        compose.onNodeWithTag("authentication_save").performClick()
        compose.runOnIdle { assertNotNull(saved!!.password); assertEquals("key", saved!!.keyId) }
        compose.onNodeWithTag("authentication_key").performScrollTo().performClick()
        compose.onNodeWithTag("authentication_key_option_0").performClick()
        compose.onNodeWithTag("authentication_save").performClick()
        compose.runOnIdle { assertNull(saved!!.keyId); assertNotNull(saved!!.password) }
    }

    @Test fun authenticationChoicesAreSearchableAndDefaultToPrompting() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { AuthenticationChoice(selected, authentications, { selected = it }, "picker") } }
        compose.onNodeWithTag("picker").performClick()
        compose.onNodeWithTag("picker_option_0").assertIsSelected()
        compose.onNodeWithTag("picker_search").performTextInput("Second")
        compose.onNodeWithTag("picker_option_1").assertDoesNotExist()
        compose.onNodeWithTag("picker_option_2").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }

    @Test fun tsnetQuickConnectUsesTheChosenAuthenticationReference() {
        val device = TsnetDevice("device", "synthetic", "synthetic.example.ts.net", "linux", listOf("100.64.0.2"), true, 0, false)
        var profile: ConnectionProfile? = null
        compose.setContent { MaterialTheme {
            TsnetQuickConnectDialog(device, emptyList(), emptyList(), {}, { profile = it }, {}, {}, authentications = authentications)
        } }
        compose.onNodeWithTag("embedded_tsnet_quick_connect_username").performTextInput("test")
        compose.onNodeWithTag("embedded_tsnet_quick_connect_credential").performScrollTo().performClick()
        compose.onNodeWithTag("embedded_tsnet_quick_connect_credential_option_2").performClick()
        compose.onNodeWithTag("embedded_tsnet_quick_connect_confirm").performClick()
        compose.runOnIdle { assertEquals("two", profile!!.authenticationId); assertEquals(AuthenticationMethod.AUTOMATIC, profile!!.authentication) }
    }

    @Test fun passwordRemovalListsOnlyAffectedHostsAndRequiresConfirmation() {
        var removed = 0
        var removing by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (removing) RemovalConfirmationDialog(PendingRemovalRequest.Authentication("one"), listOf(host(), host("two"), host("other", null)),
                { removing = false }, { removed++ })
        } }
        compose.onNodeWithText("Host one", substring = true).assertExists()
        compose.onNodeWithText("Host two", substring = true).assertExists()
        compose.onNodeWithText("Host other", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_keep)).performClick()
        compose.runOnIdle { assertFalse(removing); assertEquals(0, removed) }
        compose.onNodeWithTag("confirm-removal").assertDoesNotExist()
    }

    @Test fun onlyConfirmingPasswordRemovalInvokesTheMutation() {
        var removed = 0
        compose.setContent { MaterialTheme {
            RemovalConfirmationDialog(PendingRemovalRequest.Authentication("one"), listOf(host()), {}, { removed++ })
        } }
        compose.onNodeWithTag("confirm-removal").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    private fun prompt() = SessionPrompt.Authentication("request", "session",
        SessionPromptText.App(SessionPromptTextKind.PASSWORD_TITLE, "Example"),
        SessionPromptText.App(SessionPromptTextKind.KEY_PASSWORD_FALLBACK_INSTRUCTION),
        listOf(AuthenticationField(SessionPromptText.App(SessionPromptTextKind.PASSWORD_FIELD), false),
            AuthenticationField(SessionPromptText.App(SessionPromptTextKind.REMEMBER_PASSWORD), true, toggle = true)))

    @Test fun passwordPromptSaveIsUncheckedAndSubmissionRelaysTheChoice() {
        val input = UUID.randomUUID().toString()
        var answer: List<String>? = null
        compose.setContent { MaterialTheme { SessionPromptDialog(prompt()) { answer = it } } }
        compose.onNodeWithTag("authentication_prompt_toggle_1").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("authentication_prompt_field_0").performTextInput(input)
        compose.onNodeWithTag("authentication_prompt_submit").performClick()
        compose.runOnIdle { assertEquals(listOf(input, AuthenticationField.TOGGLE_ON), answer) }
    }

    @Test fun cancellingThePasswordPromptReturnsNoAnswers() {
        var responses = 0
        compose.setContent { MaterialTheme { SessionPromptDialog(prompt()) { responses++; assertNull(it) } } }
        compose.onNodeWithTag("authentication_prompt_field_0").performTextInput(UUID.randomUUID().toString())
        compose.onNodeWithTag("authentication_prompt_cancel").performClick()
        compose.runOnIdle { assertEquals(1, responses) }
    }

    private fun layout(width: Int, height: Int, font: Float) {
        val metadata = listOf(AuthenticationSummary("one", "A long password name for the shared test credential"))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font)) { MaterialTheme {
            Surface(Modifier.width(width.dp).height(height.dp)) {
                AuthenticationScreen(VaultStatus.Ready, metadata, emptyList(), listOf(host()), AuthenticationEditorActions(), {}, { _, _, _ -> }, false)
            }
        } } }
        compose.onNodeWithTag("authentication_list").performScrollToKey("one")
        compose.onNodeWithTag("authentication_edit_one").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("authentication_password_value").assertIsDisplayed()
        compose.onNodeWithTag("authentication_save").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun phoneWithLargeFontsKeepsCredentialActionsReachable() = layout(320, 480, 2f)
    @Test fun tabletKeepsCredentialActionsReachable() = layout(800, 900, 1.3f)
}
