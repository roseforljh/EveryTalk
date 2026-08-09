package com.android.everytalk.util.theme

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppThemeControllerTest {
    private lateinit var context: Context
    private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousNightMode = AppCompatDelegate.getDefaultNightMode()
        AppThemeController.setTheme(context, AppTheme.SYSTEM)
    }

    @After
    fun tearDown() {
        AppThemeController.setTheme(context, AppTheme.SYSTEM)
        AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }

    @Test
    fun `主题偏好解析支持系统浅色和深色`() {
        assertEquals(AppTheme.SYSTEM, resolveAppTheme(null))
        assertEquals(AppTheme.SYSTEM, resolveAppTheme(""))
        assertEquals(AppTheme.LIGHT, resolveAppTheme("light"))
        assertEquals(AppTheme.DARK, resolveAppTheme("dark"))
        assertEquals(AppTheme.SYSTEM, resolveAppTheme("unknown"))
    }

    @Test
    fun `主题选项按系统浅色深色排列并应用到全局夜间模式`() {
        val expectedModes = listOf(
            AppTheme.SYSTEM to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppTheme.LIGHT to AppCompatDelegate.MODE_NIGHT_NO,
            AppTheme.DARK to AppCompatDelegate.MODE_NIGHT_YES,
        )

        assertEquals(expectedModes.map { it.first }, AppTheme.entries)
        expectedModes.forEach { (theme, nightMode) ->
            AppThemeController.setTheme(context, theme)
            assertEquals(theme, AppThemeController.currentTheme(context))
            assertEquals(nightMode, AppCompatDelegate.getDefaultNightMode())
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        AppThemeController.applySavedTheme(context)

        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.getDefaultNightMode(),
        )
    }
}
