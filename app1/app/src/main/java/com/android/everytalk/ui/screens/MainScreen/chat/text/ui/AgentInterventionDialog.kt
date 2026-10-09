package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.R
import com.android.everytalk.util.locale.localizeUiMessage
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import com.android.everytalk.ui.components.dialog.AppDialogActionContent
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
    onResolveEphemeral: suspend (PendingIntervention, CharArray) -> Boolean,
    onCreateAuthorization: suspend (PendingIntervention, CharArray) -> Boolean,
    onStartCloudflareReauthorization: (PendingIntervention) -> Unit,
    cloudflareReauthInProgress: Boolean = false,
    onLoadCloudflareResources: suspend (PendingIntervention) -> List<com.android.everytalk.data.computer.ResourceOption>,
    onSelectCloudflareResource: (PendingIntervention, String) -> Unit,
    onReject: suspend (PendingIntervention) -> Boolean,
    onConfirmUnknownDelivered: (PendingIntervention) -> Unit,
    onContinueUnknown: (PendingIntervention) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dialogBg = appDialogContainerColor()
    val dialogContent = appDialogContentColor()
    val dialogBorder = appDialogBorderColor()
    var sensitiveInput by remember(intervention.suspensionId, intervention.rowVersion) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var submitting by remember(intervention.suspensionId) { mutableStateOf(false) }
    var submissionError by remember(intervention.suspensionId) { mutableStateOf<String?>(null) }
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
            catch (error: Exception) { resourceError = error.message ?: context.getString(R.string.agent_intervention_resource_failed) }
            finally { loading = false }
        }
    }
    val reauthBusy = isCloudflareReauthorization && cloudflareReauthInProgress
    val canSubmit = if (reauthBusy || submitting) false else if (requiresUserDecision) true else if (isResourceSelection) selectedId != null && !loading else when (intervention.materialKind) {
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
        title = { Text(stringResource(R.string.agent_intervention_title), fontWeight = FontWeight.Bold) },
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
                        text = stringResource(R.string.agent_intervention_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isResourceSelection) {
                    Text(stringResource(R.string.agent_intervention_select_resource))
                    if (loading) Text(stringResource(R.string.agent_intervention_loading_resources))
                    resourceError?.let { Text(context.localizeUiMessage(it), color = MaterialTheme.colorScheme.error) }
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        resources.forEach { resource ->
                            TextButton(onClick = { selectedId = resource.id }, enabled = !loading) {
                                // Worker 的 displayName 和 id 就是同一个字符串，只有两者不同时才补第二行。
                                val label = (if (selectedId == resource.id) "✓ " else "") + resource.displayName +
                                    if (resource.id == resource.displayName) "" else "\n" + resource.id
                                Text(label)
                            }
                        }
                    }
                    if (!loading && resources.isEmpty()) Text(stringResource(R.string.agent_intervention_no_resources))
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.agent_intervention_refresh_resources)) }
                } else if (isCloudflareReauthorization) {
                    Text(
                        text = stringResource(R.string.agent_intervention_cloudflare_expired),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else when (intervention.materialKind) {
                    ResolutionMaterialKind.NONE -> Text(
                        text = field?.label?.let(context::localizeUiMessage) ?: stringResource(R.string.agent_intervention_confirm_action),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ResolutionMaterialKind.EPHEMERAL -> OutlinedTextField(
                        value = sensitiveInput,
                        onValueChange = { sensitiveInput = it },
                        label = { Text(field?.label?.let(context::localizeUiMessage) ?: stringResource(R.string.agent_intervention_sensitive_input)) },
                        singleLine = true,
                        enabled = !submitting,
                        shape = AppDialogTextFieldShape,
                        colors = appDialogTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    ResolutionMaterialKind.DURABLE_REFERENCE -> OutlinedTextField(
                        value = sensitiveInput,
                        onValueChange = { sensitiveInput = it },
                        label = { Text(field?.label?.let(context::localizeUiMessage) ?: stringResource(R.string.agent_intervention_credentials)) },
                        singleLine = true,
                        enabled = !submitting,
                        shape = AppDialogTextFieldShape,
                        colors = appDialogTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (!submitting) {
                    val error = submissionError ?: if (intervention.state == SuspensionState.WAITING_USER_REENTRY) {
                        stringResource(R.string.agent_intervention_reenter)
                    } else null
                    error?.let { Text(context.localizeUiMessage(it), color = MaterialTheme.colorScheme.error) }
                }
                if (fieldKind in setOf(
                        AgentInterventionPolicyRegistry.FieldKind.SENSITIVE_TEXT,
                        AgentInterventionPolicyRegistry.FieldKind.AUTHORIZATION_SECRET,
                    )
                ) {
                    Text(
                        text = if (fieldKind == AgentInterventionPolicyRegistry.FieldKind.AUTHORIZATION_SECRET) {
                            stringResource(R.string.agent_intervention_secret_notice)
                        } else {
                            stringResource(R.string.agent_intervention_input_notice)
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
                        ResolutionMaterialKind.EPHEMERAL,
                        ResolutionMaterialKind.DURABLE_REFERENCE -> {
                            if (submitting) return@Button
                            val chars = sensitiveInput.toCharArray()
                            sensitiveInput = ""
                            submitting = true
                            submissionError = null
                            // 等待真实接收结果；清空输入是密钥清理，不等同于任务已经恢复。
                            scope.launch {
                                try {
                                    val accepted = if (intervention.materialKind == ResolutionMaterialKind.EPHEMERAL) {
                                        onResolveEphemeral(intervention, chars)
                                    } else onCreateAuthorization(intervention, chars)
                                    if (!accepted) submissionError = context.getString(R.string.agent_intervention_input_not_accepted)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    // 异常可能包含凭据，只展示固定错误提示。
                                    submissionError = context.getString(R.string.agent_intervention_submit_failed)
                                } finally {
                                    chars.fill('\u0000')
                                    submitting = false
                                }
                            }
                        }
                    }
                },
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = dialogContent,
                    contentColor = dialogBg,
                ),
            ) {
                AppDialogActionContent(
                    label = if (submitting) stringResource(R.string.voice_processing) else if (requiresUserDecision) stringResource(R.string.agent_intervention_confirm_completed) else if (isCloudflareReauthorization) stringResource(R.string.cloudflare_reauthorize) else stringResource(R.string.chat_input_resume),
                    isLoading = reauthBusy || submitting,
                    loadingContentDescription = if (submitting) stringResource(R.string.agent_intervention_submitting) else stringResource(R.string.agent_intervention_reauthorizing),
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                enabled = !submitting,
                onClick = {
                    sensitiveInput = ""
                    if (requiresUserDecision) onContinueUnknown(intervention) else {
                        submitting = true
                        submissionError = null
                        scope.launch {
                            try {
                                if (!onReject(intervention)) submissionError = context.getString(R.string.agent_intervention_reject_not_accepted)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                submissionError = context.getString(R.string.agent_intervention_reject_failed)
                            } finally {
                                submitting = false
                            }
                        }
                    }
                },
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = dialogContent,
                ),
                border = BorderStroke(1.dp, dialogBorder),
            ) {
                Text(
                    if (requiresUserDecision) stringResource(R.string.agent_intervention_replan) else stringResource(R.string.agent_host_command_reject),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

@Composable
private fun capabilityTitle(capability: String): String = when (capability) {
    "cloudflare.reauthorize" -> stringResource(R.string.agent_capability_cloudflare)
    "cloudflare.resource.select" -> stringResource(R.string.agent_capability_resource)
    "git.push" -> stringResource(R.string.agent_capability_git)
    "ssh.connect" -> stringResource(R.string.agent_capability_ssh)
    "privilege.sudo.execute" -> stringResource(R.string.agent_capability_sudo)
    "terminal.interaction" -> stringResource(R.string.agent_capability_terminal)
    "server.restart.confirm" -> stringResource(R.string.agent_capability_server)
    "skill.openai_api_access" -> stringResource(R.string.agent_capability_api)
    else -> stringResource(R.string.agent_capability_default)
}
