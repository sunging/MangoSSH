package website.sung.mangossh.presentation

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.then
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalThemeId
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.ui.theme.terminalChromeColorScheme

/** Exercises the production confirmation without opening a transport or touching the vault. */
class TerminalReconnectDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private val confirmations = mutableListOf<Boolean>()
    private var expectedTitle = ""
    private var phase by mutableStateOf(TerminalSessionPhase.OPEN)
    private var sessionId by mutableStateOf("first")

    private fun mountLifecycle() {
        compose.setContent {
            if (visible) {
                var requested by rememberTerminalReconnectRequest(sessionId, phase)
                if (requested) TerminalReconnectDialog(false, false, TerminalAppearance().colorScheme,
                    onConfirm = { confirmations += it; requested = false },
                    onDismiss = { requested = false })
            }
        }
    }

    @Test fun closingPromptsDirectlyAndCancellationDoesNotReconnectOrReopen() {
        mountLifecycle()
        compose.onNodeWithTag("terminal_reconnect_dialog").assertDoesNotExist()
        compose.runOnIdle { phase = TerminalSessionPhase.CLOSED }
        compose.onNodeWithTag("terminal_reconnect_dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), confirmations) }
        compose.onNodeWithTag("terminal_reconnect_cancel").performClick()
        compose.onNodeWithTag("terminal_reconnect_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), confirmations) }
    }

    @Test fun revisitingAClosedTerminalPromptsAgain() {
        phase = TerminalSessionPhase.CLOSED
        mountLifecycle()
        compose.onNodeWithTag("terminal_reconnect_cancel").performClick()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("terminal_reconnect_dialog").assertIsDisplayed()
        compose.onNodeWithTag("terminal_reconnect_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(false), confirmations) }
    }

    @Test fun openingAnotherClosedTerminalHasItsOwnConfirmation() {
        phase = TerminalSessionPhase.CLOSED
        mountLifecycle()
        compose.onNodeWithTag("terminal_reconnect_cancel").performClick()
        compose.runOnIdle { sessionId = "second" }
        compose.onNodeWithTag("terminal_reconnect_dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), confirmations) }
    }

    private fun mount(workspace: Boolean = false, snippet: Boolean = true,
        terminalTheme: TerminalThemeId = TerminalThemeId.MANGO_DARK,
        size: DpSize = DpSize(393.dp, 800.dp), fontScale: Float = 1f, locale: String = "en") {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }).resources
        expectedTitle = resources.getString(R.string.terminal_reconnect)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size) then
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                // Deliberately oppose the terminal palette to catch inheriting the app theme.
                CompositionLocalProvider(LocalResources provides resources) {
                    MaterialTheme(colorScheme = if (terminalTheme == TerminalThemeId.MANGO_DARK) lightColorScheme() else darkColorScheme()) {
                        if (visible) TerminalReconnectDialog(workspace, snippet,
                            TerminalAppearance(theme = terminalTheme).colorScheme,
                            onConfirm = { confirmations += it; visible = false },
                            onDismiss = { visible = false })
                    }
                }
            }
        }
    }

    @Test fun unavailableSnippetIsHiddenAndCannotBeReplayed() {
        mount(snippet = false)
        compose.onNodeWithTag("terminal_reconnect_snippet").assertDoesNotExist()
        assertPaletteAndCapture(TerminalThemeId.MANGO_DARK, "dark-no-snippet")
        compose.onNodeWithTag("terminal_reconnect_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(false), confirmations) }
    }

    @Test fun tappingTheLabelTogglesTheWholeOption() {
        mount()
        compose.onNodeWithTag("terminal_reconnect_snippet_label", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("terminal_reconnect_snippet").assertIsOn()
        compose.onNodeWithTag("terminal_reconnect_snippet_label", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("terminal_reconnect_snippet").assertIsOff()
    }

    @Test fun darkTerminalUsesDarkDialogEvenWithLightAppTheme() {
        mount()
        assertPaletteAndCapture(TerminalThemeId.MANGO_DARK, "dark-en")
    }

    @Test fun lightTerminalUsesLightDialogEvenWithDarkAppTheme() {
        mount(terminalTheme = TerminalThemeId.SOLARIZED_LIGHT, locale = "zh-CN")
        assertPaletteAndCapture(TerminalThemeId.SOLARIZED_LIGHT, "light-zh")
    }

    @Test fun largeTextInCompactLandscapeKeepsActionsReachable() {
        mount(size = DpSize(640.dp, 360.dp), fontScale = 1.5f)
        compose.onNodeWithTag("terminal_reconnect_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(false), confirmations) }
    }

    private fun assertPaletteAndCapture(theme: TerminalThemeId, name: String) {
        compose.onAllNodesWithText(expectedTitle).assertCountEquals(2)
        val image = compose.onNodeWithTag("terminal_reconnect_dialog").captureToImage()
        val pixels = image.toPixelMap()
        val expected = terminalChromeColorScheme(TerminalAppearance(theme = theme).colorScheme).surfaceContainerHigh.toArgb()
        assertEquals(expected, pixels[pixels.width / 2, 8].toArgb())
        if (InstrumentationRegistry.getArguments().getString("captureReconnectPreview") == "true") {
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "reconnect-dialog-preview").apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                assertTrue(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
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
