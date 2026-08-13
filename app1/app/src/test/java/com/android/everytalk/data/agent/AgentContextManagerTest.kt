package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextManagerTest {
    private val manager = AgentContextManager()

    @Test
    fun `多工具调用缺少一个结果时整组都不进入上下文`() {
        val assistant = assistantWithCalls("call-1", "call-2")
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "检查服务器"),
            assistant,
            toolResult("call-1"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertEquals(listOf("user-1"), cleaned.map { it.id })
    }

    @Test
    fun `所有结果齐全时按原顺序保留完整工具组`() {
        val assistant = assistantWithCalls("call-1", "call-2")
        val messages = listOf(
            assistant,
            toolResult("call-1"),
            toolResult("call-2"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertEquals(messages, cleaned)
        assertTrue(cleaned[1] is AgentToolResultApiMessage)
    }

    @Test
    fun `没有新增历史时不会反复压缩同一检查点`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "很长的旧任务".repeat(200)),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-1",
            sessionId = "session-1",
            configIdSnapshot = "config-1",
            summary = "旧任务摘要",
            summarizedThroughItemId = "user-1",
            prefixFingerprint = agentTranscriptFingerprint(
                listOf("user-1|user|null|${messages.single().content}"),
            ),
            retainedTailJson = """{"summary_role":"user","retained_ids":[]}""",
            tokensBefore = 2_000,
            estimatedTokensAfter = 100,
            summaryRequestId = "request-1",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val prepared = manager.prepare(
            requestId = "request-2",
            request = ChatRequest(
                messages = messages,
                provider = "OpenAI",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "test",
                model = "model",
                contextManagement = RequestContextManagement(
                    configId = "config-1",
                    maxContextTokens = 1_000,
                    reservedOutputTokens = 100,
                    compactThresholdTokens = 1,
                    autoCompressionEnabled = true,
                ),
            ),
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            checkpoint = checkpoint,
        )

        assertEquals(null, prepared.compactionPlan)
        val summary = prepared.messages.single() as SimpleTextApiMessage
        assertTrue(summary.content.contains("旧任务摘要"))
    }

    private fun assistantWithCalls(vararg ids: String): AgentAssistantApiMessage =
        AgentAssistantApiMessage(
            id = "assistant-1",
            toolCalls = ids.map { id ->
                AgentToolCallApiPart(id = id, name = "exec", arguments = JsonObject(emptyMap()))
            },
        )

    private fun toolResult(id: String): AgentToolResultApiMessage = AgentToolResultApiMessage(
        id = "result-$id",
        toolCallId = id,
        toolName = "exec",
        content = JsonPrimitive("ok"),
    )
}
