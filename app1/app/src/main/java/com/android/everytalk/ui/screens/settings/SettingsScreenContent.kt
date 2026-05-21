package com.android.everytalk.ui.screens.settings

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig

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
                        text = "默认配置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "一键启用平台默认配置。密钥与地址由后端安全注入。",
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
                text = "将自动添加以下图像模型:",
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
                Text("启用默认配置", fontWeight = FontWeight.SemiBold)
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
    onAddModelForApiKeyClick: (apiKey: String, existingProvider: String, existingAddress: String, existingChannel: String, existingModality: ModalityType) -> Unit,
    onDeleteModelForApiKey: (configToDelete: ApiConfig) -> Unit,
    onEditConfigClick: (config: ApiConfig) -> Unit,
    onDeleteConfigGroup: (representativeConfig: ApiConfig) -> Unit,
    onRefreshModelsClick: (config: ApiConfig) -> Unit,
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
                    "暂无API配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "点击上方按钮添加您的第一个配置",
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
                    isRefreshing = isRefreshingModels.contains("${configsForKeyAndModality.first().key}-${modalityType}")
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun ExternalWebSearchSettingsContent(
    selectedProviderId: String?,
    configs: Map<String, ExternalWebSearchProviderConfig>,
    onSelectProvider: (ExternalWebSearchProvider) -> Unit,
    onEditProvider: (ExternalWebSearchProvider) -> Unit,
    topContentPadding: Dp = 0.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breath")
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(topContentPadding - 16.dp))
        ExternalWebSearchProvider.entries.forEach { provider ->
            val config = configs[provider.providerId]
            val isSelected = selectedProviderId == provider.providerId
            val isConfigured = !config?.apiKey.isNullOrBlank()
            val backgroundColor = provider.accentColor.copy(alpha = if (isSelected) 0.14f else 0.08f)
            val borderColor = if (isSelected) {
                provider.accentColor.copy(alpha = 0.8f)
            } else {
                Color.White.copy(alpha = 0.15f)
            }

            Surface(
                onClick = { onEditProvider(provider) },
                shape = RoundedCornerShape(20.dp),
                color = backgroundColor,
                border = androidx.compose.foundation.BorderStroke(1.2.dp, borderColor),
                tonalElevation = if (isSelected) 2.dp else 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = if (isConfigured) 8.dp else 0.dp)
                            )
                            if (isConfigured) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .alpha(breatheAlpha)
                                        .background(Color(0xFF4CAF50), CircleShape)
                                )
                            }
                        }

                        Text(
                            text = provider.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectProvider(provider) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = if (isSelected) painterResource(R.drawable.ic_check_circle) else painterResource(R.drawable.ic_circle_empty),
                            contentDescription = "选择 ${provider.displayName}",
                            tint = if (isSelected) provider.accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
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
    var showModelPopup by remember { mutableStateOf(false) }
    var showConfirmDeleteGroupDialog by remember { mutableStateOf(false) }
    // 仅拦截"甩动"(fling)的动量,避免外层跟随;不拦截普通滚动,确保列表可正常滚动
    val innerListNestedScroll = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                // 消耗所有可用的 fling 速度,阻止向外层传递
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 同样消耗剩余 fling 动量,彻底避免连带外层
                return available
            }
        }
    }
    val providerName =
        configsInGroup.firstOrNull()?.provider?.ifBlank { null } ?: "综合平台"
    val displayTitle = OpenClawSettingsRules.displayTitleForSettingsGroup(providerName)
    val displaySubtitle = OpenClawSettingsRules.displaySubtitleForSettingsGroup(providerName)
    val connectionSummary = OpenClawSettingsRules.connectionSummaryLabel(providerName, configsInGroup.firstOrNull()?.address.orEmpty())
    val secretSummary = OpenClawSettingsRules.secretSummaryLabel(providerName, apiKey)
    val remoteTargetSummary = OpenClawSettingsRules.remoteTargetLabel(configsInGroup.firstOrNull()?.openClawSessionId.orEmpty())
    val firstCfg = configsInGroup.firstOrNull()
    val isPinnedGroup = firstCfg != null && OpenClawSettingsRules.isPinnedSettingsGroup(firstCfg.provider)
    val canExpandModels = firstCfg != null && OpenClawSettingsRules.canExpandSettingsModels(firstCfg.provider)
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
                        text = displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!displaySubtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                        if (!remoteTargetSummary.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = remoteTargetSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        }
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
                            contentDescription = "删除配置组",
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
                                    contentDescription = "刷新模型列表",
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
                                    contentDescription = "为此Key和类型添加模型",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (showModelPopup && canExpandModels) {
                    ModelListPopup(
                        configs = configsInGroup,
                        selectedConfigId = selectedConfigIdInApp,
                        onSelectConfig = onSelectConfig,
                        onDeleteConfig = onDeleteModelForApiKey,
                        onDismiss = { showModelPopup = false }
                    )
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
            text = "您确定要删除 \"$providerName\" 的所有 ${modalityType.displayName} 模型配置吗?\n\n此操作会删除 ${configsInGroup.size} 个模型,且不可撤销。"
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
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "删除模型",
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
            title = "删除模型",
            text = "您确定要删除模型 \"${config.name.ifEmpty { config.model }}\" 吗?此操作不可撤销。"
        )
    }
}

@Composable
private fun ModelListPopup(
    configs: List<ApiConfig>,
    selectedConfigId: String?,
    onSelectConfig: (ApiConfig) -> Unit,
    onDeleteConfig: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val selectedColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)

    val scaleAnim = remember { androidx.compose.animation.core.Animatable(0.8f) }
    val alphaAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val emphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { scaleAnim.animateTo(1f, androidx.compose.animation.core.tween(120, easing = emphasizedDecelerate)) }
            launch { alphaAnim.animateTo(1f, androidx.compose.animation.core.tween(30, easing = decelerateEasing)) }
        }
    }

    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 320.dp)
                .heightIn(max = 400.dp)
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    alpha = alphaAnim.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp)
                )
                .border(1.dp, popupBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = cardBg
        ) {
            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无模型",
                        fontSize = 16.sp,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    configs.forEach { config ->
                        val isSelected = config.id == selectedConfigId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectConfig(config)
                                    onDismiss()
                                }
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
                            IconButton(
                                onClick = { onDeleteConfig(config) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trash),
                                    contentDescription = "删除",
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
}