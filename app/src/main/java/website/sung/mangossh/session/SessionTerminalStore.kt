package website.sung.mangossh.session

import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * Retains terminal emulators while their transports run, including when no
 * terminal composable is currently attached.
 *
 * ConnectBot's emulator is explicitly service-compatible and owns its
 * scrollback internally. Holding it here prevents background output from being
 * dropped when Compose removes a terminal screen from composition.
 */
internal class SessionTerminalStore(
    private val onKeyboardInput: (sessionId: String, bytes: ByteArray) -> Unit,
    private val onResize: (sessionId: String, columns: Int, rows: Int) -> Unit,
) {
    private val emulators = ConcurrentHashMap<String, TerminalEmulator>()
    private val _clipboardCopies = MutableSharedFlow<TerminalClipboardCopy>(extraBufferCapacity = 8)

    /** UI-only OSC 52 copy requests; no clipboard content is persisted or logged. */
    val clipboardCopies = _clipboardCopies.asSharedFlow()

    /** Creates the terminal before the transport can start producing output. */
    fun create(sessionId: String) {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = INITIAL_ROWS,
            initialCols = INITIAL_COLUMNS,
            defaultForeground = Color(0xFFF4F4F4),
            defaultBackground = Color(0xFF101416),
            onKeyboardInput = { bytes -> onKeyboardInput(sessionId, bytes) },
            onResize = { dimensions -> onResize(sessionId, dimensions.columns, dimensions.rows) },
            onClipboardCopy = { selected ->
                _clipboardCopies.tryEmit(TerminalClipboardCopy(sessionId = sessionId, text = selected))
            },
            autoDetectUrls = true,
        )
        check(emulators.putIfAbsent(sessionId, emulator) == null) {
            "A terminal emulator already exists for this session."
        }
    }

    /** Returns the live emulator for a visible session, if it has not ended. */
    fun terminalFor(sessionId: String): TerminalEmulator? = emulators[sessionId]

    /** Writes transport bytes even while the UI is backgrounded. */
    fun append(sessionId: String, bytes: ByteArray) {
        emulators[sessionId]?.writeInput(bytes)
    }

    /** Releases the app's reference once a transport has fully ended. */
    fun remove(sessionId: String) {
        emulators.remove(sessionId)
    }

    private companion object {
        const val INITIAL_COLUMNS = 80
        const val INITIAL_ROWS = 24
    }
}
