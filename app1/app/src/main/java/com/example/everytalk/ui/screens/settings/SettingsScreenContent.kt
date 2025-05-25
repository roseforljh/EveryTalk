package com.example.everytalk.ui.screens.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap // 新增导入 for snap animation
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.ApiConfig

internal fun maskApiKeyShort(key: String): String {
    return if (key.length > 4) "****${key.takeLast(4)}" else "****"
}

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    paddingValues: PaddingValues,
    apiConfigsByApiKey: Map<String, List<ApiConfig>>,
    onAddFullConfigClick: () -> Unit,
    onSelectConfig: (config: ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    onAddModelForApiKeyClick: (apiKey: String, existingProvider: String, existingAddress: String) -> Unit,
    onDeleteModelForApiKey: (configToDelete: ApiConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Button(
            onClick = onAddFullConfigClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加配置")
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("添加配置")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        if (apiConfigsByApiKey.isEmpty()) {
            Text(
                "暂无API配置，请点击上方按钮添加。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
                    .align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        } else {
            apiConfigsByApiKey.forEach { (apiKey, configsForKey) ->
                if (configsForKey.isNotEmpty()) {
                    ApiKeyItemGroup(
                        apiKey = apiKey,
                        configsInGroup = configsForKey,
                        onSelectConfig = onSelectConfig,
                        selectedConfigIdInApp = selectedConfigIdInApp,
                        onAddModelForApiKeyClick = {
                            val representativeConfig = configsForKey.first()
                            onAddModelForApiKeyClick(
                                apiKey,
                                representativeConfig.provider,
                                representativeConfig.address
                            )
                        },
                        onDeleteModelForApiKey = onDeleteModelForApiKey
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyItemGroup(
    apiKey: String,
    configsInGroup: List<ApiConfig>,
    onSelectConfig: (ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    onAddModelForApiKeyClick: () -> Unit,
    onDeleteModelForApiKey: (ApiConfig) -> Unit
) {
    var expandedModels by remember { mutableStateOf(false) }
    val providerName = configsInGroup.firstOrNull()?.provider?.ifBlank { null } ?: "综合平台"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Key: ${maskApiKeyShort(apiKey)}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expandedModels = !expandedModels }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onAddModelForApiKeyClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "为此Key添加模型",
                            tint = Color.Gray
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expandedModels,
                enter = expandVertically(animationSpec = tween(durationMillis = 200)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 200)) + fadeOut(
                    animationSpec = tween(durationMillis = 150)
                )
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    if (configsInGroup.isEmpty()) {
                        Text(
                            "此密钥下暂无模型，请点击右上方 \"+\" 添加。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    } else {
                        configsInGroup.forEach { config ->
                            ModelItem(
                                config = config,
                                isSelected = config.id == selectedConfigIdInApp,
                                onSelect = { onSelectConfig(config) },
                                onDelete = { onDeleteModelForApiKey(config) }
                            )
                            if (config != configsInGroup.last()) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(
                                        alpha = 0.1f
                                    ),
                                    modifier = Modifier.padding(
                                        start = 40.dp,
                                        end = 8.dp,
                                        top = 4.dp,
                                        bottom = 4.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelItem(
    config: ApiConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val itemBackgroundColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFF4F4F4) else Color.Transparent,
        animationSpec = if (isSelected) tween(durationMillis = 200) else snap(),
        label = "ModelItemBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(itemBackgroundColor)
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircleOutline else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = "选择模型",
            tint = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifEmpty { config.model },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = Color.Black
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除模型 ${config.name}",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}