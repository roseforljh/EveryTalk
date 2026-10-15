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
    onDeleteCloudData: () -> Unit,
    onResumeUpload: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val message by controller.message.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showResumeConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.configured) Text("当前版本未配置账号数据服务，本机数据仍可使用。", style = MaterialTheme.typography.bodySmall)
        if (state.rejectedCount > 0) {
            Text("${state.rejectedCount} 条未能同步", color = MaterialTheme.colorScheme.error)
            state.rejectedItems.take(8).forEach { item ->
                Text("• ${item.entity}/${item.id.take(12)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (item.code == "CONTAINS_SECRET" && item.entity == "user_settings") {
                    Text("这个提示词含疑似密钥，没有同步", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            state.lastSyncAt?.let { "上次同步：${DateFormat.getDateTimeInstance().format(Date(it))}" }
                ?: "上次同步：从未成功同步",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showDeleteConfirm = true }, enabled = state.configured) {
            Text("删除云端数据")
        }
        if (state.pausedAfterCloudReset) {
            Text("云端清空后已暂停上传，本机数据保留。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showResumeConfirm = true }, enabled = state.configured) { Text("重新上传本机数据") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (showDeleteConfirm) AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("删除云端数据？") },
        text = { Text("这会删除当前账号的云端数据，并暂停本机上传；本机聊天和设置会保留。服务器加密备份删除后最多保留 7 天。") },
        confirmButton = {
            TextButton(onClick = { showDeleteConfirm = false; onDeleteCloudData() }) { Text("确认删除") }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
    )
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
