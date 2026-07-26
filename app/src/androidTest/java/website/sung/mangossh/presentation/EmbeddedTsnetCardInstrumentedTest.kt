package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus

class EmbeddedTsnetCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowCardExposesBothEnrollmentEntrypointsAndClearsDialogInput() {
        var submittedLength = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    EmbeddedTsnetCard(
                        status = EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED),
                        onBeginBrowserEnrollment = {},
                        onBeginAuthKeyEnrollment = {
                            submittedLength = it.size
                            it.fill('\u0000')
                        },
                        onLogout = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("embedded_tsnet_browser_login").assertIsDisplayed()
        composeRule.onNodeWithTag("embedded_tsnet_auth_key_login").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("embedded_tsnet_auth_key_input").performTextInput("xy")
        composeRule.onNodeWithTag("embedded_tsnet_auth_key_confirm").performClick()
        composeRule.onAllNodesWithTag("embedded_tsnet_auth_key_input").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(2, submittedLength) }
    }
}
