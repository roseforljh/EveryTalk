package com.android.everytalk.data.agent

import com.android.everytalk.data.network.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    PERSISTING_RESULT,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class AgentEntryKind {
    ASSISTANT,
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

/** AgentLoop 对界面和持久化层发出的生命周期事件。 */
sealed interface AgentEvent {
    val runId: String

    data class RunStarted(override val runId: String) : AgentEvent
    data class ContextPreparing(override val runId: String, val requestOrdinal: Int) : AgentEvent
    data class ModelRequestStarted(
        override val runId: String,
        val requestId: String,
        val requestOrdinal: Int,
    ) : AgentEvent

    data class StreamEvent(
        override val runId: String,
        val requestId: String,
        val event: com.android.everytalk.data.network.AppStreamEvent,
    ) : AgentEvent

    data class ToolExecutionStarted(
        override val runId: String,
        val requestId: String,
        val call: AgentContentBlock.ToolCall,
    ) : AgentEvent

    data class ToolExecutionCompleted(
        override val runId: String,
        val requestId: String,
        val result: AgentContentBlock.ToolResult,
    ) : AgentEvent

    data class UsageFinalized(
        override val runId: String,
        val requestId: String,
        val usage: TokenUsage,
    ) : AgentEvent

    data class RunCompleted(override val runId: String) : AgentEvent
    data class RunFailed(override val runId: String, val message: String) : AgentEvent
}
