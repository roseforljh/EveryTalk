package com.android.everytalk.ui.components.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
        Text(
            text = String(Character.toChars(codepoint)),
            fontFamily = MdiFontFamily,
            color = tint,
            fontSize = size.value.sp,
            modifier = modifier.size(size)
        )
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
        Text(
            text = String(Character.toChars(codepoint)),
            fontFamily = MdiFontFamily,
            color = tint,
            fontSize = size.value.sp,
            modifier = modifier.size(size)
        )
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
