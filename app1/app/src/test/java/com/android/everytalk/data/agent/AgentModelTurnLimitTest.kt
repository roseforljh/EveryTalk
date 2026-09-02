package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelTurnLimitTest {
    @Test
    fun `恢复Run只获得总上限内剩余轮次`() {
        assertEquals(listOf(49, 50), remainingAgentModelTurnOrdinals(49).toList())
        assertTrue(remainingAgentModelTurnOrdinals(51).isEmpty())
    }

    @Test
    fun `Provider初始请求后最多自动重试三次`() {
        assertTrue(canRetryProviderAttempt(1))
        assertTrue(canRetryProviderAttempt(2))
        assertTrue(canRetryProviderAttempt(3))
        assertEquals(false, canRetryProviderAttempt(4))
    }

    @Test
    fun `相同参数连续调用第三次终止`() {
        val guard = ToolLoopGuard()
        fun call(id: String) = AgentContentBlock.ToolCall(
            id = id,
            name = "exec",
            arguments = buildJsonObject { put("command", "uname -a") },
        )

        assertNull(guard.record(call("call-1")))
        assertNull(guard.record(call("call-2")))
        assertEquals("同一工具和参数连续调用 3 次，已终止 Agent", guard.record(call("call-3")))
    }

    @Test
    fun `工具指纹忽略Map插入顺序且系统指纹包含默认提示`() {
        val first = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "你好")),
            provider = "provider",
            channel = "OpenAI",
            apiAddress = "https://example.test",
            apiKey = "key",
            model = "model",
            tools = listOf(linkedMapOf("type" to "function", "function" to mapOf("name" to "tool"))),
        )
        val second = first.copy(
            tools = listOf(linkedMapOf("function" to mapOf("name" to "tool"), "type" to "function")),
        )

        assertEquals(agentToolSchemaFingerprint(first), agentToolSchemaFingerprint(second))
        assertNotEquals(
            agentSystemPromptFingerprint(emptyList()),
            agentSystemPromptFingerprint(listOf(SimpleTextApiMessage(role = "system", content = "自定义"))),
        )
    }
}
