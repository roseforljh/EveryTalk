package com.example.everytalk.ui.screens.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
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
import com.example.everytalk.data.DataClass.ModalityType
import androidx.compose.ui.unit.sp
import com.example.everytalk.ui.screens.viewmodel.ConfigManager

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    paddingValues: PaddingValues,
    apiConfigsByApiKeyAndModality: Map<String, Map<ModalityType, List<ApiConfig>>>,
    onAddFullConfigClick: () -> Unit,
    onSelectConfig: (config: ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    onAddModelForApiKeyClick: (apiKey: String, existingProvider: String, existingAddress: String, existingModality: ModalityType) -> Unit,
    onDeleteModelForApiKey: (configToDelete: ApiConfig) -> Unit,
    onEditConfigClick: (config: ApiConfig) -> Unit,
    onDeleteConfigGroup: (apiKey: String, modalityType: ModalityType) -> Unit,
    onRefreshModelsClick: (config: ApiConfig) -> Unit,
    isRefreshingModels: Set<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Button(
            onClick = onAddFullConfigClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加配置")
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("添加配置")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        if (apiConfigsByApiKeyAndModality.isEmpty()) {
            Text(
                "暂无API配置，请点击上方按钮添加。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
                    .align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            apiConfigsByApiKeyAndModality.forEach { (apiKey, configsByModality) ->
                configsByModality.forEach { (modalityType, configsForKeyAndModality) ->
                    if (configsForKeyAndModality.isNotEmpty()) {
                        ApiKeyItemGroup(
                            apiKey = apiKey,
                            modalityType = modalityType,
                            configsInGroup = configsForKeyAndModality,
                            onSelectConfig = onSelectConfig,
                            selectedConfigIdInApp = selectedConfigIdInApp,
                            onAddModelForApiKeyClick = {
                                val representativeConfig = configsForKeyAndModality.first()
                                onAddModelForApiKeyClick(
                                    apiKey,
                                    representativeConfig.provider,
                                    representativeConfig.address,
                                    representativeConfig.modalityType
                                )
                            },
                            onDeleteModelForApiKey = onDeleteModelForApiKey,
                            onEditConfigClick = { onEditConfigClick(configsForKeyAndModality.first()) },
                            onDeleteGroup = { onDeleteConfigGroup(apiKey, modalityType) },
                            onRefreshModelsClick = { onRefreshModelsClick(configsForKeyAndModality.first()) },
                            isRefreshing = isRefreshingModels.contains("$apiKey-${modalityType}")
                        )
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyItemGroup(
    modifier: Modifier = Modifier,
    apiKey: String,
    modalityType: ModalityType,
    configsInGroup: List<ApiConfig>,
    onSelectConfig: (ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    onAddModelForApiKeyClick: () -> Unit,
    onDeleteModelForApiKey: (ApiConfig) -> Unit,
    onEditConfigClick: () -> Unit,
    onDeleteGroup: () -> Unit,
    onRefreshModelsClick: () -> Unit,
    isRefreshing: Boolean
) {
    var expandedModels by remember { mutableStateOf(false) }
    var showConfirmDeleteGroupDialog by remember { mutableStateOf(false) }
    val providerName =
        configsInGroup.firstOrNull()?.provider?.ifBlank { null } ?: "综合平台"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEditConfigClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
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
                        text = "Key: ${maskApiKey(apiKey)}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(
                    onClick = { showConfirmDeleteGroupDialog = true },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = "删除配置组",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                    text = "Models (${configsInGroup.size})",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (modalityType != ModalityType.IMAGE) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = onRefreshModelsClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新模型列表",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onAddModelForApiKeyClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "为此Key和类型添加模型",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                            "此分类下暂无模型，请点击右上方 \"+\" 添加。",
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
                                        start = 24.dp,
                                        end = 0.dp,
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

    if (showConfirmDeleteGroupDialog) {
        ConfirmDeleteDialog(
            onDismissRequest = { showConfirmDeleteGroupDialog = false },
            onConfirm = {
                onDeleteGroup()
                showConfirmDeleteGroupDialog = false
            },
            title = "删除整个配置组?",
            text = "您确定要删除 “$providerName” 的所有 ${modalityType.displayName} 模型配置吗？\n\n此操作会删除 ${configsInGroup.size} 个模型，且不可撤销。"
        )
    }
}

@Composable
private fun ModelItem(
    config: ApiConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var showConfirmDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = "选择模型",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifEmpty { config.model },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(
            onClick = { showConfirmDeleteDialog = true },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除模型",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showConfirmDeleteDialog) {
        ConfirmDeleteDialog(
            onDismissRequest = { showConfirmDeleteDialog = false },
            onConfirm = {
                onDelete()
                showConfirmDeleteDialog = false
            },
            title = "删除模型",
            text = "您确定要删除模型 “${config.name}” 吗？此操作不可撤销。"
        )
    }
}