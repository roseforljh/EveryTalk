package com.android.everytalk.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier

/**
 * 胶囊形状的比例选择按钮
 */
@Composable
fun ImageRatioButton(
    selectedRatio: ImageRatio,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 比例图标
            Icon(
                imageVector = Icons.Default.AspectRatio,
                contentDescription = "选择比例",
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF00BCD4) // 青绿色
            )
            
            // 比例文本
            Text(
                text = selectedRatio.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * 带状态管理的比例选择按钮组合（支持按模型家族动态限制候选与 Seedream 清晰度）
 */
@Composable
fun ImageRatioSelector(
    selectedRatio: ImageRatio,
    onRatioChanged: (ImageRatio) -> Unit,
    modifier: Modifier = Modifier,
    // 新增：允许的比例名（例如 ["1:1","16:9"]），为空则沿用默认
    allowedRatioNames: List<String>? = null,
    // 新增：模型家族（用于决定是否展示清晰度分段）
    family: ModelFamily? = null,
    // 新增：仅 Seedream 有效的清晰度状态与回调
    seedreamQuality: QualityTier = QualityTier.Q2K,
    onQualityChange: ((QualityTier) -> Unit)? = null,
    // 新增：Gemini 尺寸状态与回调
    geminiImageSize: String? = null,
    onGeminiImageSizeChange: ((String) -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    
    // 按钮
    ImageRatioButton(
        selectedRatio = selectedRatio,
        onClick = { showDialog = true },
        modifier = modifier
    )
    
    // 弹窗
    if (showDialog) {
        ImageRatioSelectionDialog(
            selectedRatio = selectedRatio,
            onRatioSelected = onRatioChanged,
            onDismiss = { showDialog = false },
            allowedRatioNames = allowedRatioNames,
            family = family,
            seedreamQuality = seedreamQuality,
            onQualityChange = onQualityChange,
            geminiImageSize = geminiImageSize,
            onGeminiImageSizeChange = onGeminiImageSizeChange
        )
    }
}