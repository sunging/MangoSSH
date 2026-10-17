package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.data.vault.PortForwardRule
import website.sung.mangossh.data.vault.PortForwardType
import website.sung.mangossh.domain.AuthenticationMethod
import website.sung.mangossh.domain.ConnectionProfile

/** Pure editor tests use synthetic endpoints and never touch the application's vault. */
class PortForwardEditorInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val example = ConnectionProfile(id = "synthetic", label = "Example",
        hostname = "synthetic.invalid", username = "test", authentication = AuthenticationMethod.AUTOMATIC)
    private fun show(initial: PortForwardRule? = null, hosts: List<ConnectionProfile> = listOf(example),
        onSave: (PortForwardRule, EditorSaveOperation) -> Unit = { _, _ -> }) {
        compose.setContent { MaterialTheme {
            PortForwardRuleDialog(initial, hosts, {}, onSave)
        } }
    }

    @Test fun newRulesPrefillLoopbackAndExplainAllThreeDirections() {
        show()
        compose.onNodeWithTag("forward_bind_address").performScrollTo().assertTextContains("127.0.0.1")
        compose.onNodeWithTag("forward_destination_address").performScrollTo().assertTextContains("127.0.0.1")
        compose.onNodeWithText(context.getString(R.string.port_forward_local_summary)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("forward_type_REMOTE").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.port_forward_remote_summary)).assertExists()
        compose.onNodeWithTag("forward_type_DYNAMIC").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.port_forward_dynamic_summary)).assertExists()
        compose.onNodeWithTag("forward_destination_address").assertDoesNotExist()
    }

    @Test fun switchingTypesAndEditingRetainsEnteredAddresses() {
        show(PortForwardRule("rule", "synthetic", PortForwardType.LOCAL, "127.0.0.2", 1080, "localhost", 8080))
        compose.onNodeWithTag("forward_bind_address").performScrollTo().assertTextContains("127.0.0.2")
        compose.onNodeWithTag("forward_destination_address").performScrollTo().assertTextContains("localhost")
            .performTextReplacement("127.0.0.3")
        compose.onNodeWithTag("forward_type_DYNAMIC").performScrollTo().performClick()
        compose.onNodeWithTag("forward_type_REMOTE").performScrollTo().performClick()
        compose.onNodeWithTag("forward_destination_address").performScrollTo().assertTextContains("127.0.0.3")
        compose.onNodeWithTag("forward_bind_address").performScrollTo().assertTextContains("127.0.0.2")
    }

    @Test fun manyHostsStayCollapsedAndSearchSelectsTheCorrectDuplicateName() {
        val hosts = (0..49).map { example.copy(id = "host-$it", label = "Example", hostname = "host-$it.synthetic.invalid") }
        var saved: PortForwardRule? = null
        show(PortForwardRule("rule", "host-17", PortForwardType.LOCAL, "127.0.0.2", 1080, "localhost", 8080), hosts) { rule, _ -> saved = rule }
        compose.onNodeWithTag("forward_host").performScrollTo().assertTextContains("Example")
        compose.onNodeWithTag("forward_host_options").assertDoesNotExist()
        compose.onNodeWithTag("forward_host_option_0").assertDoesNotExist()
        compose.onNodeWithTag("forward_bind_address").performScrollTo().performTextReplacement("127.0.0.3")
        compose.onNodeWithTag("forward_host").performScrollTo().performClick()
        compose.onNodeWithTag("forward_host_search").performTextInput("HOST-42.SYNTHETIC.INVALID")
        compose.onNodeWithTag("forward_host_option_17").assertDoesNotExist()
        compose.onNodeWithTag("forward_host_option_42").assertTextContains("test@host-42.synthetic.invalid", substring = true)
            .performClick()
        compose.onNodeWithTag("forward_host_options").assertDoesNotExist()
        compose.onNodeWithTag("forward_bind_address").performScrollTo().assertTextContains("127.0.0.3")
        compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("host-42", saved?.profileId)
            org.junit.Assert.assertEquals("127.0.0.3", saved?.bindHost)
        }
    }

    @Test fun cancellingAHostSearchPreservesTheEditedRuleHost() {
        var saved: PortForwardRule? = null
        show(PortForwardRule("rule", example.id, PortForwardType.LOCAL, "127.0.0.2", 1080, "localhost", 8080),
            listOf(example, example.copy(id = "other", label = "Other"))) { rule, _ -> saved = rule }
        compose.onNodeWithTag("forward_host").performScrollTo().performClick()
        compose.onNodeWithTag("forward_host_search").performTextInput("no-matching-synthetic-host")
        compose.onNodeWithText(context.getString(R.string.jump_no_matches)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.common_cancel))[
            compose.onAllNodesWithText(context.getString(R.string.common_cancel)).fetchSemanticsNodes().lastIndex].performClick()
        compose.onNodeWithTag("forward_host_options").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(example.id, saved?.profileId) }
    }
}
