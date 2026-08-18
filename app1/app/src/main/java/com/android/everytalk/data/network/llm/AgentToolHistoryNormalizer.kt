package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage

/**
 * 在进入各 Provider 协议层前统一清理工具历史。
 *
 * 中断或旧数据可能留下孤立、重复、名称错配的 Tool Call/Result。这里按调用 ID 建立唯一配对，
 * 任一并行工具组不完整时整组移除协议字段；Assistant 已生成的普通正文仍然保留。
 */
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

            if (message.text.isNotBlank() || message.reasoning.isNotBlank()) {
                add(message.copy(toolCalls = emptyList()))
            }
            index++
        }
    }
}
