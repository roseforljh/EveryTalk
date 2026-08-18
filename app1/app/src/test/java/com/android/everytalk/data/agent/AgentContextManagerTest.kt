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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `工具结果没有紧跟调用时丢弃整个工具组`() {
        val messages = listOf(
            assistantWithCalls("call-1", "call-2"),
            toolResult("call-1"),
            SimpleTextApiMessage(id = "user-2", role = "user", content = "插入的新消息"),
            toolResult("call-2"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertEquals(listOf("user-2"), cleaned.map { it.id })
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

    @Test
    fun `检查点后的超长工具轮次继续使用user摘要角色`() {
        val user = SimpleTextApiMessage(id = "user-1", role = "user", content = "持续处理")
        val messages = listOf(
            user,
            assistantWithCalls("call-1").copy(id = "assistant-1"),
            toolResult("call-1", "x".repeat(4_000)),
            assistantWithCalls("call-2").copy(id = "assistant-2"),
            toolResult("call-2", "y".repeat(4_000)),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-user",
            sessionId = "session-1",
            configIdSnapshot = "config-1",
            summary = "当前任务摘要",
            summarizedThroughItemId = user.id,
            prefixFingerprint = agentTranscriptFingerprint(listOf("user-1|user|null|${user.content}")),
            retainedTailJson = """{"summary_role":"user","retained_ids":[]}""",
            tokensBefore = 4_000,
            estimatedTokensAfter = 1_000,
            summaryRequestId = "request-1",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val plan = checkNotNull(
            manager.prepare(
                requestId = "request-checkpoint-split",
                request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 1),
                limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
                checkpoint = checkpoint,
            ).compactionPlan
        )

        assertEquals("user", plan.summaryRole)
        assertTrue(plan.isSplitTurn)
    }

    @Test
    fun `只有最新用户消息达到软阈值时保留原文继续`() {
        val prepared = manager.prepare(
            requestId = "request-latest",
            request = request(
                messages = listOf(
                    SimpleTextApiMessage(id = "latest-user", role = "user", content = "最新消息".repeat(40)),
                ),
                maxContextTokens = 4_096,
                compactThresholdTokens = 1,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 256, maxContextTokens = 4_096),
        )

        assertNull(prepared.compactionPlan)
        assertEquals(listOf("latest-user"), prepared.messages.map { it.id })
    }

    @Test
    fun `单个用户任务产生多轮工具调用时从原子组边界切分`() {
        val messages = buildList {
            add(SimpleTextApiMessage(id = "user-1", role = "user", content = "持续检查服务器"))
            repeat(4) { index ->
                val callId = "call-${index + 1}"
                add(assistantWithCalls(callId).copy(id = "assistant-${index + 1}"))
                add(toolResult(callId, "x".repeat(4_000)))
            }
        }

        val plan = manager.prepare(
            requestId = "request-split-turn",
            request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 1),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
        ).compactionPlan

        val splitPlan = checkNotNull(plan)
        assertTrue(splitPlan.isSplitTurn)
        assertEquals("user", splitPlan.summaryRole)
        assertTrue(splitPlan.messagesToSummarize.any { it.id == "user-1" })
        assertFalse(splitPlan.retainedTailIds.contains("user-1"))
        assertTrue(splitPlan.retainedTailIds.first().startsWith("assistant-"))
    }

    @Test
    fun `多轮历史按近期Token预算保留多个回合`() {
        val messages = buildList {
            repeat(6) { index ->
                val turn = index + 1
                add(SimpleTextApiMessage(id = "user-$turn", role = "user", content = "u".repeat(1_000)))
                add(SimpleTextApiMessage(id = "assistant-$turn", role = "assistant", content = "a".repeat(1_000)))
            }
        }

        val plan = checkNotNull(
            manager.prepare(
                requestId = "request-recent-budget",
                request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 1),
                limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
            ).compactionPlan
        )

        assertTrue(plan.retainedTailIds.contains("user-5"))
        assertTrue(plan.retainedTailIds.contains("user-6"))
        assertFalse(plan.retainedTailIds.contains("user-1"))
    }

    @Test
    fun `官方原生压缩在软阈值先获得处理机会`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "旧消息".repeat(300)),
            SimpleTextApiMessage(id = "assistant-1", role = "assistant", content = "旧回复".repeat(300)),
            SimpleTextApiMessage(id = "user-2", role = "user", content = "继续"),
        )

        val prepared = manager.prepare(
            requestId = "request-native-first",
            request = request(
                messages = messages,
                channel = "Codex",
                apiAddress = "https://api.openai.com/v1/responses",
                maxContextTokens = 8_192,
                compactThresholdTokens = 1,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
        )

        assertNull(prepared.compactionPlan)
        assertEquals(messages.map { it.id }, prepared.messages.map { it.id })
    }

    private fun assistantWithCalls(vararg ids: String): AgentAssistantApiMessage =
        AgentAssistantApiMessage(
            id = "assistant-1",
            toolCalls = ids.map { id ->
                AgentToolCallApiPart(id = id, name = "exec", arguments = JsonObject(emptyMap()))
            },
        )

    private fun toolResult(id: String, content: String = "ok"): AgentToolResultApiMessage = AgentToolResultApiMessage(
        id = "result-$id",
        toolCallId = id,
        toolName = "exec",
        content = JsonPrimitive(content),
    )

    private fun request(
        messages: List<com.android.everytalk.data.DataClass.AbstractApiMessage>,
        channel: String = "OpenAI兼容",
        apiAddress: String = "https://example.test",
        maxContextTokens: Int,
        compactThresholdTokens: Long,
    ): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = channel,
        apiAddress = apiAddress,
        apiKey = "test",
        model = "model",
        contextManagement = RequestContextManagement(
            configId = "config-1",
            maxContextTokens = maxContextTokens,
            reservedOutputTokens = 512,
            compactThresholdTokens = compactThresholdTokens,
            autoCompressionEnabled = true,
        ),
    )
}
