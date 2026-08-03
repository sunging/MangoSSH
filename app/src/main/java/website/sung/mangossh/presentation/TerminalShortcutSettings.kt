package website.sung.mangossh.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.UUID
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalModifier
import website.sung.mangossh.domain.TerminalShortcutAction
import website.sung.mangossh.domain.TerminalShortcutConfig
import website.sung.mangossh.domain.TerminalShortcutItem
import website.sung.mangossh.domain.TerminalShortcutKey
import website.sung.mangossh.domain.TerminalSpecialKey

/** Device-local editor entry point for the global floating terminal shortcut bar. */
@Composable
internal fun TerminalShortcutSettingsCard(
    config: TerminalShortcutConfig,
    onSave: (TerminalShortcutConfig) -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag("terminal_shortcuts_card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.terminal_shortcuts_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.terminal_shortcuts_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ShortcutPreview(config.items.filter(TerminalShortcutItem::visible))
            Text(
                stringResource(R.string.terminal_shortcuts_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { showEditor = true },
                modifier = Modifier.testTag("terminal_shortcuts_customize"),
            ) {
                Text(stringResource(R.string.terminal_shortcuts_customize))
            }
        }
    }

    if (showEditor) {
        TerminalShortcutEditorDialog(
            initial = config,
            onDismiss = { showEditor = false },
            onSave = {
                onSave(it)
                showEditor = false
            },
        )
    }
}

@Composable
private fun ShortcutPreview(items: List<TerminalShortcutItem>) {
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.terminal_shortcuts_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            AssistChip(onClick = {}, label = { Text(item.displayLabel()) })
        }
    }
}

@Composable
private fun TerminalShortcutEditorDialog(
    initial: TerminalShortcutConfig,
    onDismiss: () -> Unit,
    onSave: (TerminalShortcutConfig) -> Unit,
) {
    val draft = remember(initial) { mutableStateListOf<TerminalShortcutItem>().apply { addAll(initial.items) } }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("terminal_shortcuts_editor"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.terminal_shortcuts_editor_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(12.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            stringResource(R.string.terminal_shortcuts_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    draft.forEachIndexed { index, item ->
                        ShortcutEditorRow(
                            item = item,
                            index = index,
                            lastIndex = draft.lastIndex,
                            onVisibilityChange = { draft[index] = item.copy(visible = it) },
                            onMoveUp = {
                                val moved = draft.removeAt(index)
                                draft.add(index - 1, moved)
                            },
                            onMoveDown = {
                                val moved = draft.removeAt(index)
                                draft.add(index + 1, moved)
                            },
                            onEdit = { editingIndex = index },
                            onDelete = { draft.removeAt(index) },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { adding = true },
                        enabled = draft.size < TerminalShortcutConfig.MAX_ITEMS,
                        modifier = Modifier.testTag("terminal_shortcuts_add"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.terminal_shortcuts_add))
                    }
                    TextButton(
                        onClick = {
                            draft.clear()
                            draft.addAll(TerminalShortcutConfig.defaults().items)
                        },
                        modifier = Modifier.testTag("terminal_shortcuts_restore"),
                    ) {
                        Text(stringResource(R.string.terminal_shortcuts_restore))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("terminal_shortcuts_cancel"),
                    ) {
                        Text(stringResource(R.string.terminal_shortcuts_cancel))
                    }
                    Button(
                        onClick = { onSave(TerminalShortcutConfig(draft.toList())) },
                        modifier = Modifier.testTag("terminal_shortcuts_save"),
                    ) {
                        Text(stringResource(R.string.terminal_shortcuts_save))
                    }
                }
            }
        }
    }

    val editedIndex = editingIndex
    if (editedIndex != null) {
        ShortcutItemDialog(
            initial = draft[editedIndex],
            otherItems = draft.filterIndexed { index, _ -> index != editedIndex },
            onDismiss = { editingIndex = null },
            onSave = {
                draft[editedIndex] = it
                editingIndex = null
            },
        )
    }
    if (adding) {
        ShortcutItemDialog(
            initial = null,
            otherItems = draft,
            onDismiss = { adding = false },
            onSave = {
                draft.add(it)
                adding = false
            },
        )
    }
}

@Composable
private fun ShortcutEditorRow(
    item: TerminalShortcutItem,
    index: Int,
    lastIndex: Int,
    onVisibilityChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("terminal_shortcut_row_$index")) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = item.visible,
                    onCheckedChange = onVisibilityChange,
                    modifier = Modifier.testTag("terminal_shortcut_visible_$index"),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.displayLabel())
                    Text(
                        item.action.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.testTag("terminal_shortcut_move_up_$index"),
                ) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.terminal_shortcuts_move_up))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < lastIndex,
                    modifier = Modifier.testTag("terminal_shortcut_move_down_$index"),
                ) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.terminal_shortcuts_move_down))
                }
                IconButton(onClick = onEdit, modifier = Modifier.testTag("terminal_shortcut_edit_$index")) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.terminal_shortcuts_edit))
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("terminal_shortcut_delete_$index")) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.terminal_shortcuts_delete))
                }
            }
        }
    }
}

@Composable
private fun TerminalShortcutAction.summary(): String = when (this) {
    TerminalShortcutAction.Paste -> stringResource(R.string.terminal_shortcut_summary_paste)
    is TerminalShortcutAction.Modifier -> stringResource(R.string.terminal_shortcut_summary_modifier, modifier.label)
    is TerminalShortcutAction.Text -> stringResource(
        R.string.terminal_shortcut_summary_text,
        value.codePointCount(0, value.length),
    )
    is TerminalShortcutAction.SpecialKey -> stringResource(R.string.terminal_shortcut_summary_special, key.label)
    is TerminalShortcutAction.Chord -> stringResource(R.string.terminal_shortcut_summary_chord, defaultLabel())
}

private enum class ShortcutActionKind { PASTE, MODIFIER, TEXT, SPECIAL, CHORD }
private enum class ChordKeyKind { CHARACTER, SPECIAL }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ShortcutItemDialog(
    initial: TerminalShortcutItem?,
    otherItems: List<TerminalShortcutItem>,
    onDismiss: () -> Unit,
    onSave: (TerminalShortcutItem) -> Unit,
) {
    val initialAction = initial?.action
    var kind by remember(initial?.id) { mutableStateOf(initialAction.kind()) }
    var label by remember(initial?.id) { mutableStateOf(initial?.labelOverride.orEmpty()) }
    var modifier by remember(initial?.id) {
        mutableStateOf((initialAction as? TerminalShortcutAction.Modifier)?.modifier ?: TerminalModifier.CTRL)
    }
    var textValue by remember(initial?.id) {
        mutableStateOf((initialAction as? TerminalShortcutAction.Text)?.value.orEmpty())
    }
    var specialKey by remember(initial?.id) {
        mutableStateOf((initialAction as? TerminalShortcutAction.SpecialKey)?.key ?: TerminalSpecialKey.ESCAPE)
    }
    val initialChord = initialAction as? TerminalShortcutAction.Chord
    var chordModifiers by remember(initial?.id) {
        mutableStateOf(initialChord?.modifiers ?: setOf(TerminalModifier.CTRL))
    }
    var chordKeyKind by remember(initial?.id) {
        mutableStateOf(if (initialChord?.key is TerminalShortcutKey.Special) ChordKeyKind.SPECIAL else ChordKeyKind.CHARACTER)
    }
    var chordCharacter by remember(initial?.id) {
        mutableStateOf((initialChord?.key as? TerminalShortcutKey.Character)?.value?.toString() ?: "C")
    }
    var chordSpecial by remember(initial?.id) {
        mutableStateOf((initialChord?.key as? TerminalShortcutKey.Special)?.value ?: TerminalSpecialKey.TAB)
    }
    val labelValid = label.isEmpty() || (label.isNotBlank() && label.codePointCount(0, label.length) <= TerminalShortcutConfig.MAX_LABEL_CODE_POINTS)
    val textValid = textValue.isNotEmpty() && textValue.codePointCount(0, textValue.length) <= TerminalShortcutConfig.MAX_TEXT_CODE_POINTS
    val characterValid = chordCharacter.length == 1 && chordCharacter.single().code in 0x20..0x7E
    val utilityDuplicate = when (kind) {
        ShortcutActionKind.PASTE -> otherItems.any { it.action == TerminalShortcutAction.Paste }
        ShortcutActionKind.MODIFIER -> otherItems.any {
            (it.action as? TerminalShortcutAction.Modifier)?.modifier == modifier
        }
        else -> false
    }
    val candidateAction = when (kind) {
        ShortcutActionKind.PASTE -> TerminalShortcutAction.Paste
        ShortcutActionKind.MODIFIER -> TerminalShortcutAction.Modifier(modifier)
        ShortcutActionKind.TEXT -> TerminalShortcutAction.Text(textValue)
        ShortcutActionKind.SPECIAL -> TerminalShortcutAction.SpecialKey(specialKey)
        ShortcutActionKind.CHORD -> if (chordKeyKind == ChordKeyKind.CHARACTER && characterValid) {
            TerminalShortcutAction.Chord(chordModifiers, TerminalShortcutKey.Character(chordCharacter.single()))
        } else if (chordKeyKind == ChordKeyKind.SPECIAL) {
            TerminalShortcutAction.Chord(chordModifiers, TerminalShortcutKey.Special(chordSpecial))
        } else {
            null
        }
    }
    val textLabelValid = kind != ShortcutActionKind.TEXT || (label.isNotBlank() && labelValid)
    val canSave = candidateAction != null && labelValid && textLabelValid &&
        (kind != ShortcutActionKind.TEXT || textValid) &&
        (kind != ShortcutActionKind.CHORD || chordModifiers.isNotEmpty()) &&
        !utilityDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.terminal_shortcut_add_title else R.string.terminal_shortcut_edit_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.terminal_shortcut_action_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShortcutActionKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            modifier = Modifier.testTag("terminal_shortcut_kind_${option.name.lowercase()}"),
                            label = { Text(stringResource(option.labelResource())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = {
                        Text(stringResource(if (kind == ShortcutActionKind.TEXT) R.string.terminal_shortcut_label_required else R.string.terminal_shortcut_label_optional))
                    },
                    singleLine = true,
                    isError = !labelValid || !textLabelValid,
                    modifier = Modifier.fillMaxWidth().testTag("terminal_shortcut_label"),
                )
                when (kind) {
                    ShortcutActionKind.PASTE -> Unit
                    ShortcutActionKind.MODIFIER -> {
                        Text(stringResource(R.string.terminal_shortcut_modifier), style = MaterialTheme.typography.labelLarge)
                        ModifierSelector(selected = setOf(modifier), multiple = false) { modifier = it.single() }
                    }
                    ShortcutActionKind.TEXT -> OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        label = { Text(stringResource(R.string.terminal_shortcut_text_content)) },
                        minLines = 2,
                        isError = !textValid,
                        modifier = Modifier.fillMaxWidth().testTag("terminal_shortcut_text"),
                    )
                    ShortcutActionKind.SPECIAL -> SpecialKeySelector(specialKey) { specialKey = it }
                    ShortcutActionKind.CHORD -> {
                        Text(stringResource(R.string.terminal_shortcut_chord_modifiers), style = MaterialTheme.typography.labelLarge)
                        ModifierSelector(selected = chordModifiers, multiple = true) { chordModifiers = it }
                        Text(stringResource(R.string.terminal_shortcut_chord_key), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChordKeyKind.entries.forEach { option ->
                                FilterChip(
                                    selected = chordKeyKind == option,
                                    onClick = { chordKeyKind = option },
                                    label = { Text(stringResource(option.labelResource())) },
                                )
                            }
                        }
                        if (chordKeyKind == ChordKeyKind.CHARACTER) {
                            OutlinedTextField(
                                value = chordCharacter,
                                onValueChange = { chordCharacter = it },
                                label = { Text(stringResource(R.string.terminal_shortcut_character)) },
                                singleLine = true,
                                isError = !characterValid,
                                modifier = Modifier.fillMaxWidth().testTag("terminal_shortcut_character"),
                            )
                        } else {
                            SpecialKeySelector(chordSpecial) { chordSpecial = it }
                        }
                    }
                }
                if (!labelValid) ErrorText(R.string.terminal_shortcut_error_label)
                if (!textLabelValid) ErrorText(R.string.terminal_shortcut_error_text_label)
                if (kind == ShortcutActionKind.TEXT && !textValid) ErrorText(R.string.terminal_shortcut_error_text)
                if (kind == ShortcutActionKind.CHORD && chordKeyKind == ChordKeyKind.CHARACTER && !characterValid) {
                    ErrorText(R.string.terminal_shortcut_error_character)
                }
                if (kind == ShortcutActionKind.CHORD && chordModifiers.isEmpty()) {
                    ErrorText(R.string.terminal_shortcut_error_modifiers)
                }
                if (utilityDuplicate) ErrorText(R.string.terminal_shortcut_error_duplicate)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        TerminalShortcutItem(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            action = requireNotNull(candidateAction),
                            labelOverride = label.takeIf(String::isNotEmpty),
                            visible = initial?.visible ?: true,
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag("terminal_shortcut_item_save"),
            ) {
                Text(stringResource(R.string.terminal_shortcuts_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_shortcuts_cancel)) }
        },
    )
}

@Composable
private fun ModifierSelector(
    selected: Set<TerminalModifier>,
    multiple: Boolean,
    onSelected: (Set<TerminalModifier>) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TerminalModifier.entries.sortedBy(TerminalModifier::mask).forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = {
                    onSelected(
                        if (!multiple) {
                            setOf(option)
                        } else if (option in selected) {
                            selected - option
                        } else {
                            selected + option
                        },
                    )
                },
                label = { Text(option.label) },
                modifier = Modifier.testTag("terminal_shortcut_modifier_${option.preferenceValue}"),
            )
        }
    }
}

@Composable
private fun SpecialKeySelector(selected: TerminalSpecialKey, onSelected: (TerminalSpecialKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("terminal_shortcut_special_key")) {
            Text(selected.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TerminalSpecialKey.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorText(resource: Int) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun TerminalShortcutAction?.kind(): ShortcutActionKind = when (this) {
    TerminalShortcutAction.Paste -> ShortcutActionKind.PASTE
    is TerminalShortcutAction.Modifier -> ShortcutActionKind.MODIFIER
    is TerminalShortcutAction.Text -> ShortcutActionKind.TEXT
    is TerminalShortcutAction.SpecialKey -> ShortcutActionKind.SPECIAL
    is TerminalShortcutAction.Chord -> ShortcutActionKind.CHORD
    null -> ShortcutActionKind.SPECIAL
}

private fun ShortcutActionKind.labelResource(): Int = when (this) {
    ShortcutActionKind.PASTE -> R.string.terminal_shortcut_action_paste
    ShortcutActionKind.MODIFIER -> R.string.terminal_shortcut_action_modifier
    ShortcutActionKind.TEXT -> R.string.terminal_shortcut_action_text
    ShortcutActionKind.SPECIAL -> R.string.terminal_shortcut_action_special
    ShortcutActionKind.CHORD -> R.string.terminal_shortcut_action_chord
}

private fun ChordKeyKind.labelResource(): Int = when (this) {
    ChordKeyKind.CHARACTER -> R.string.terminal_shortcut_key_character
    ChordKeyKind.SPECIAL -> R.string.terminal_shortcut_key_special
}
