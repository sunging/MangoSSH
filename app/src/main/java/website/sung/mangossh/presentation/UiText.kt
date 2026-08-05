package website.sung.mangossh.presentation

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * Text crossing a presentation-state boundary.
 *
 * Application wording remains resource-backed until render time so a locale
 * change also updates retained ViewModel state. [Verbatim] is reserved for
 * user-created or server-owned values that must never be translated.
 */
@Immutable
sealed interface UiText {
    data class Resource(
        @StringRes val resourceId: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Verbatim(val value: String) : UiText
}

/** Resolves text against the current Compose configuration. */
@Composable
internal fun UiText.asString(): String = resolve(LocalContext.current)

/** Resolves text at an Android UI boundary without altering verbatim values. */
internal fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> context.getString(resourceId, *arguments.toTypedArray())
    is UiText.Verbatim -> value
}

/** Creates a resource-backed message with positional formatting arguments. */
internal fun uiText(@StringRes resourceId: Int, vararg arguments: Any): UiText =
    UiText.Resource(resourceId, arguments.toList())
