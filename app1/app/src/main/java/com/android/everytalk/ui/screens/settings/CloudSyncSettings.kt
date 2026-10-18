package com.android.everytalk.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
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
        if (!state.configured) Text(stringResource(R.string.cloud_sync_unconfigured), style = MaterialTheme.typography.bodySmall)
        if (state.rejectedCount > 0) {
            Text(stringResource(R.string.cloud_sync_rejected_count, state.rejectedCount), color = MaterialTheme.colorScheme.error)
            state.rejectedItems.take(8).forEach { item ->
                val category = when (item.kind ?: item.entity) {
                    "mcp_servers" -> R.string.cloud_sync_category_mcp
                    "computer_workspaces" -> R.string.cloud_sync_category_workspace
                    "computers" -> R.string.cloud_sync_category_computer
                    "conversation_settings" -> R.string.cloud_sync_category_conversation_settings
                    "api_configs" -> R.string.cloud_sync_category_model
                    "voice_configs" -> R.string.cloud_sync_category_voice
                    "messages" -> R.string.cloud_sync_category_messages
                    "sessions" -> R.string.cloud_sync_category_sessions
                    "memories" -> R.string.cloud_sync_category_memories
                    "services" -> if (item.id == "credentials") R.string.cloud_sync_category_credentials else R.string.cloud_sync_category_history_service
                    else -> R.string.cloud_sync_category_item
                }
                Text(stringResource(R.string.cloud_sync_item_label, stringResource(category), item.id.take(12), item.code), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (item.code == "MESSAGE_PAYLOAD_TOO_LARGE") {
                    Text(stringResource(R.string.cloud_sync_message_payload_too_large),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (item.code in setOf("ACCOUNT_KEY_NOT_CONFIGURED", "FILE_SERVICE_NOT_CONFIGURED", "SERVER_UPGRADE_REQUIRED", "NOT_FOUND")) {
                    Text(stringResource(R.string.cloud_sync_server_upgrade_required), style = MaterialTheme.typography.bodySmall)
                }
                if (item.code == "CREDENTIAL_DECRYPTION_FAILED") Text(stringResource(R.string.cloud_sync_credential_decryption_failed), style = MaterialTheme.typography.bodySmall)
                if (item.code == "CREDENTIAL_STORAGE_FAILED") Text(stringResource(R.string.cloud_sync_credential_storage_failed), style = MaterialTheme.typography.bodySmall)
                if (item.code == "CONTAINS_SECRET") {
                    val hint = if (item.kind == "conversation_settings") R.string.cloud_sync_prompt_contains_secret else R.string.cloud_sync_config_contains_secret
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            state.lastSyncAt?.let { stringResource(R.string.cloud_sync_last_sync, DateFormat.getDateTimeInstance().format(Date(it))) }
                ?: stringResource(R.string.cloud_sync_never_synced),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.pausedAfterCloudReset) {
            Text(stringResource(R.string.cloud_sync_cloud_reset_paused), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showResumeConfirm = true }, enabled = state.configured) { Text(stringResource(R.string.cloud_sync_resume_upload)) }
        }
    }
    if (showResumeConfirm) AlertDialog(
        onDismissRequest = { showResumeConfirm = false },
        title = { Text(stringResource(R.string.cloud_sync_resume_title)) },
        text = { Text(stringResource(R.string.cloud_sync_resume_description)) },
        confirmButton = { TextButton(onClick = { showResumeConfirm = false; onResumeUpload() }) { Text(stringResource(R.string.cloud_sync_upload)) } },
        dismissButton = { TextButton(onClick = { showResumeConfirm = false }) { Text(stringResource(R.string.cloud_sync_cancel)) } },
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
                Text(stringResource(R.string.cloud_sync_cloud_reset_question))
                Row {
                    TextButton(onClick = onUpload) { Text(stringResource(R.string.cloud_sync_upload)) }
                    TextButton(onClick = { controller.decideEpoch(false) }) { Text(stringResource(R.string.cloud_sync_dont_upload)) }
                }
            }
        }
    }
}
