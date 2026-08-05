package website.sung.mangossh.session

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * Resolves fixed session and notification wording against the selected app locale.
 *
 * AppCompat uses an Activity-scoped override on Android 12 and lower, so
 * application and Service contexts must be wrapped before reading resources.
 */
internal fun Context.appString(@StringRes resourceId: Int, vararg arguments: Any): String =
    appLocaleContext().getString(resourceId, *arguments)

private fun Context.appLocaleContext(): Context {
    val languageTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (languageTags.isBlank()) return this

    val localizedConfiguration = Configuration(resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(languageTags))
    }
    return createConfigurationContext(localizedConfiguration)
}
