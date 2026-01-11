package com.android.everytalk.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier
import kotlinx.coroutines.launch

/**
 * 图像比例选择弹窗
 */
@Composable
fun ImageRatioSelectionDialog(
    selectedRatio: ImageRatio,
    onRatioSelected: (ImageRatio) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allowedRatioNames: List<String>? = null,
    family: ModelFamily? = null,
    seedreamQuality: QualityTier = QualityTier.Q2K,
    onQualityChange: ((QualityTier) -> Unit)? = null,
    geminiImageSize: String? = null,
    onGeminiImageSizeChange: ((String) -> Unit)? = null
) {
    val allRatios = remember { ImageRatio.DEFAULT_RATIOS.filter { !it.isAuto } }
    val filteredRatios = remember(allowedRatioNames, allRatios, family) {
        if (allowedRatioNames.isNullOrEmpty()) {
            allRatios
        } else {
            if (family == ModelFamily.MODAL_Z_IMAGE) {
                val caps = ImageGenCapabilities.getCapabilities(family)
                caps.ratios.filter { it.ratio in allowedRatioNames }.map { opt ->
                    val ratioPart = opt.ratio.substringAfter(" ").trim()
                    val parts = ratioPart.split(":")
                    var w = 1024
                    var h = 1024
                    if (parts.size == 2) {
                        val wr = parts[0].toFloatOrNull() ?: 1f
                        val hr = parts[1].toFloatOrNull() ?: 1f
                        if (wr >= hr) {
                            h = (1024 / wr * hr).toInt()
                        } else {
                            w = (1024 / hr * wr).toInt()
                        }
                    }
                    ImageRatio(displayName = opt.displayName, width = w, height = h)
                }
            } else {
                val names = allowedRatioNames.map { it.trim() }.toSet()
                val existing = allRatios.filter { r ->
                    val name = r.displayName.trim()
                    name in names
                }
                val existingNames = existing.map { it.displayName.trim() }.toSet()
                val missing = names - existingNames
                val dynamic = missing.mapNotNull { name ->
                    val parts = name.split(":")
                    if (parts.size == 2) {
                        val wRatio = parts[0].toFloatOrNull()
                        val hRatio = parts[1].toFloatOrNull()
                        if (wRatio != null && hRatio != null && wRatio > 0 && hRatio > 0) {
                            val baseSize = 1024f
                            val (w, h) = if (wRatio >= hRatio) {
                                baseSize to (baseSize / wRatio * hRatio)
                            } else {
                                (baseSize / hRatio * wRatio) to baseSize
                            }
                            ImageRatio(displayName = name, width = w.toInt(), height = h.toInt())
                        } else null
                    } else null
                }
                existing + dynamic
            }
        }
    }

    val displayRatios = remember(filteredRatios, family) {
        val ratios = filteredRatios
        if (family == ModelFamily.KOLORS) {
            buildList {
                ratios.forEach { r ->
                    if (r.displayName.trim() == "3:4") {
                        add(ImageRatio(displayName = "3:4", width = 960, height = 1280))
                        add(ImageRatio(displayName = "3:4", width = 768, height = 1024))
                    } else {
                        add(r)
                    }
                }
            }
        } else {
            ratios
        }
    }

    // 入场动画
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.9f) }

    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, animationSpec = tween(durationMillis = 250)) }
        launch { scale.animateTo(1f, animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "选择图像比例",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Seedream 清晰度选择
                if (family == ModelFamily.SEEDREAM) {
                    QualitySelector(
                        options = listOf("2K", "4K"),
                        selectedOption = if (seedreamQuality == QualityTier.Q2K) "2K" else "4K",
                        onOptionSelected = { option ->
                            onQualityChange?.invoke(if (option == "2K") QualityTier.Q2K else QualityTier.Q4K)
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Gemini 尺寸选择
                if (family == ModelFamily.GEMINI && onGeminiImageSizeChange != null) {
                    QualitySelector(
                        options = listOf("2K", "4K"),
                        selectedOption = geminiImageSize ?: "2K",
                        onOptionSelected = { option -> onGeminiImageSizeChange(option) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 比例选项网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 320.dp)
                        .fillMaxWidth()
                ) {
                    // AUTO 独占一行
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ImageRatioItem(
                            ratio = ImageRatio.AUTO,
                            isSelected = ImageRatio.AUTO == selectedRatio,
                            onClick = {
                                onRatioSelected(ImageRatio.AUTO)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            isAutoItem = true,
                            family = family
                        )
                    }
                    // 其它比例
                    items(displayRatios) { ratio ->
                        ImageRatioItem(
                            ratio = ratio,
                            isSelected = ratio == selectedRatio,
                            onClick = {
                                onRatioSelected(ratio)
                                onDismiss()
                            },
                            family = family
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        "取消",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    }
}

/**
 * 清晰度选择器
 */
@Composable
private fun QualitySelector(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val backgroundColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
                val contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(backgroundColor)
                        .clickable { onOptionSelected(option) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge
                    )
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
    isAutoItem: Boolean = false,
    family: ModelFamily? = null
) {
    val isDarkTheme = isSystemInDarkTheme()

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFF5F5F5)
    }

    // 未选中时也显示边框，增加视觉区分
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        if (isDarkTheme) Color(0xFF404040) else Color(0xFFE0E0E0)
    }

    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val itemHeight = if (isAutoItem) 48.dp else 64.dp

    Card(
        modifier = modifier
            .height(itemHeight)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ratio.isAuto) {
                // AUTO 模式：图标 + 文字在左侧
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "AUTO",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor
                    )
                }
            } else {
                // 普通比例：预览框 + 文字在左侧紧凑排列
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RatioPreviewBox(ratio = ratio, isSelected = isSelected)
                    Column {
                        Text(
                            text = ratio.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor
                        )
                        if (family == ModelFamily.KOLORS && ratio.displayName.trim() == "3:4") {
                            Text(
                                text = "${ratio.width}x${ratio.height}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 右侧选中图标
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 比例预览框
 */
@Composable
private fun RatioPreviewBox(
    ratio: ImageRatio,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val aspectRatio = ratio.width.toFloat() / ratio.height.toFloat()
    val maxSize = 28.dp

    val (boxWidth, boxHeight) = if (aspectRatio >= 1) {
        maxSize to (maxSize / aspectRatio)
    } else {
        (maxSize * aspectRatio) to maxSize
    }

    val boxColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .size(width = boxWidth, height = boxHeight)
            .background(color = boxColor, shape = RoundedCornerShape(3.dp))
    )
}
