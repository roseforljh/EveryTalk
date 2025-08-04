package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 85.dp,
    contentPaddingHorizontal: Dp = 8.dp,
    bottomAlignPadding: Dp = 12.dp,
    titleFontSize: TextUnit = 12.sp,
    iconButtonSize: Dp = 36.dp,
    iconSize: Dp = 22.dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MaterialTheme.colorScheme.surface),

        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = contentPaddingHorizontal),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .padding(horizontal = 4.dp)
                    .align(Alignment.Bottom)
                    .padding(bottom = bottomAlignPadding - 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceDim, // 响应主题的淡灰色背景，与纯白有轻微区分
                    modifier = Modifier
                        .height(28.dp)
                        .wrapContentWidth(unbounded = false)
                        .clip(CircleShape)
                        .clickable(onClick = onTitleClick)
                ) {
                    Text(
                        text = selectedConfigName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = titleFontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .align(Alignment.Center)
                            .offset(y = (-1.8).dp)
                    )
                }
            }


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
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}