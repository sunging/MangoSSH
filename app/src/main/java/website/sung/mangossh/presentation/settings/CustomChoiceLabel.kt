package website.sung.mangossh.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import website.sung.mangossh.R

/**
 * Labels a picker option that the discrete choice list does not contain and
 * that is therefore offered only because it is the persisted value — a
 * 45-second keepalive, say. The marker tells the user why an off-grid value is
 * in the dialog and that leaving it is a deliberate choice.
 *
 * See `domain/SettingsChoices.kt` for how such a value reaches the list.
 */
@Composable
internal fun markCustom(label: String, custom: Boolean): String =
    if (custom) stringResource(R.string.settings_choice_custom_value, label) else label
