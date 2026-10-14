package com.android.everytalk.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.sync.SyncController

/** 复用设置的 Material 3 卡片与确认框；登录判断由调用方统一使用 FeatureGate。 */
@Composable
internal fun CloudSyncSettings(
    controller: SyncController,
    locked: Boolean,
    onEnable: () -> Unit,
) {
    val state by controller.state.collectAsState()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("云同步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (locked) Icon(Icons.Outlined.Lock, contentDescription = "需要登录")
                Switch(checked = state.enabled, enabled = state.configured,
                    onCheckedChange = { if (it) onEnable() else controller.disable() })
            }
            Text(if (state.configured) "开启后上传文字对话、思考过程、长期记忆、置顶和分组。图片、附件及 API Key 不上传。"
                else "云同步服务尚未配置", style = MaterialTheme.typography.bodySmall)
            if (state.rejectedCount > 0) Text("${state.rejectedCount} 条未能同步", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 独立于账号页面挂载；登录返回原设置页面时仍可继续首次上传确认。 */
@Composable
internal fun SyncUploadConfirmation(controller: SyncController) {
    val prompt by controller.uploadPrompt.collectAsState()
    if (prompt) AlertDialog(
        onDismissRequest = controller::dismissUploadPrompt,
        title = { Text("开启云同步") },
        text = { Text("是否把本地对话上传到账号？选择“不上传”会保留已有数据为本地数据，仅同步后续变更。") },
        confirmButton = { TextButton(onClick = { controller.confirmEnable(true) }) { Text("上传") } },
        dismissButton = {
            TextButton(onClick = { controller.confirmEnable(false) }) { Text("不上传") }
            TextButton(onClick = controller::dismissUploadPrompt) { Text("暂不") }
        },
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
