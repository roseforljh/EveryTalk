package com.android.everytalk.ui.theme
import com.android.everytalk.statecontroller.*

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 所有表单、搜索和自定义输入框共用的大圆角，避免页面各自定义小圆角。 */
val AppInputFieldShape = RoundedCornerShape(24.dp)

private val DarkColorScheme = darkColorScheme(
    // 标准 Material 控件共用灰阶主色，按钮、链接和进度圈不再各自回落到蓝色。
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkTextPrimary,
    
    // 次要操作同样使用中性灰阶。
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkTextPrimary,
    
    // 强调层级靠亮度区分。
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
    inversePrimary = Color.Black,
    
    // Material 3 新增颜色
    surfaceTint = DarkPrimary,                     // 表面色调
    surfaceBright = DarkCardElevated,              // 明亮表面
    surfaceDim = DarkSurfaceDim,                   // 比 surfaceBright 暗
    surfaceContainerLowest = DarkBackground,       // 最低容器
    surfaceContainerLow = DarkSurface,             // 低容器
    surfaceContainerHigh = DarkSurfaceContainer,   // 高容器
    surfaceContainerHighest = DarkCardElevated     // 最高容器
)

private val LightColorScheme = lightColorScheme(
    // 主色调
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color.Black,

    // 次要色调
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color.Black,

    // 强调色
    tertiary = Color(0xFF555555),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E8),
    onTertiaryContainer = Color.Black,

    // 背景和表面 - 全部改为纯白
    background = Color.White, // 主聊天背景改为纯白
    onBackground = Color(0xFF000000),
    surface = Color.White, // 卡片、对话框表面改为纯白
    onSurface = Color(0xFF000000),
    surfaceVariant = Color.White, // 表面变体改为纯白
    onSurfaceVariant = Color(0xFF49454F),

    // 错误状态
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // 轮廓线
    outline = Color(0xFF797979),
    outlineVariant = Color(0xFFCACACA),

    // 其他颜色
    scrim = Color.Black,
    inverseSurface = Color(0xFF313131),
    inverseOnSurface = Color(0xFFF4F4F4),
    inversePrimary = Color.White,

    // Material 3 新增颜色 - 优化弹出选项卡颜色
    surfaceTint = Color.White,
    // 主背景保持纯白，容器逐级压暗，否则白卡片在白底上没有层次。
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE8E8E8), // 比 surfaceBright 暗，语义上是“被压暗的表面”
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8)
)

internal fun lightThemeOnBackground(): Color = LightColorScheme.onBackground
internal fun lightThemeOnSurface(): Color = LightColorScheme.onSurface
internal fun lightThemeOnSurfaceVariant(): Color = LightColorScheme.onSurfaceVariant

@Composable
fun App1Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 默认保持品牌黑白主题，只有显式启用时才使用系统壁纸配色。
    dynamicColor: Boolean = false,
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
        shapes = Shapes(extraSmall = AppInputFieldShape),
        content = content
    )
}
