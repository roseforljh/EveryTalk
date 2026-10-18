package com.android.everytalk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.android.everytalk.util.web.linkIcon

/**
 * 同步显示地址对应的本地图标，尺寸和无障碍说明由调用方提供。
 * 彩色标志保留原色，单色标志及未知网站的地球图标随主题着色，无需加载占位。
 */
@Composable
fun WebsiteIcon(
    href: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val icon = remember(href) { linkIcon(href) }
    Image(
        painter = painterResource(icon.resource),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = if (icon.multicolor) null else ColorFilter.tint(tint),
    )
}
