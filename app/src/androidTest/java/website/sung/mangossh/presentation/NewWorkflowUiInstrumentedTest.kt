package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** Local composition fixtures never instantiate the production ViewModel or mutate preferences. */
class NewWorkflowUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun largeFontShortEditorRequiresDiscardConfirmation() {
        val identity = SourceIdentity("/draft", 3, 1)
        val source = EditableRemoteText("/draft", "old", false, "\n", identity, RemoteTarget(identity, 384, true), ByteArray(32))
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(420.dp)) {
                    RemoteTextEditorScreen(RemoteEditorUiState("test", source, draft = "changed"), {}, { _, _ -> }, {}, { closed = true })
                } }
            }
        }
        compose.onNodeWithText(text(R.string.editor_save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.onNodeWithText(text(R.string.editor_unsaved)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(closed) }
        compose.onNodeWithText(text(R.string.editor_discard)).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun directOverwriteNeedsItsSeparateRiskConfirmation() {
        var decision: TransferConflictDecision? = null
        compose.setContent { MaterialTheme {
            TransferConflictDialog(TransferConflict("test", "target", ScpTransferDirection.UPLOAD, true, false)) { decision = it }
        } }
        compose.onNodeWithText(text(R.string.editor_direct)).performClick()
        compose.onNodeWithText(text(R.string.editor_direct_risk)).assertIsDisplayed()
        compose.runOnIdle { assertNull(decision) }
        compose.onAllNodesWithText(text(R.string.editor_direct)).onLast().performClick()
        compose.runOnIdle { assertEquals(TransferConflictAction.DIRECT_OVERWRITE, decision?.action) }
    }
    @Test fun busyKeyManagementBlocksRepeatedGenerationAndImport() {
        compose.setContent {
            MaterialTheme {
                KeysScreen(website.sung.mangossh.data.vault.VaultStatus.Ready, emptyList(),
                    { _, _ -> fail("Repeated generation") }, { _, _, _ -> fail("Repeated import") },
                    { fail("Unexpected removal") }, { _, _ -> fail("Unexpected export") }, busy = true)
            }
        }
        compose.onNodeWithText(text(R.string.ui_generate_key)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.ui_import_private_key)).assertIsNotEnabled()
    }

}
