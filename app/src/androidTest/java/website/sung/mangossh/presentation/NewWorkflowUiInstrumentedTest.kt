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
                    { _, _, _, _ -> fail("Repeated generation") }, { _, _, _, _ -> fail("Repeated import") },
                    { fail("Unexpected removal") }, { _, _, _ -> fail("Unexpected export") }, busy = true)
            }
        }
        compose.onNodeWithText(text(R.string.ui_generate_key)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.ui_import_private_key)).assertIsNotEnabled()
    }
    @Test fun editingAKeySubmitsTheNewNameWithoutTouchingItsEncryption() {
        // Placeholder key material: the dialog only inspects headers and never decodes it.
        val key = website.sung.mangossh.data.vault.StoredSshKey("key", "Old name", "ssh-ed25519",
            "ssh-ed25519 AAAA Old name", "SHA256:placeholder", "placeholder")
        var edit: Pair<String, website.sung.mangossh.data.keys.KeyEditRequest>? = null
        compose.setContent {
            MaterialTheme {
                KeysScreen(website.sung.mangossh.data.vault.VaultStatus.Ready, listOf(key),
                    { _, _, _, _ -> fail("Unexpected generation") }, { _, _, _, _ -> fail("Unexpected import") },
                    { fail("Unexpected removal") }, { _, _, _ -> fail("Unexpected export") }, busy = false,
                    onEdit = { id, request -> edit = id to request })
            }
        }
        compose.onNodeWithText(text(R.string.common_edit)).performClick()
        compose.onNode(hasSetTextAction() and hasText("Old name")).performTextReplacement("New name")
        compose.onNodeWithText(text(R.string.common_save)).performClick()
        compose.runOnIdle {
            assertEquals("key", edit?.first)
            assertEquals("New name", edit?.second?.label)
            assertEquals(website.sung.mangossh.data.keys.KeyPassphraseChange.Keep, edit?.second?.passphrase)
            assertEquals(false, edit?.second?.rememberPassphrase)
        }
    }
    @Test fun exportMenuOffersClipboardFileAndShareForBothKeyHalves() {
        val key = website.sung.mangossh.data.vault.StoredSshKey("key", "Key", "ssh-ed25519",
            "ssh-ed25519 AAAA Key", "SHA256:placeholder", "placeholder")
        val exports = mutableListOf<Triple<String, KeyExportPart, KeyExportTarget>>()
        compose.setContent {
            MaterialTheme {
                KeysScreen(website.sung.mangossh.data.vault.VaultStatus.Ready, listOf(key),
                    { _, _, _, _ -> fail("Unexpected generation") }, { _, _, _, _ -> fail("Unexpected import") },
                    { fail("Unexpected removal") }, { id, part, target -> exports += Triple(id, part, target) }, busy = false)
            }
        }
        // The public half is preselected and carries no private-key warning.
        compose.onNodeWithText(text(R.string.ui_export)).performClick()
        compose.onNodeWithText(text(R.string.ui_export_to_file)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_private_key_export_warning)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ui_copy_to_clipboard)).performClick()
        compose.onNodeWithText(text(R.string.ui_export)).performClick()
        compose.onNodeWithText(text(R.string.ui_private_key)).performClick()
        compose.onNodeWithText(text(R.string.ui_private_key_export_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_share_to_app)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("key", KeyExportPart.PUBLIC, KeyExportTarget.Clipboard),
                Triple("key", KeyExportPart.PRIVATE, KeyExportTarget.Share)), exports)
        }
    }
    @Test fun keyUnlockPromptReturnsThePassphraseAndTheRememberChoice() {
        val prompt = SessionPrompt.Authentication("request", "session",
            SessionPromptText.App(SessionPromptTextKind.UNLOCK_KEY_TITLE, "Key"),
            SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_INSTRUCTION),
            listOf(AuthenticationField(SessionPromptText.App(SessionPromptTextKind.KEY_PASSPHRASE_FIELD), false),
                AuthenticationField(SessionPromptText.App(SessionPromptTextKind.REMEMBER_KEY_PASSPHRASE), true, toggle = true)))
        var answer: List<String>? = null
        compose.setContent { MaterialTheme { SessionPromptDialog(prompt) { answer = it } } }
        // Placeholder input: the dialog only relays text and never decrypts anything.
        compose.onNode(hasSetTextAction()).performTextInput("placeholder")
        compose.onNodeWithText(text(R.string.ui_remember_passphrase)).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(text(R.string.common_submit)).performClick()
        compose.runOnIdle { assertEquals(listOf("placeholder", AuthenticationField.TOGGLE_ON), answer) }
    }

}
