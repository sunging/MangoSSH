package website.sung.mangossh.presentation

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.content.ClipData
import android.graphics.Typeface
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
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
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalBehavior
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalSpecialKey
import website.sung.mangossh.ui.theme.terminalChromeColorScheme

/**
 * Renders an already-running terminal emulator.
 *
 * The emulator belongs to the application session runtime rather than this
 * composable, so leaving this screen keeps the remote session and scrollback
 * intact while foreground-service ownership continues in the background.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalSessionScreen(
    session: TerminalSessionState,
    terminalEmulator: TerminalEmulator,
    appearance: TerminalAppearance,
    behavior: TerminalBehavior,
    shortcutConfig: TerminalShortcutConfig,
    clipboardCopies: SharedFlow<TerminalClipboardCopy>,
    onSend: (ByteArray) -> Unit,
    resourceSnapshot: ServerResourceSnapshot?,
    onRequestResources: () -> Unit,
    onOpenFileBrowser: () -> Unit,
    onRequestLeave: () -> Unit,
    sessionFontSizeSp: Int?,
    onSessionFontSizeChange: (Int) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember(session.id) { FocusRequester() }
    val terminalModifierState = remember(session.id) { TerminalModifierState() }
    val isOpen = session.phase == TerminalSessionPhase.OPEN
    val isImeVisible = WindowInsets.isImeVisible
    val colorScheme = appearance.colorScheme
    val terminalTypeface = remember(appearance.font) {
        ResourcesCompat.getFont(context, appearance.font.fontResourceId()) ?: Typeface.MONOSPACE
    }
    var showSoftKeyboard by remember(session.id) { mutableStateOf(isOpen) }
    var keyboardShowRequest by remember(session.id) { mutableIntStateOf(0) }
    var showResourceReport by remember(session.id) { mutableStateOf(false) }

    // Chrome visibility: immersive hides both bars and the system bars at once; the two
    // per-element toggles (reachable from the long-press menu) are independent of it so a
    // user can, say, keep the shortcut bar while hiding just the title bar.
    var immersive by rememberSaveable(session.id) { mutableStateOf(false) }
    var topBarVisible by rememberSaveable(session.id) { mutableStateOf(true) }
    var shortcutBarVisible by rememberSaveable(session.id) { mutableStateOf(true) }
    var chromeMenuExpanded by remember(session.id) { mutableStateOf(false) }
    val showTopBar = topBarVisible && !immersive
    val showShortcutBar = shortcutBarVisible && !immersive

    // The floating exit-immersive button fades to a low alpha after a few idle seconds so
    // it doesn't linger over live terminal output, but stays tappable at any alpha.
    var immersiveExitFaded by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(immersive) {
        immersiveExitFaded = false
        if (immersive) {
            delay(IMMERSIVE_EXIT_FADE_DELAY_MS)
            immersiveExitFaded = true
        }
    }

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

    // Leaving the terminal must release the wake lock even if the preference changes mid-session.
    DisposableEffect(view, behavior.keepScreenOn) {
        view.keepScreenOn = behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Immersive mode hides the system status/navigation bars themselves, on top of this
    // screen's own title and shortcut bars. Bars remain swipe-revealable (transient), and
    // are unconditionally restored on dispose so leaving this screen never leaves the rest
    // of the app with hidden system bars.
    DisposableEffect(activity, view, immersive) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Let immersive mode draw all the way under the display cutout too; restore whatever
    // cutout mode this window had before immersive mode changed it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        DisposableEffect(activity, immersive) {
            val window = activity?.window
            val previousCutoutMode = window?.attributes?.layoutInDisplayCutoutMode
            if (window != null && immersive) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            onDispose {
                if (window != null && previousCutoutMode != null) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
            }
        }
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
                    // ime and navigationBars overlap at the bottom of the window; padding
                    // for both separately would double-count that overlap whenever the
                    // keyboard is open. union() takes the larger of the two instead.
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
                    )
                    // The title bar below draws into the status bar itself and consumes this
                    // inset when shown. Hidden (by the user, or by immersive mode already
                    // zeroing the inset), the terminal needs it back so its first row isn't
                    // drawn under the status bar / notch.
                    .let { base ->
                        if (showTopBar) {
                            base
                        } else {
                            base.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                        }
                    }
                    // Catch an Esc that bubbled up unconsumed because focus was on the top bar
                    // or the shortcut chips rather than the terminal, before the platform can
                    // turn it into Back/Menu/Home. Down dispatches the key; both edges are
                    // consumed so no fallback is synthesized.
                    .onKeyEvent { event ->
                        if (consumesUnhandledEscape(event.nativeKeyEvent.keyCode, isOpen)) {
                            if (event.type == KeyEventType.KeyDown) {
                                dispatchTerminalShortcut(
                                    action = TerminalShortcutAction.SpecialKey(TerminalSpecialKey.ESCAPE),
                                    modifierState = terminalModifierState,
                                    terminalEmulator = terminalEmulator,
                                    onPaste = pasteFromClipboard,
                                )
                            }
                            true
                        } else {
                            false
                        }
                    },
            ) {
                if (showTopBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("terminal_title_bar")
                            // The background is drawn outside the inset padding so it extends
                            // into the status bar, making the bar read as one continuous
                            // surface instead of a blank strip sitting above a separate one.
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .windowInsetsPadding(
                                WindowInsets.statusBars.union(WindowInsets.displayCutout)
                                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${session.title} · ${session.endpoint} · ${session.phase.label()}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        IconButton(
                            modifier = Modifier.size(40.dp),
                            onClick = onRequestLeave,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.ui_back_to_hosts),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            modifier = Modifier.size(40.dp),
                            onClick = onOpenFileBrowser,
                            enabled = isOpen,
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = stringResource(R.string.ui_remote_files),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            modifier = Modifier.size(40.dp),
                            onClick = {
                                showResourceReport = true
                                onRequestResources()
                            },
                            enabled = isOpen,
                        ) {
                            Icon(
                                Icons.Outlined.Storage,
                                contentDescription = stringResource(R.string.ui_server_resources),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Box {
                            // IconButton has no onLongClick, so this button is a plain clickable
                            // Box instead: a tap toggles immersive mode, a long press opens a
                            // menu to show/hide the title and shortcut bars independently, or
                            // reset a pinch-to-zoom result.
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("terminal_immersive_toggle")
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = { immersive = !immersive },
                                        onLongClick = { chromeMenuExpanded = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (immersive) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = stringResource(
                                        if (immersive) R.string.terminal_exit_immersive_mode else R.string.terminal_enter_immersive_mode,
                                    ),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = chromeMenuExpanded,
                                onDismissRequest = { chromeMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("terminal_menu_toggle_title_bar"),
                                    text = {
                                        Text(
                                            stringResource(
                                                if (topBarVisible) R.string.terminal_hide_title_bar else R.string.terminal_show_title_bar,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        topBarVisible = !topBarVisible
                                        chromeMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("terminal_menu_toggle_shortcut_bar"),
                                    text = {
                                        Text(
                                            stringResource(
                                                if (shortcutBarVisible) {
                                                    R.string.terminal_hide_shortcut_bar
                                                } else {
                                                    R.string.terminal_show_shortcut_bar
                                                },
                                            ),
                                        )
                                    },
                                    onClick = {
                                        shortcutBarVisible = !shortcutBarVisible
                                        chromeMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("terminal_menu_reset_zoom"),
                                    text = { Text(stringResource(R.string.terminal_reset_zoom)) },
                                    onClick = {
                                        onSessionFontSizeChange(appearance.fontSizeSp)
                                        chromeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // In landscape a side notch/cutout sits inside the terminal area
                        // itself (there is no title bar there to absorb it); keep text off it.
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                ) {
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
                            rightAltMode = behavior.rightAltMode.toTermlib(),
                            delKeyMode = behavior.delKeyMode.toTermlib(),
                            maxZoomScale = behavior.maxPinchZoomScale,
                            fontSizeOverride = sessionFontSizeSp?.sp,
                            onFontSizeCommit = { onSessionFontSizeChange(it.value.roundToInt()) },
                        )
                    }

                    // Immersive mode hides the button that opened it along with the rest of
                    // the chrome; this is the way back. It fades to a low, still-tappable
                    // alpha after a few seconds so it does not linger over live output.
                    if (immersive) {
                        val exitAlpha by animateFloatAsState(
                            targetValue = if (immersiveExitFaded) IMMERSIVE_EXIT_FADED_ALPHA else 1f,
                            label = "immersiveExitAlpha",
                        )
                        IconButton(
                            onClick = { immersive = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .testTag("terminal_immersive_exit")
                                .padding(8.dp)
                                .alpha(exitAlpha)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                Icons.Outlined.FullscreenExit,
                                contentDescription = stringResource(R.string.terminal_exit_immersive_mode),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (showShortcutBar) {
                    key(session.id, terminalEmulator) {
                        TerminalShortcutBar(
                            enabled = isOpen,
                            config = shortcutConfig,
                            activeModifiers = terminalModifierState.activeModifiers,
                            onAction = { item ->
                                dispatchTerminalShortcut(item.action, terminalModifierState, terminalEmulator, pasteFromClipboard)
                            },
                            onStartRepeat = { item ->
                                val repeatedAction = captureTerminalShortcutRepeat(item.action, terminalModifierState)
                                val dispatch: () -> Unit = {
                                    dispatchTerminalShortcut(repeatedAction, terminalModifierState, terminalEmulator, pasteFromClipboard)
                                }
                                dispatch
                            },
                        )
                    }
                }
            }
        }

        if (showResourceReport) {
            AlertDialog(
                onDismissRequest = { showResourceReport = false },
                title = { Text(stringResource(R.string.ui_server_resources)) },
                text = {
                    SelectionContainer {
                        Text(
                            resourceSnapshot?.report ?: stringResource(R.string.ui_reading_resource_information_from_the_server),
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRequestResources() }) { Text(stringResource(R.string.common_refresh)) }
                },
                dismissButton = {
                    TextButton(onClick = { showResourceReport = false }) { Text(stringResource(R.string.common_close)) }
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
private const val IMMERSIVE_EXIT_FADE_DELAY_MS = 3_000L
private const val IMMERSIVE_EXIT_FADED_ALPHA = 0.18f

/** Returns the localized, application-owned label for a live session phase. */
@Composable
internal fun TerminalSessionPhase.label(): String = when (this) {
    TerminalSessionPhase.CONNECTING -> stringResource(R.string.ui_connecting_2)
    TerminalSessionPhase.VERIFYING_HOST_KEY -> stringResource(R.string.ui_verifying_fingerprint)
    TerminalSessionPhase.AUTHENTICATING -> stringResource(R.string.ui_authenticating)
    TerminalSessionPhase.OPEN -> stringResource(R.string.ui_connected)
    TerminalSessionPhase.FAILED -> stringResource(R.string.ui_failed)
    TerminalSessionPhase.CLOSED -> stringResource(R.string.ui_closed)
}
