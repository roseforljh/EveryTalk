package com.android.everytalk.ui.theme

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTypographyTokenTest {
    @Test
    fun `GPT基础正文和标题使用新版字号行高字重字距`() {
        assertEquals(17f, AppTypography.bodyLarge.fontSize.value)
        assertEquals(26f, AppTypography.bodyLarge.lineHeight.value)
        assertEquals(FontWeight.Normal, AppTypography.bodyLarge.fontWeight)
        assertEquals(0.1f, AppTypography.bodyLarge.letterSpacing.value)
        assertEquals(14f, AppTypography.bodyMedium.fontSize.value)
        assertEquals(20f, AppTypography.bodyMedium.lineHeight.value)
        assertEquals(14f, AppTypography.titleMedium.fontSize.value)
        assertEquals(FontWeight.Medium, AppTypography.titleMedium.fontWeight)
        // TopAppBar 标题 oiy0.h = 17/26/500；对话框标题 oiy0.l = 22/28/500
        assertEquals(17f, AppTypography.titleLarge.fontSize.value)
        assertEquals(FontWeight.Medium, AppTypography.titleLarge.fontWeight)
        assertEquals(22f, AppTypography.headlineLarge.fontSize.value)
        assertEquals(FontWeight.Medium, AppTypography.headlineLarge.fontWeight)
    }

    @Test
    fun `GPT回退字体保留系统无OpenAI Sans时的Roboto路径`() {
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, AppTypography.bodyLarge.fontFamily)
    }
}
