package com.android.everytalk.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.sync.SyncController
import java.text.DateFormat
import java.util.Date

/** 登录即同步，不再提供独立面板或开关；只保留账号的数据管理操作。 */
@Composable
internal fun AccountDataActions(
    controller: SyncController,
    onResumeUpload: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var showResumeConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.configured) Text("当前版本未配置账号数据服务，本机数据仍可使用。", style = MaterialTheme.typography.bodySmall)
        if (state.rejectedCount > 0) {
            Text("${state.rejectedCount} 条未能同步", color = MaterialTheme.colorScheme.error)
            state.rejectedItems.take(8).forEach { item ->
                val category = when (item.kind ?: item.entity) {
                    "mcp_servers" -> "MCP 配置"
                    "computer_workspaces" -> "远程工作区"
                    "computers" -> "远程电脑"
                    "conversation_settings" -> "会话设置"
                    "api_configs" -> "模型配置"
                    "voice_configs" -> "语音配置"
                    "messages" -> "消息"
                    "sessions" -> "会话"
                    "memories" -> "长期记忆"
                    "services" -> if (item.id == "credentials") "账号凭据恢复服务" else "文件与完整历史服务"
                    else -> "同步项目"
                }
                Text("• $category / ${item.id.take(12)}：${item.code}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (item.code == "MESSAGE_PAYLOAD_TOO_LARGE") {
                    Text("这条消息需要完整历史文件服务补传。本机原文保留；服务可用后会自动补传。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (item.code in setOf("ACCOUNT_KEY_NOT_CONFIGURED", "FILE_SERVICE_NOT_CONFIGURED", "SERVER_UPGRADE_REQUIRED", "NOT_FOUND")) {
                    Text("服务器需要更新并配置账号密钥和文件存储，完成后会自动恢复；本机数据保留。", style = MaterialTheme.typography.bodySmall)
                }
                if (item.code == "CREDENTIAL_DECRYPTION_FAILED") Text("这项凭据的云端密文损坏，本机 Key 保留，其他项目继续同步。", style = MaterialTheme.typography.bodySmall)
                if (item.code == "CREDENTIAL_STORAGE_FAILED") Text("这项凭据暂时无法从本机加密存储读取或写入，其他项目继续同步。", style = MaterialTheme.typography.bodySmall)
                if (item.code == "CONTAINS_SECRET") {
                    val hint = if (item.kind == "conversation_settings") "这个提示词含疑似密钥，没有同步" else "该配置含疑似密钥，没有同步；本机配置保留。"
                    Text(hint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            state.lastSyncAt?.let { "上次同步：${DateFormat.getDateTimeInstance().format(Date(it))}" }
                ?: "上次同步：从未成功同步",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.pausedAfterCloudReset) {
            Text("云端清空后已暂停上传，本机数据保留。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showResumeConfirm = true }, enabled = state.configured) { Text("重新上传本机数据") }
        }
    }
    if (showResumeConfirm) AlertDialog(
        onDismissRequest = { showResumeConfirm = false },
        title = { Text("重新上传本机数据？") },
        text = { Text("本机的文字对话、思考过程、长期记忆、置顶和分组将重新合并到当前账号。") },
        confirmButton = { TextButton(onClick = { showResumeConfirm = false; onResumeUpload() }) { Text("上传") } },
        dismissButton = { TextButton(onClick = { showResumeConfirm = false }) { Text("取消") } },
    )
}

/** epoch 变化后使用浮动提示，不阻止用户继续操作聊天或设置。 */
@Composable
internal fun SyncEpochNotice(controller: SyncController, onUpload: () -> Unit) {
    val state by controller.state.collectAsState()
    if (state.epochDecision == null) return
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
        ElevatedCard(Modifier.padding(12.dp).widthIn(max = 480.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("云端数据已被清空，是否重新上传本机数据？")
                Row {
                    TextButton(onClick = onUpload) { Text("上传") }
                    TextButton(onClick = { controller.decideEpoch(false) }) { Text("不上传") }
                }
            }
        }
    }
}
