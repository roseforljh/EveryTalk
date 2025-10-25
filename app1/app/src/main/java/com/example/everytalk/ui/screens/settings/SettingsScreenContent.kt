package com.example.everytalk.ui.screens.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
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
    onAddModelForApiKeyClick: (apiKey: String, existingProvider: String, existingAddress: String, existingChannel: String, existingModality: ModalityType) -> Unit,
    onDeleteModelForApiKey: (configToDelete: ApiConfig) -> Unit,
    onEditConfigClick: (config: ApiConfig) -> Unit,
    onDeleteConfigGroup: (representativeConfig: ApiConfig) -> Unit,
    onRefreshModelsClick: (config: ApiConfig) -> Unit,
    isRefreshingModels: Set<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // 添加配置按钮 - 更现代的设计
        ElevatedButton(
            onClick = onAddFullConfigClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = Color(0xFF616161),
                contentColor = Color.White
            ),
            elevation = ButtonDefaults.elevatedButtonElevation(
                defaultElevation = 4.dp,
                pressedElevation = 8.dp
            )
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加配置",
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "添加新配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (apiConfigsByApiKeyAndModality.isEmpty()) {
            // 空状态提示 - 更友好的设计
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无API配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击上方按钮添加您的第一个配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
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
                                    representativeConfig.key,
                                    representativeConfig.provider,
                                    representativeConfig.address,
                                    representativeConfig.channel,
                                    representativeConfig.modalityType
                                )
                            },
                            onDeleteModelForApiKey = onDeleteModelForApiKey,
                            onEditConfigClick = { onEditConfigClick(configsForKeyAndModality.first()) },
                            onDeleteGroup = { onDeleteConfigGroup(configsForKeyAndModality.first()) },
                            onRefreshModelsClick = { onRefreshModelsClick(configsForKeyAndModality.first()) },
                            isRefreshing = isRefreshingModels.contains("$apiKey-${modalityType}")
                        )
                        Spacer(Modifier.height(16.dp))
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
    // 仅拦截“甩动”(fling)的动量，避免外层跟随；不拦截普通滚动，确保列表可正常滚动
    val innerListNestedScroll = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                // 消耗所有可用的 fling 速度，阻止向外层传递
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 同样消耗剩余 fling 动量，彻底避免连带外层
                return available
            }
        }
    }
    val providerName =
        configsInGroup.firstOrNull()?.provider?.ifBlank { null } ?: "综合平台"
    val isDarkMode = isSystemInDarkTheme()
    val cardContainerColor = if (isDarkMode) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    }
    val cardBorderColor = if (isDarkMode) {
        Color.White.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val cardElevation = if (isDarkMode) 6.dp else 2.dp

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = cardContainerColor
        ),
        elevation = CardDefaults.outlinedCardElevation(
            defaultElevation = cardElevation
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = cardBorderColor
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            // 头部信息 + 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = {
                            // 禁止“默认”图像配置打开编辑弹窗（provider=默认 且 模态=IMAGE）
                            val firstCfg = configsInGroup.firstOrNull()
                            val isDefaultImageGroup = firstCfg != null
                                    && firstCfg.modalityType == ModalityType.IMAGE
                                    && firstCfg.provider.trim().lowercase() in listOf("默认","default")
                            if (!isDefaultImageGroup) {
                                onEditConfigClick()
                            }
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Key: ${maskApiKey(apiKey)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal
                    )
                }
                IconButton(
                    onClick = { showConfirmDeleteGroupDialog = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = "删除配置组",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 模型列表标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expandedModels = !expandedModels }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "模型列表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${configsInGroup.size} 个模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (modalityType != ModalityType.IMAGE) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            IconButton(
                                onClick = onRefreshModelsClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新模型列表",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
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
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
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
                // 限高 + 内部滚动，避免长列表撑满屏幕
                if (configsInGroup.isEmpty()) {
                    Text(
                        "此分类下暂无模型，点击右上方 \"+\" 添加模型",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 16.dp),
                        fontWeight = FontWeight.Normal
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(configsInGroup) { config ->
                            ModelItem(
                                config = config,
                                isSelected = config.id == selectedConfigIdInApp,
                                onSelect = { onSelectConfig(config) },
                                onDelete = { onDeleteModelForApiKey(config) }
                            )
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
            text = "您确定要删除 \"$providerName\" 的所有 ${modalityType.displayName} 模型配置吗？\n\n此操作会删除 ${configsInGroup.size} 个模型，且不可撤销。"
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
    val isDarkMode = isSystemInDarkTheme()
    val rowBackgroundColor = if (isSelected) {
        if (isDarkMode) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        }
    } else {
        if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .background(rowBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选择指示器
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = "选择模型",
            tint = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(Modifier.width(12.dp))
        
        // 模型名称
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifEmpty { config.model },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (config.name.isNotEmpty() && config.name != config.model) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = config.model,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // 删除按钮
        IconButton(
            onClick = { showConfirmDeleteDialog = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除模型",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
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
            text = "您确定要删除模型 \"${config.name.ifEmpty { config.model }}\" 吗？此操作不可撤销。"
        )
    }
}