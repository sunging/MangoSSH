package website.sung.mangossh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import website.sung.mangossh.R
import website.sung.mangossh.security.AppLockStore.Companion.MAX_PIN_LENGTH
import website.sung.mangossh.security.AppLockStore.Companion.MIN_PIN_LENGTH

private val LocalPinDialogTablet = compositionLocalOf<Boolean?> { null }
private val pinTabletWidth = 600.dp

/** Uses the host window size to choose between the fullscreen phone and centered tablet layouts. */
@Composable
internal fun PinEntryDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600 &&
        LocalWindowInfo.current.containerDpSize.width >= pinTabletWidth
    val density = LocalDensity.current
    val clipboard = LocalClipboard.current
    val hostKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(hostKeyboard) { hostKeyboard?.hide() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        CompositionLocalProvider(
            LocalPinDialogTablet provides tablet,
            LocalDensity provides density,
            LocalClipboard provides clipboard,
            content = content,
        )
    }
}

/** Keeps the draft in this composition and exposes only its length to accessibility services. */
@Composable
internal fun PinEntry(
    title: String,
    actionLabel: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    feedback: String? = null,
    feedbackIsError: Boolean = false,
    inputEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
    secondaryIcon: ImageVector? = null,
    onEdit: (() -> Unit)? = null,
    autoSubmitLength: Int? = null,
) {
    var draft by remember { mutableStateOf("") }
    val canEdit by rememberUpdatedState(inputEnabled)
    val edited by rememberUpdatedState(onEdit)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val maxDigits = autoSubmitLength ?: MAX_PIN_LENGTH
    LaunchedEffect(focus, keyboard) {
        focus.requestFocus()
        keyboard?.hide()
    }

    fun replaceDraft(value: String) {
        if (canEdit && value.length <= maxDigits && draft != value) {
            draft = value
            edited?.invoke()
        }
    }

    fun appendDigit(digit: Char) {
        if (draft.length < maxDigits) replaceDraft(draft + digit)
    }

    fun submit() {
        val ready = autoSubmitLength?.let { draft.length == it } ?: (draft.length >= MIN_PIN_LENGTH)
        if (canEdit && ready) {
            val value = draft
            draft = ""
            onSubmit(value)
        }
    }

    // A known length makes the final digit a submit gesture without testing shorter prefixes.
    LaunchedEffect(draft, inputEnabled, autoSubmitLength) {
        if (draft.length == autoSubmitLength) submit()
    }

    fun paste() {
        if (!canEdit) return
        scope.launch {
            val clip = clipboard.getClipEntry()?.clipData ?: return@launch
            val text = clip.getItemAt(0).text ?: return@launch
            // Keep Unicode digits verbatim so existing PINs remain usable.
            val pasteLimit = if (autoSubmitLength == null) MAX_PIN_LENGTH else maxDigits + 1
            val digits = text.asSequence().filter(Char::isDigit).take(pasteLimit).joinToString("")
            if (digits.isNotEmpty()) replaceDraft(digits)
        }
    }

    val dialogTablet = LocalPinDialogTablet.current
    val tablet = dialogTablet ?: (LocalWindowInfo.current.containerDpSize.width >= pinTabletWidth)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier.fillMaxSize()
            .then(if (dialogTablet == true) Modifier else Modifier.background(colors.surface))
            .safeDrawingPadding()
            .padding(horizontal = if (tablet) 16.dp else 0.dp)
            .onPreviewKeyEvent { event ->
                if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                val pressed = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (pressed) submit()
                        true
                    }
                    Key.Backspace -> {
                        if (pressed) replaceDraft(draft.dropLast(1))
                        true
                    }
                    else -> {
                        val digit = event.utf16CodePoint.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar()
                        if (digit == null || !digit.isDigit()) return@onPreviewKeyEvent false
                        if (pressed) appendDigit(digit)
                        true
                    }
                }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Measure after system insets so large footer labels leave room to scroll the keypad.
        val compactHeight = maxHeight < 240.dp
        val verticalPadding = if (compactHeight) 0.dp else 12.dp
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .padding(vertical = if (tablet && !compactHeight) 16.dp else 0.dp),
            shape = if (tablet) MaterialTheme.shapes.extraLarge else RectangleShape,
            color = colors.surface,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = verticalPadding)) {
                Box(
                    Modifier.weight(1f, fill = !tablet).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.pin_entry_length, MIN_PIN_LENGTH, MAX_PIN_LENGTH),
                            modifier = Modifier.padding(top = 4.dp),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        PinProgress(draft.length, inputEnabled, ::paste)
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(bottom = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (feedback != null) Text(
                                feedback,
                                color = if (feedbackIsError) colors.error else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                        PinKeypad(
                            digitsEnabled = inputEnabled && draft.length < maxDigits,
                            deleteEnabled = inputEnabled && draft.isNotEmpty(),
                            onDigit = ::appendDigit,
                            onDelete = { replaceDraft(draft.dropLast(1)) },
                            onClear = { replaceDraft("") },
                        )
                    }
                }
                val hasSecondary = secondaryLabel != null && onSecondary != null
                if (actionLabel != null || hasSecondary) {
                    Spacer(Modifier.height(verticalPadding))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = verticalPadding / 2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (secondaryLabel != null && onSecondary != null) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                TextButton(onSecondary, Modifier.testTag("pin_secondary"), enabled = secondaryEnabled) {
                                    if (secondaryIcon != null) {
                                        Icon(secondaryIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(secondaryLabel, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        if (actionLabel != null) {
                            Box(
                                modifier = if (hasSecondary) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Button(
                                    onClick = ::submit,
                                    modifier = Modifier.testTag("pin_submit"),
                                    enabled = inputEnabled && draft.length >= MIN_PIN_LENGTH,
                                ) {
                                    Text(actionLabel, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinProgress(count: Int, enabled: Boolean, onPaste: () -> Unit) {
    val description = pluralStringResource(R.plurals.pin_entry_count, count, count)
    val pasteLabel = stringResource(R.string.pin_entry_paste)
    var showPaste by remember(enabled) { mutableStateOf(false) }
    val dotColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pin_progress")
            .semantics {
                contentDescription = description
                if (enabled) customActions = listOf(CustomAccessibilityAction(pasteLabel) {
                    onPaste()
                    true
                })
            }
            .then(if (enabled) Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { showPaste = true })
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(count) { Box(Modifier.size(10.dp).background(dotColor, CircleShape)) }
        }
        Box {
            DropdownMenu(expanded = showPaste, onDismissRequest = { showPaste = false }) {
                DropdownMenuItem(
                    text = { Text(pasteLabel) },
                    onClick = {
                        showPaste = false
                        onPaste()
                    },
                )
            }
        }
    }
}

@Composable
private fun PinKeypad(
    digitsEnabled: Boolean,
    deleteEnabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = 12.dp
    val clearLabel = stringResource(R.string.pin_entry_clear)
    val deleteLabel = stringResource(R.string.pin_entry_delete_digit)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val keySize = ((maxWidth - spacing * 2) / 3).coerceIn(0.dp, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (digits in listOf("123", "456", "789")) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (digit in digits) {
                        PinDigitKey(digit, digitsEnabled, Modifier.size(keySize), onDigit)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                Spacer(Modifier.size(keySize))
                PinDigitKey('0', digitsEnabled, Modifier.size(keySize), onDigit)
                PinKey(
                    Modifier.size(keySize).testTag("pin_backspace"),
                    enabled = deleteEnabled,
                    containerColor = colors.secondaryContainer,
                    onClick = onDelete,
                    onClickLabel = deleteLabel,
                    onLongClick = onClear,
                    onLongClickLabel = clearLabel,
                ) { Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = deleteLabel) }
            }
        }
    }
}

@Composable
private fun PinDigitKey(digit: Char, enabled: Boolean, modifier: Modifier, onDigit: (Char) -> Unit) {
    PinKey(
        modifier.testTag("pin_digit_$digit"),
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { onDigit(digit) },
    ) { Text(digit.toString(), style = MaterialTheme.typography.headlineSmall) }
}

@Composable
private fun PinKey(
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    onClick: () -> Unit,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val contentColor = if (enabled) contentColorFor(containerColor)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier.clip(CircleShape)
            .background(if (enabled) containerColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}
