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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.mcp.*
import com.android.everytalk.ui.screens.settings.DialogShape
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerListDialog(
    serverStates: Map<String, McpServerState>,
    onAddServer: (McpServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var serverToDeleteId by remember { mutableStateOf<String?>(null) }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MCP 服务器",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "管理您的 MCP 连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalIconButton(
                    onClick = { showAddDialog = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加服务器")
                }
            }
        },
        text = {
            if (serverStates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
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
                        .heightIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                ) {
                    items(serverStates.values.toList(), key = { it.config.id }) { state ->
                        McpServerItem(
                            serverState = state,
                            onToggle = { onToggleServer(state.config.id, it) },
                            onDeleteClick = { serverToDeleteId = state.config.id }
                        )
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

    if (showAddDialog) {
        AddMcpServerDialog(
            onConfirm = { config ->
                onAddServer(config)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
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
    onToggle: (Boolean) -> Unit,
    onDeleteClick: () -> Unit
) {
    val config = serverState.config
    val status = serverState.status
    
    val iconColor = getServerIconColor(config.name)
    val icon = getServerIcon(config.name)

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (config.enabled && status is McpStatus.Connected)
                iconColor.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onConfirm: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf(McpServerPreset.CUSTOM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(McpTransportType.SSE) }

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
                text = "新建连接",
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(McpServerPreset.entries.toList()) { preset ->
                            val isSelected = selectedPreset == preset
                            val presetColor = when (preset) {
                                McpServerPreset.CUSTOM -> Color(0xFF8B5CF6)
                                McpServerPreset.EXA_SEARCH -> Color(0xFF6366F1)
                                McpServerPreset.FIRECRAWL -> Color(0xFFEF4444)
                            }
                            val borderColor by animateColorAsState(
                                if (isSelected) presetColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                label = "borderColor"
                            )
                            val containerColor by animateColorAsState(
                                if (isSelected) presetColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                label = "containerColor"
                            )
                            
                            Surface(
                                onClick = { selectedPreset = preset },
                                shape = RoundedCornerShape(16.dp),
                                color = containerColor,
                                border = BorderStroke(1.5.dp, borderColor),
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(80.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = getServerIcon(preset.name),
                                        contentDescription = null,
                                        tint = if (isSelected) presetColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) presetColor else MaterialTheme.colorScheme.onSurface
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
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                horizontalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                McpTransportType.entries.forEachIndexed { index, type ->
                                    val isSelected = transportType == type
                                    val buttonColor = if (index == 0) Color(0xFF10B981) else Color(0xFF3B82F6)
                                    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) buttonColor else Color.Transparent)
                                            .clickable { transportType = type },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = type.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
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
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = textFieldShape,
                            colors = DialogTextFieldColors,
                            leadingIcon = {
                                Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    )
                    onConfirm(config)
                },
                enabled = isValid
            ) {
                Text("添加")
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
