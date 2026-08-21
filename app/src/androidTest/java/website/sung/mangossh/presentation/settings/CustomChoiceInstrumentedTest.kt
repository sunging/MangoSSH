package website.sung.mangossh.presentation.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.ConnectionPreferences
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior

/**
 * A preference store accepts any in-range value, so a persisted value the
 * picker does not list — 45-second keepalive, 1500-line scrollback — must still
 * appear in the dialog as the current selection. Without it the dialog opens
 * with nothing selected and the stored value is lost on the next tap.
 */
class CustomChoiceInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keepalivePickerOffersAPersistedValueTheChoiceListOmits() {
        var keepalive by mutableStateOf(45)
        composeRule.setContent {
            MaterialTheme {
                ConnectionSettingsPage(
                    state = ConnectionSettingsState(ConnectionPreferences(keepaliveSeconds = keepalive)),
                    callbacks = ConnectionSettingsCallbacks(
                        onSetKeepaliveSeconds = { keepalive = it },
                        onSetConnectTimeoutSeconds = {},
                        onSetSshTerminalType = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("settings_keepalive_control").performClick()
        composeRule.onNodeWithTag("settings_keepalive_45").assertIsSelected()
        ConnectionPreferences.KEEPALIVE_CHOICES.forEach { seconds ->
            composeRule.onNodeWithTag("settings_keepalive_$seconds").assertExists()
        }

        composeRule.onNodeWithTag("settings_keepalive_60").performClick()
        composeRule.runOnIdle { assertEquals(60, keepalive) }
    }

    @Test
    fun connectTimeoutPickerOffersAPersistedValueTheChoiceListOmits() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionSettingsPage(
                    state = ConnectionSettingsState(ConnectionPreferences(connectTimeoutSeconds = 45)),
                    callbacks = ConnectionSettingsCallbacks(
                        onSetKeepaliveSeconds = {},
                        onSetConnectTimeoutSeconds = {},
                        onSetSshTerminalType = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("settings_connect_timeout_control").performClick()
        composeRule.onNodeWithTag("settings_connect_timeout_45").assertIsSelected()
    }

    @Test
    fun scrollbackAndPinchZoomPickersOfferPersistedValuesTheChoiceListsOmit() {
        var behavior by mutableStateOf(
            TerminalBehavior(scrollbackLines = 1_500, maxPinchZoomScale = 2.5f),
        )
        composeRule.setContent {
            MaterialTheme {
                TerminalSettingsPage(
                    modifier = Modifier.testTag(TERMINAL_PAGE),
                    state = TerminalSettingsState(appearance = TerminalAppearance(), behavior = behavior),
                    callbacks = TerminalSettingsCallbacks(
                        onSetFont = {},
                        onSetFontSize = {},
                        onSetTheme = {},
                        onSetCustomColors = {},
                        onResetAppearance = {},
                        onSetScrollbackLines = { behavior = behavior.copy(scrollbackLines = it) },
                        onSetBoldAsBright = {},
                        onSetAutoDetectUrls = {},
                        onSetKeepScreenOn = {},
                        onSetMaxPinchZoomScale = { behavior = behavior.copy(maxPinchZoomScale = it) },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(TERMINAL_PAGE).performScrollToNode(hasTestTag("settings_scrollback_control"))
        composeRule.onNodeWithTag("settings_scrollback_control").performClick()
        composeRule.onNodeWithTag("settings_scrollback_1500").assertIsSelected()
        composeRule.onNodeWithTag("settings_scrollback_2500").performClick()
        composeRule.runOnIdle { assertEquals(2_500, behavior.scrollbackLines) }

        composeRule.onNodeWithTag(TERMINAL_PAGE).performScrollToNode(hasTestTag("settings_pinch_zoom_control"))
        composeRule.onNodeWithTag("settings_pinch_zoom_control").performClick()
        composeRule.onNodeWithTag("settings_pinch_zoom_2.5").assertIsSelected()
    }

    private companion object {
        const val TERMINAL_PAGE = "terminal_settings_page"
    }
}
