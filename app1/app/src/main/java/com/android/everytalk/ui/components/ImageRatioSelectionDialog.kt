package com.android.everytalk.ui.components

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier

/**
 * 图像比例选择弹窗（可按模型家族与候选比列动态展示）
 */
@Composable
fun ImageRatioSelectionDialog(
    selectedRatio: ImageRatio,
    onRatioSelected: (ImageRatio) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // 动态参数（可选）：允许展示的比例名称（如 ["1:1","16:9"]），为空则沿用默认集合
    allowedRatioNames: List<String>? = null,
    // 若为 Seedream 家族可显示清晰度（2K/4K）
    family: ModelFamily? = null,
    seedreamQuality: QualityTier = QualityTier.Q2K,
    onQualityChange: ((QualityTier) -> Unit)? = null
) {
    // 依据 allowedRatioNames 过滤默认比例集合（始终保留 AUTO）
    val allRatios = remember { ImageRatio.DEFAULT_RATIOS.filter { !it.isAuto } }
    val filteredRatios = remember(allowedRatioNames, allRatios) {
        if (allowedRatioNames.isNullOrEmpty()) {
            allRatios
        } else {
            val names = allowedRatioNames.map { it.trim() }.toSet()
            val existing = allRatios.filter { r ->
                val name = r.displayName.trim()
                name in names
            }
            // 对于不在默认集合中的比例（如 3:2），尝试动态解析并添加
            val existingNames = existing.map { it.displayName.trim() }.toSet()
            val missing = names - existingNames
            val dynamic = missing.mapNotNull { name ->
                val parts = name.split(":")
                if (parts.size == 2) {
                    val wRatio = parts[0].toFloatOrNull()
                    val hRatio = parts[1].toFloatOrNull()
                    if (wRatio != null && hRatio != null && wRatio > 0 && hRatio > 0) {
                        // 动态计算分辨率：以 1024 为基准长边，确保生成的 ImageRatio 具有合理的像素尺寸
                        // 避免直接使用比例整数（如 3x2）导致生成的图片极小
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
 
    // Kolors 专属：将 3:4 比例在展示时展开为两个具体分辨率选项（960×1280 与 768×1024）
    val displayRatios = remember(filteredRatios, family) {
        if (family == ModelFamily.KOLORS) {
            buildList {
                filteredRatios.forEach { r ->
                    if (r.displayName.trim() == "3:4") {
                        add(
                            ImageRatio(
                                displayName = "3:4",
                                width = 960,
                                height = 1280
                            )
                        )
                        add(
                            ImageRatio(
                                displayName = "3:4",
                                width = 768,
                                height = 1024
                            )
                        )
                    } else {
                        add(r)
                    }
                }
            }
        } else {
            filteredRatios
        }
    }
 
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
                .height(420.dp) // 略增高度，容纳清晰度分段控件
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

                // 仅 Seedream 家族显示 2K / 4K 清晰度选择（使用 Button 组合以兼容旧版 Material3）
                if (family == ModelFamily.SEEDREAM) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .heightIn(min = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val is2K = seedreamQuality == QualityTier.Q2K
                        val is4K = seedreamQuality == QualityTier.Q4K

                        if (is2K) {
                            FilledTonalButton(
                                onClick = { /* no-op when already selected */ },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) { Text("2K") }
                        } else {
                            OutlinedButton(
                                onClick = { onQualityChange?.invoke(QualityTier.Q2K) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) { Text("2K") }
                        }

                        if (is4K) {
                            FilledTonalButton(
                                onClick = { /* no-op when already selected */ },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) { Text("4K") }
                        } else {
                            OutlinedButton(
                                onClick = { onQualityChange?.invoke(QualityTier.Q4K) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) { Text("4K") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

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
                            fixedHeight = 44.dp,
                            family = family
                        )
                    }
                    // 其它比例两列展示（Kolors 下 3:4 展开为两个分辨率）
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
    fixedHeight: Dp? = null,
    family: ModelFamily? = null
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

            // Kolors 家族下对 3:4 比例的尺寸区分提示：在每个 3:4 条目下显示其具体分辨率
            if (family == ModelFamily.KOLORS && ratio.displayName.trim() == "3:4") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${ratio.width}×${ratio.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            
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