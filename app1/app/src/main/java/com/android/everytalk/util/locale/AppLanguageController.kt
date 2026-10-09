package com.android.everytalk.util.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import android.content.Context
import android.content.res.Configuration
import androidx.core.app.LocaleManagerCompat

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

/**
 * 后台控制器和服务也按应用选择的语言读取资源。
 * 不修改全局配置；每次调用重新读取语言，因此切换后不会沿用旧 Context 的语言。
 * 跟随系统时保留传入的 Context，Compose 与测试中的显式语言配置仍然有效。
 */
fun Context.appLanguageContext(): Context {
    val locales = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        LocaleManagerCompat.getApplicationLocales(this)
    } else {
        // 旧版本的持久化会稍后写盘，优先读取 AppCompat 内存中的最新选择。
        AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }
            ?: LocaleManagerCompat.getApplicationLocales(this)
    }
    if (locales.isEmpty) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
    }
    return createConfigurationContext(configuration)
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
