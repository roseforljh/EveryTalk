package com.android.everytalk.util.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage(val languageTag: String?) {
    SYSTEM(languageTag = null),
    SIMPLIFIED_CHINESE(languageTag = "zh-CN"),
    ENGLISH(languageTag = "en"),
}

object AppLanguageController {
    fun currentLanguage(): AppLanguage = resolveAppLanguage(
        AppCompatDelegate.getApplicationLocales().toLanguageTags(),
    )

    fun setLanguage(language: AppLanguage) {
        val locales = language.languageTag
            ?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        if (locales != AppCompatDelegate.getApplicationLocales()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

internal fun resolveAppLanguage(languageTags: String): AppLanguage {
    val primaryTag = languageTags.substringBefore(',').trim()
    if (primaryTag.isEmpty()) return AppLanguage.SYSTEM

    return when (Locale.forLanguageTag(primaryTag).language) {
        Locale.CHINESE.language -> AppLanguage.SIMPLIFIED_CHINESE
        Locale.ENGLISH.language -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
}
