package website.sung.mangossh.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalSpecialKey

/**
 * Shared compact keyboard layout. Missing callbacks make a visually faithful, inert preview.
 * Repeats belong to this composition and its resumed lifecycle, never to a remote session job.
 */
@Composable
internal fun TerminalShortcutBar(
    config: TerminalShortcutConfig,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeModifiers: Set<TerminalModifier> = emptySet(),
    onAction: ((TerminalShortcutItem) -> Unit)? = null,
    onStartRepeat: ((TerminalShortcutItem) -> (() -> Unit))? = null,
) {
    val rows = remember(config) { config.displayRows() }
    if (rows.isEmpty()) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var multiplePointers by remember { mutableStateOf(false) }
    val canInteract = enabled && resumed && !multiplePointers
    val currentCanInteract by rememberUpdatedState(canInteract)
    val visualHeight = with(LocalDensity.current) { maxOf(40.dp, 24.sp.toDp() + 8.dp) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Fit the six keyboard columns on small phones; wider phones show six or seven keys.
        // Each row now meets the standard 48dp touch height without overlapping adjacent rows.
        val minimumButtonWidth = ((maxWidth - 18.dp) / 6).coerceIn(48.dp, 60.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("terminal_shortcut_bar")
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(Unit) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                multiplePointers = event.changes.count { it.pressed } > 1
                            }
                        }
                    } finally {
                        multiplePointers = false
                    }
                }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(rows.maxOf(List<TerminalShortcutItem>::size)) { column ->
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rows.forEach { row ->
                        row.getOrNull(column)?.let { item ->
                            key(item.id, item.action) {
                                val modifierAction = item.action as? TerminalShortcutAction.Modifier
                                TerminalShortcutButton(
                                    item = item,
                                    enabled = enabled && resumed,
                                    selected = modifierAction?.modifier in activeModifiers,
                                    visualHeight = visualHeight,
                                    minimumWidth = minimumButtonWidth,
                                    gestureEnabled = canInteract,
                                    onClick = onAction?.let { callback ->
                                        { if (currentCanInteract) callback(item) }
                                    },
                                    onStartRepeat = onStartRepeat?.takeIf { item.action.isRepeatableArrow() }?.let { callback ->
                                        { callback(item) }
                                    },
                                    canDispatch = { currentCanInteract },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalShortcutButton(
    item: TerminalShortcutItem,
    enabled: Boolean,
    selected: Boolean,
    visualHeight: Dp,
    minimumWidth: Dp,
    gestureEnabled: Boolean,
    onClick: (() -> Unit)?,
    onStartRepeat: (() -> (() -> Unit))?,
    canDispatch: () -> Boolean,
) {
    val interactions = remember { MutableInteractionSource() }
    val latestClick by rememberUpdatedState(onClick)
    val latestStartRepeat by rememberUpdatedState(onStartRepeat)
    val latestCanDispatch by rememberUpdatedState(canDispatch)
    val label = item.displayLabel()
    val description = item.accessibilityLabel()
    val colors = MaterialTheme.colorScheme
    val foreground = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        selected -> colors.onSecondaryContainer
        else -> colors.onSurface
    }
    val background = if (selected && enabled) colors.secondaryContainer else colors.surface
    val border = if (selected && enabled) colors.primary else colors.outlineVariant
    var buttonModifier = Modifier
        .widthIn(min = minimumWidth, max = 128.dp)
        .fillMaxWidth()
        .height(visualHeight + 8.dp)
        .testTag("terminal_shortcut_button_${item.id}")
        .semantics(mergeDescendants = true) {
            contentDescription = description
            if (item.action is TerminalShortcutAction.Modifier) this.selected = selected
        }
    if (onClick != null) {
        buttonModifier = if (onStartRepeat == null) {
            buttonModifier.clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactions,
                indication = ripple(),
                onClick = { latestClick?.invoke() },
            )
        } else {
            buttonModifier
                .semantics {
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) latestClick?.invoke()
                        enabled
                    }
                }
                .onKeyEvent { event ->
                    if (enabled && event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                        if (event.type == KeyEventType.KeyUp) latestClick?.invoke()
                        true
                    } else {
                        false
                    }
                }
                .focusable(enabled, interactions)
                .indication(interactions, ripple())
                .arrowPressInput(
                    enabled = gestureEnabled,
                    interactions = interactions,
                    canDispatch = { latestCanDispatch() },
                    onClick = { latestClick?.invoke() },
                    onStartRepeat = { checkNotNull(latestStartRepeat).invoke() },
                )
        }
    }
    Box(modifier = buttonModifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(visualHeight),
            shape = MaterialTheme.shapes.small,
            color = background,
            contentColor = foreground,
            border = BorderStroke(1.dp, if (enabled) border else border.copy(alpha = 0.38f)),
        ) {
            Box(modifier = Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                if (item.action == TerminalShortcutAction.Paste) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    val isArrowGlyph = item.action.isRepeatableArrow() && item.labelOverride == null
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = if (isArrowGlyph) 20.sp else 15.sp,
                            lineHeight = if (isArrowGlyph) 24.sp else 20.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Defers terminal input until a tap finishes or a stationary hold qualifies. Down/move events
 * remain available to the parent scroller, including after a hold, so dragging never becomes a key.
 */
private fun Modifier.arrowPressInput(
    enabled: Boolean,
    interactions: MutableInteractionSource,
    canDispatch: () -> Boolean,
    onClick: () -> Unit,
    onStartRepeat: () -> (() -> Unit),
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown()
            val press = PressInteraction.Press(down.position)
            interactions.tryEmit(press)
            var repeating = false
            var released = false
            val repeater = launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                if (canDispatch()) {
                    val dispatch = onStartRepeat()
                    repeating = true
                    while (canDispatch()) {
                        dispatch()
                        delay(80)
                    }
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (
                        !canDispatch() || event.changes.any { it.id != down.id && it.pressed } ||
                        change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat()
                    ) break
                    if (change.changedToUpIgnoreConsumed()) {
                        released = true
                        repeater.cancel()
                        change.consume()
                        if (!repeating) onClick()
                        break
                    }
                    // A parent may claim this move later in the same event pass.
                    awaitPointerEvent(PointerEventPass.Final)
                    if (change.isConsumed) break
                }
            } finally {
                repeater.cancel()
                interactions.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }
}

/** Only plain arrow actions repeat; page keys, chords and text macros remain single activation. */
internal fun TerminalShortcutAction.isRepeatableArrow(): Boolean = this is TerminalShortcutAction.SpecialKey &&
    key in setOf(TerminalSpecialKey.UP, TerminalSpecialKey.DOWN, TerminalSpecialKey.LEFT, TerminalSpecialKey.RIGHT)

@Composable
private fun TerminalShortcutItem.accessibilityLabel(): String {
    // A text macro's accessible name is its label, never its potentially lengthy payload.
    if (action is TerminalShortcutAction.Text) return displayLabel()
    val actionLabel = when ((action as? TerminalShortcutAction.SpecialKey)?.key) {
        TerminalSpecialKey.UP -> stringResource(R.string.terminal_shortcut_arrow_up)
        TerminalSpecialKey.DOWN -> stringResource(R.string.terminal_shortcut_arrow_down)
        TerminalSpecialKey.LEFT -> stringResource(R.string.terminal_shortcut_arrow_left)
        TerminalSpecialKey.RIGHT -> stringResource(R.string.terminal_shortcut_arrow_right)
        else -> action.defaultLabel()
    }
    val name = labelOverride?.let { "$it, $actionLabel" } ?: actionLabel
    return if (action.isRepeatableArrow()) stringResource(R.string.terminal_shortcut_repeat_description, name) else name
}
