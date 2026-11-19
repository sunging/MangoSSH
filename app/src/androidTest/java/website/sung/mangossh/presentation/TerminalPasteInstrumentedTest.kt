package website.sung.mangossh.presentation

import android.content.ClipData
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableSharedFlow
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.session.ServerResourceState
import website.sung.mangossh.session.TerminalClipboardCopy
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState

/** Exercises the screen and JNI emitter with a private clipboard and no connections. */
class TerminalPasteInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val sent = StringBuffer()
    private val clipboard = TestClipboard("first\nsecond")
    private var activeSession by mutableStateOf("synthetic-1")
    private var generation by mutableStateOf(0L)

    @Test fun cancellingSendsNothingAndConfirmationSendsOnlyThePreviewedSnapshot() {
        mount()
        paste()
        compose.onNodeWithTag("terminal_paste_preview").assertTextEquals("first\nsecond")
        compose.onNodeWithTag("terminal_paste_cancel").performClick()
        compose.onNodeWithTag("terminal_paste_confirmation").assertDoesNotExist()
        assertEquals("", sent.toString())

        paste()
        compose.onNodeWithTag("terminal_paste_preview").assertTextEquals("first\nsecond")
        compose.runOnIdle { clipboard.text = "replacement" }
        compose.onNodeWithTag("terminal_paste_submit").performClick()
        awaitSent("\u001b[200~first\nsecond\u001b[201~")
    }

    @Test fun aNewTargetAndLockGenerationEachWithdrawPendingPaste() {
        mount()
        paste()
        compose.onNodeWithTag("terminal_paste_confirmation").assertExists()
        compose.runOnIdle { activeSession = "synthetic-2" }
        compose.onNodeWithTag("terminal_paste_confirmation").assertDoesNotExist()

        paste()
        compose.onNodeWithTag("terminal_paste_confirmation").assertExists()
        compose.runOnIdle { generation++ }
        compose.onNodeWithTag("terminal_paste_confirmation").assertDoesNotExist()

        compose.runOnIdle { clipboard.text = "single line" }
        paste()
        awaitSent("\u001b[200~single line\u001b[201~")
    }

    private fun mount() {
        compose.setContent {
            val id = activeSession
            val emulator = remember(id) {
                TerminalEmulatorFactory.create(
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = Color.White,
                    defaultBackground = Color.Black,
                    onKeyboardInput = { sent.append(it.decodeToString()) },
                ).also { it.writeInput("\u001b[?2004h".encodeToByteArray()) }
            }
            DisposableEffect(emulator) { onDispose(emulator::close) }
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                TerminalSessionScreen(
                    session = TerminalSessionState(
                        id = id,
                        profileId = "synthetic",
                        title = "Terminal test",
                        endpoint = "terminal.invalid",
                        protocol = ConnectionProtocol.SSH,
                        phase = TerminalSessionPhase.OPEN,
                        username = "test",
                    ),
                    terminalEmulator = emulator,
                    appearance = TerminalAppearance(),
                    behavior = TerminalBehavior(),
                    shortcutConfig = TerminalShortcutConfig.defaults(),
                    clipboardCopies = remember { MutableSharedFlow<TerminalClipboardCopy>() },
                    pasteGeneration = { generation.takeIf { id == activeSession } },
                    resourceState = ServerResourceState(),
                    onRequestResources = {},
                    onResourceMonitoringChange = {},
                    onOpenFileBrowser = {},
                    onRequestLeave = {},
                    sessionFontSizeSp = null,
                    onSessionFontSizeChange = {},
                )
            }
        }
    }

    private fun paste() = compose.onNodeWithTag("terminal_shortcut_button_default-paste").performClick()

    private fun awaitSent(expected: String) {
        compose.waitUntil(5_000) { sent.length >= expected.length }
        assertEquals(expected, sent.toString())
    }

    private class TestClipboard(var text: String) : Clipboard {
        override suspend fun getClipEntry(): ClipEntry = ClipEntry(ClipData.newPlainText("test", text))
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    }
}
