package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    onTitleClick: () -> Unit, // 新增：点击标题/模型名称的回调
    modifier: Modifier = Modifier,
    barHeight: Dp = 85.dp,
    contentPaddingHorizontal: Dp = 8.dp,
    bottomAlignPadding: Dp = 12.dp,
    titleFontSize: TextUnit = 12.sp, // 稍微调小一点以便适应胶囊
    iconButtonSize: Dp = 36.dp,
    iconSize: Dp = 22.dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(Color.White),

        color = Color.White,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = contentPaddingHorizontal),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Navigation Icon
            Box(
                modifier = Modifier
                    .size(iconButtonSize)
                    .align(Alignment.Bottom)
                    .padding(bottom = bottomAlignPadding),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "打开导航菜单",
                        tint = Color.Black,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }

            // Title as a Capsule Button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight) // 让Box和Bar一样高，方便垂直居中胶囊
                    .padding(horizontal = 4.dp) // 给胶囊一些呼吸空间
                    .align(Alignment.Bottom) // 确保胶囊在底部对齐线上
                    .padding(bottom = bottomAlignPadding - 4.dp), // 调整胶囊的底部对齐，使其视觉上更协调
                contentAlignment = Alignment.BottomCenter // 胶囊按钮在Box中底部居中
            ) {
                Surface( // 使用Surface制作胶囊背景和形状
                    shape = CircleShape, // 胶囊形状 (等同于 RoundedCornerShape(percent = 50))
                    color = Color(0xffececec), // 胶囊背景色
                    modifier = Modifier
                        .height(28.dp) // 胶囊的高度
                        .wrapContentWidth(unbounded = false) //修饰符以显式声明包裹内容宽度,确保它不超过父容器允许的宽度
                        .clip(CircleShape) // 确保点击效果也在胶囊内
                        .clickable(onClick = onTitleClick) // 点击事件
                ) {
                    Text(
                        text = selectedConfigName,
                        color = Color(0xff57585d),
                        fontSize = titleFontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp) // 胶囊内部文字的 padding
                            .align(Alignment.Center) // 文字在Surface内居中
                            .offset(y = (-1.8).dp)
                    )
                }
            }


            // Action Icon
            Box(
                modifier = Modifier
                    .size(iconButtonSize)
                    .align(Alignment.Bottom)
                    .padding(bottom = bottomAlignPadding),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = Color.Black,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}