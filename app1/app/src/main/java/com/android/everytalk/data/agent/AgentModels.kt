/** 远端完成先保存 appendToolResult，再恢复模型 continueRun */
package com.android.everytalk.data.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolApprovalRequest

/**
 * 一次用户输入对应一个 AgentRun。普通聊天同样创建 Run，通常只包含一次模型请求。
 */
enum class AgentRunStatus {
    CREATED,
    PREPARING_CONTEXT,
    COMPACTING_CONTEXT,
    WAITING_MODEL,
    STREAMING_MODEL,
    CHECKING_PERMISSION,
    WAITING_APPROVAL,
    EXECUTING_TOOL,
    /** Tool 已经交给 VPS，Android 只等待远端状态和结果。 */
    WAITING_REMOTE_EXECUTION,
    /** VPS 命令已完成，等待模型调用以续写最终 AI 回复。 */
    MODEL_CONTINUATION_PENDING,
    PERSISTING_RESULT,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * 记录 AgentRun 状态变更或生命周期中断的具体原因。
 */
object AgentTerminalReasons {
    const val USER_STOP = "USER_STOP"
    const val APP_INTERRUPTED = "APP_INTERRUPTED"
    const val FORCE_STOP_RECOVERED = "FORCE_STOP_RECOVERED"
    const val SYSTEM_RECOVERED = "SYSTEM_RECOVERED"
    const val CONNECTION_LOST = "CONNECTION_LOST"
    const val RECONNECTED = "RECONNECTED"
    const val PERMISSION_WAITING = "PERMISSION_WAITING"
    const val MODEL_CONTINUATION_PENDING = "MODEL_CONTINUATION_PENDING"
    const val VPS_RESTARTED = "VPS_RESTARTED"
    const val REMOTE_TASK_MISSING = "REMOTE_TASK_MISSING"
    const val REMOTE_PROCESS_TERMINATED = "REMOTE_PROCESS_TERMINATED"
    const val CONFIG_ERROR = "CONFIG_ERROR"
}

/** Agent 编排、状态或持久化失败，禁止在 UI 层伪装成网络错误。 */
const val AGENT_INTERNAL_ERROR_TYPE = "agent_internal"

enum class AgentEntryKind {
    ASSISTANT,
    TOOL_EXECUTION_STARTED,
    TOOL_RESULT,
    APPROVAL_REQUEST,
    APPROVAL_DECISION,
    STATUS,
}

enum class AgentEntryStatus {
    STREAMING,
    FINAL,
    PARTIAL,
    UNKNOWN,
}

enum class AgentRequestPurpose {
    AGENT_TURN,
    COMPACTION,
}

enum class AgentRequestStatus {
    PREPARED,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class AgentUsageQuality {
    MEASURED,
    ESTIMATED,
    PARTIAL,
    UNKNOWN,
}

enum class AgentCompactionStatus {
    PREPARING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class AgentApprovalDecision {
    APPROVED,
    REJECTED,
    RETRY,
    KEEP_UNKNOWN,
}

/**
 * Run 跨进程恢复所需的非敏感请求快照。
 * API Key 始终从本地 ApiConfig 读取，禁止写进 Agent 表。
 */
@Serializable
data class AgentRequestSnapshot(
    val messages: List<AbstractApiMessage>,
    val provider: String,
    val channel: String,
    val apiAddress: String?,
    val model: String,
    val forceGoogleReasoningPrompt: Boolean? = null,
    val useWebSearch: Boolean? = null,
    val generationConfig: GenerationConfig? = null,
    val toolsJson: JsonElement? = null,
    val toolChoiceJson: JsonElement? = null,
    val qwenEnableSearch: Boolean? = null,
    val customModelParametersJson: JsonElement? = null,
    val customExtraBodyJson: JsonElement? = null,
    val enableCodeExecution: Boolean? = null,
    val contextManagement: RequestContextManagement? = null,
    val computerRequestContext: ComputerRequestContext? = null,
)

@Serializable
data class AgentApprovalRecord(
    val approvalRequestId: String,
    val requestId: String,
    val toolCall: AgentContentBlock.ToolCall,
    val pendingToolCalls: List<AgentContentBlock.ToolCall>,
    val request: ComputerToolApprovalRequest? = null,
    val decision: AgentApprovalDecision? = null,
    val decidedAt: Long? = null,
    val toolResultAlreadyPersisted: Boolean = false,
    /** 恢复批次时从 pendingToolCalls 重新进入正常预检，不直接处理 toolCall。 */
    val resumePendingToolCallsOnly: Boolean = false,
)

/** 工具真正进入 Executor 前立即落库，重启时据此判断是否可能已经产生外部副作用。 */
@Serializable
data class AgentToolExecutionRecord(
    val requestId: String,
    val toolCall: AgentContentBlock.ToolCall,
    val startedAt: Long,
)

/** Provider 中立的消息内容。协议转换只发生在各 Transport 内。 */
@Serializable
sealed class AgentContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AgentContentBlock()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : AgentContentBlock()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    ) : AgentContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val isError: Boolean = false,
        val truncated: Boolean = false,
        val fullResultPath: String? = null,
        val fullResultBytes: Long? = null,
        val fullResultSha256: String? = null,
    ) : AgentContentBlock()
}

/** 一次模型返回的完整中立结果，工具调用按模型原始顺序保存。 */
data class AgentAssistantTurn(
    val blocks: List<AgentContentBlock>,
    val finishReason: String? = null,
) {
    val toolCalls: List<AgentContentBlock.ToolCall>
        get() = blocks.filterIsInstance<AgentContentBlock.ToolCall>()
}

/** 当前 Run 的三套 Token 口径。 */
data class AgentUsageSummary(
    val activeContextTokens: Long,
    val activeContext: com.android.everytalk.data.database.entities.AgentContextSnapshotEntity?,
    val runInputTokens: Long,
    val runOutputTokens: Long,
    val runTotalTokens: Long,
    val requestCount: Int,
    val compactionRequestCount: Int,
)
