package website.sung.mangossh.presentation

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogWindow
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.CommandSnippet
import website.sung.mangossh.data.vault.StoredSshKey
import website.sung.mangossh.domain.*

/** Synthetic profiles and empty key material only; captures use one test-owned cache directory. */
class HostEditorInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun host(id: String = "test") = ConnectionProfile(id = id, label = "Example", hostname = "synthetic.invalid",
        username = "test", authentication = AuthenticationMethod.PASSWORD)
    private val keys = listOf(StoredSshKey("key", "Example key", "", "", "", ""), StoredSshKey("second", "Second key", "", "", "", ""))
    private val snippets = listOf(CommandSnippet("snippet", "Example snippet", ""))

    private fun show(controller: HostEditorController, width: Int = 400, height: Int = 780, font: Float = 1f,
        onDismiss: () -> Unit = {}, onSave: (ConnectionProfileDraft) -> Unit = {}, saveOperation: EditorSaveOperation? = null) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font)) { MaterialTheme {
            Surface(Modifier.width(width.dp).height(height.dp)) {
                HostEditorScreen(controller, listOf(host("jump")), ConnectionPreferences(), keys, snippets, onDismiss, onSave, saveOperation = saveOperation)
            }
        } } }
    }
    private fun open(page: HostEditorPage) = compose.onNodeWithTag("host_editor_open_${page.name}").performScrollTo().performClick()
    private fun back() = compose.onNodeWithTag("host_editor_back").performClick()
    private fun capture(name: String) {
        val directory = File(context.cacheDir, "test-host-editor-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onNodeWithTag("host_editor").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun homeIsCompactAndDetailChangesOnlySaveFromHome() {
        val controller = HostEditorController(HostEditorDraft.from(host()))
        var saved: ConnectionProfileDraft? = null
        show(controller, onSave = { saved = it })
        compose.onNodeWithTag("host_editor_save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Example key").assertDoesNotExist()
        compose.onNodeWithText("Example snippet").assertDoesNotExist()
        compose.onNodeWithTag("host_editor_timeout").assertDoesNotExist()
        capture("phone")
        open(HostEditorPage.ADVANCED)
        compose.onNodeWithTag("host_editor_save").assertDoesNotExist()
        compose.onNodeWithTag("host_editor_timeout").performClick()
        compose.onNodeWithTag("host_editor_timeout_option_3").performClick()
        back()
        compose.runOnIdle { assertNull(saved); assertEquals(20, controller.draft.overrides.connectTimeoutSeconds) }
        compose.onNodeWithTag("host_editor_save").performClick()
        compose.runOnIdle { assertEquals(20, saved?.overrides?.connectTimeoutSeconds) }
    }

    @Test fun simpleChoicesStayInlineWhileLongListsRemainSearchable() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(authentication = AuthenticationMethod.PRIVATE_KEY, keyId = "key"))
        show(controller)
        open(HostEditorPage.CONNECTION)
        compose.onNodeWithTag("host_editor_authentication_option_1").performScrollTo().performClick()
        compose.onNodeWithTag("host_editor_authentication_option_1").assertIsSelected()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("host_editor_authentication_option_0").performScrollTo().performClick()
        compose.onNodeWithTag("host_editor_protocol").performScrollTo()
        capture("connection-inline")
        compose.onNodeWithTag("host_editor_key").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithTag("host_editor_key_search").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        back()
        open(HostEditorPage.STARTUP)
        compose.onNodeWithTag("host_editor_workspace_option_3").performScrollTo().performClick()
        compose.onNodeWithTag("host_editor_workspace_option_3").assertIsSelected()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        capture("startup-inline")
        back()
        open(HostEditorPage.SECURITY)
        compose.onNodeWithTag("host_editor_reauthenticate_option_2").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(true, controller.draft.reauthenticate) }
    }

    @Test fun restoredEditorKeepsDraftDestinationAndMainScroll() {
        val restoration = StateRestorationTester(compose)
        lateinit var controller: HostEditorController
        restoration.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            controller = rememberHostEditorController(host())
            Box(Modifier.width(360.dp).height(500.dp)) {
                HostEditorScreen(controller, emptyList(), ConnectionPreferences(), keys, snippets, {}, {})
            }
        } } }
        compose.onNodeWithTag("host_editor_label").performTextReplacement("Updated")
        compose.onNodeWithTag("host_editor_open_ADVANCED").performScrollTo()
        val scroll = compose.onNodeWithTag("host_editor_scroll_MAIN").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("host_editor_open_ADVANCED").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("host_editor_timeout").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Updated", controller.draft.label) }
        back()
        val restoredScroll = compose.onNodeWithTag("host_editor_scroll_MAIN").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(scroll, restoredScroll, 1f)
        back()
        compose.onNodeWithText(text(R.string.editor_unsaved)).assertIsDisplayed()
    }

    @Test fun switchingToMoshCanCancelOrConfirmClearingJumps() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(jumpIds = listOf("jump")))
        show(controller)
        open(HostEditorPage.CONNECTION)
        fun chooseMosh() {

            compose.onNodeWithTag("host_editor_protocol_option_1").performScrollTo().performClick()
        }
        chooseMosh()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(ConnectionProtocol.SSH, controller.draft.protocol); assertEquals(listOf("jump"), controller.draft.jumpIds) }
        chooseMosh()
        compose.onNodeWithTag("host_editor_confirm_change").performClick()
        compose.runOnIdle { assertEquals(ConnectionProtocol.MOSH, controller.draft.protocol); assertTrue(controller.draft.jumpIds.isEmpty()) }
        back()
        open(HostEditorPage.JUMPS)
        compose.onNodeWithText(text(R.string.jump_add)).assertDoesNotExist()
    }

    @Test fun snippetAndCreateOrAttachRequireConfirmationInBothDirections() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(startupSnippetId = "snippet", workspace = TmuxWorkspace(name = "work")))
        show(controller)
        open(HostEditorPage.STARTUP)

        compose.onNodeWithTag("host_editor_workspace_option_3").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("snippet", controller.draft.startupSnippetId) }
        compose.onNodeWithTag("host_editor_confirm_change").performClick()
        compose.onNodeWithTag("host_editor_workspace_name").assertTextContains("work")
        compose.runOnIdle { assertNull(controller.draft.startupSnippetId); assertEquals(WorkspaceMode.CREATE_OR_ATTACH, controller.draft.workspace.mode) }
        compose.onNodeWithTag("host_editor_snippet").performClick()
        compose.onNodeWithTag("host_editor_snippet_option_1").performClick()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(WorkspaceMode.CREATE_OR_ATTACH, controller.draft.workspace.mode) }
        compose.onNodeWithTag("host_editor_snippet").performClick()
        compose.onNodeWithTag("host_editor_snippet_option_1").performClick()
        compose.onNodeWithTag("host_editor_confirm_change").performClick()
        compose.runOnIdle { assertEquals(WorkspaceMode.DISABLED, controller.draft.workspace.mode); assertEquals("snippet", controller.draft.startupSnippetId) }
    }

    @Test fun disablingAgentHidesDetailsAndPreservesAllowlist() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(agentPolicy = HostAgentPolicy(listOf("key"))))
        show(controller)
        open(HostEditorPage.SECURITY)
        compose.onNodeWithTag("host_editor_agent_keys").assertDoesNotExist()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("host_editor_agent_enabled"))).performClick()
        compose.onNodeWithTag("host_editor_agent_keys").performClick()
        compose.onNodeWithText(text(R.string.host_policy_selected_key)).performClick()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf("key"), controller.draft.agentPolicy.allowedKeyIds) }
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("host_editor_agent_enabled"))).performClick()
        compose.onNodeWithTag("host_editor_agent_keys").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("key"), controller.draft.agentPolicy.allowedKeyIds) }
    }

    @Test fun largeFontAllowlistPickerCanScrollToAndApplyKeys() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(agentForwarding = true))
        show(controller, width = 320, height = 420, font = 1.6f)
        open(HostEditorPage.SECURITY)
        compose.onNodeWithTag("host_editor_agent_keys").performScrollTo().performClick()
        compose.onNodeWithTag("host_editor_agent_key_options").performScrollToNode(hasTestTag("host_editor_agent_custom"))
        compose.onNodeWithTag("host_editor_agent_custom").performClick()
        compose.onNodeWithTag("host_editor_agent_key_options").performScrollToNode(hasText("Second key"))
        compose.onNodeWithText("Second key").performClick()
        compose.onNodeWithTag("host_editor_agent_keys_apply").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("second"), controller.draft.agentPolicy.allowedKeyIds) }
    }

    @Test fun missingLoginKeyIsVisibleFromHomeAndTailnetResolvesIt() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(authentication = AuthenticationMethod.PRIVATE_KEY, keyId = "missing"))
        show(controller)
        compose.onNodeWithTag("host_editor_save").assertIsNotEnabled()
        compose.onNodeWithTag("host_editor_open_CONNECTION").assertTextContains(text(R.string.host_editor_needs_attention))
        open(HostEditorPage.CONNECTION)

        compose.onNodeWithTag("host_editor_route_option_1").performScrollTo().performClick()
        compose.onNodeWithTag("host_editor_key").assertDoesNotExist()
        back()
        compose.onNodeWithTag("host_editor_save").assertIsEnabled()
    }

    @Test fun shortLargeFontLayoutKeepsSaveVisibleAndAllCategoriesReachable() {
        val controller = HostEditorController(HostEditorDraft.from(host()))
        show(controller, width = 320, height = 420, font = 1.6f)
        compose.onNodeWithTag("host_editor_save").assertIsDisplayed()
        capture("short-large")
        HostEditorPage.entries.filter { it != HostEditorPage.MAIN }.forEach { page -> open(page); back() }
        compose.onNodeWithTag("host_editor_save").assertIsDisplayed()
        compose.onNodeWithTag("host_editor_username").performScrollTo()
        val username = compose.onNodeWithTag("host_editor_username").getUnclippedBoundsInRoot()
        val port = compose.onNodeWithTag("host_editor_port").getUnclippedBoundsInRoot()
        assertTrue(port.top >= username.bottom)
    }

    @Test fun wideLayoutUsesSharedNavigationAndRetainsAllHostFields() {
        val original = host().copy(favorite = true, position = 4, connectionCount = 7, lastConnectedAtEpochMillis = 123)
        val controller = HostEditorController(HostEditorDraft.from(original))
        var saved: ConnectionProfileDraft? = null
        show(controller, width = 720, height = 900, onSave = { saved = it })
        capture("tablet")
        compose.onNodeWithTag("host_editor_save").performClick()
        compose.runOnIdle { assertEquals(original, saved?.toProfile()) }
    }

    @Test fun closeCancelsWithoutSaving() {
        val controller = HostEditorController(HostEditorDraft.from(host()))
        var dismissed = false
        show(controller, onDismiss = { dismissed = true }, onSave = { fail("Cancel must not save") })
        compose.onNodeWithTag("host_editor_label").performTextReplacement("Changed")
        back()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertFalse(dismissed) }
        back()
        compose.onNodeWithTag("host_editor_discard").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun phoneDialogDoesNotAutofocusAndKeepsSaveAccessibleAfterTyping() {
        compose.setContent { MaterialTheme {
            HostEditorDialog(emptyList(), ConnectionPreferences(), host(), keys, snippets, {}, { _, _ -> })
        } }
        compose.onNodeWithTag("host_editor_label").assertIsNotFocused()
        compose.onNodeWithTag("host_editor_label").performClick().performTextInput(" updated")
        val label = compose.onNodeWithTag("host_editor_label").assertIsFocused()
        assertEquals("Example updated", label.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("host_editor_save").assertIsDisplayed()
        capture("phone-after-typing")
    }

    @Test fun dialogBackDispatcherReturnsToMain() {
        var dismissed = false
        compose.setContent { MaterialTheme {
            HostEditorDialog(emptyList(), ConnectionPreferences(), host(), keys, snippets, { dismissed = true }, { _, _ -> })
        } }
        open(HostEditorPage.ADVANCED)
        // Dispatch through the Dialog window's Back owner. API 33+ does not route system Back as KEYCODE_BACK.
        lateinit var backDispatcher: OnBackPressedDispatcher
        onView(isRoot()).inRoot(isDialogWindow()).check { view, _ ->
            backDispatcher = checkNotNull(view.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher
        }
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("host_editor_scroll_MAIN").assertExists()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun savingDisablesRepeatAndFailureRetainsDraftForRetry() {
        val controller = HostEditorController(HostEditorDraft.from(host()).copy(label = "Retained draft"))
        val save = EditorSaveOperation()
        show(controller, saveOperation = save, onSave = { save.begin() })
        compose.onNodeWithTag("host_editor_save").performClick().assertIsNotEnabled()
        compose.runOnIdle { save.finish(uiText(R.string.vault_write_failed)) }
        compose.onNodeWithTag("host_editor_save").assertIsEnabled()
        compose.onNodeWithText(text(R.string.vault_write_failed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("Retained draft", controller.draft.label) }
    }
}
