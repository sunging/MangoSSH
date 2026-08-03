package website.sung.mangossh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import website.sung.mangossh.R
import website.sung.mangossh.domain.TerminalAppearance
import website.sung.mangossh.domain.TerminalCustomColors
import website.sung.mangossh.domain.TerminalFont
import website.sung.mangossh.domain.TerminalThemeId

/** Controls device-local terminal font, size, palette, and basic custom colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalAppearanceCard(
    appearance: TerminalAppearance,
    onSetFont: (TerminalFont) -> Unit,
    onSetFontSize: (Int) -> Unit,
    onSetTheme: (TerminalThemeId) -> Unit,
    onSetCustomColors: (TerminalCustomColors) -> Unit,
    onReset: () -> Unit,
) {
    var fontExpanded by rememberSaveable { mutableStateOf(false) }
    var themeExpanded by rememberSaveable { mutableStateOf(false) }
    var showCustomEditor by rememberSaveable { mutableStateOf(false) }
    val scheme = appearance.colorScheme
    val decreaseFontSizeDescription = stringResource(R.string.terminal_decrease_font_size)
    val increaseFontSizeDescription = stringResource(R.string.terminal_increase_font_size)
    val previewFamily = remember(appearance.font) {
        FontFamily(Font(appearance.font.fontResourceId()))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("terminal_appearance_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.terminal_appearance_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.terminal_appearance_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
                expanded = fontExpanded,
                onExpandedChange = { fontExpanded = !fontExpanded },
            ) {
                OutlinedTextField(
                    value = appearance.font.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.terminal_font_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_font_dropdown")
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = fontExpanded,
                    onDismissRequest = { fontExpanded = false },
                ) {
                    TerminalFont.entries.forEach { font ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(font.label()) },
                            onClick = {
                                onSetFont(font)
                                fontExpanded = false
                            },
                            modifier = Modifier.testTag("terminal_font_${font.preferenceValue}"),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.terminal_font_size_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        enabled = appearance.fontSizeSp > TerminalAppearance.MIN_FONT_SIZE_SP,
                        onClick = { onSetFontSize(appearance.fontSizeSp - 1) },
                        modifier = Modifier
                            .testTag("terminal_font_size_decrease")
                            .semantics {
                                contentDescription = decreaseFontSizeDescription
                            },
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = null)
                    }
                    Text(
                        stringResource(R.string.terminal_font_size_value, appearance.fontSizeSp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        enabled = appearance.fontSizeSp < TerminalAppearance.MAX_FONT_SIZE_SP,
                        onClick = { onSetFontSize(appearance.fontSizeSp + 1) },
                        modifier = Modifier
                            .testTag("terminal_font_size_increase")
                            .semantics {
                                contentDescription = increaseFontSizeDescription
                            },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                }
                Slider(
                    value = appearance.fontSizeSp.toFloat(),
                    onValueChange = { onSetFontSize(it.roundToInt()) },
                    valueRange = TerminalAppearance.MIN_FONT_SIZE_SP.toFloat()..TerminalAppearance.MAX_FONT_SIZE_SP.toFloat(),
                    steps = TerminalAppearance.MAX_FONT_SIZE_SP - TerminalAppearance.MIN_FONT_SIZE_SP - 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_font_size_slider"),
                )
            }

            ExposedDropdownMenuBox(
                expanded = themeExpanded,
                onExpandedChange = { themeExpanded = !themeExpanded },
            ) {
                OutlinedTextField(
                    value = appearance.theme.label(appearance.customColors),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.terminal_theme_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_theme_dropdown")
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false },
                ) {
                    TerminalThemeId.entries
                        .filterNot { it == TerminalThemeId.CUSTOM }
                        .forEach { theme ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(theme.label()) },
                                onClick = {
                                    onSetTheme(theme)
                                    themeExpanded = false
                                },
                                modifier = Modifier.testTag("terminal_theme_${theme.preferenceValue}"),
                            )
                        }
                }
            }

            TerminalThemePreview(
                scheme = scheme,
                fontFamily = previewFamily,
                fontSizeSp = appearance.fontSizeSp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCustomEditor = true },
                    modifier = Modifier.testTag("terminal_customize_colors"),
                ) {
                    Text(stringResource(R.string.terminal_customize_colors))
                }
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("terminal_restore_defaults"),
                ) {
                    Text(stringResource(R.string.terminal_restore_defaults))
                }
            }
        }
    }

    if (showCustomEditor) {
        TerminalCustomColorsDialog(
            appearance = appearance,
            onDismiss = { showCustomEditor = false },
            onSave = {
                onSetCustomColors(it)
                showCustomEditor = false
            },
        )
    }
}

@Composable
private fun TerminalThemePreview(
    scheme: website.sung.mangossh.domain.TerminalColorScheme,
    fontFamily: FontFamily,
    fontSizeSp: Int,
) {
    Surface(
        color = Color(scheme.defaultBackgroundArgb),
        contentColor = Color(scheme.defaultForegroundArgb),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("terminal_theme_preview"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.terminal_preview_text),
                fontFamily = fontFamily,
                fontSize = fontSizeSp.sp,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                scheme.ansiColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(color), MaterialTheme.shapes.extraSmall),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalCustomColorsDialog(
    appearance: TerminalAppearance,
    onDismiss: () -> Unit,
    onSave: (TerminalCustomColors) -> Unit,
) {
    val initialColors = appearance.customColors
    var baseTheme by remember(appearance) {
        mutableStateOf(initialColors?.baseTheme ?: appearance.theme.takeIf { it != TerminalThemeId.CUSTOM } ?: TerminalThemeId.DEFAULT)
    }
    var foregroundHex by remember(appearance) {
        mutableStateOf(terminalArgbToHex(initialColors?.foregroundArgb ?: appearance.colorScheme.defaultForegroundArgb))
    }
    var backgroundHex by remember(appearance) {
        mutableStateOf(terminalArgbToHex(initialColors?.backgroundArgb ?: appearance.colorScheme.defaultBackgroundArgb))
    }
    var cursorHex by remember(appearance) {
        mutableStateOf(terminalArgbToHex(initialColors?.cursorArgb ?: appearance.colorScheme.cursorArgb))
    }
    var baseExpanded by rememberSaveable { mutableStateOf(false) }

    val foreground = parseTerminalOpaqueArgb(foregroundHex)
    val background = parseTerminalOpaqueArgb(backgroundHex)
    val cursor = parseTerminalOpaqueArgb(cursorHex)
    val colors = if (foreground != null && background != null && cursor != null) {
        TerminalCustomColors(baseTheme, foreground, background, cursor)
    } else {
        null
    }
    val hasHexError = colors == null
    val hasContrastError = colors != null && !TerminalAppearance.isValidCustomColors(colors)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_custom_colors_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.terminal_custom_colors_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                ExposedDropdownMenuBox(
                    expanded = baseExpanded,
                    onExpandedChange = { baseExpanded = !baseExpanded },
                ) {
                    OutlinedTextField(
                        value = baseTheme.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.terminal_custom_base_theme_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = baseExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = baseExpanded,
                        onDismissRequest = { baseExpanded = false },
                    ) {
                        TerminalThemeId.entries
                            .filterNot { it == TerminalThemeId.CUSTOM }
                            .forEach { theme ->
                                androidx.compose.material3.DropdownMenuItem(
                            text = { Text(theme.label()) },
                            onClick = {
                                        baseTheme = theme
                                        val baseScheme = TerminalAppearance(theme = theme).colorScheme
                                        foregroundHex = terminalArgbToHex(baseScheme.defaultForegroundArgb)
                                        backgroundHex = terminalArgbToHex(baseScheme.defaultBackgroundArgb)
                                        cursorHex = terminalArgbToHex(baseScheme.cursorArgb)
                                baseExpanded = false
                            },
                            modifier = Modifier.testTag("terminal_custom_base_${theme.preferenceValue}"),
                                )
                            }
                    }
                }
                TerminalColorField(
                    label = stringResource(R.string.terminal_foreground_label),
                    value = foregroundHex,
                    onValueChange = { foregroundHex = it },
                    color = foreground,
                    hasError = hasHexError && foreground == null,
                    testTag = "terminal_custom_foreground",
                )
                TerminalColorField(
                    label = stringResource(R.string.terminal_background_label),
                    value = backgroundHex,
                    onValueChange = { backgroundHex = it },
                    color = background,
                    hasError = hasHexError && background == null,
                    testTag = "terminal_custom_background",
                )
                TerminalColorField(
                    label = stringResource(R.string.terminal_cursor_label),
                    value = cursorHex,
                    onValueChange = { cursorHex = it },
                    color = cursor,
                    hasError = hasHexError && cursor == null,
                    testTag = "terminal_custom_cursor",
                )
                if (hasHexError) {
                    Text(
                        stringResource(R.string.terminal_color_hex_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("terminal_custom_hex_error"),
                    )
                } else if (hasContrastError) {
                    Text(
                        stringResource(R.string.terminal_color_contrast_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("terminal_custom_contrast_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = colors != null && TerminalAppearance.isValidCustomColors(colors),
                onClick = { colors?.let(onSave) },
                modifier = Modifier.testTag("terminal_custom_save"),
            ) {
                Text(stringResource(R.string.terminal_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
        },
    )
}

@Composable
private fun TerminalColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    color: Int?,
    hasError: Boolean,
    testTag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.extraSmall,
                ),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = hasError,
            modifier = Modifier
                .weight(1f)
                .testTag(testTag),
        )
    }
}

@Composable
private fun TerminalFont.label(): String = stringResource(
    when (this) {
        TerminalFont.CASCADIA_MONO_PL -> R.string.terminal_font_cascadia_mono_pl
        TerminalFont.JETBRAINS_MONO_NL -> R.string.terminal_font_jetbrains_mono_nl
        TerminalFont.FIRA_CODE -> R.string.terminal_font_fira_code
    },
)

@Composable
private fun TerminalThemeId.label(customColors: TerminalCustomColors? = null): String = when (this) {
    TerminalThemeId.MANGO_DARK -> stringResource(R.string.terminal_theme_mango_dark)
    TerminalThemeId.DRACULA -> stringResource(R.string.terminal_theme_dracula)
    TerminalThemeId.NORD -> stringResource(R.string.terminal_theme_nord)
    TerminalThemeId.SOLARIZED_DARK -> stringResource(R.string.terminal_theme_solarized_dark)
    TerminalThemeId.SOLARIZED_LIGHT -> stringResource(R.string.terminal_theme_solarized_light)
    TerminalThemeId.CUSTOM -> stringResource(
        R.string.terminal_theme_custom,
        (customColors?.baseTheme ?: TerminalThemeId.DEFAULT).label(),
    )
}

internal fun parseTerminalOpaqueArgb(value: String): Int? {
    val match = HEX_COLOR.matchEntire(value.trim()) ?: return null
    return (0xFF000000L or match.groupValues[1].toLong(16)).toInt()
}

internal fun terminalArgbToHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private val HEX_COLOR = Regex("#([0-9A-Fa-f]{6})")
