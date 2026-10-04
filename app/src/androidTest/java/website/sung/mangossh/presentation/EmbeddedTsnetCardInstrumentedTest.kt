package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
                        onBeginAuthKeyEnrollment = { key, _ ->
                            submittedLength = key.size
                            key.fill('\u0000')
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

    @Test
    fun controlServerIsValidatedAndNormalizedForNewEnrollment() {
        var submittedServer: String? = null
        composeRule.setContent {
            MaterialTheme {
                EmbeddedTsnetCard(
                    status = EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED),
                    onBeginBrowserEnrollment = { submittedServer = it },
                    onBeginAuthKeyEnrollment = { key, _ -> key.fill('\u0000') },
                    onLogout = {},
                )
            }
        }

        val field = composeRule.onNodeWithTag("embedded_tsnet_control_url")
        field.performTextInput("http://headscale.example.com")
        composeRule.onNodeWithTag("embedded_tsnet_browser_login").assertIsNotEnabled()
        composeRule.onNodeWithTag("embedded_tsnet_auth_key_login").assertIsNotEnabled()

        field.performTextClearance()
        field.performTextInput("https://Headscale.example.com/")
        composeRule.onNodeWithTag("embedded_tsnet_browser_login").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("https://headscale.example.com", submittedServer) }
    }

    @Test
    fun registeredNodeShowsItsServerReadOnly() {
        composeRule.setContent {
            MaterialTheme {
                EmbeddedTsnetCard(
                    status = EmbeddedTsnetStatus(EmbeddedTsnetPhase.READY_IDLE, authKeyAllowed = false),
                    controlUrl = "https://headscale.example.com",
                    onBeginBrowserEnrollment = {},
                    onBeginAuthKeyEnrollment = { key, _ -> key.fill('\u0000') },
                    onLogout = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("embedded_tsnet_control_url").assertCountEquals(0)
        composeRule.onNodeWithTag("embedded_tsnet_control_url_current").assertIsDisplayed()
    }
}
