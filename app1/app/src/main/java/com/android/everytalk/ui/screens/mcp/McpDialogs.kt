package com.android.everytalk.ui.screens.mcp

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.mcp.*
import com.android.everytalk.ui.screens.settings.DialogShape
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp)
            )
        } else {
            serverToDeleteId = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ElevatedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
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
                contentDescription = "添加 MCP 服务器",
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "添加 MCP 服务器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.border(
            width = 0.5.dp,
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(28.dp)
        ),
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
        lowerName.contains("exa") -> Icons.Filled.Search
        lowerName.contains("firecrawl") || lowerName.contains("crawl") -> Icons.Filled.Language
        lowerName.contains("wiki") -> Icons.Filled.MenuBook
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
        lowerName.contains("exa") -> Color(0xFF6366F1)
        lowerName.contains("firecrawl") -> Color(0xFFEF4444)
        lowerName.contains("wiki") -> Color(0xFF3B82F6)
        lowerName.contains("news") -> Color(0xFF8B5CF6)
        lowerName.contains("tavily") -> Color(0xFF10B981)
        else -> MaterialTheme.colorScheme.primary
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

    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val contentColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.25.dp,
            color = if (config.enabled && status is McpStatus.Connected)
                iconColor.copy(alpha = 0.65f)
            else
                Color.White.copy(alpha = 0.42f)
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
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (status) {
                                        is McpStatus.Connected -> iconColor
                                        is McpStatus.Connecting -> MaterialTheme.colorScheme.tertiary
                                        is McpStatus.Error -> MaterialTheme.colorScheme.error
                                        is McpStatus.Idle -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (status) {
                                is McpStatus.Connected -> "${serverState.tools.size} 个可用工具"
                                is McpStatus.Connecting -> "正在连接..."
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column {
                Text(
                    text = "可用工具",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = serverState.config.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(serverState.tools, key = { it.name }) { tool ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                tool.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
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

    val textFieldShape = RoundedCornerShape(16.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.border(
            width = 0.5.dp,
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(28.dp)
        ),
        title = {
            Text(
                text = if (existingConfig == null) "新建连接" else "编辑连接",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
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
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(McpServerPreset.entries, key = { it.name }) { preset ->
                            val isSelected = selectedPreset == preset
                            val presetColor = when (preset) {
                                McpServerPreset.CUSTOM -> Color(0xFF8B5CF6)
                                McpServerPreset.EXA_SEARCH -> Color(0xFF6366F1)
                                McpServerPreset.FIRECRAWL -> Color(0xFFEF4444)
                                McpServerPreset.CONTEXT7 -> Color(0xFF10B981)
                            }

                            Surface(
                                onClick = { selectedPreset = preset },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) presetColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(1.dp, if (isSelected) presetColor else Color.Transparent),
                                modifier = Modifier
                                    .widthIn(min = 104.dp)
                                    .height(40.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = getServerIcon(preset.name),
                                        contentDescription = null,
                                        tint = if (isSelected) presetColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) presetColor else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
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
                             Icon(Icons.Outlined.Label, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                McpTransportType.entries.forEach { type ->
                                    val isSelected = transportType == type
                                    val buttonColor = MaterialTheme.colorScheme.primary
                                    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) buttonColor else Color.Transparent)
                                            .clickable { transportType = type },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = type.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = textColor
                                        )
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
            TextButton(
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
                enabled = isValid
            ) {
                Text(if (existingConfig == null) "添加" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * MCP 工具选择对话框
 */
@Composable
fun McpToolSelectionDialog(
    availableTools: List<Pair<McpServerConfig, McpTool>>,
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = "选择 MCP 工具",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (availableTools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无可用工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableTools, key = { "${it.first.id}:${it.second.name}" }) { (server, tool) ->
                        val toolId = "${server.id}:${tool.name}"
                        val isSelected = selectedTools.contains(toolId)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    tool.description?.let { desc ->
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "来自: ${server.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToolToggle(toolId) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}
