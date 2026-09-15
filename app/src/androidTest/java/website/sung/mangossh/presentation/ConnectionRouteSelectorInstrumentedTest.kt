package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.*

/** Route choices remain inline and reachable on narrow screens without an extra dialog. */
class ConnectionRouteSelectorInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun allThreeRoutesRemainUsableAtNarrowWidth() {
        val controller = HostEditorController(HostEditorDraft(hostname = "synthetic.invalid", username = "test",
            authentication = AuthenticationMethod.PASSWORD)).apply { page = HostEditorPage.CONNECTION }
        composeRule.setContent { MaterialTheme {
            Box(Modifier.width(240.dp).height(600.dp)) {
                HostEditorScreen(controller, emptyList(), ConnectionPreferences(), emptyList(), emptyList(), {}, {})
            }
        } }
        ConnectionRoute.entries.forEachIndexed { index, route ->
            ConnectionRoute.entries.indices.forEach { option ->
                composeRule.onNodeWithTag("host_editor_route_option_$option").performScrollTo().assertIsDisplayed()
            }
            composeRule.onNodeWithTag("host_editor_route_option_$index").performScrollTo().performClick()
            composeRule.onAllNodes(isDialog()).assertCountEquals(0)
            composeRule.runOnIdle { assertEquals(route, controller.draft.route) }
        }
        composeRule.runOnIdle {
            assertEquals(AuthenticationMethod.TAILSCALE_SSH, controller.draft.authentication)
            controller.draft = controller.draft.copy(authentication = AuthenticationMethod.PASSWORD)
        }
        composeRule.onNodeWithTag("host_editor_route_option_2").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(AuthenticationMethod.PASSWORD, controller.draft.authentication) }
    }
}
