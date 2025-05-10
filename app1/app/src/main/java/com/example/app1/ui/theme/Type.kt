package com.example.app1.ui.theme // 确保这是你的主题包名

import com.example.app1.R // <<--- 尝试添加这个导入语句
import androidx.compose.material3.Typography // 保持默认的 Typography 导入
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 1. 定义 OppoSans FontFamily
val OppoSans = FontFamily(
    // 根据你拥有的字体文件添加 Font() 实例
    // 常规
    Font(R.font.oppo_sans_regular, FontWeight.Normal, FontStyle.Normal), // 普通样式
    Font(R.font.oppo_sans_regular, FontWeight.W400, FontStyle.Normal), // 明确指定 W400
)

// 2. 修改或创建应用的主 Typography
//    将默认的 fontFamily 指向我们定义的 OppoSans
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal, // 或 Bold
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal, // 或 Bold
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal, // 或 Bold
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Bold, // 通常 Title 用 Bold
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Bold, // 通常 Title 用 Bold
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Medium, // Title Small 可能用 Medium
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OppoSans, // <-- 应用字体
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)