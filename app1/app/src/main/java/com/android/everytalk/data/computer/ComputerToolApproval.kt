package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AgentLoop 在真正创建 ComputerExecution 前读取的本地审批请求。
 * 这里只携带冻结后的展示数据，审批结果通过 ComputerRequestContext 的一次性凭证回传。
 */
@Serializable
sealed interface ComputerToolApprovalRequest {
    val toolCallId: String
    val context: ComputerRequestContext

    @Serializable
    @SerialName("host_command")
    data class HostCommand(
        override val toolCallId: String,
        val request: ComputerHostCommandConfirmationRequest,
    ) : ComputerToolApprovalRequest {
        override val context: ComputerRequestContext = request.context
    }

    @Serializable
    @SerialName("public_preview")
    data class PublicPreview(
        override val toolCallId: String,
        val request: ComputerPublicPreviewRequest,
        val computerName: String,
    ) : ComputerToolApprovalRequest {
        override val context: ComputerRequestContext = request.context
    }

    @Serializable
    @SerialName("unknown_execution")
    data class UnknownExecution(
        override val toolCallId: String,
        override val context: ComputerRequestContext,
        val computerName: String,
        val toolName: String,
        val detail: String,
        val isWriteOperation: Boolean,
    ) : ComputerToolApprovalRequest
}

/** UI 复用现有权限卡片时使用的轻量投影。 */
data class PendingComputerToolApproval(
    val runId: String,
    val approvalRequestId: String,
    val request: ComputerToolApprovalRequest,
)

fun PendingComputerToolApproval.hostConfirmationRequest(): ComputerHostCommandConfirmationRequest? = when (val pending = request) {
    is ComputerToolApprovalRequest.HostCommand -> pending.request.copy(requestId = approvalRequestId)
    is ComputerToolApprovalRequest.UnknownExecution -> ComputerHostCommandConfirmationRequest(
        requestId = approvalRequestId,
        context = pending.context,
        computerName = pending.computerName,
        command = pending.detail,
        cwd = "",
        requestsPrivilege = false,
        reason = if (pending.isWriteOperation) {
            "上次操作的结果无法确认，重新执行可能重复修改 VPS"
        } else {
            "上次操作的结果无法确认"
        },
        risks = emptySet(),
        decisionMode = ComputerApprovalDecisionMode.RETRY_OR_KEEP_UNKNOWN,
    )
    else -> null
}

fun PendingComputerToolApproval.publicPreviewRequest(): ComputerPublicPreviewRequest? =
    (request as? ComputerToolApprovalRequest.PublicPreview)?.request

val PendingComputerToolApproval.isUnknownExecution: Boolean
    get() = request is ComputerToolApprovalRequest.UnknownExecution

enum class ComputerToolApprovalPhase {
    BEFORE_EXECUTION,
    RETRY_UNKNOWN,
}

typealias ComputerToolApprovalProvider = suspend (
    toolName: String,
    arguments: JsonObject,
    toolCallId: String,
    requestContext: ComputerRequestContext?,
    phase: ComputerToolApprovalPhase,
) -> ComputerToolApprovalRequest?
