package website.sung.mangossh.presentation

import android.content.ClipData
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import website.sung.mangossh.R
import website.sung.mangossh.session.TerminalClipboardCopy
import website.sung.mangossh.session.TerminalSessionPhase
import website.sung.mangossh.session.TerminalSessionState
import website.sung.mangossh.session.ServerResourceSnapshot
import website.sung.mangossh.domain.ConnectionProtocol
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.ui.theme.terminalChromeColorScheme

/**
 * Renders an already-running terminal emulator.
 *
 * The emulator belongs to the application session runtime rather than this
 * composable, so leaving this screen keeps the remote session and scrollback
 * intact while foreground-service ownership continues in the background.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalSessionScreen(
    session: TerminalSessionState,
    terminalEmulator: TerminalEmulator,
    appearance: TerminalAppearance,
    shortcutConfig: TerminalShortcutConfig,
    clipboardCopies: SharedFlow<TerminalClipboardCopy>,
    onSend: (ByteArray) -> Unit,
    resourceSnapshot: ServerResourceSnapshot?,
    onRequestResources: () -> Unit,
    onRequestLeave: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember(session.id) { FocusRequester() }
    val terminalModifierState = remember(session.id) { TerminalModifierState() }
    val isOpen = session.phase == TerminalSessionPhase.OPEN
    val isImeVisible = WindowInsets.isImeVisible
    val supportsSshChannels = session.protocol == ConnectionProtocol.SSH
    val colorScheme = appearance.colorScheme
    val terminalTypeface = remember(appearance.font) {
        ResourcesCompat.getFont(context, appearance.font.fontResourceId()) ?: Typeface.MONOSPACE
    }
    var showSoftKeyboard by remember(session.id) { mutableStateOf(isOpen) }
    var keyboardShowRequest by remember(session.id) { mutableIntStateOf(0) }
    var showResourceReport by remember(session.id) { mutableStateOf(false) }
    val pasteFromClipboard: () -> Unit = {
        scope.launch {
            val text = clipboard.getClipEntry()
                ?.clipData
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.takeIf(String::isNotEmpty)
            if (text != null) onSend(text.encodeToByteArray())
        }
    }

    LaunchedEffect(clipboardCopies, session.id) {
        clipboardCopies
            .filter { it.sessionId == session.id }
            .collect { copy ->
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("Terminal copy", copy.text)),
                )
            }
    }

    LaunchedEffect(isOpen) {
        showSoftKeyboard = isOpen
        if (!isOpen) terminalModifierState.clearTransients()
    }

    DisposableEffect(session.id) {
        onDispose(terminalModifierState::clearTransients)
    }

    LaunchedEffect(terminalEmulator, colorScheme) {
        terminalEmulator.applyColorScheme(
            ansiColors = colorScheme.ansiColors.toIntArray(),
            defaultForeground = colorScheme.defaultForegroundArgb,
            defaultBackground = colorScheme.defaultBackgroundArgb,
        )
    }

    LaunchedEffect(keyboardShowRequest, isOpen) {
        if (keyboardShowRequest == 0 || !isOpen) return@LaunchedEffect

        // The terminal library only calls its forceful ImeInputView.showIme() when this
        // flag changes. Keep the false state through a composition before raising it again.
        showSoftKeyboard = false
        delay(IME_REOPEN_RESET_DELAY_MS)
        showSoftKeyboard = true
    }

    // The whole session screen follows the terminal palette so the bars framing
    // emulator output never fall back to the device light/dark theme.
    MaterialTheme(colorScheme = remember(colorScheme) { terminalChromeColorScheme(colorScheme) }) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
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
                    IconButton(onClick = onRequestLeave) {
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
                    key(appearance.font, appearance.fontSizeSp) {
                        Terminal(
                            terminalEmulator = terminalEmulator,
                            modifier = Modifier.fillMaxSize(),
                            typeface = terminalTypeface,
                            initialFontSize = appearance.fontSizeSp.sp,
                            backgroundColor = Color(colorScheme.defaultBackgroundArgb),
                            // termlib uses this rendering argument for its cursor; text colors
                            // continue to come from the emulator's configured default palette.
                            foregroundColor = Color(colorScheme.cursorArgb),
                            selectionBackgroundColor = Color(colorScheme.selectionBackgroundArgb),
                            selectionForegroundColor = Color(colorScheme.selectionForegroundArgb),
                            keyboardEnabled = isOpen,
                            showSoftKeyboard = showSoftKeyboard,
                            focusRequester = terminalFocusRequester,
                            modifierManager = terminalModifierState,
                            onTerminalTap = {
                                if (isOpen && !isImeVisible) {
                                    terminalFocusRequester.requestFocus()
                                    keyboardShowRequest += 1
                                }
                            },
                            onPasteRequest = pasteFromClipboard,
                            onInterceptKey = { event ->
                                if (
                                    event.type == KeyEventType.KeyDown &&
                                    event.isCtrlPressed &&
                                    event.key == Key.V
                                ) {
                                    pasteFromClipboard()
                                    terminalModifierState.clearTransients()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    }
                }

                TerminalKeyBar(
                    enabled = isOpen,
                    config = shortcutConfig,
                    terminalEmulator = terminalEmulator,
                    modifierState = terminalModifierState,
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
}

/** Maps a bundled appearance option to its packaged Android font resource. */
internal fun TerminalFont.fontResourceId(): Int = when (this) {
    TerminalFont.CASCADIA_MONO_PL -> R.font.cascadia_mono_pl_regular
    TerminalFont.JETBRAINS_MONO_NL -> R.font.jetbrains_mono_nl_regular
    TerminalFont.FIRA_CODE -> R.font.fira_code_regular
}

private const val IME_REOPEN_RESET_DELAY_MS = 50L

@Composable
private fun TerminalKeyBar(
    enabled: Boolean,
    config: TerminalShortcutConfig,
    terminalEmulator: TerminalEmulator,
    modifierState: TerminalModifierState,
    onPaste: () -> Unit,
) {
    val visibleItems = config.items.filter { it.visible }
    if (visibleItems.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleItems.forEach { item ->
            val click = {
                dispatchTerminalShortcut(
                    action = item.action,
                    modifierState = modifierState,
                    terminalEmulator = terminalEmulator,
                    onPaste = onPaste,
                )
            }
            val modifierAction = item.action as? TerminalShortcutAction.Modifier
            if (modifierAction != null) {
                FilterChip(
                    selected = modifierState.isActive(modifierAction.modifier),
                    onClick = click,
                    enabled = enabled,
                    label = { Text(item.displayLabel()) },
                )
            } else {
                AssistChip(
                    onClick = click,
                    enabled = enabled,
                    label = { Text(item.displayLabel()) },
                    leadingIcon = if (item.action == TerminalShortcutAction.Paste) {
                        { Icon(Icons.Outlined.ContentPaste, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
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
