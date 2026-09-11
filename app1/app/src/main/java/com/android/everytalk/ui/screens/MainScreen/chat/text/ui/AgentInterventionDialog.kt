package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.agent.AgentInterventionPolicyRegistry
import com.android.everytalk.data.agent.PendingIntervention
import com.android.everytalk.data.agent.ResolutionMaterialKind
import com.android.everytalk.data.agent.SuspensionState
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors

/**
 * 本地 Policy Registry 驱动的统一接力卡片。
 * 模型提供的 reason 只作为说明文字；字段类型和提交方式来自可信本地投影。
 */
@Composable
internal fun AgentInterventionDialog(
    intervention: PendingIntervention,
    onResolveNone: (PendingIntervention) -> Unit,
    onResolveEphemeral: (PendingIntervention, CharArray) -> Unit,
    onCreateAuthorization: (PendingIntervention, CharArray) -> Unit,
    onStartCloudflareReauthorization: (PendingIntervention) -> Unit,
    onLoadCloudflareResources: suspend (PendingIntervention) -> List<com.android.everytalk.data.computer.ResourceOption>,
    onSelectCloudflareResource: (PendingIntervention, String) -> Unit,
    onReject: (PendingIntervention) -> Unit,
    onConfirmUnknownDelivered: (PendingIntervention) -> Unit,
    onContinueUnknown: (PendingIntervention) -> Unit,
) {
    val dialogBg = appDialogContainerColor()
    val dialogContent = appDialogContentColor()
    val dialogBorder = appDialogBorderColor()
    var sensitiveInput by remember(intervention.suspensionId, intervention.rowVersion) { mutableStateOf("") }
    val field = intervention.fields.firstOrNull()
    val fieldKind = field?.kind
    val requiresUserDecision = intervention.state == SuspensionState.USER_DECISION_REQUIRED
    val isCloudflareReauthorization = intervention.capabilityId == "cloudflare.reauthorize"
    val isResourceSelection = intervention.capabilityId == "cloudflare.resource.select"
    var resources by remember(intervention.suspensionId) { mutableStateOf<List<com.android.everytalk.data.computer.ResourceOption>>(emptyList()) }
    var selectedId by remember(intervention.suspensionId) { mutableStateOf<String?>(null) }
    var resourceError by remember(intervention.suspensionId) { mutableStateOf<String?>(null) }
    var refresh by remember(intervention.suspensionId) { mutableStateOf(0) }
    var loading by remember(intervention.suspensionId) { mutableStateOf(false) }
    LaunchedEffect(intervention.suspensionId, refresh) {
        if (isResourceSelection) {
            loading = true
            resourceError = null
            selectedId = null
            try { resources = onLoadCloudflareResources(intervention) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { resourceError = error.message ?: "读取资源失败" }
            finally { loading = false }
        }
    }
    val canSubmit = if (requiresUserDecision) true else if (isResourceSelection) selectedId != null && !loading else when (intervention.materialKind) {
        ResolutionMaterialKind.NONE -> true
        ResolutionMaterialKind.EPHEMERAL -> sensitiveInput.isNotEmpty()
        ResolutionMaterialKind.DURABLE_REFERENCE -> sensitiveInput.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.border(1.dp, dialogBorder, AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = dialogContent,
        textContentColor = dialogContent,
        title = { Text("需要你接力", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = capabilityTitle(intervention.capabilityId),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = intervention.reasonSafe,
                    style = MaterialTheme.typography.bodySmall,
                    color = appDialogSubtextColor(0.76f),
                )
                intervention.userVisibleContext?.takeIf(String::isNotBlank)?.let { context ->
                    Text(
                        text = context,
                        style = MaterialTheme.typography.bodySmall,
                        color = appDialogSubtextColor(0.64f),
                    )
                }
                if (requiresUserDecision) {
                    Text(
                        text = "外部动作是否完成无法自动确认。旧密码或 OTP 已丢弃，禁止重新输入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isResourceSelection) {
                    Text("请选择当前 Account 的资源。选择不会执行原工具。")
                    if (loading) Text("正在读取资源列表…")
                    resourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        resources.forEach { resource ->
                            TextButton(onClick = { selectedId = resource.id }, enabled = !loading) {
                                Text((if (selectedId == resource.id) "✓ " else "") + resource.displayName + "\n" + resource.id)
                            }
                        }
                    }
                    if (!loading && resources.isEmpty()) Text("当前没有可选资源")
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("刷新资源列表") }
                } else if (isCloudflareReauthorization) {
                    Text(
                        text = "Cloudflare 授权已失效。点击下方按钮登录同一 Cloudflare Computer；原工具调用会在授权恢复后重新交给模型决定。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else when (intervention.materialKind) {
                    ResolutionMaterialKind.NONE -> Text(
                        text = field?.label ?: "确认当前操作",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ResolutionMaterialKind.EPHEMERAL -> OutlinedTextField(
                        value = sensitiveInput,
                        onValueChange = { sensitiveInput = it },
                        label = { Text(field?.label ?: "敏感输入") },
                        singleLine = true,
                        shape = AppDialogTextFieldShape,
                        colors = appDialogTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    ResolutionMaterialKind.DURABLE_REFERENCE -> OutlinedTextField(
                        value = sensitiveInput,
                        onValueChange = { sensitiveInput = it },
                        label = { Text(field?.label ?: "授权凭据") },
                        singleLine = true,
                        shape = AppDialogTextFieldShape,
                        colors = appDialogTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (fieldKind in setOf(
                        AgentInterventionPolicyRegistry.FieldKind.SENSITIVE_TEXT,
                        AgentInterventionPolicyRegistry.FieldKind.AUTHORIZATION_SECRET,
                    )
                ) {
                    Text(
                        text = if (fieldKind == AgentInterventionPolicyRegistry.FieldKind.AUTHORIZATION_SECRET) {
                            "凭据会由 Android Keystore 加密保存，模型只能使用当前操作的受限能力。"
                        } else {
                            "内容只交给本地可信 Adapter，不会发送给模型或写入聊天记录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = appDialogSubtextColor(0.64f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    if (requiresUserDecision) {
                        onConfirmUnknownDelivered(intervention)
                    } else if (isResourceSelection) {
                        selectedId?.let { onSelectCloudflareResource(intervention, it) }
                    } else if (isCloudflareReauthorization) {
                        onStartCloudflareReauthorization(intervention)
                    } else when (intervention.materialKind) {
                        ResolutionMaterialKind.NONE -> onResolveNone(intervention)
                        ResolutionMaterialKind.EPHEMERAL -> {
                            val chars = sensitiveInput.toCharArray()
                            sensitiveInput = ""
                            onResolveEphemeral(intervention, chars)
                        }
                        ResolutionMaterialKind.DURABLE_REFERENCE -> {
                            val chars = sensitiveInput.toCharArray()
                            sensitiveInput = ""
                            onCreateAuthorization(intervention, chars)
                        }
                    }
                },
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = dialogContent,
                    contentColor = dialogBg,
                ),
            ) {
                Text(
                    if (requiresUserDecision) "确认已完成" else if (isCloudflareReauthorization) "重新授权" else "继续",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    sensitiveInput = ""
                    if (requiresUserDecision) onContinueUnknown(intervention) else onReject(intervention)
                },
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = dialogContent,
                ),
                border = BorderStroke(1.dp, dialogBorder),
            ) {
                Text(
                    if (requiresUserDecision) "保持未知并重规划" else "拒绝",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

private fun capabilityTitle(capability: String): String = when (capability) {
    "cloudflare.reauthorize" -> "恢复 Cloudflare 授权"
    "cloudflare.resource.select" -> "选择 Cloudflare 资源"
    "git.push" -> "提供 Git 仓库授权"
    "ssh.connect" -> "提供 SSH 登录能力"
    "privilege.sudo.execute" -> "输入 sudo 密码"
    "terminal.interaction" -> "接管终端输入"
    "server.restart.confirm" -> "确认服务器操作"
    "skill.openai_api_access" -> "提供 API 授权"
    else -> "提供执行能力"
}
