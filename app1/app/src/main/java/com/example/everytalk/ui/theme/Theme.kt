package com.example.everytalk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    // 主色调 - 使用柔和的蓝色
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkTextPrimary,
    
    // 次要色调 - 柔和的绿色
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkTextPrimary,
    
    // 强调色 - 柔和的金黄色
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkTextPrimary,
    
    // 背景和表面 - 关键改进：统一的灰色调
    background = DarkBackground,                    // 柔和深灰背景
    onBackground = DarkOnBackground,               // 柔和白色文字
    surface = DarkCardBackground,                  // 卡片使用深灰而非白色
    onSurface = DarkOnCard,                       // 卡片上的文字
    surfaceVariant = DarkSurfaceVariant,          // 表面变体
    onSurfaceVariant = DarkOnSurfaceVariant,      // 表面变体文字
    surfaceContainer = DarkSurfaceContainer,       // 容器颜色
    
    // 错误状态 - 柔和的红色
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkTextPrimary,
    
    // 轮廓线 - 微妙的分割
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    
    // 其他颜色 - 协调的配色
    scrim = Color.Black.copy(alpha = 0.7f),       // 稍微透明的遮罩
    inverseSurface = DarkTextPrimary,              // 反色表面
    inverseOnSurface = DarkBackground,             // 反色表面文字
    inversePrimary = Purple40,                     // 反色主色调
    
    // Material 3 新增颜色
    surfaceTint = DarkPrimary,                     // 表面色调
    surfaceBright = DarkCardElevated,              // 明亮表面
    surfaceDim = DarkPopupBackground,              // 夜间模式弹出选项卡使用深灰色
    surfaceContainerLowest = DarkBackground,       // 最低容器
    surfaceContainerLow = DarkSurface,             // 低容器
    surfaceContainerHigh = DarkSurfaceContainer,   // 高容器
    surfaceContainerHighest = DarkCardElevated     // 最高容器
)

private val LightColorScheme = lightColorScheme(
    // 主色调
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = Color.White, // 纯白按钮背景
    onPrimaryContainer = Color(0xFF21005D),
    
    // 次要色调
    secondary = PurpleGrey40,
    onSecondary = Color.White,
    secondaryContainer = Color.White, // 纯白按钮背景
    onSecondaryContainer = Color(0xFF1D192B),
    
    // 强调色
    tertiary = Pink40,
    onTertiary = Color.White,
    tertiaryContainer = Color.White, // 纯白按钮背景
    onTertiaryContainer = Color(0xFF31111D),
    
    // 背景和表面 - 全部改为纯白
    background = Color.White, // 主聊天背景改为纯白
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White, // 卡片、对话框表面改为纯白
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color.White, // 表面变体改为纯白
    onSurfaceVariant = Color(0xFF49454F),
    
    // 错误状态
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    
    // 轮廓线
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    
    // 其他颜色
    scrim = Color.Black,
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Purple80,
    
    // Material 3 新增颜色 - 优化弹出选项卡颜色
    surfaceTint = Color.White,
    surfaceBright = Color.White,
    surfaceDim = LightPopupBackground, // 白天模式弹出选项卡使用淡灰色
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White
)

@Composable
fun App1Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}