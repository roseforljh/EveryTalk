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
    @SerialName("local_file_write")
    data class LocalFileWrite(
        override val toolCallId: String,
        override val context: ComputerRequestContext,
        val path: String,
        val contentSha256: String,
        val bytes: Long,
        /** 审批时的文件版本；null 表示新建，文件被其他操作改变后必须重新审批。 */
        val previousSha256: String? = null,
    ) : ComputerToolApprovalRequest

    /** Cloudflare 所有会修改云端资源的操作共用此审批投影。 */
    @Serializable
    @SerialName("cloudflare_write")
    data class CloudflareWrite(
        override val toolCallId: String,
        override val context: ComputerRequestContext,
        val accountId: String,
        val authorizationId: String,
        val toolName: String,
        val safeSummary: String,
        /** 审批时冻结的授权代次；退出登录或重新授权后旧审批自动失效。 */
        val generation: Long,
        /** 对工具名、Account 和实际参数做摘要，防止审批后替换资源目标或内容。 */
        val requestHash: String,
    ) : ComputerToolApprovalRequest

    /** 旧值来自可信 API 读取，Account 和 generation 冻结在持久化审批记录里。 */
    @Serializable
    @SerialName("cloudflare_cron_change")
    data class CloudflareCronChange(
        override val toolCallId: String,
        override val context: ComputerRequestContext,
        val computerName: String,
        val accountId: String,
        val authorizationId: String,
        val generation: Long,
        val workerName: String,
        val previousSchedules: List<String>,
        val schedules: List<String>,
        val requestHash: String,
    ) : ComputerToolApprovalRequest

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
    is ComputerToolApprovalRequest.LocalFileWrite -> ComputerHostCommandConfirmationRequest(
        requestId = approvalRequestId,
        context = pending.context,
        computerName = "本地 Workspace",
        command = "保存文件：${pending.path}\n大小：${pending.bytes} 字节\nSHA-256：${pending.contentSha256}",
        cwd = "",
        requestsPrivilege = false,
        reason = "Agent 请求在本地 Workspace 创建或覆盖文件",
        risks = emptySet(),
    )
    is ComputerToolApprovalRequest.CloudflareWrite -> ComputerHostCommandConfirmationRequest(
        requestId = approvalRequestId,
        context = pending.context,
        computerName = "Cloudflare",
        command = "操作：${pending.toolName}\nAccount：${pending.accountId}\n${pending.safeSummary}",
        cwd = "",
        requestsPrivilege = false,
            reason = "该操作会修改 Cloudflare 云端资源",
        risks = emptySet(),
    )
    is ComputerToolApprovalRequest.CloudflareCronChange -> ComputerHostCommandConfirmationRequest(
        requestId = approvalRequestId,
        context = pending.context,
        computerName = pending.computerName,
        command = "Worker：${pending.workerName}\nAccount：${pending.accountId}\n旧值：\n" +
            pending.previousSchedules.joinToString("\n").ifEmpty { "无" } + "\n新值：\n" +
            pending.schedules.joinToString("\n").ifEmpty { "无（移除全部定时触发器）" },
        cwd = "",
        requestsPrivilege = false,
        reason = "修改 Cloudflare Cron 定时触发器（UTC）",
        risks = emptySet(),
    )
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
