package com.android.everytalk.ui.screens.settings
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.statecontroller.controller.config.modelConfigGroupId
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.screens.mcp.McpServerListContent
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)

@Composable
private fun ImageDefaultPinnedCard(
    onActivate: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardContainerColor = if (isDark) Color.Black else Color.White
    val cardBorderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = cardContainerColor),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_default_configuration),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_default_configuration_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp)
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                text = stringResource(R.string.settings_default_image_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• Kwai-Kolors/Kolors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onActivate,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(stringResource(R.string.settings_enable_default_configuration), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun SettingsScreenContent(
    paddingValues: PaddingValues,
    apiConfigsByApiKeyAndModality: Map<String, Map<ModalityType, List<ApiConfig>>>,
    onAddFullConfigClick: () -> Unit,
    onSelectConfig: (config: ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    onAddModelForApiKeyClick: (representativeConfig: ApiConfig) -> Unit,
    onDeleteModelForApiKey: (configToDelete: ApiConfig) -> Unit,
    onEditConfigClick: (config: ApiConfig) -> Unit,
    onDeleteConfigGroup: (representativeConfig: ApiConfig) -> Unit,
    onRefreshModelsClick: (config: ApiConfig) -> Unit,
    onConfigureModelParameters: ((config: ApiConfig) -> Unit)?,
    isRefreshingModels: Set<String>,
    isImageMode: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // 设置为完全透明,实现沉浸式效果
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Spacer(Modifier.height(paddingValues.calculateTopPadding()))
        if (apiConfigsByApiKeyAndModality.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.settings_no_api_configuration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_add_first_configuration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isImageMode) {
                Spacer(modifier = Modifier.height(16.dp))
                ImageDefaultPinnedCard(onActivate = { onAddFullConfigClick() })
            }
        } else {
            val allGroups = apiConfigsByApiKeyAndModality.flatMap { (apiKey, configsByModality) ->
                configsByModality.map { (modalityType, configsForKeyAndModality) ->
                    Triple(apiKey, modalityType, configsForKeyAndModality)
                }
            }.filter { it.third.isNotEmpty() }

            allGroups.forEach { (apiKey, modalityType, configsForKeyAndModality) ->
                ApiKeyItemGroup(
                    apiKey = apiKey,
                    modalityType = modalityType,
                    configsInGroup = configsForKeyAndModality,
                    onSelectConfig = onSelectConfig,
                    selectedConfigIdInApp = selectedConfigIdInApp,
                    onAddModelForApiKeyClick = {
                        onAddModelForApiKeyClick(configsForKeyAndModality.first())
                    },
                    onDeleteModelForApiKey = onDeleteModelForApiKey,
                    onEditConfigClick = { onEditConfigClick(configsForKeyAndModality.first()) },
                    onDeleteGroup = { onDeleteConfigGroup(configsForKeyAndModality.first()) },
                    onRefreshModelsClick = { onRefreshModelsClick(configsForKeyAndModality.first()) },
                    onConfigureModelParameters = onConfigureModelParameters,
                    isRefreshing = modelConfigGroupId(configsForKeyAndModality.first()) in isRefreshingModels
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        Spacer(Modifier.height(paddingValues.calculateBottomPadding()))
    }
}

/**
 * 设置页的统一工具页。
 *
 * 联网搜索服务直接作为 MCP 能力的一部分展示和管理。
 * 搜索服务仍沿用原有配置状态，避免改变已有搜索逻辑。
 */
@Composable
internal fun McpSettingsContent(
    selectedProviderId: String?,
    webSearchConfigs: Map<String, ExternalWebSearchProviderConfig>,
    onSelectWebSearchProvider: (ExternalWebSearchProvider) -> Unit,
    onEditWebSearchProvider: (ExternalWebSearchProvider) -> Unit,
    mcpServerStates: Map<String, McpServerState>,
    onAddMcpServer: (McpServerConfig) -> Unit,
    onUpdateMcpServer: (McpServerConfig) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onLoginMcp: (com.android.everytalk.data.mcp.McpOAuthProvider) -> Unit,
    onConfigureMail: (com.android.everytalk.data.mcp.McpMailProvider, String?) -> Unit,
    oauthBusy: Boolean,
) {
    McpServerListContent(
        serverStates = mcpServerStates,
        onLogin = onLoginMcp,
        onConfigureMail = onConfigureMail,
        showOAuthPlaceholders = true,
        oauthBusy = oauthBusy,
        onAddServer = onAddMcpServer,
        onUpdateServer = onUpdateMcpServer,
        onRemoveServer = onRemoveMcpServer,
        onToggleServer = onToggleMcpServer,
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = topContentPadding, bottom = bottomContentPadding,
        ),
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_tab_mcp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                ExternalWebSearchProviderCards(
                    selectedProviderId = selectedProviderId,
                    configs = webSearchConfigs,
                    onSelectProvider = onSelectWebSearchProvider,
                    onEditProvider = onEditWebSearchProvider,
                )
            }
        },
    )
}

@Composable
private fun ExternalWebSearchProviderCards(
    selectedProviderId: String?,
    configs: Map<String, ExternalWebSearchProviderConfig>,
    onSelectProvider: (ExternalWebSearchProvider) -> Unit,
    onEditProvider: (ExternalWebSearchProvider) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    ExternalWebSearchProvider.entries.forEach { provider ->
        val config = configs[provider.providerId]
        val isSelected = selectedProviderId == provider.providerId
        val isConfigured = !config?.apiKey.isNullOrBlank()
        val containerColor = if (isDark) Color(0xFF141414) else Color.White
        val borderColor = if (isSelected) {
            provider.accentColor.copy(alpha = 0.6f)
        } else {
            if (isDark) Color(0xFF2E2E2E) else Color(0xFFEDEDED)
        }
        val contentAlpha = if (isConfigured) 1f else 0.5f

        OutlinedCard(
            onClick = { onEditProvider(provider) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = containerColor
            ),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = provider.accentColor.copy(alpha = 0.1f * contentAlpha),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(provider.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = provider.accentColor.copy(alpha = contentAlpha)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(provider.descriptionRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = isSelected && isConfigured,
                    onCheckedChange = { enabled ->
                        if (enabled) onSelectProvider(provider)
                    },
                    enabled = isConfigured,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = provider.accentColor,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                )
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
    onConfigureModelParameters: ((ApiConfig) -> Unit)?,
    isRefreshing: Boolean
) {
    var showModelPopup by remember { mutableStateOf(false) }
    var showConfirmDeleteGroupDialog by remember { mutableStateOf(false) }
    val providerName = configsInGroup.firstOrNull()?.provider?.ifBlank { null }
    val providerDisplayName = providerName?.let { localizedProviderLabel(it) }
        ?: stringResource(R.string.settings_integrated_platform)
    val connectionSummary = stringResource(
        R.string.settings_address_summary,
        configsInGroup.firstOrNull()?.address.orEmpty().trim(),
    )
    val secretSummary = stringResource(
        R.string.settings_key_summary,
        SettingsEndpointRules.maskApiKey(apiKey, stringResource(R.string.settings_not_configured)),
    )
    val firstCfg = configsInGroup.firstOrNull()
    val isPinnedGroup = firstCfg != null && SettingsEndpointRules.isPinnedSettingsGroup(firstCfg.provider)
    val canExpandModels = firstCfg != null && SettingsEndpointRules.canExpandSettingsModels(firstCfg.provider)
    val isDarkMode = isSystemInDarkTheme()
    val cardContainerColor = if (isDarkMode) Color.Black else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF414141) else Color(0xFFF3F3F3)
    val cardElevation = 0.dp

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = cardContainerColor
        ),
        elevation = CardDefaults.outlinedCardElevation(
            defaultElevation = cardElevation
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = cardBorderColor
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            // 头部信息 + 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base ->
                        if (isPinnedGroup) base
                        else base.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onEditConfigClick() }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerDisplayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (!isPinnedGroup) {
                        Text(
                            text = connectionSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = secretSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                if (isPinnedGroup) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pin),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                    )
                } else {
                    IconButton(
                        onClick = { showConfirmDeleteGroupDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_remove_circle),
                            contentDescription = stringResource(R.string.settings_delete_configuration_group),
                            tint = Color(0xFFEF5350).copy(alpha = 0.8f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 模型列表标题行
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = canExpandModels,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (canExpandModels) {
                                showModelPopup = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_model_list),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_model_count,
                                configsInGroup.size,
                                configsInGroup.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isPinnedGroup && canExpandModels) {
                            val rotation = if (isRefreshing) {
                                val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
                                val animatedRotation by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "refresh_rotation"
                                )
                                animatedRotation
                            } else {
                                0f
                            }
                            IconButton(
                                onClick = onRefreshModelsClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.settings_refresh_model_list),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer { rotationZ = rotation }
                                )
                            }
                        }
                        if (!isPinnedGroup) {
                            IconButton(
                                onClick = onAddModelForApiKeyClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_plus),
                                    contentDescription = stringResource(R.string.settings_add_model_to_key),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                ModelListPopup(
                    expanded = showModelPopup && canExpandModels,
                    configs = configsInGroup,
                    selectedConfigId = selectedConfigIdInApp,
                    onSelectConfig = onSelectConfig,
                    onDeleteConfig = onDeleteModelForApiKey,
                    onConfigureModelParameters = onConfigureModelParameters,
                    onDismiss = { showModelPopup = false }
                )
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
            title = stringResource(R.string.settings_delete_group_title),
            text = stringResource(
                R.string.settings_delete_group_description,
                providerDisplayName,
                stringResource(modalityType.displayNameRes),
                configsInGroup.size,
            ),
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
        MaterialTheme.colorScheme.surface
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
            painter = if (isSelected) painterResource(R.drawable.ic_check_circle) else painterResource(R.drawable.ic_circle_empty),
            contentDescription = stringResource(R.string.model_select),
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
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.settings_delete_model_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
            title = stringResource(R.string.settings_delete_model_title),
            text = stringResource(
                R.string.settings_delete_model_description,
                config.name.ifEmpty { config.model },
            ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListPopup(
    expanded: Boolean,
    configs: List<ApiConfig>,
    selectedConfigId: String?,
    onSelectConfig: (ApiConfig) -> Unit,
    onDeleteConfig: (ApiConfig) -> Unit,
    onConfigureModelParameters: ((ApiConfig) -> Unit)?,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val selectedColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)
    val sortedConfigs = remember(configs) { sortModelConfigs(configs) }
    val selectedIndex = remember(sortedConfigs, selectedConfigId) {
        sortedConfigs.indexOfFirst { it.id == selectedConfigId }
    }
    // 使用完整列表并让当前模型靠近弹窗中部。LazyColumn 自己处理滚动，不再截断其余模型。
    val listState = key(sortedConfigs, selectedConfigId) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0),
        )
    }

    AppFloatingCardPopup(
        visible = expanded,
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        modifier = Modifier
            .widthIn(min = 220.dp, max = 320.dp)
            .heightIn(max = 400.dp),
    ) {
        if (configs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_no_models),
                    fontSize = 16.sp,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                state = listState,
            ) {
                items(
                    items = sortedConfigs,
                    key = ApiConfig::id,
                ) { config ->
                    val isSelected = config.id == selectedConfigId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    onSelectConfig(config)
                                    onDismiss()
                                },
                                onLongClick = onConfigureModelParameters?.let { configure ->
                                    {
                                        configure(config)
                                        onDismiss()
                                    }
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = selectedColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = config.name.ifEmpty { config.model },
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) selectedColor else textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (onConfigureModelParameters != null) {
                            IconButton(
                                onClick = {
                                    onConfigureModelParameters(config)
                                    onDismiss()
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings_slider),
                                    contentDescription = stringResource(R.string.model_parameters_open),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { onDeleteConfig(config) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_trash),
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
