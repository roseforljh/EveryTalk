package com.android.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.R

@Composable
fun AppTopBar(
    selectedConfigName: String,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTitleClick: () -> Unit,
    onSystemPromptClick: () -> Unit,
    systemPrompt: String,
    isSystemPromptExpanded: Boolean,
    isSystemPromptEngaged: Boolean = false,
    onToggleSystemPromptEngaged: () -> Unit = {},
    modifier: Modifier = Modifier,
    barHeight: Dp = 85.dp,
    contentPaddingHorizontal: Dp = 12.dp,
    bottomAlignPadding: Dp = 12.dp,
    titleFontSize: TextUnit = 16.sp,
    iconButtonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp
) {
    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF212121) else Color(0xFFF0F0F0)
    val borderColor = if (isDark) Color(0xFF3E3E3E) else Color(0xFFD0D0D0)
    val textColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)
    val iconColor = if (isDark) Color.White else Color(0xFF0D0D0D)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 48.dp)
            .padding(contentPaddingHorizontal)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧菜单按钮 - 圆形 + 边框
            Box(
                modifier = Modifier
                    .size(iconButtonSize)
                    .clip(CircleShape)
                    .background(buttonBg)
                    .border(1.dp, borderColor, CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onMenuClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu),
                    contentDescription = "菜单",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 中间模型选择器 - 胶囊 + 边框，紧挨左侧按钮
            Box(
                modifier = Modifier
                    .widthIn(max = 180.dp)
                    .height(iconButtonSize)
                    .clip(RoundedCornerShape(24.dp))
                    .background(buttonBg)
                    .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                    .clickable(onClick = onTitleClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedConfigName,
                    color = textColor,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 右侧设置按钮 - 圆形 + 边框
            Box(
                modifier = Modifier
                    .size(iconButtonSize)
                    .clip(CircleShape)
                    .background(buttonBg)
                    .border(1.dp, borderColor, CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onSettingsClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_compose),
                    contentDescription = "设置",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
