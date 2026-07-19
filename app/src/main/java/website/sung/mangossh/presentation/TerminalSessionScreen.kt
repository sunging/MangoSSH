package website.sung.mangossh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.Terminal
import website.sung.mangossh.session.TerminalOutput
import website.sung.mangossh.session.TerminalOutputSource
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState
import website.sung.mangossh.session.ServerResourceSnapshot
import website.sung.mangossh.domain.ConnectionProtocol

@Composable
fun TerminalSessionScreen(
    session: TerminalSessionState,
    output: SharedFlow<TerminalOutput>,
    onSend: (ByteArray) -> Unit,
    onResize: (columns: Int, rows: Int) -> Unit,
    resourceSnapshot: ServerResourceSnapshot?,
    onRequestResources: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sendCurrent by rememberUpdatedState(onSend)
    val resizeCurrent by rememberUpdatedState(onResize)
    val clipboardCurrent by rememberUpdatedState(clipboard)
    val isOpen = session.phase == TerminalSessionPhase.OPEN
    val supportsSshChannels = session.protocol == ConnectionProtocol.SSH
    var showResourceReport by remember(session.id) { mutableStateOf(false) }
    val pasteFromClipboard: () -> Unit = {
        val text = clipboard.getText()?.text?.takeIf(String::isNotEmpty)
        if (text != null) onSend(text.encodeToByteArray())
    }

    val emulator = remember(session.id) {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color(0xFFF4F4F4),
            defaultBackground = Color(0xFF101416),
            onKeyboardInput = { bytes -> sendCurrent(bytes) },
            onResize = { dimensions -> resizeCurrent(dimensions.columns, dimensions.rows) },
            onClipboardCopy = { selected -> clipboardCurrent.setText(AnnotatedString(selected)) },
            autoDetectUrls = true,
        )
    }

    val language = LocalConfiguration.current.locales[0]?.language
    LaunchedEffect(emulator, session.id, language) {
        output
            .filter { it.sessionId == session.id }
            .collect { item ->
                val bytes = if (item.source == TerminalOutputSource.LOCALIZABLE_NOTICE) {
                    MangoUiLiteralLocalization
                        .resolve(item.bytes.decodeToString(), language)
                        .encodeToByteArray()
                } else {
                    item.bytes
                }
                emulator.writeInput(bytes)
            }
    }
    val localizedSessionDetail = session.detail?.let(::localizedUiLiteral)
    LaunchedEffect(session.id, localizedSessionDetail) {
        localizedSessionDetail?.let { detail ->
            emulator.writeInput("\r\n[MangoSSH] $detail\r\n".encodeToByteArray())
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101416),
        contentColor = Color(0xFFF4F4F4),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${session.endpoint} · ${session.phase.label()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMinimize) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = localizedUiLiteral("返回主机列表"),
                    )
                }
                IconButton(
                    onClick = {
                        showResourceReport = true
                        onRequestResources()
                    },
                    enabled = isOpen && supportsSshChannels,
                ) {
                    Icon(Icons.Outlined.Storage, contentDescription = localizedUiLiteral("服务器资源"))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = localizedUiLiteral("关闭会话"))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Terminal(
                    terminalEmulator = emulator,
                    modifier = Modifier.fillMaxSize(),
                    keyboardEnabled = isOpen,
                    showSoftKeyboard = isOpen,
                    onPasteRequest = pasteFromClipboard,
                    onInterceptKey = { event ->
                        if (
                            event.type == KeyEventType.KeyDown &&
                            event.isCtrlPressed &&
                            event.key == Key.V
                        ) {
                            pasteFromClipboard()
                            true
                        } else {
                            false
                        }
                    },
                )
            }

            TerminalKeyBar(
                enabled = isOpen,
                onSend = onSend,
                onPaste = pasteFromClipboard,
            )
        }
    }

    if (showResourceReport) {
        AlertDialog(
            onDismissRequest = { showResourceReport = false },
            title = { Text(localizedUiLiteral("服务器资源")) },
            text = {
                SelectionContainer {
                    Text(
                        resourceSnapshot?.report ?: localizedUiLiteral("正在从服务器读取资源信息…"),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRequestResources() }) { Text(localizedUiLiteral("刷新")) }
            },
            dismissButton = {
                TextButton(onClick = { showResourceReport = false }) { Text(localizedUiLiteral("关闭")) }
            },
        )
    }
}

@Composable
private fun TerminalKeyBar(
    enabled: Boolean,
    onSend: (ByteArray) -> Unit,
    onPaste: () -> Unit,
) {
    val keys = listOf(
        "ESC" to "\u001B",
        "TAB" to "\t",
        "Ctrl+C" to "\u0003",
        "Ctrl+D" to "\u0004",
        "Ctrl+L" to "\u000C",
        "Ctrl+Z" to "\u001A",
        "↑" to "\u001B[A",
        "↓" to "\u001B[B",
        "←" to "\u001B[D",
        "→" to "\u001B[C",
        "|" to "|",
        "~" to "~",
        "/" to "/",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onPaste,
            enabled = enabled,
            label = { Text(localizedUiLiteral("粘贴")) },
            leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
        )
        keys.forEach { (label, value) ->
            AssistChip(
                onClick = { onSend(value.encodeToByteArray()) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}

/** Returns the localized, application-owned label for a live session phase. */
@Composable
internal fun TerminalSessionPhase.label(): String = localizedUiLiteral(
    when (this) {
        TerminalSessionPhase.CONNECTING -> "连接中"
        TerminalSessionPhase.VERIFYING_HOST_KEY -> "验证指纹"
        TerminalSessionPhase.AUTHENTICATING -> "认证中"
        TerminalSessionPhase.OPEN -> "已连接"
        TerminalSessionPhase.FAILED -> "失败"
        TerminalSessionPhase.CLOSED -> "已关闭"
    },
)
