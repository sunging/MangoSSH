package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.domain.*

/** Synthetic host labels exercise draft controls without reading the production vault. */
class HostWorkflowControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun host(id: String) = ConnectionProfile(id = id, label = id, hostname = "synthetic.invalid",
        username = "test", authentication = AuthenticationMethod.PASSWORD)

    @Test fun jumpCandidatesAppearOnlyInPickerAndSelectionPreservesOrder() {
        var selected by mutableStateOf(emptyList<String>())
        val hosts = listOf(host("alpha"), host("beta"), host("self"), host("nested").copy(jumpProfileIds = listOf("alpha")))
        compose.setContent { MaterialTheme {
            JumpChainEditor(selected, { selected = it }, hosts, "self", ConnectionProtocol.SSH)
        } }
        compose.onNodeWithText("alpha").assertDoesNotExist()
        compose.onNodeWithText("beta").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.jump_add)).performClick()
        compose.onNodeWithText("self").assertDoesNotExist()
        compose.onNodeWithText("nested").assertDoesNotExist()
        compose.onNodeWithText("alpha").performClick()
        compose.onNodeWithText("1. alpha").assertIsDisplayed()
        compose.onNodeWithText("beta").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.jump_add)).performClick()
        compose.onNodeWithText(text(R.string.jump_search)).performTextInput("beta")
        compose.onNode(hasText("beta") and hasAnyAncestor(hasTestTag("jump-picker-list"))).performClick()
        compose.runOnIdle { assertEquals(listOf("alpha", "beta"), selected) }
        compose.onAllNodesWithText(text(R.string.common_remove)).onFirst().performClick()
        compose.onNodeWithText("1. beta").assertIsDisplayed()
    }

    @Test fun cancellingPickerDoesNotChangeDraftAndFourHopsDisableAdding() {
        var selected by mutableStateOf(emptyList<String>())
        val hosts = (1..5).map { host("hop-$it") }
        compose.setContent { MaterialTheme {
            JumpChainEditor(selected, { selected = it }, hosts, null, ConnectionProtocol.SSH)
        } }
        compose.onNodeWithText(text(R.string.jump_add)).performClick()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()); selected = hosts.take(4).map { it.id } }
        compose.onNodeWithText(text(R.string.jump_add)).assertIsNotEnabled()
        compose.onNodeWithText("hop-5").assertDoesNotExist()
    }

    @Test fun workspaceDialogOffersCreateOrAttachByName() {
        var selected: TmuxWorkspace? = null
        compose.setContent { MaterialTheme {
            WorkspaceDialog(load = { emptyList() }, onOpen = { selected = it }, onDismiss = {})
        } }
        compose.onNodeWithText(text(R.string.workspace_name)).performTextInput("work")
        compose.onNodeWithText(text(R.string.workspace_create_or_attach)).performClick()
        compose.runOnIdle { assertEquals(TmuxWorkspace(WorkspaceMode.CREATE_OR_ATTACH, "work"), selected) }
    }
}
