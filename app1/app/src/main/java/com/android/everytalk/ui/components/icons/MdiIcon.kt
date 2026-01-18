package com.android.everytalk.ui.components.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.android.everytalk.R

private val MdiFontFamily = FontFamily(Font(R.font.materialdesignicons))

/**
 * MDI 图标组件
 * 使用 Material Design Icons 字体渲染图标
 *
 * @param name 图标名，如 "database-import"（不含 mdi: 前缀）
 * @param modifier Modifier
 * @param size 图标大小
 * @param tint 图标颜色
 */
@Composable
fun MdiIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current
) {
    val codepoint = MdiIconMap.getCodepoint(name)
    if (codepoint != null) {
        val fontSize = size.value.sp
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String(Character.toChars(codepoint)),
                fontFamily = MdiFontFamily,
                color = tint,
                fontSize = fontSize,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    lineHeight = fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        }
    }
}

/**
 * 自适应大小的 MDI 图标组件
 * 自动填充父容器并居中
 *
 * @param name 图标名，如 "database-import"（不含 mdi: 前缀）
 * @param modifier Modifier（应用于外层容器）
 * @param tint 图标颜色
 * @param padding 图标与容器边缘的内边距比例（0-1），默认 0.2 表示留 20% 边距
 */
@Composable
fun MdiIconAdaptive(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    padding: Float = 0.2f
) {
    val codepoint = MdiIconMap.getCodepoint(name)
    if (codepoint != null) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val availableSize = min(maxWidth, maxHeight)
            val iconSize = availableSize * (1f - padding)
            val fontSize = with(LocalDensity.current) { iconSize.toSp() }

            Text(
                text = String(Character.toChars(codepoint)),
                fontFamily = MdiFontFamily,
                color = tint,
                fontSize = fontSize,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    lineHeight = fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        }
    }
}

/**
 * 解析 mdi: 前缀的图标名并渲染
 *
 * @param icon 图标标识，如 "mdi:database-import"
 * @param modifier Modifier
 * @param size 图标大小
 * @param tint 图标颜色
 * @return true 如果成功渲染，false 如果图标不存在或格式不支持
 */
@Composable
fun MdiIconFromString(
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current
): Boolean {
    val normalized = icon.trim().lowercase()
    if (!normalized.startsWith("mdi:")) return false

    val name = normalized.removePrefix("mdi:")
    val codepoint = MdiIconMap.getCodepoint(name)

    if (codepoint != null) {
        val fontSize = size.value.sp
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String(Character.toChars(codepoint)),
                fontFamily = MdiFontFamily,
                color = tint,
                fontSize = fontSize,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    lineHeight = fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        }
        return true
    }
    return false
}

/**
 * 检查 mdi: 图标是否存在
 */
fun isMdiIconAvailable(icon: String): Boolean {
    val normalized = icon.trim().lowercase()
    if (!normalized.startsWith("mdi:")) return false
    val name = normalized.removePrefix("mdi:")
    return MdiIconMap.contains(name)
}
