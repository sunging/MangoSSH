package website.sung.mangossh.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import website.sung.mangossh.R

/** Lets the user choose the app's display language, independent of the device locale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSettingsCard(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag("app-language-card")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.app_language_title), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.app_language_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedButton(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("app-language-selector"),
                    onClick = { expanded = true },
                ) {
                    Text(selected.label())
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem(
                            modifier = Modifier.testTag("app-language-option_${option.name}"),
                            text = { Text(option.label()) },
                            onClick = {
                                expanded = false
                                if (option != selected) onSelect(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLanguage.label(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.app_language_system
        AppLanguage.ENGLISH -> R.string.app_language_english
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.app_language_simplified_chinese
    },
)
