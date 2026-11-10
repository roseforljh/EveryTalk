package com.android.everytalk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ChatGPT 1.2026.279 的公开排版 token 映射。
 *
 * APK 的 `ii00` 明确使用 Roboto → OpenAI Sans → sans-serif 回退，
 * Android 本地没有 OpenAI Sans 文件时由 SansSerif 继续走系统 Roboto。
 * 字距使用 APK token 的 sp 数值，不再全部清零。
 */
private val ChatGptSans = FontFamily.SansSerif

private fun chatText(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Float,
) = TextStyle(
    fontFamily = ChatGptSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val AppTypography = Typography(
    displayLarge = chatText(36, 44, FontWeight.Normal, 0.32f),
    displayMedium = chatText(28, 36, FontWeight.Normal, 0.32f),
    displaySmall = chatText(24, 32, FontWeight.Medium, 0.1f),
    headlineLarge = chatText(22, 28, FontWeight.Medium, 0.1f),
    headlineMedium = chatText(17, 26, FontWeight.Medium, 0.1f),
    headlineSmall = chatText(17, 26, FontWeight.Normal, 0.1f),
    titleLarge = chatText(17, 26, FontWeight.Medium, 0.1f),
    titleMedium = chatText(14, 20, FontWeight.Medium, 0.1f),
    titleSmall = chatText(14, 20, FontWeight.Normal, 0.1f),
    bodyLarge = chatText(17, 26, FontWeight.Normal, 0.1f),
    bodyMedium = chatText(14, 20, FontWeight.Normal, 0.1f),
    bodySmall = chatText(14, 18, FontWeight.Normal, 0.15f),
    labelLarge = chatText(14, 20, FontWeight.Medium, 0.2f),
    labelMedium = chatText(13, 18, FontWeight.Normal, 0.2f),
    labelSmall = chatText(12, 16, FontWeight.Normal, 0.2f),
)
