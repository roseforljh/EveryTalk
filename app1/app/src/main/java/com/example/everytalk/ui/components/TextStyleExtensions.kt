package com.example.everytalk.ui.components

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.times
import androidx.compose.ui.unit.sp

private const val DEFAULT_LINE_HEIGHT_RATIO = 1.25f

internal fun TextStyle.normalizeForChat(): TextStyle {
    val targetLineHeight = when {
        lineHeight == TextUnit.Unspecified && fontSize != TextUnit.Unspecified ->
            fontSize * DEFAULT_LINE_HEIGHT_RATIO
        else -> lineHeight
    }
    return this.copy(
        lineHeight = targetLineHeight,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

internal fun TextStyle.normalizeForInlineCode(): TextStyle = normalizeForChat().copy(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp
)