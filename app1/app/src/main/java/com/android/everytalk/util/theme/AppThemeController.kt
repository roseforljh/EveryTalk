package com.android.everytalk.util.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(
    internal val storageValue: String,
    internal val nightMode: Int,
) {
    SYSTEM(
        storageValue = "system",
        nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    ),
    LIGHT(
        storageValue = "light",
        nightMode = AppCompatDelegate.MODE_NIGHT_NO,
    ),
    DARK(
        storageValue = "dark",
        nightMode = AppCompatDelegate.MODE_NIGHT_YES,
    ),
}

object AppThemeController {
    private const val PREFERENCES_NAME = "appearance_preferences"
    private const val THEME_KEY = "app_theme"

    fun applySavedTheme(context: Context) {
        applyTheme(currentTheme(context))
    }

    fun currentTheme(context: Context): AppTheme = resolveAppTheme(
        preferences(context).getString(THEME_KEY, null),
    )

    fun setTheme(context: Context, theme: AppTheme) {
        preferences(context)
            .edit()
            .putString(THEME_KEY, theme.storageValue)
            .apply()
        applyTheme(theme)
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun applyTheme(theme: AppTheme) {
        if (AppCompatDelegate.getDefaultNightMode() != theme.nightMode) {
            AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        }
    }
}

internal fun resolveAppTheme(storageValue: String?): AppTheme = AppTheme.entries
    .firstOrNull { it.storageValue == storageValue }
    ?: AppTheme.SYSTEM
