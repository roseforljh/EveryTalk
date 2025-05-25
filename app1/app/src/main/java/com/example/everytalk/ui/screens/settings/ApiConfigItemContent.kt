package com.example.everytalk.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.ApiConfig


@Composable
internal fun ApiConfigItemContent( // internal使其在包内可见
    config: ApiConfig,
    onDeleteClick: () -> Unit,
    onItemEditClick: () -> Unit,
    onSelectClick: () -> Unit,
    isSelectedForEditing: Boolean,
    isCurrentlySelectedInApp: Boolean
) {
    val targetBackgroundColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    val targetBorderColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        else -> Color.LightGray.copy(alpha = 0.6f)
    }
    val backgroundColor by animateColorAsState(targetBackgroundColor, label = "ConfigItemBgAnim")
    val borderColor by animateColorAsState(targetBorderColor, label = "ConfigItemBorderAnim")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, MaterialTheme.shapes.medium)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onItemEditClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                config.model.ifBlank { "(未命名模型)" },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(color = Color.Black),
                fontWeight = if (isSelectedForEditing || isCurrentlySelectedInApp) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "平台: ${config.provider.ifBlank { "未指定" }}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.DarkGray),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "地址: ${config.address.take(20)}${if (config.address.length > 20) "..." else ""}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "密钥: ${maskApiKey(config.key)}", // maskApiKey 可以移到这里或 SettingsUtils.kt
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelectClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isCurrentlySelectedInApp) Icons.Filled.CheckCircleOutline else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "选择配置 ${config.model}",
                    tint = if (isCurrentlySelectedInApp) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    "删除配置 ${config.model}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
