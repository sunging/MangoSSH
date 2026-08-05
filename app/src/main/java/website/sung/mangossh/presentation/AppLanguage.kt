package website.sung.mangossh.presentation

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Supported application-language choices exposed by MangoSSH settings. */
enum class AppLanguage(private val languageTag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ;

    /** Locale list accepted by AppCompat; an empty list restores system language resolution. */
    fun localeList(): LocaleListCompat = languageTag
        ?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()

    companion object {
        /** Reads the persisted AppCompat selection, treating an unknown legacy tag as system default. */
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: return SYSTEM
            return entries.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) } ?: SYSTEM
        }
    }
}
