package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.everytalk.data.DataClass.ImageRatio

/**
 * 图像比例选择弹窗
 */
@Composable
fun ImageRatioSelectionDialog(
    selectedRatio: ImageRatio,
    onRatioSelected: (ImageRatio) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(380.dp) // 稍微减少对话框总高度
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题（固定不滚动）
                Text(
                    text = "选择图像比例",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
                
                // 可滚动的内容区域（使用单一 LazyVerticalGrid，使 AUTO 与其他选项同级并一起滚动）
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    // AUTO 独占一行，降低高度，左右对称；与其它选项同级一起滚动
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ImageRatioItem(
                            ratio = ImageRatio.AUTO,
                            isSelected = ImageRatio.AUTO == selectedRatio,
                            onClick = {
                                onRatioSelected(ImageRatio.AUTO)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            fixedHeight = 44.dp
                        )
                    }
                    // 其它比例两列展示
                    items(ImageRatio.DEFAULT_RATIOS.filter { !it.isAuto }) { ratio ->
                        ImageRatioItem(
                            ratio = ratio,
                            isSelected = ratio == selectedRatio,
                            onClick = {
                                onRatioSelected(ratio)
                                onDismiss()
                            }
                        )
                    }
                }
                
                // 取消按钮（固定在底部）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

/**
 * 单个比例选项
 */
@Composable
private fun ImageRatioItem(
    ratio: ImageRatio,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fixedHeight: Dp? = null
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    
    // 根据是否传入固定高度决定尺寸约束，避免强制纵横比导致高度被放大
    val sizeModifier = if (fixedHeight != null) {
        Modifier.height(fixedHeight)
    } else {
        Modifier.aspectRatio(1.2f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(sizeModifier)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 比例显示
            Text(
                text = ratio.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 比例预览框或AUTO图标
            if (ratio.isAuto) {
                AutoModeIcon(isSelected = isSelected)
            } else {
                RatioPreviewBox(
                    ratio = ratio,
                    isSelected = isSelected
                )
            }
        }
    }
}

/**
 * 比例预览小框
 */
@Composable
private fun RatioPreviewBox(
    ratio: ImageRatio,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val aspectRatio = ratio.width.toFloat() / ratio.height.toFloat()
    val maxSize = 24.dp
    
    val (boxWidth, boxHeight) = if (aspectRatio >= 1) {
        // 横图或正方形
        maxSize to (maxSize / aspectRatio)
    } else {
        // 竖图
        (maxSize * aspectRatio) to maxSize
    }
    
    // 使用主题主色作为示意图颜色，未选中时降低不透明度以保持层级
    val boxColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 1f else 0.75f)
    
    Box(
        modifier = modifier
            .size(width = boxWidth, height = boxHeight)
            .background(
                color = boxColor,
                shape = RoundedCornerShape(2.dp)
            )
    )
}

/**
 * AUTO模式图标
 */
@Composable
private fun AutoModeIcon(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val iconColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    }
    
    Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = "自动模式",
        modifier = modifier.size(24.dp),
        tint = iconColor
    )
}