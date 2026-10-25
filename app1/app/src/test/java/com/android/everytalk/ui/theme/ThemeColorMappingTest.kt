package com.android.everytalk.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorMappingTest {

    @Test
    fun `所有输入框别名使用同一24dp大圆角`() {
        assertEquals(24f, AppInputFieldShape.topStart.toPx(Size(200f, 80f), Density(1f)), 0f)
        assertEquals(AppInputFieldShape, com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape)
        assertEquals(AppInputFieldShape, com.android.everytalk.ui.screens.settings.DialogShape)
    }

    @Test
    fun `dark theme primary text colors are pure white`() {
        assertEquals(Color(0xFFFFFFFF), DarkOnBackground)
        assertEquals(Color(0xFFFFFFFF), DarkOnSurface)
        assertEquals(Color(0xFFFFFFFF), DarkOnCard)
        assertEquals(Color(0xFFFFFFFF), DarkTextPrimary)
    }

    @Test
    fun `light theme primary text colors are pure black`() {
        assertEquals(Color(0xFF000000), lightThemeOnBackground())
        assertEquals(Color(0xFF000000), lightThemeOnSurface())
    }

    @Test
    fun `secondary text colors remain grayscale`() {
        assertEquals(Color(0xFF49454F), lightThemeOnSurfaceVariant())
        assertEquals(Color(0xFFBBBBBB), DarkOnSurfaceVariant)
    }
}
