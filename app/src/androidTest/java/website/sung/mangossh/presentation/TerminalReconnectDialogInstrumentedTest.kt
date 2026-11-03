package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the production confirmation without opening a transport or touching the vault. */
class TerminalReconnectDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private val confirmations = mutableListOf<Boolean>()

    private fun mount(workspace: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                if (visible) TerminalReconnectDialog(workspace,
                    onConfirm = { confirmations += it; visible = false },
                    onDismiss = { visible = false })
            }
        }
    }

    @Test fun confirmationDefaultsToNoSnippet() {
        mount()
        compose.onNodeWithTag("terminal_reconnect_snippet").assertIsOff()
        compose.onNodeWithTag("terminal_reconnect_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(false), confirmations) }
    }

    @Test fun cancellingDoesNotReconnectAndReopeningRequiresFreshSnippetConsent() {
        mount()
        compose.onNodeWithTag("terminal_reconnect_snippet").performClick()
        compose.onNodeWithTag("terminal_reconnect_cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), confirmations); visible = true }
        compose.onNodeWithTag("terminal_reconnect_snippet").assertIsOff().performClick()
        compose.onNodeWithTag("terminal_reconnect_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(true), confirmations) }
    }

    @Test fun workspaceConfirmationCannotReplayASnippet() {
        mount(workspace = true)
        compose.onNodeWithTag("terminal_reconnect_snippet").assertDoesNotExist()
        compose.onNodeWithTag("terminal_reconnect_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(false), confirmations) }
    }
}
