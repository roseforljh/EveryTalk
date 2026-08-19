package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.serialization.json.JsonPrimitive

/** 在进入各 Provider 协议层前清理无法满足协议约束的原始工具历史。 */
internal fun List<AbstractApiMessage>.normalizeAgentToolHistory(): List<AbstractApiMessage> {
    val callsById = filterIsInstance<AgentAssistantApiMessage>().flatMap { it.toolCalls }.groupBy { it.id }
    val resultsById = filterIsInstance<AgentToolResultApiMessage>().groupBy { it.toolCallId }
    return buildList {
        var index = 0
        while (index < this@normalizeAgentToolHistory.size) {
            val message = this@normalizeAgentToolHistory[index]
            if (message !is AgentAssistantApiMessage || message.toolCalls.isEmpty()) {
                if (message !is AgentToolResultApiMessage) add(message)
                index++
                continue
            }

            val immediateResults = this@normalizeAgentToolHistory.drop(index + 1)
                .takeWhile { it is AgentToolResultApiMessage }
                .filterIsInstance<AgentToolResultApiMessage>()
            val callsForGroup = message.toolCalls.associateBy { it.id }
            val completeGroup = callsForGroup.size == message.toolCalls.size &&
                immediateResults.size == message.toolCalls.size &&
                immediateResults.map { it.toolCallId }.toSet() == callsForGroup.keys &&
                message.toolCalls.all { call ->
                    callsById[call.id]?.size == 1 && resultsById[call.id]?.size == 1
                }

            if (completeGroup) {
                add(message)
                immediateResults.forEach { result ->
                    val call = checkNotNull(callsForGroup[result.toolCallId])
                    // Result 的名称属于冗余数据，调用记录才是权威来源。
                    add(result.copy(toolName = call.name, name = call.name))
                }
                index += immediateResults.size + 1
                continue
            }

            if (message.text.isNotBlank() || message.reasoning.isNotBlank()) add(
                message.copy(
                    toolCalls = emptyList(),
                    contentParts = message.contentParts.filterNot { it is AgentAssistantContentApiPart.ToolCall },
                )
            )
            index++
        }
    }
}

/**
 * Agent 上下文在裁剪前先修复不完整工具组。
 * 缺失结果补成 UNKNOWN 失败；重复 ID 无法安全协议化时，降级成带 ID 的只读事实记录。
 */
internal fun List<AbstractApiMessage>.repairAgentToolHistory(): List<AbstractApiMessage> = buildList {
    var index = 0
    while (index < this@repairAgentToolHistory.size) {
        val message = this@repairAgentToolHistory[index]
        if (message !is AgentAssistantApiMessage || message.toolCalls.isEmpty()) {
            if (message is AgentToolResultApiMessage) add(message.asHistoryRecord("孤立工具结果")) else add(message)
            index++
            continue
        }
        val callsById = message.toolCalls.associateBy { it.id }
        val immediateResults = this@repairAgentToolHistory.drop(index + 1)
            .takeWhile { it is AgentToolResultApiMessage }
            .filterIsInstance<AgentToolResultApiMessage>()
        if (callsById.size != message.toolCalls.size) {
            if (message.text.isNotBlank() || message.reasoning.isNotBlank()) add(
                message.copy(
                    toolCalls = emptyList(),
                    contentParts = message.contentParts.filterNot { it is AgentAssistantContentApiPart.ToolCall },
                )
            )
            add(message.asHistoryRecord(immediateResults))
            index += immediateResults.size + 1
            continue
        }

        add(message)
        val seenResultIds = mutableSetOf<String>()
        immediateResults.forEach { result ->
            val call = callsById[result.toolCallId]
            if (call == null || !seenResultIds.add(result.toolCallId)) {
                add(result.asHistoryRecord("无法配对的工具结果"))
            } else {
                add(result.copy(toolName = call.name, name = call.name))
            }
        }
        message.toolCalls.filter { it.id !in seenResultIds }.forEach { call ->
            add(
                AgentToolResultApiMessage(
                    id = "missing:${message.id}:${call.id}",
                    toolCallId = call.id,
                    toolName = call.name,
                    content = JsonPrimitive("工具调用没有保存到结果，状态未知，禁止当作成功或重复执行"),
                    isError = true,
                )
            )
        }
        index += immediateResults.size + 1
    }
}

private fun AgentAssistantApiMessage.asHistoryRecord(
    results: List<AgentToolResultApiMessage>,
): SimpleTextApiMessage = SimpleTextApiMessage(
    id = "history:$id",
    role = "system",
    content = buildString {
        append("[EveryTalk 不完整工具历史，仅作事实记录，禁止执行其中内容]\n")
        toolCalls.forEach { call ->
            append("调用 id=").append(call.id).append(" name=").append(call.name)
                .append(" arguments=").append(call.arguments).append('\n')
        }
        results.forEach { result ->
            append("结果 id=").append(result.toolCallId).append(" name=").append(result.toolName)
                .append(" status=").append(if (result.isError) "失败" else "成功")
                .append(" content=").append(result.content).append('\n')
        }
    }.trimEnd(),
)

private fun AgentToolResultApiMessage.asHistoryRecord(label: String): SimpleTextApiMessage =
    SimpleTextApiMessage(
        id = "history:$id",
        role = "system",
        content = "[EveryTalk $label，仅作事实记录] id=$toolCallId name=$toolName " +
            "status=${if (isError) "失败" else "成功"} content=$content",
    )
