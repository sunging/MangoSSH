package website.sung.mangossh.presentation.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.presentation.AppLockScreen
import website.sung.mangossh.security.AppLockConfiguration

/** PIN drafts are runtime-generated and must never enter saved instance state. */
class AppPinUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun pin(size: Int) = CharArray(size) { ('0'.code + SecureRandom().nextInt(10)).toChar() }.concatToString()
    private val configuration = AppLockConfiguration(false, false)
    private fun callbacks(save: (String) -> Unit = {}) = SecuritySettingsCallbacks(save, {}, {}, {}, {})

    @Test fun pinEditorRequiresMatchingSupportedLengthAndClearsAfterClosing() {
        var saved: String? = null
        compose.setContent { MaterialTheme { SecuritySettingsPage(SecuritySettingsState(configuration), callbacks { saved = it }) } }
        compose.onNodeWithText(text(R.string.ui_set_pin)).performClick()
        val valid = pin(4)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(valid.take(3))
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(valid.take(3))
        compose.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(valid)
        compose.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(valid)
        compose.onNodeWithText(text(R.string.common_save)).performClick()
        compose.runOnIdle { assertTrue(saved == valid) }
        compose.onNodeWithText(text(R.string.ui_set_pin)).performClick()
        compose.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test fun twelveDigitPinIsAcceptedAndDraftsAreNotRestored() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { SecuritySettingsPage(SecuritySettingsState(configuration), callbacks()) } }
        compose.onNodeWithText(text(R.string.ui_set_pin)).performClick()
        val valid = pin(12)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(valid)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(valid)
        compose.onNodeWithText(text(R.string.common_save)).assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { node ->
            assertTrue(node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty())
        }
    }

    @Test fun lockScreenClearsSubmittedAndRestoredPinAndBiometricsAreOptIn() {
        val restoration = StateRestorationTester(compose)
        var supplied = false
        restoration.setContent { MaterialTheme {
            AppLockScreen(false, configuration.copy(pinConfigured = true), null, { supplied = true }, {}, {})
        } }
        compose.onNodeWithText(text(R.string.ui_use_biometrics)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput(pin(6))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.ui_unlock_with_pin)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput(pin(6))
        compose.onNodeWithText(text(R.string.ui_unlock_with_pin)).performClick()
        compose.runOnIdle { assertTrue(supplied) }
        compose.onNodeWithText(text(R.string.ui_unlock_with_pin)).assertIsNotEnabled()
    }
}
