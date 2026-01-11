package com.android.everytalk.ui.screens.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.mcp.*
import com.android.everytalk.ui.screens.settings.DialogShape
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import java.util.UUID

/**
 * MCP 服务器列表对话框
 */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCP 服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加服务器")
                }
            }
        },
        text = {
            if (serverStates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "暂无 MCP 服务器",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "点击右上角添加服务器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(serverStates.values.toList(), key = { it.config.id }) { state ->
                        McpServerItem(
                            serverState = state,
                            onToggle = { onToggleServer(state.config.id, it) },
                            onRemove = { onRemoveServer(state.config.id) }
                        )
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

/**
 * MCP 服务器列表项
 */
@Composable
private fun McpServerItem(
    serverState: McpServerState,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val config = serverState.config
    val connectionState = serverState.connectionState

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态指示器
                Icon(
                    imageVector = when (connectionState) {
                        McpConnectionState.CONNECTED -> Icons.Filled.CheckCircle
                        McpConnectionState.CONNECTING -> Icons.Filled.Sync
                        McpConnectionState.ERROR -> Icons.Filled.Error
                        McpConnectionState.DISCONNECTED -> Icons.Outlined.Circle
                    },
                    contentDescription = null,
                    tint = when (connectionState) {
                        McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        McpConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                        McpConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        McpConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = config.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (serverState.tools.isNotEmpty()) {
                        Text(
                            text = "${serverState.tools.size} 个工具",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    serverState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggle
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 添加 MCP 服务器对话框
 */
@Composable
fun AddMcpServerDialog(
    onConfirm: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(McpTransportType.SSE) }

    val isValid = name.isNotBlank() && url.isNotBlank() &&
        (url.startsWith("http://") || url.startsWith("https://"))

    // 输入框圆角样式
    val textFieldShape = RoundedCornerShape(16.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "添加 MCP 服务器",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务器名称") },
                    placeholder = { Text("例如: 文件搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = textFieldShape,
                    colors = DialogTextFieldColors
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = textFieldShape,
                    colors = DialogTextFieldColors,
                    isError = url.isNotBlank() &&
                        !url.startsWith("http://") &&
                        !url.startsWith("https://"),
                    supportingText = if (url.isNotBlank() &&
                        !url.startsWith("http://") &&
                        !url.startsWith("https://")) {
                        { Text("URL 必须以 http:// 或 https:// 开头") }
                    } else null
                )

                // 传输类型选择
                Column {
                    Text(
                        text = "传输类型",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        McpTransportType.values().forEach { type ->
                            val isSelected = transportType == type
                            Surface(
                                onClick = { transportType = type },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected)
                                    androidx.compose.foundation.BorderStroke(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary
                                    )
                                else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = type.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = McpServerConfig(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        url = url.trim(),
                        transportType = transportType
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
        containerColor = MaterialTheme.colorScheme.surface,
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
