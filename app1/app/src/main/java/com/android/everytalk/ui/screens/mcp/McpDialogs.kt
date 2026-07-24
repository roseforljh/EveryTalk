package com.android.everytalk.ui.screens.mcp
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.mcp.*
import com.android.everytalk.ui.components.EveryTalkTimedLoadingStatus
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors

@Composable
fun McpServerListContent(
    serverStates: Map<String, McpServerState>,
    onAddServer: (McpServerConfig) -> Unit,
    onUpdateServer: (McpServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<McpServerConfig?>(null) }
    var serverToDeleteId by remember { mutableStateOf<String?>(null) }
    var serverForToolsDialog by remember { mutableStateOf<McpServerState?>(null) }

    if (serverToDeleteId != null) {
        val server = serverStates[serverToDeleteId]
        if (server != null) {
            AlertDialog(
                onDismissRequest = { serverToDeleteId = null },
                modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
                title = { Text("移除服务器") },
                text = { Text("确定要移除 '${server.config.name}' 吗？此操作无法撤销。") },
                confirmButton = {
                    Button(
                        onClick = {
                            onRemoveServer(serverToDeleteId!!)
                            serverToDeleteId = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("移除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { serverToDeleteId = null }) {
                        Text("取消")
                    }
                },
                containerColor = appDialogContainerColor(),
                titleContentColor = appDialogContentColor(),
                textContentColor = appDialogContentColor(),
                shape = AppDialogShape
            )
        } else {
            serverToDeleteId = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (serverStates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "暂无连接",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "添加 MCP 服务器以扩展能力",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
            ) {
                items(serverStates.values.toList(), key = { it.config.id }) { state ->
                    McpServerItem(
                        serverState = state,
                        onClick = { serverToEdit = state.config },
                        onToolsClick = { serverForToolsDialog = state },
                        onToggle = { onToggleServer(state.config.id, it) },
                        onDeleteClick = { serverToDeleteId = state.config.id }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onConfirm = { config ->
                onAddServer(config)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    serverToEdit?.let { editingConfig ->
        AddMcpServerDialog(
            existingConfig = editingConfig,
            onConfirm = { updatedConfig ->
                onUpdateServer(updatedConfig)
                serverToEdit = null
            },
            onDismiss = { serverToEdit = null }
        )
    }

    serverForToolsDialog?.let { selectedServer ->
        McpServerToolsDialog(
            serverState = selectedServer,
            onDismiss = { serverForToolsDialog = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerListDialog(
    serverStates: Map<String, McpServerState>,
    onAddServer: (McpServerConfig) -> Unit,
    onUpdateServer: (McpServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        title = null,
        text = {
            McpServerListContent(
                serverStates = serverStates,
                onAddServer = onAddServer,
                onUpdateServer = onUpdateServer,
                onRemoveServer = onRemoveServer,
                onToggleServer = onToggleServer,
                modifier = Modifier.heightIn(max = 600.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
private fun getServerIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val lowerName = name.lowercase()
    return when {
        lowerName.contains("context7") -> Icons.Filled.AutoAwesome
        lowerName.contains("exa") -> Icons.Filled.Search
        lowerName.contains("firecrawl") || lowerName.contains("crawl") -> Icons.Filled.Language
        lowerName.contains("wiki") -> Icons.AutoMirrored.Filled.MenuBook
        lowerName.contains("news") -> Icons.Filled.Newspaper
        lowerName.contains("tavily") -> Icons.Filled.TravelExplore
        lowerName.contains("search") -> Icons.Filled.Search
        lowerName.contains("web") -> Icons.Filled.Public
        lowerName.contains("code") || lowerName.contains("github") -> Icons.Filled.Code
        lowerName.contains("data") || lowerName.contains("database") -> Icons.Filled.Storage
        lowerName.contains("ai") || lowerName.contains("chat") -> Icons.Filled.AutoAwesome
        lowerName.contains("file") || lowerName.contains("doc") -> Icons.Filled.Description
        lowerName.contains("mail") || lowerName.contains("email") -> Icons.Filled.Email
        lowerName.contains("calendar") || lowerName.contains("schedule") -> Icons.Filled.CalendarMonth
        lowerName.contains("weather") -> Icons.Filled.Cloud
        lowerName.contains("map") || lowerName.contains("location") -> Icons.Filled.Place
        else -> Icons.Filled.Extension
    }
}

@Composable
private fun getServerIconColor(name: String): Color {
    val lowerName = name.lowercase()
    return when {
        lowerName.contains("context7") -> Color(0xFF10B981)
        lowerName.contains("exa") -> Color(0xFF6366F1)
        lowerName.contains("firecrawl") -> Color(0xFFEF4444)
        lowerName.contains("wiki") -> Color(0xFF3B82F6)
        lowerName.contains("news") -> Color(0xFF8B5CF6)
        lowerName.contains("tavily") -> Color(0xFF10B981)
        lowerName.contains("code") || lowerName.contains("github") -> Color(0xFFF59E0B)
        else -> Color(0xFF6B7280)
    }
}

@Composable
private fun McpServerItem(
    serverState: McpServerState,
    onClick: () -> Unit,
    onToolsClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDeleteClick: () -> Unit
) {
    val config = serverState.config
    val status = serverState.status
    
    val iconColor = getServerIconColor(config.name)
    val icon = getServerIcon(config.name)

    val isDarkMode = isSystemInDarkTheme()
    val containerColor = if (isDarkMode) Color.Black else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = MaterialTheme.colorScheme.onSurface

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (config.enabled && status is McpStatus.Connected)
                iconColor.copy(alpha = 0.5f)
            else
                cardBorderColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (config.enabled) iconColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (config.enabled) iconColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (config.enabled) contentColor else contentColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (status is McpStatus.Connecting) {
                        EveryTalkTimedLoadingStatus(
                            text = "正在连接",
                            size = 12.dp,
                            strokeWidth = 1.5.dp,
                            textStyle = MaterialTheme.typography.labelMedium,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = "MCP 正在连接",
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (status) {
                                            is McpStatus.Connected -> iconColor
                                            is McpStatus.Error -> MaterialTheme.colorScheme.error
                                            is McpStatus.Idle -> MaterialTheme.colorScheme.outline
                                            is McpStatus.Connecting -> MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (status) {
                                    is McpStatus.Connected -> "${serverState.tools.size} 个可用工具"
                                    is McpStatus.Connecting -> "正在连接"
                                    is McpStatus.Error -> "连接失败"
                                    is McpStatus.Idle -> "已暂停"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (status is McpStatus.Connected && serverState.tools.isNotEmpty()) {
                                    Modifier.clickable(onClick = onToolsClick)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = iconColor,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        uncheckedBorderColor = Color.White.copy(alpha = 0.4f)
                    )
                )
            }
            
            if (!config.enabled || status is McpStatus.Error) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (status is McpStatus.Error) "检查配置或网络" else "点击开关以启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status is McpStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerToolsDialog(
    serverState: McpServerState,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val dlgBg = if (isDark) Color.Black else Color.White
    val dlgBorder = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val dlgContent = if (isDark) Color.White else Color(0xFF0D0D0D)
    val dlgSubtext = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, dlgBorder, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        containerColor = dlgBg,
        titleContentColor = dlgContent,
        textContentColor = dlgContent,
        title = {
            Column {
                Text(
                    text = "可用工具",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = dlgContent
                )
                Text(
                    text = serverState.config.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = dlgSubtext
                )
            }
        },
        text = {
            if (serverState.tools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可用工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dlgSubtext
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dlgBg, Color.Transparent),
                                    startY = 0f,
                                    endY = 56f
                                ),
                                size = Size(size.width, 56f)
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, dlgBg),
                                    startY = size.height - 56f,
                                    endY = size.height
                                ),
                                topLeft = Offset(0f, size.height - 56f),
                                size = Size(size.width, 56f)
                            )
                        }
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
                    ) {
                        items(serverState.tools, key = { it.name }) { tool ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF8F8F8)
                                ),
                                border = BorderStroke(1.dp, dlgBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = dlgContent
                                    )
                                    tool.description?.takeIf { it.isNotBlank() }?.let { description ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = dlgSubtext
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = dlgContent,
                    contentColor = dlgBg
                )
            ) {
                Text("关闭", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/**
 * MCP 服务器预设
 */
enum class McpServerPreset(
    val displayName: String,
    val description: String,
    val urlTemplate: String,
    val transportType: McpTransportType,
    val requiresApiKey: Boolean = true,
    val apiKeyPlaceholder: String = "API Key",
    val useHeaderAuth: Boolean = false,
    val headerName: String = ""
) {
    CUSTOM(
        displayName = "自定义",
        description = "手动配置",
        urlTemplate = "",
        transportType = McpTransportType.SSE,
        requiresApiKey = false
    ),
    EXA_SEARCH(
        displayName = "Exa",
        description = "AI 搜索引擎",
        urlTemplate = "https://mcp.exa.ai/mcp?exaApiKey={API_KEY}&tools=web_search_exa,get_code_context_exa",
        transportType = McpTransportType.HTTP,
        requiresApiKey = true,
        apiKeyPlaceholder = "Exa API Key"
    ),
    FIRECRAWL(
        displayName = "Firecrawl",
        description = "网页抓取/解析",
        urlTemplate = "https://mcp.firecrawl.dev/{API_KEY}/v2/mcp",
        transportType = McpTransportType.HTTP,
        requiresApiKey = true,
        apiKeyPlaceholder = "Firecrawl API Key"
    ),
    CONTEXT7(
        displayName = "Context7",
        description = "官方文档检索",
        urlTemplate = "https://mcp.context7.com/mcp",
        transportType = McpTransportType.HTTP,
        requiresApiKey = true,
        apiKeyPlaceholder = "Context7 API Key",
        useHeaderAuth = true,
        headerName = "CONTEXT7_API_KEY"
    );

    fun buildUrl(apiKey: String): String {
        return urlTemplate.replace("{API_KEY}", apiKey)
    }
    
    fun buildHeaders(apiKey: String): Map<String, String> {
        return if (useHeaderAuth && headerName.isNotBlank()) {
            mapOf(headerName to apiKey)
        } else {
            emptyMap()
        }
    }
}

@Composable
fun AddMcpServerDialog(
    existingConfig: McpServerConfig? = null,
    onConfirm: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember(existingConfig) {
        mutableStateOf(
            when {
                existingConfig == null -> McpServerPreset.CUSTOM
                existingConfig.url.contains("mcp.exa.ai", ignoreCase = true) -> McpServerPreset.EXA_SEARCH
                existingConfig.url.contains("mcp.firecrawl.dev", ignoreCase = true) -> McpServerPreset.FIRECRAWL
                existingConfig.url.contains("mcp.context7.com", ignoreCase = true) -> McpServerPreset.CONTEXT7
                else -> McpServerPreset.CUSTOM
            }
        )
    }
    var name by remember(existingConfig) { mutableStateOf(existingConfig?.name.orEmpty()) }
    var url by remember(existingConfig) { mutableStateOf(existingConfig?.url.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var transportType by remember(existingConfig) {
        mutableStateOf(
            when (existingConfig) {
                is McpServerConfig.StreamableHTTPServer -> McpTransportType.HTTP
                else -> McpTransportType.SSE
            }
        )
    }

    LaunchedEffect(selectedPreset) {
        if (selectedPreset != McpServerPreset.CUSTOM) {
            name = selectedPreset.displayName
            transportType = selectedPreset.transportType
        }
    }

    val isValid = if (selectedPreset == McpServerPreset.CUSTOM) {
        name.isNotBlank() && url.isNotBlank() &&
            (url.startsWith("http://") || url.startsWith("https://"))
    } else {
        name.isNotBlank() && apiKey.isNotBlank()
    }

    val isDarkTheme = isSystemInDarkTheme()
    val mcpDialogBg = if (isDarkTheme) Color.Black else Color.White
    val mcpBorderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val mcpContentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val textFieldShape = RoundedCornerShape(16.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = mcpDialogBg,
        modifier = Modifier.border(
            width = 1.dp,
            color = mcpBorderColor,
            shape = RoundedCornerShape(28.dp)
        ),
        title = {
            Text(
                text = if (existingConfig == null) "新建连接" else "编辑连接",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = mcpContentColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "选择类型",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, mcpBorderColor, RoundedCornerShape(16.dp))
                            .padding(4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        val presets = McpServerPreset.entries
                        val itemWidth = 112.dp
                        val itemSpacing = 6.dp
                        val selectedIndex = presets.indexOf(selectedPreset).coerceAtLeast(0)
                        val indicatorOffset by animateDpAsState(
                            targetValue = (itemWidth + itemSpacing) * selectedIndex,
                            animationSpec = tween(durationMillis = 180),
                            label = "mcpPresetIndicatorOffset"
                        )

                        Box(
                            modifier = Modifier
                                .width(itemWidth * presets.size + itemSpacing * (presets.size - 1))
                                .fillMaxHeight()
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                                    .width(itemWidth)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(mcpContentColor.copy(alpha = 0.10f))
                                    .border(
                                        1.dp,
                                        mcpContentColor.copy(alpha = 0.28f),
                                        RoundedCornerShape(12.dp)
                                    )
                            )

                            Row(modifier = Modifier.fillMaxSize()) {
                                presets.forEachIndexed { index, preset ->
                                    val isSelected = selectedPreset == preset
                                    Box(
                                        modifier = Modifier
                                            .width(itemWidth)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedPreset = preset }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = getServerIcon(preset.name),
                                                contentDescription = null,
                                                tint = mcpContentColor.copy(alpha = if (isSelected) 1f else 0.62f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = preset.displayName,
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = mcpContentColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (index < presets.size - 1) {
                                        Spacer(modifier = Modifier.width(itemSpacing))
                                    }
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        placeholder = { Text("给服务器起个名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        colors = DialogTextFieldColors,
                        leadingIcon = {
                             Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    )

                    if (selectedPreset == McpServerPreset.CUSTOM) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("服务器 URL") },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = textFieldShape,
                            colors = DialogTextFieldColors,
                            isError = url.isNotBlank() &&
                                !url.startsWith("http://") &&
                                !url.startsWith("https://"),
                            leadingIcon = {
                                Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            supportingText = if (url.isNotBlank() &&
                                !url.startsWith("http://") &&
                                !url.startsWith("https://")) {
                                { Text("必须以 http:// 或 https:// 开头") }
                            } else null
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "传输协议",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, mcpBorderColor, RoundedCornerShape(16.dp))
                                    .padding(4.dp)
                            ) {
                                val itemWidth = (maxWidth - 8.dp) / McpTransportType.entries.size
                                val selectedIndex = McpTransportType.entries.indexOf(transportType).coerceAtLeast(0)
                                val indicatorOffset by animateDpAsState(
                                    targetValue = itemWidth * selectedIndex,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "mcpTransportIndicatorOffset"
                                )

                                Box(
                                    modifier = Modifier
                                        .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                                        .width(itemWidth)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(mcpContentColor.copy(alpha = 0.10f))
                                        .border(
                                            width = 1.dp,
                                            color = mcpContentColor.copy(alpha = 0.28f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                )

                                Row(modifier = Modifier.fillMaxSize()) {
                                    McpTransportType.entries.forEach { type ->
                                        val isSelected = transportType == type
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { transportType = type },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = type.name,
                                                fontSize = 15.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = mcpContentColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(selectedPreset.apiKeyPlaceholder) },
                            placeholder = { Text("粘贴 API Key") },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = textFieldShape,
                            colors = DialogTextFieldColors,
                            leadingIcon = {
                                Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalUrl = if (selectedPreset == McpServerPreset.CUSTOM) {
                        url.trim()
                    } else {
                        selectedPreset.buildUrl(apiKey.trim())
                    }
                    val headers = if (selectedPreset != McpServerPreset.CUSTOM) {
                        selectedPreset.buildHeaders(apiKey.trim())
                    } else {
                        emptyMap()
                    }
                    val config = McpServerConfig.createDefault(
                        name = name.trim(),
                        url = finalUrl,
                        transportType = if (selectedPreset == McpServerPreset.CUSTOM) transportType else selectedPreset.transportType,
                        headers = headers
                    ).clone(
                        id = existingConfig?.id ?: McpServerConfig.createDefault(
                            name = name.trim(),
                            url = finalUrl,
                            transportType = if (selectedPreset == McpServerPreset.CUSTOM) transportType else selectedPreset.transportType,
                            headers = headers
                        ).id,
                        commonOptions = McpCommonOptions(
                            enable = existingConfig?.enabled ?: true,
                            name = name.trim(),
                            headers = headers.toList()
                        )
                    )
                    onConfirm(config)
                },
                enabled = isValid,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = mcpContentColor,
                    contentColor = mcpDialogBg,
                    disabledContainerColor = mcpBorderColor,
                    disabledContentColor = mcpContentColor.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    if (existingConfig == null) "添加" else "保存",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = mcpContentColor
                ),
                border = BorderStroke(1.dp, mcpBorderColor)
            ) {
                Text("取消", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
