package com.android.everytalk.ui.screens.mcp

import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 8.dp),
    // 合并设置页把搜索配置作为列表头，所有内容共用一个滚动区域；聊天弹窗不传此项。
    header: (@Composable () -> Unit)? = null,
    onLogin: ((McpOAuthProvider) -> Unit)? = null,
    // 为 true 时把 GitHub/Cloudflare 这类内置 OAuth 服务渲染成未登录占位卡片；
    // 打开开关即发起登录，登录成功后落库变成普通服务器卡片。
    showOAuthPlaceholders: Boolean = false,
    oauthBusy: Boolean = false,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<McpServerConfig?>(null) }
    var serverToDeleteId by remember { mutableStateOf<String?>(null) }
    var serverForToolsDialog by remember { mutableStateOf<McpServerState?>(null) }
    var serverForManageDialog by remember { mutableStateOf<McpServerState?>(null) }

    if (serverToDeleteId != null) {
        val server = serverStates[serverToDeleteId]
        if (server != null) {
            AlertDialog(
                onDismissRequest = { serverToDeleteId = null },
                modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
                title = { Text(stringResource(R.string.mcp_remove_server_title)) },
                text = {
                    Text(stringResource(R.string.mcp_remove_server_description, server.config.name))
                },
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
                        Text(stringResource(R.string.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { serverToDeleteId = null }) {
                        Text(stringResource(R.string.action_cancel))
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = contentPadding,
    ) {
        if (header != null) {
            item(key = "settings-header", contentType = "header") { header() }
        }
        // 设置页已有内置服务卡片，仅独立 MCP 弹窗需要显示空连接提示。
        if (serverStates.isEmpty() && header == null && !showOAuthPlaceholders) {
            item(key = "empty", contentType = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (header == null) Modifier.fillParentMaxHeight() else Modifier)
                        .padding(vertical = 24.dp),
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
                                    painterResource(R.drawable.ic_gpt_connectors),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.mcp_no_connections),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.mcp_no_connections_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            items(serverStates.values.toList(), key = { it.config.id }) { state ->
                McpServerItem(
                    serverState = state,
                    onClick = { serverForManageDialog = state },
                    onToolsClick = { serverForToolsDialog = state },
                    onToggle = { onToggleServer(state.config.id, it) },
                )
            }
            if (showOAuthPlaceholders) {
                items(
                    McpOAuthProvider.entries.filter { !serverStates.containsKey(it.serverId) },
                    key = { "oauth-${it.key}" },
                    contentType = { "oauth" }
                ) { provider ->
                    McpOAuthProviderItem(
                        provider = provider,
                        busy = oauthBusy,
                        onClick = { onLogin?.invoke(provider) },
                        onToggle = { enabled -> if (enabled) onLogin?.invoke(provider) }
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

    serverForManageDialog?.let { selected ->
        val oauthProvider = McpOAuthProvider.entries.firstOrNull { it.serverId == selected.config.id }
        McpServerManageDialog(
            serverState = selected,
            onDismiss = { serverForManageDialog = null },
            onEdit = if (oauthProvider != null) null else { ->
                serverForManageDialog = null
                serverToEdit = selected.config
            },
            onRelogin = if (oauthProvider != null && onLogin != null) { ->
                serverForManageDialog = null
                onLogin(oauthProvider)
            } else null,
            onShowTools = if (selected.tools.isNotEmpty()) { ->
                serverForToolsDialog = selected
            } else null,
            onDelete = {
                serverForManageDialog = null
                serverToDeleteId = selected.config.id
            }
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
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

@Composable
private fun getServerIcon(name: String): Int {
    val lowerName = name.lowercase()
    return when {
        lowerName.contains("cloudflare") -> R.drawable.ic_cloudflare
        lowerName.contains("github") -> R.drawable.ic_github
        lowerName.contains("context7") -> R.drawable.ic_gpt_sparkle
        lowerName.contains("exa") -> R.drawable.ic_search
        lowerName.contains("firecrawl") || lowerName.contains("crawl") -> R.drawable.ic_globe
        lowerName.contains("wiki") -> R.drawable.ic_gpt_book_open_study
        lowerName.contains("news") -> R.drawable.ic_gpt_newspaper
        lowerName.contains("tavily") -> R.drawable.ic_gpt_deep_research
        lowerName.contains("search") -> R.drawable.ic_search
        lowerName.contains("web") -> R.drawable.ic_globe
        lowerName.contains("code") || lowerName.contains("github") -> R.drawable.ic_gpt_code
        lowerName.contains("data") || lowerName.contains("database") -> R.drawable.ic_gpt_data_controls
        lowerName.contains("ai") || lowerName.contains("chat") -> R.drawable.ic_gpt_sparkle
        lowerName.contains("file") || lowerName.contains("doc") -> R.drawable.ic_gpt_file_document
        lowerName.contains("mail") || lowerName.contains("email") -> R.drawable.ic_gpt_email
        lowerName.contains("calendar") || lowerName.contains("schedule") -> R.drawable.ic_gpt_calendar
        lowerName.contains("weather") -> R.drawable.ic_gpt_weather_cloud
        lowerName.contains("map") || lowerName.contains("location") -> R.drawable.ic_gpt_map_pin
        else -> R.drawable.ic_gpt_puzzle_piece
    }
}

@Composable
private fun getServerIconColor(name: String): Color {
    val lowerName = name.lowercase()
    return when {
        lowerName.contains("cloudflare") -> Color(0xFFF59E0B)
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
) {
    val config = serverState.config
    val status = serverState.status

    val iconColor = getServerIconColor(config.name)
    val icon = getServerIcon(config.name)

    val isDarkMode = isSystemInDarkTheme()
    val containerColor = if (isDarkMode) Color(0xFF141414) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF2E2E2E) else Color(0xFFEDEDED)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val active = config.enabled && status is McpStatus.Connected

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) iconColor.copy(alpha = 0.6f) else cardBorderColor
        )
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
                color = if (config.enabled) iconColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (config.enabled) iconColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

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
                        text = stringResource(R.string.mcp_connecting),
                        size = 12.dp,
                        strokeWidth = 1.5.dp,
                        textStyle = MaterialTheme.typography.labelMedium,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = stringResource(R.string.mcp_connecting_content_description),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
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
                                is McpStatus.Connected -> pluralStringResource(
                                    R.plurals.mcp_available_tool_count,
                                    serverState.tools.size,
                                    serverState.tools.size,
                                )
                                is McpStatus.Connecting -> stringResource(R.string.mcp_connecting)
                                is McpStatus.Error -> stringResource(R.string.mcp_connection_failed)
                                is McpStatus.Idle -> stringResource(R.string.mcp_paused)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status is McpStatus.Error)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
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
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = iconColor,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            )
        }
    }
}

/**
 * 未登录的内置 OAuth 服务占位卡片（GitHub / Cloudflare）。
 * 点击卡片或打开开关都会触发浏览器登录；登录成功后落库，变为普通服务器卡片。
 */
@Composable
private fun McpOAuthProviderItem(
    provider: McpOAuthProvider,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val iconColor = getServerIconColor(provider.displayName)
    val icon = getServerIcon(provider.displayName)
    val isDarkMode = isSystemInDarkTheme()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isDarkMode) Color(0xFF141414) else Color.White
        ),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isDarkMode) Color(0xFF2E2E2E) else Color(0xFFEDEDED)
        )
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
                color = iconColor.copy(alpha = 0.1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = iconColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.mcp_oauth_description),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = false,
                onCheckedChange = onToggle,
                enabled = !busy,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            )
        }
    }
}

/**
 * 点击服务器卡片后的管理对话框：编辑、重新登录、查看工具、删除。
 * OAuth 服务隐藏编辑（地址固定不可改），普通服务隐藏重新登录。
 */
@Composable
private fun McpServerManageDialog(
    serverState: McpServerState,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onRelogin: (() -> Unit)?,
    onShowTools: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val dlgBg = if (isDark) Color(0xFF141414) else Color.White
    val dlgBorder = if (isDark) Color(0xFF2E2E2E) else Color(0xFFEDEDED)
    val dlgContent = if (isDark) Color.White else Color(0xFF0D0D0D)
    val dlgSubtext = MaterialTheme.colorScheme.onSurfaceVariant
    val config = serverState.config

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, dlgBorder, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        containerColor = dlgBg,
        titleContentColor = dlgContent,
        textContentColor = dlgContent,
        title = {
            Column {
                Text(
                    text = config.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = dlgContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = config.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = dlgSubtext,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                onEdit?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.mcp_action_edit),
                            modifier = Modifier.fillMaxWidth(),
                            color = dlgContent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                onRelogin?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.mcp_oauth_relogin),
                            modifier = Modifier.fillMaxWidth(),
                            color = dlgContent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                onShowTools?.let { action ->
                    TextButton(
                        onClick = {
                            action()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.mcp_available_tools),
                            modifier = Modifier.fillMaxWidth(),
                            color = dlgContent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.action_remove),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
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
                    text = stringResource(R.string.mcp_available_tools),
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
                        text = stringResource(R.string.mcp_no_available_tools),
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
                Text(stringResource(R.string.action_close), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/**
 * MCP 服务器预设
 */
enum class McpServerPreset(
    val displayName: String,
    val urlTemplate: String,
    val transportType: McpTransportType,
    val requiresApiKey: Boolean = true,
    val apiKeyPlaceholder: String = "API Key",
    val useHeaderAuth: Boolean = false,
    val headerName: String = ""
) {
    CUSTOM(
        displayName = "",
        urlTemplate = "",
        transportType = McpTransportType.SSE,
        requiresApiKey = false
    ),
    EXA_SEARCH(
        displayName = "Exa",
        urlTemplate = "https://mcp.exa.ai/mcp?exaApiKey={API_KEY}&tools=web_search_exa,get_code_context_exa",
        transportType = McpTransportType.HTTP,
        requiresApiKey = true,
        apiKeyPlaceholder = "Exa API Key"
    ),
    FIRECRAWL(
        displayName = "Firecrawl",
        urlTemplate = "https://mcp.firecrawl.dev/{API_KEY}/v2/mcp",
        transportType = McpTransportType.HTTP,
        requiresApiKey = true,
        apiKeyPlaceholder = "Firecrawl API Key"
    ),
    CONTEXT7(
        displayName = "Context7",
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
                text = stringResource(
                    if (existingConfig == null) R.string.mcp_new_connection else R.string.mcp_edit_connection,
                ),
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
                        text = stringResource(R.string.mcp_select_type),
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
                                                painter = painterResource(getServerIcon(preset.name)),
                                                contentDescription = null,
                                                tint = mcpContentColor.copy(alpha = if (isSelected) 1f else 0.62f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = if (preset == McpServerPreset.CUSTOM) {
                                                    stringResource(R.string.mcp_preset_custom)
                                                } else {
                                                    preset.displayName
                                                },
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
                        label = { Text(stringResource(R.string.mcp_name_label)) },
                        placeholder = { Text(stringResource(R.string.mcp_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        colors = DialogTextFieldColors,
                        leadingIcon = {
                             Icon(painterResource(R.drawable.ic_gpt_product_tag), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    )

                    if (selectedPreset == McpServerPreset.CUSTOM) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(stringResource(R.string.mcp_server_url_label)) },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = textFieldShape,
                            colors = DialogTextFieldColors,
                            isError = url.isNotBlank() &&
                                !url.startsWith("http://") &&
                                !url.startsWith("https://"),
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            supportingText = if (url.isNotBlank() &&
                                !url.startsWith("http://") &&
                                !url.startsWith("https://")) {
                                { Text(stringResource(R.string.mcp_url_invalid)) }
                            } else null
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.mcp_transport_protocol),
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
                            placeholder = { Text(stringResource(R.string.mcp_api_key_hint)) },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = textFieldShape,
                            colors = DialogTextFieldColors,
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_gpt_key), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        painter = painterResource(if (apiKeyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                        contentDescription = stringResource(
                                            if (apiKeyVisible) R.string.mcp_hide_api_key else R.string.mcp_show_api_key,
                                        ),
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
                    stringResource(
                        if (existingConfig == null) R.string.action_add else R.string.action_save,
                    ),
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
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
