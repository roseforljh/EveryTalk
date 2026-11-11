package com.android.everytalk.data.agent

/** Pi AgentEvent 的 Android 内部等价事件，不携带消息正文、Tool 参数或 Secret。 */
enum class AgentLifecyclePhase {
    AGENT_START,
    AGENT_END,
    TURN_START,
    TURN_END,
    MESSAGE_START,
    MESSAGE_END,
    TOOL_EXECUTION_START,
    TOOL_EXECUTION_UPDATE,
    TOOL_EXECUTION_END,
}

data class AgentLifecycleEvent(
    val phase: AgentLifecyclePhase,
    val runId: String,
    val modelTurnOrdinal: Int? = null,
    val requestId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean? = null,
    val durationMs: Long? = null,
)
