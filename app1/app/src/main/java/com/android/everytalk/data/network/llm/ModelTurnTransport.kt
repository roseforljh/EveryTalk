package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ChatRequest
import kotlinx.coroutines.flow.Flow

/** 一次真实上游模型请求。Transport 禁止执行工具或启动下一轮请求。 */
data class ModelTurnRequest(
    val requestId: String,
    val runId: String,
    val ordinal: Int,
    val request: ChatRequest,
)

/**
 * 四个 Provider 只实现一次协议编码和一次流解析。AgentLoop 负责后续工具和循环。
 */
fun interface ModelTurnTransport {
    fun streamTurn(request: ModelTurnRequest): Flow<AppStreamEvent>
}

/** Provider 单轮流结束后，由 AgentLoop 使用的归一化结果。 */
data class ModelTurnResult(
    val assistant: com.android.everytalk.data.agent.AgentAssistantTurn,
    val usage: TokenUsage?,
    val firstEventAt: Long?,
)
