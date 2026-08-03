package website.sung.mangossh.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

class TerminalShortcutSettingsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addsCtrlAltChordAndCommitsOnlyWhenOuterEditorSaves() {
        var config by mutableStateOf(TerminalShortcutConfig(emptyList()))
        composeRule.setContent {
            MaterialTheme {
                TerminalShortcutSettingsCard(config = config, onSave = { config = it })
            }
        }

        composeRule.onNodeWithTag("terminal_shortcuts_card").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_shortcuts_customize").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_add").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_kind_chord").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_modifier_alt").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_character").performTextReplacement("T")
        composeRule.onNodeWithTag("terminal_shortcut_item_save").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<TerminalShortcutItem>(), config.items) }
        composeRule.onNodeWithTag("terminal_shortcuts_save").performClick()

        composeRule.runOnIdle {
            assertEquals(1, config.items.size)
            assertEquals(
                TerminalShortcutAction.Chord(
                    setOf(TerminalModifier.CTRL, TerminalModifier.ALT),
                    TerminalShortcutKey.Character('T'),
                ),
                config.items.single().action,
            )
        }
    }

    @Test
    fun editsHidesMovesAndDeletesUsingDraftState() {
        val first = TerminalShortcutItem("home", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.HOME))
        val second = TerminalShortcutItem("end", TerminalShortcutAction.SpecialKey(TerminalSpecialKey.END))
        var config by mutableStateOf(TerminalShortcutConfig(listOf(first, second)))
        composeRule.setContent {
            MaterialTheme {
                TerminalShortcutSettingsCard(config = config, onSave = { config = it })
            }
        }

        composeRule.onNodeWithTag("terminal_shortcuts_customize").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_edit_0").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_label").performTextReplacement("Home key")
        composeRule.onNodeWithTag("terminal_shortcut_item_save").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_visible_0").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_move_down_0").performClick()
        composeRule.onNodeWithTag("terminal_shortcut_delete_0").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_save").performClick()

        composeRule.runOnIdle {
            assertEquals("home", config.items.single().id)
            assertEquals("Home key", config.items.single().labelOverride)
            assertFalse(config.items.single().visible)
        }
    }

    @Test
    fun restoreDefaultsRemainsDraftUntilSaved() {
        var config by mutableStateOf(TerminalShortcutConfig(emptyList()))
        composeRule.setContent {
            MaterialTheme {
                TerminalShortcutSettingsCard(config = config, onSave = { config = it })
            }
        }

        composeRule.onNodeWithTag("terminal_shortcuts_customize").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_restore").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_cancel").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<TerminalShortcutItem>(), config.items) }

        composeRule.onNodeWithTag("terminal_shortcuts_customize").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_restore").performClick()
        composeRule.onNodeWithTag("terminal_shortcuts_save").performClick()
        composeRule.runOnIdle { assertEquals(TerminalShortcutConfig.defaults(), config) }
    }
}
