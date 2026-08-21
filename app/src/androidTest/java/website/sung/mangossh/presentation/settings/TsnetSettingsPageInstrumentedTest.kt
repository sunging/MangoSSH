package website.sung.mangossh.presentation.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.session.tsnet.EmbeddedTsnetBuildInfo
import website.sung.mangossh.session.tsnet.EmbeddedTsnetPhase
import website.sung.mangossh.session.tsnet.EmbeddedTsnetStatus

class TsnetSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageShowsBothTheEnrollmentCardAndTheVendoredVersionLine() {
        composeRule.setContent {
            MaterialTheme {
                TsnetSettingsPage(
                    status = EmbeddedTsnetStatus(EmbeddedTsnetPhase.UNENROLLED),
                    callbacks = TsnetSettingsCallbacks(
                        onBeginBrowserEnrollment = {},
                        onBeginAuthKeyEnrollment = {},
                        onLogout = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("embedded_tsnet_status").assertIsDisplayed()
        composeRule.onNodeWithTag("embedded_tsnet_version")
            .assertIsDisplayed()
            .assertTextEquals("Embedded node version ${EmbeddedTsnetBuildInfo.TAILSCALE_VERSION}")
    }
}
