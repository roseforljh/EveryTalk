package com.android.everytalk.data.agent

import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.WebSearchToolResultExtractor
import com.android.everytalk.data.network.computerExecutionCompletedEvent
import com.android.everytalk.data.network.estimateToolLoopJsonTokens
import com.android.everytalk.data.network.truncateToolOutput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_AGENT_TOOL_RESULT_TOKENS = 64_000L

/**
 * 统一工具执行入口。服务器三档权限仍由 ComputerToolExecutor 内的公共策略处理。
 */
class AgentToolRuntime(
    private val executorProvider: () -> AppToolExecutor?,
) {
    suspend fun execute(
        call: AgentContentBlock.ToolCall,
        computerContext: ComputerRequestContext?,
        maxModelResultTokens: Long = MAX_AGENT_TOOL_RESULT_TOKENS,
        emit: suspend (AppStreamEvent) -> Unit,
    ): AgentContentBlock.ToolResult {
        val executor = executorProvider()
            ?: return AgentContentBlock.ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                content = kotlinx.serialization.json.JsonPrimitive("工具执行器未初始化"),
                isError = true,
            )
        return try {
            val raw = executor(
                call.name,
                call.arguments,
                call.id,
                computerContext,
            ) { status -> emit(AppStreamEvent.ExecutionStatusUpdate(status)) }
            computerExecutionCompletedEvent(raw, call.id)?.let { emit(it) }
            WebSearchToolResultExtractor.extract(call.name, raw)
                .takeIf(List<*>::isNotEmpty)
                ?.let { emit(AppStreamEvent.WebSearchResults(it)) }
            val bounded = boundModelToolResult(
                result = stripUiOnlyFields(raw),
                maxTokens = maxModelResultTokens.coerceIn(64L, MAX_AGENT_TOOL_RESULT_TOKENS),
            )
            AgentContentBlock.ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                content = bounded.content,
                truncated = bounded.truncated,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AgentContentBlock.ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                content = kotlinx.serialization.json.JsonPrimitive(error.message ?: "工具执行失败"),
                isError = true,
            )
        }
    }

    private fun stripUiOnlyFields(result: JsonElement): JsonElement = (result as? JsonObject)
        ?.let { JsonObject(it.filterKeys { key -> key != "_images" }) }
        ?: result

    /**
     * 统一限制所有工具回传给模型的内容，避免一个诊断命令挤满整个上下文。
     * SSH 层已保留 stdout/stderr 的截断元数据，这里继续保留首尾和原始字符数。
     */
    private fun boundModelToolResult(result: JsonElement, maxTokens: Long): BoundedToolResult {
        if (estimateToolLoopJsonTokens(result) <= maxTokens) {
            return BoundedToolResult(result, truncated = false)
        }
        val raw = result.toString()
        val bounded = truncateToolOutput(raw, maxTokens)
        return BoundedToolResult(
            content = JsonPrimitive(
                "$bounded\n[原始工具结果字符数：${raw.length}；发给模型的内容已截断]",
            ),
            truncated = true,
        )
    }
}

private data class BoundedToolResult(
    val content: JsonElement,
    val truncated: Boolean,
)
