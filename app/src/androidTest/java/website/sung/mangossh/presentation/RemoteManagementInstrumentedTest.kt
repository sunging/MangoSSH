package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.session.*

/** Synthetic UI state and callbacks only; no production ViewModel, vault, preferences or remote writes. */
class RemoteManagementInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = RemoteFileEntry("synthetic folder", "/fixture/folder", RemoteFileKind.DIRECTORY, permissions = "2755")
    private val file = RemoteFileEntry("synthetic.txt", "/fixture/synthetic.txt", RemoteFileKind.FILE, permissions = "644")
    private val state = RemoteBrowserUiState("synthetic-session", "Synthetic", "/fixture", entries = listOf(folder, file), isLoading = false, platform = RemotePlatform.POSIX)
    private fun label(id: Int) = context.getString(id)

    @Composable private fun Browser(value: RemoteBrowserUiState, onSelect: (String) -> Unit = {}, onEdit: (String) -> Unit = {},
        onOperation: (RemoteOperationRequest) -> Unit = {}) {
        RemoteFileBrowserScreen(value, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {},
            onEditText = onEdit, onToggleSelection = onSelect, onOperation = onOperation)
    }

    @Test fun foldersExposeTheSameManagementMenuAsFiles() {
        compose.setContent { MaterialTheme { Browser(state) } }
        compose.onAllNodesWithTag("remote-file-actions")[0].performClick()
        listOf(R.string.fm_rename, R.string.fm_delete, R.string.fm_permissions, R.string.fm_copy, R.string.fm_cut).forEach {
            compose.onNodeWithText(label(it)).assertExists()
        }
    }
    @Test fun fileMenuOpensEditorDirectly() {
        var edited: String? = null
        compose.setContent { MaterialTheme { Browser(state, onEdit = { edited = it }) } }
        compose.onAllNodesWithTag("remote-file-actions")[1].performClick()
        compose.onNodeWithText(label(R.string.editor_edit)).performClick()
        compose.runOnIdle { assertEquals(file.path, edited) }
    }
    @Test fun longPressEntersSelectionAndTappingAddsAnotherItem() {
        compose.setContent {
            var current by remember { mutableStateOf(state) }
            MaterialTheme { Browser(current, onSelect = { path -> current = current.copy(selectedPaths = current.selectedPaths + path) }) }
        }
        compose.onAllNodesWithTag("remote-file-entry")[0].performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.fm_selected, 1)).assertExists()
        compose.onAllNodesWithTag("remote-file-entry")[1].performClick()
        compose.onNodeWithText(context.getString(R.string.fm_selected, 2)).assertExists()
    }
    @Test fun pasteUsesClipboardIntentAndCurrentDirectory() {
        var request: RemoteOperationRequest? = null
        val clipboard = RemoteClipboard(state.sessionId, listOf("/source/item"), true)
        compose.setContent { MaterialTheme { Browser(state.copy(clipboard = clipboard), onOperation = { request = it }) } }
        compose.onNodeWithText(context.getString(R.string.fm_paste, 1)).performClick()
        compose.runOnIdle { assertEquals(RemoteOperationKind.MOVE, request?.kind); assertEquals(state.path, request?.destination) }
    }
    @Test fun windowsPermissionsExplainAclWithoutOfferingSave() {
        compose.setContent { MaterialTheme { RemoteManagementDialog(RemoteManagementAction(RemoteOperationKind.PERMISSIONS, listOf(folder.path)),
            state.copy(platform = RemotePlatform.WINDOWS), {}, {}, {}) } }
        compose.onNodeWithText(label(R.string.fm_windows_permissions)).assertExists()
        compose.onNodeWithText(label(R.string.fm_preview)).assertDoesNotExist()
    }
    @Test fun recursivePermissionsOfferSeparateFileAndFolderModes() {
        compose.setContent { MaterialTheme { RemoteManagementDialog(RemoteManagementAction(RemoteOperationKind.PERMISSIONS, listOf(folder.path)), state, {}, {}, {}) } }
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText(label(R.string.fm_file_mode)).assertExists()
        compose.onNodeWithText(label(R.string.fm_directory_mode)).assertExists()
        compose.onNodeWithText(label(R.string.fm_preview)).performScrollTo().assertIsDisplayed()
    }
    @Test fun directOverwriteRequiresItsOwnConfirmation() {
        var result: RemoteOperationDecision? = null
        val prompt = RemoteOperationPrompt("synthetic", "task", RemoteOperationRequest(state.sessionId, RemoteOperationKind.COPY),
            listOf("/source", "/target"), conflict = true, replaceAllowed = true, directOverwrite = true)
        compose.setContent { MaterialTheme { RemoteOperationConfirmation(prompt) { result = it } } }
        compose.onNodeWithText(label(R.string.fm_replace)).performClick()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithText(label(R.string.fm_direct_detail)).assertExists()
        compose.onNodeWithText(label(R.string.fm_confirm_direct)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(RemoteOperationChoice.DIRECT_OVERWRITE, result?.choice) }
    }
    @Test fun longPreviewAtDoubleFontKeepsConfirmationReachable() {
        var result: RemoteOperationDecision? = null
        val prompt = RemoteOperationPrompt("synthetic", "task", RemoteOperationRequest(state.sessionId, RemoteOperationKind.DELETE),
            List(12) { "/synthetic/" + "long-name-".repeat(12) + it })
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { MaterialTheme {
                RemoteOperationConfirmation(prompt) { result = it }
            } }
        }
        compose.onNodeWithText(label(R.string.fm_confirm)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(RemoteOperationChoice.PROCEED, result?.choice) }
    }
    @Test fun permissionsAtDoubleFontKeepAccessControlsAndPreviewReachable() {
        var request: RemoteOperationRequest? = null
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { MaterialTheme {
                RemoteManagementDialog(RemoteManagementAction(RemoteOperationKind.PERMISSIONS, listOf(folder.path)), state, {}, { request = it }, {})
            } }
        }
        compose.onNodeWithContentDescription(label(R.string.fm_owner) + " " + label(R.string.fm_read)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.fm_preview)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(RemoteOperationKind.PERMISSIONS, request?.kind) }
    }
}
