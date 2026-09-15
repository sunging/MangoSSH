package website.sung.mangossh.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R

/** Values sit below labels so translated text and large fonts cannot crowd out the action. */
@Composable
internal fun HostEditorRow(title: String, summary: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, error: Boolean = false,
    trailingIcon: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
    dense: Boolean = false, trailingValue: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = if (dense) 48.dp else 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = if (dense) 12.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailingValue != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailingValue, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(trailingIcon, null, Modifier.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** Small sets are immediately selectable; numeric presets use anchored menus, searchable lists use dialogs. */
@Composable
internal fun <T> HostEditorChoice(title: String, summary: String, selected: T, options: List<T>,
    optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit, tag: String,
    searchable: Boolean = false, error: Boolean = false, compact: Boolean = false, valueLabel: String? = null) {
    if (!searchable && !compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { index, option ->
                    val isSelected = selected == option
                    FilterChip(selected = isSelected, onClick = { onSelect(option) },
                        label = { Text(optionLabel(option)) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                        modifier = Modifier.heightIn(min = 40.dp).testTag("${tag}_option_$index"))
                }
            }
            if (error) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    if (compact && !searchable) {
        Box {
            HostEditorRow(title, summary, { picking = true }, Modifier.testTag(tag), error, Icons.Outlined.ArrowDropDown,
                dense = true, trailingValue = valueLabel)
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.testTag("${tag}_options")) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); picking = false },
                        leadingIcon = { if (selected == option) Icon(Icons.Outlined.Check, null) },
                        modifier = Modifier.testTag("${tag}_option_$index"))
                }
            }
        }
        return
    }
    HostEditorRow(title, summary, { picking = true }, Modifier.testTag(tag), error, dense = true, trailingValue = valueLabel)
    if (picking) {
        var query by rememberSaveable { mutableStateOf("") }
        val labels = options.map { optionLabel(it) }
        val matching = options.indices.filter { labels[it].contains(query, ignoreCase = true) }
        AlertDialog(onDismissRequest = { picking = false }, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searchable) OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.common_search)) }, modifier = Modifier.fillMaxWidth().testTag("${tag}_search"))
                if (matching.isEmpty()) Text(stringResource(R.string.jump_no_matches))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("${tag}_options")) {
                    itemsIndexed(matching) { _, index ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected == options[index], role = Role.RadioButton) {
                                onSelect(options[index]); picking = false
                            }.padding(vertical = 12.dp).testTag("${tag}_option_$index"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == options[index], null)
                            Text(labels[index], Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
