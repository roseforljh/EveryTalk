package com.android.everytalk.ui.screens.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.mcp.*
import com.android.everytalk.ui.components.EveryTalkTimedLoadingStatus
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.appinfo.SettingsGroupCard
import com.android.everytalk.ui.screens.appinfo.SettingsRowDivider
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import java.net.URI

/** 设置页 MCP 列表：已添加的服务器放在一个分组卡片里，末行是唯一的添加入口。 */
@Composable
fun McpServerListContent(
    serverStates: Map<String, McpServerState>,
    onAddClick: () -> Unit,
    onConnectKeyProvider: (McpKeyProvider, String) -> Unit,
    sharedKeys: Map<McpKeyProvider, String> = emptyMap(),
    onUpdateServer: (McpServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onLogin: (McpOAuthProvider) -> Unit,
    onConfigureMail: (McpMailProvider, String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: @Composable () -> Unit = {},
) {
    var serverToEdit by remember { mutableStateOf<McpServerConfig?>(null) }
    var serverToDelete by remember { mutableStateOf<McpServerConfig?>(null) }
    var manageId by remember { mutableStateOf<String?>(null) }
    var keyProvider by remember { mutableStateOf<McpKeyProvider?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header", contentType = "header") { header() }
        item(key = "servers", contentType = "servers") {
            McpSectionLabel(stringResource(R.string.mcp_section_servers))
            SettingsGroupCard {
                serverStates.values.forEach { state ->
                    McpServerRow(
                        state = state,
                        onClick = { manageId = state.config.id },
                        onToggle = { onToggleServer(state.config.id, it) },
                    )
                    SettingsRowDivider()
                }
                McpKeyProvider.entries.filter { p -> serverStates.values.none { p.matches(it.config) } }.forEach { provider ->
                    McpServerRow(
                        state = McpServerState(provider.config("").clone(commonOptions = McpCommonOptions(enable = false, name = provider.displayName))),
                        subtitle = stringResource(when {
                            !sharedKeys[provider].isNullOrBlank() -> R.string.mcp_key_shared_description
                            provider.keyRequired -> R.string.mcp_key_description
                            else -> R.string.mcp_key_optional_description
                        }),
                        onClick = { keyProvider = provider },
                        // 已有共用 Key 或无需 Key 时，打开开关即接入。
                        onToggle = { on ->
                            val shared = sharedKeys[provider].orEmpty()
                            if (on) { if (provider.keyRequired && shared.isBlank()) keyProvider = provider else onConnectKeyProvider(provider, shared) }
                        },
                    )
                    SettingsRowDivider()
                }
                // 登录类服务不走通用地址表单；未接入时以灰色行常驻列表，点按即登录或配置授权码。
                McpOAuthProvider.entries.filter { it.serverId !in serverStates }.forEach { provider ->
                    McpServerRow(
                        state = McpServerState(provider.defaultConfig().clone(commonOptions = McpCommonOptions(enable = false, name = provider.displayName))),
                        subtitle = stringResource(R.string.mcp_oauth_description),
                        onClick = { onLogin(provider) },
                        onToggle = { if (it) onLogin(provider) },
                    )
                    SettingsRowDivider()
                }
                McpMailProvider.entries.filter { it.serverId !in serverStates }.forEach { provider ->
                    McpServerRow(
                        state = McpServerState(provider.config("").clone(commonOptions = McpCommonOptions(enable = false, name = provider.displayName))),
                        subtitle = stringResource(R.string.mcp_mail_description),
                        onClick = { onConfigureMail(provider, null) },
                        onToggle = { if (it) onConfigureMail(provider, null) },
                    )
                    SettingsRowDivider()
                }
                McpAddRow(onClick = onAddClick)
            }
        }
    }

    // 读取最新状态，管理弹窗打开期间连接完成时工具列表同步刷新。
    manageId?.let { id -> serverStates[id] }?.let { state ->
        val config = state.config
        val oauthProvider = McpOAuthProvider.entries.firstOrNull { it.serverId == config.id }
        val mailProvider = McpMailProvider.entries.firstOrNull { it.serverId == config.id }
        McpServerManageDialog(
            state = state,
            onDismiss = { manageId = null },
            onEdit = { manageId = null; serverToEdit = config }.takeIf { oauthProvider == null && mailProvider == null },
            onRelogin = oauthProvider?.let { { manageId = null; onLogin(it) } },
            onMailReconfigure = mailProvider?.let { { manageId = null; onConfigureMail(it, config.url) } },
            onDelete = { manageId = null; serverToDelete = config },
        )
    }

    keyProvider?.let { provider ->
        McpApiKeyDialog(
            provider = provider,
            initialKey = sharedKeys[provider].orEmpty(),
            onConfirm = { onConnectKeyProvider(provider, it); keyProvider = null },
            onDismiss = { keyProvider = null },
        )
    }

    serverToEdit?.let { editing ->
        AddMcpServerDialog(
            existingConfig = editing,
            onConfirm = { onUpdateServer(it); serverToEdit = null },
            onDismiss = { serverToEdit = null },
        )
    }

    serverToDelete?.let { config ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
            title = { Text(stringResource(R.string.mcp_remove_server_title)) },
            text = { Text(stringResource(R.string.mcp_remove_server_description, config.name)) },
            confirmButton = {
                Button(
                    onClick = { onRemoveServer(config.id); serverToDelete = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun McpSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 名称决定品牌图标与强调色，自定义服务落到通用拼图图标。 */
private data class McpBrand(val icon: Int, val color: Color, val track: Color = color)

@Composable
private fun mcpBrand(name: String): McpBrand {
    val n = name.lowercase()
    val mono = if (isSystemInDarkTheme()) Color(0xFFE6EDF3) else Color(0xFF24292F)
    return when {
        "cloudflare" in n -> McpBrand(R.drawable.ic_cloudflare, Color(0xFFF6821F))
        "github" in n -> McpBrand(R.drawable.ic_github, mono, MaterialTheme.colorScheme.onSurfaceVariant)
        "notion" in n -> McpBrand(R.drawable.ic_notion, mono, MaterialTheme.colorScheme.onSurfaceVariant)
        "gmail" in n -> McpBrand(R.drawable.ic_gmail, Color(0xFFEA4335))
        "microsoft" in n || "微软" in n -> McpBrand(R.drawable.ic_microsoft, Color(0xFF00A4EF))
        "qq" in n -> McpBrand(R.drawable.ic_qq, Color(0xFF12B7F5))
        "netease" in n || "网易" in n -> McpBrand(R.drawable.ic_netease, Color(0xFFE60012))
        "exa" in n -> McpBrand(R.drawable.ic_exa, Color(0xFF6366F1))
        "tavily" in n -> McpBrand(R.drawable.ic_tavily, Color(0xFF14B8A6))
        "jina" in n -> McpBrand(R.drawable.ic_globe, Color(0xFF0EA5E9))
        "context7" in n || "doc" in n -> McpBrand(R.drawable.ic_gpt_book_open_study, Color(0xFF10B981))
        "crawl" in n || "web" in n -> McpBrand(R.drawable.ic_globe, Color(0xFFEF4444))
        "search" in n -> McpBrand(R.drawable.ic_search, Color(0xFF3B82F6))
        "mail" in n -> McpBrand(R.drawable.ic_gpt_email, Color(0xFF8B5CF6))
        "code" in n -> McpBrand(R.drawable.ic_gpt_code, Color(0xFFF59E0B))
        else -> McpBrand(R.drawable.ic_gpt_puzzle_piece, Color(0xFF6B7280))
    }
}

@Composable
private fun McpServerIcon(name: String, enabled: Boolean = true) {
    val brand = mcpBrand(name)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) brand.color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(brand.icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) brand.color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun McpServerRow(state: McpServerState, onClick: () -> Unit, onToggle: (Boolean) -> Unit, subtitle: String? = null) {
    val config = state.config
    val status = if (config.enabled) state.status else McpStatus.Idle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        McpServerIcon(config.name, config.enabled)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = config.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (config.enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (status is McpStatus.Connecting) {
                EveryTalkTimedLoadingStatus(
                    text = stringResource(R.string.mcp_connecting),
                    size = 12.dp,
                    strokeWidth = 1.5.dp,
                    textStyle = MaterialTheme.typography.bodySmall,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = stringResource(R.string.mcp_connecting_content_description),
                )
            } else {
                Text(
                    text = when (status) {
                        is McpStatus.Connected -> pluralStringResource(R.plurals.mcp_available_tool_count, state.tools.size, state.tools.size)
                        is McpStatus.Error -> stringResource(R.string.mcp_connection_failed)
                        else -> stringResource(R.string.mcp_paused)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is McpStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = config.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = mcpBrand(config.name).track,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            ),
        )
    }
}

@Composable
private fun McpAddRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.mcp_add_server),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 点击服务器后的详情：地址、错误原因、工具列表，以及编辑/重新登录/删除。 */
@Composable
private fun McpServerManageDialog(
    state: McpServerState,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onRelogin: (() -> Unit)?,
    onMailReconfigure: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val config = state.config
    val subtext = MaterialTheme.colorScheme.onSurfaceVariant
    val error = (state.status as? McpStatus.Error)?.message ?: state.errorMessage
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                McpServerIcon(config.name)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(config.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(config.url, style = MaterialTheme.typography.bodySmall, color = subtext, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (config.enabled && !error.isNullOrBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = stringResource(R.string.mcp_available_tools),
                    style = MaterialTheme.typography.labelLarge,
                    color = subtext,
                )
                if (state.tools.isEmpty()) {
                    Text(stringResource(R.string.mcp_no_available_tools), style = MaterialTheme.typography.bodyMedium, color = subtext)
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.tools, key = { it.name }) { tool ->
                            Column {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                tool.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = subtext, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Row {
                onEdit?.let { TextButton(onClick = it) { Text(stringResource(R.string.mcp_action_edit)) } }
                onRelogin?.let { TextButton(onClick = it) { Text(stringResource(R.string.mcp_oauth_relogin)) } }
                onMailReconfigure?.let { TextButton(onClick = it) { Text(stringResource(R.string.mcp_mail_reconfigure)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        },
    )
}

/** 内置服务只填一个 API Key，旁边给出官方获取页面。 */
@Composable
private fun McpApiKeyDialog(provider: McpKeyProvider, initialKey: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initialKey) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val dialogBg = appDialogContainerColor()
    val content = appDialogContentColor()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = content,
        textContentColor = content,
        title = { Text(provider.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = if (!provider.keyRequired) { { Text(stringResource(R.string.mcp_key_optional_description)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppDialogTextFieldShape,
                    colors = DialogTextFieldColors,
                )
                TextButton(onClick = { uriHandler.openUri(provider.keyPageUrl) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.mcp_get_api_key))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(key) },
                enabled = !provider.keyRequired || key.isNotBlank(),
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = content,
                    contentColor = dialogBg,
                    disabledContainerColor = appDialogBorderColor(),
                    disabledContentColor = content.copy(alpha = 0.4f),
                ),
            ) { Text(stringResource(R.string.mcp_mail_connect), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = content) }
        },
    )
}

/** 去掉常见的 mcp./api./www. 前缀，取主域名首段作为默认名称。 */
internal fun mcpNameFromUrl(url: String): String {
    val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty()
    val label = host.split('.').dropWhile { it in setOf("mcp", "api", "www") }.firstOrNull().orEmpty()
    return label.replaceFirstChar { it.uppercase() }
}

/** 每行一个 `名称: 值`；有一行格式不对就返回 null，让表单提示错误。 */
internal fun parseMcpHeaders(text: String): List<Pair<String, String>>? =
    text.lines().filter { it.isNotBlank() }.map { line ->
        val name = line.substringBefore(':', "").trim()
        if (name.isEmpty()) return null
        name to line.substringAfter(':').trim()
    }

/**
 * 通用表单转配置：地址以 /sse 结尾走 SSE，其余走 Streamable HTTP；
 * 编辑且地址未改时保留原传输方式。地址或请求头不合法返回 null。
 */
internal fun buildMcpServerConfig(
    url: String,
    name: String,
    headersText: String,
    existing: McpServerConfig? = null,
): McpServerConfig? {
    val trimmedUrl = url.trim()
    val uri = runCatching { URI(trimmedUrl) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    val headers = parseMcpHeaders(headersText) ?: return null
    val options = McpCommonOptions(
        enable = existing?.enabled ?: true,
        name = name.trim().ifEmpty { mcpNameFromUrl(trimmedUrl) },
        headers = headers,
    )
    val id = existing?.id ?: java.util.UUID.randomUUID().toString()
    val sse = if (existing != null && existing.url == trimmedUrl) existing is McpServerConfig.SseTransportServer
    else trimmedUrl.substringBefore('?').trimEnd('/').endsWith("/sse", ignoreCase = true)
    return if (sse) McpServerConfig.SseTransportServer(id, options, trimmedUrl)
    else McpServerConfig.StreamableHTTPServer(id, options, trimmedUrl)
}

/**
 * 通用添加/编辑 MCP 服务器：只填地址、名称和请求头。
 */
@Composable
fun AddMcpServerDialog(
    onConfirm: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit,
    existingConfig: McpServerConfig? = null,
) {
    var url by remember { mutableStateOf(existingConfig?.url.orEmpty()) }
    var name by remember { mutableStateOf(existingConfig?.name.orEmpty()) }
    var headersText by remember {
        mutableStateOf(existingConfig?.commonOptions?.headers?.joinToString("\n") { "${it.first}: ${it.second}" }.orEmpty())
    }
    val config = buildMcpServerConfig(url, name, headersText, existingConfig)
    val urlError = url.isNotBlank() && buildMcpServerConfig(url, "", "") == null
    val headersError = parseMcpHeaders(headersText) == null
    val dialogBg = appDialogContainerColor()
    val content = appDialogContentColor()
    val border = appDialogBorderColor()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, border, AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = content,
        textContentColor = content,
        title = {
            Text(stringResource(if (existingConfig == null) R.string.mcp_new_connection else R.string.mcp_edit_connection))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.mcp_server_url_label)) },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    isError = urlError,
                    supportingText = if (urlError) { { Text(stringResource(R.string.mcp_url_invalid)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppDialogTextFieldShape,
                    colors = DialogTextFieldColors,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_name_label)) },
                    placeholder = { Text(mcpNameFromUrl(url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppDialogTextFieldShape,
                    colors = DialogTextFieldColors,
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text(stringResource(R.string.mcp_headers_label)) },
                    placeholder = { Text("Authorization: Bearer …") },
                    minLines = 2,
                    maxLines = 5,
                    isError = headersError,
                    supportingText = if (headersError) { { Text(stringResource(R.string.mcp_headers_invalid)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppDialogTextFieldShape,
                    colors = DialogTextFieldColors,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { config?.let(onConfirm) },
                enabled = config != null,
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = content,
                    contentColor = dialogBg,
                    disabledContainerColor = border,
                    disabledContentColor = content.copy(alpha = 0.4f),
                ),
            ) {
                Text(stringResource(if (existingConfig == null) R.string.action_add else R.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = content) }
        },
    )
}

