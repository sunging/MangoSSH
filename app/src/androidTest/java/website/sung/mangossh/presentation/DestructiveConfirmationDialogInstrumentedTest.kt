package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DestructiveConfirmationDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun irreversibleActionRunsOnlyAfterExplicitConfirmation() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                DestructiveConfirmationDialog(
                    title = "移除主机？",
                    message = "此操作无法撤销。",
                    onDismiss = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithTag("removal-confirmation-dialog").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!confirmed) }
        composeRule.onNodeWithTag("confirm-removal").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
