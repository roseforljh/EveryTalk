package com.example.app1.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppTopBar(
    selectedConfigName: String,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 85.dp, // 您期望的顶栏整体高度
    contentPaddingHorizontal: Dp = 8.dp, // 内容区域左右的内边距
    bottomAlignPadding: Dp = 12.dp,       // 元素与底部的间距
    titleFontSize: TextUnit = 17.sp, // 标题字体大小
    iconButtonSize: Dp = 36.dp, // IconButton 的尺寸 (影响触摸区域)
    iconSize: Dp = 22.dp // Icon 本身的尺寸
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight) // 1. 设置顶栏的整体高度
            .background(Color.White), // 背景色直接在 Surface 上设置，如果需要的话
        color = Color.White, // Surface 的容器颜色
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize() // 填满 Surface
                .padding(horizontal = contentPaddingHorizontal), // 2. 设置内容区域的左右内边距
            // verticalAlignment 现在不再是 CenterVertically，而是通过 Box 的对齐来控制
            horizontalArrangement = Arrangement.SpaceBetween // 使标题能占据中间，图标在两边
        ) {
            // Navigation Icon - 对齐到底部
            Box(
                modifier = Modifier
                    .size(iconButtonSize) // IconButton 的尺寸
                    .align(Alignment.Bottom) // 使整个 Box 在 Row 中底部对齐
                    .padding(bottom = bottomAlignPadding), // Box 内容与底部的间距
                contentAlignment = Alignment.Center // Icon 在 IconButton 的 Box 内居中
            ) {
                IconButton(
                    onClick = onMenuClick,
                    // IconButton 本身会填满 Box
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "打开导航菜单",
                        tint = Color.Black,
                        modifier = Modifier.size(iconSize) // 设置 Icon 尺寸
                    )
                }
            }

            // Title - 对齐到底部
            Box(
                modifier = Modifier
                    .weight(1f) // 让标题占据 IconButton 之间的剩余空间
                    .fillMaxHeight() // 填充垂直空间，以便使用 align 控制
                    .padding(horizontal = 8.dp), // 标题与图标之间的间距
                contentAlignment = Alignment.BottomCenter // 内容在 Box 内底部居中
            ) {
                Text(
                    text = selectedConfigName,
                    color = Color.Black,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = bottomAlignPadding) // 文本与底部的间距
                )
            }

            // Action Icon - 对齐到底部
            Box(
                modifier = Modifier
                    .size(iconButtonSize) // IconButton 的尺寸
                    .align(Alignment.Bottom) // 使整个 Box 在 Row 中底部对齐
                    .padding(bottom = bottomAlignPadding), // Box 内容与底部的间距
                contentAlignment = Alignment.Center // Icon 在 IconButton 的 Box 内居中
            ) {
                IconButton(
                    onClick = onSettingsClick,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = Color.Black,
                        modifier = Modifier.size(iconSize) // 设置 Icon 尺寸
                    )
                }
            }
        }
    }
}