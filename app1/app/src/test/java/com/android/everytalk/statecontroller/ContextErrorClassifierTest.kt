package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ContextErrorClassifierTest {
    @Test
    fun `结构化状态和错误码优先完成分类`() {
        assertEquals(
            RequestErrorCategory.INPUT_CONTEXT_TOO_LONG,
            ContextErrorClassifier.classify(
                ProviderErrorInfo(
                    status = 400,
                    code = "context_length_exceeded",
                    message = "bad request",
                )
            ),
        )
        assertEquals(
            RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH,
            ContextErrorClassifier.classify(
                ProviderErrorInfo(
                    status = 400,
                    code = "invalid_value",
                    parameter = "max_tokens",
                    message = "bad request",
                )
            ),
        )
        assertEquals(
            RequestErrorCategory.AUTHENTICATION,
            ContextErrorClassifier.classify(ProviderErrorInfo(status = 401, message = "unauthorized")),
        )
        assertEquals(
            RequestErrorCategory.RATE_LIMITED,
            ContextErrorClassifier.classify(ProviderErrorInfo(status = 429, message = "slow down")),
        )
    }

    @Test
    fun `输入过长只移除最旧完整轮次并保留系统和最新轮次`() {
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(id = "system", role = "system", content = "规则"),
                SimpleTextApiMessage(id = "u1", role = "user", content = "旧问题"),
                SimpleTextApiMessage(id = "a1", role = "assistant", content = "旧回答"),
                SimpleTextApiMessage(id = "u2", role = "user", content = "最新问题"),
            )
        )

        val decision = ContextRecoveryPolicy.recover(
            request = request,
            error = ProviderErrorInfo(
                status = 400,
                code = "context_length_exceeded",
                message = "too long",
                maxContextTokens = 8_192,
            ),
            hasPartialOutput = false,
            attemptedCategories = emptySet(),
        )

        assertEquals(RequestErrorCategory.INPUT_CONTEXT_TOO_LONG, decision?.category)
        assertEquals(listOf("system", "u2"), decision?.request?.messages?.map { it.id })
        assertEquals(8_192, decision?.effectiveMaxContextTokens)
        assertNull(decision?.effectiveMaxOutputTokens)
    }

    @Test
    fun `输出上限过大只降低本次输出参数`() {
        val request = request(
            messages = listOf(SimpleTextApiMessage(id = "u1", role = "user", content = "问题"))
        )

        val decision = ContextRecoveryPolicy.recover(
            request = request,
            error = ProviderErrorInfo(
                status = 400,
                code = "invalid_value",
                parameter = "max_output_tokens",
                message = "too high",
                maxOutputTokens = 4_096,
            ),
            hasPartialOutput = false,
            attemptedCategories = emptySet(),
        )

        assertEquals(RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH, decision?.category)
        assertEquals(request.messages, decision?.request?.messages)
        assertEquals(4_096, decision?.request?.generationConfig?.maxOutputTokens)
        assertEquals(4_096, decision?.effectiveMaxOutputTokens)
        assertNull(decision?.effectiveMaxContextTokens)
    }

    @Test
    fun `部分输出重复恢复和非上下文错误都不会自动重发`() {
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(id = "u1", role = "user", content = "旧问题"),
                SimpleTextApiMessage(id = "a1", role = "assistant", content = "旧回答"),
                SimpleTextApiMessage(id = "u2", role = "user", content = "最新问题"),
            )
        )
        val inputError = ProviderErrorInfo(
            status = 400,
            code = "context_length_exceeded",
            message = "too long",
        )

        assertNull(
            ContextRecoveryPolicy.recover(request, inputError, true, emptySet())
        )
        assertNull(
            ContextRecoveryPolicy.recover(
                request,
                inputError,
                false,
                setOf(RequestErrorCategory.INPUT_CONTEXT_TOO_LONG),
            )
        )
        assertEquals(
            RequestErrorCategory.NETWORK,
            ContextErrorClassifier.classify(IOException("timeout")),
        )
        assertNull(
            ContextRecoveryPolicy.recover(
                request,
                ProviderErrorInfo(status = 429, message = "rate limited"),
                false,
                emptySet(),
            )
        )
    }

    private fun request(messages: List<SimpleTextApiMessage>): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = "OpenAI",
        apiAddress = "https://example.test",
        apiKey = "test-key",
        model = "test-model",
        generationConfig = GenerationConfig(maxOutputTokens = 8_192),
    )
}
