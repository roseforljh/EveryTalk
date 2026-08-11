package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProviderSafetyResponseTest {
    @Before
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `Gemini prompt and candidate safety reasons are recognized`() {
        val promptBlocked = Json.parseToJsonElement(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
        ).jsonObject
        val candidateBlocked = Json.parseToJsonElement(
            """{"candidates":[{"finishReason":"PROHIBITED_CONTENT"}]}""",
        ).jsonObject
        val allowed = Json.parseToJsonElement(
            """{"candidates":[{"finishReason":"STOP"}]}""",
        ).jsonObject

        assertEquals("SAFETY", ProviderSafetyResponse.geminiBlockReason(promptBlocked))
        assertEquals("PROHIBITED_CONTENT", ProviderSafetyResponse.geminiBlockReason(candidateBlocked))
        assertEquals(null, ProviderSafetyResponse.geminiBlockReason(allowed))
    }

    @Test
    fun `Gemini blocked stream emits typed safety error without content`() = runBlocking {
        withHttpClient(
            contentType = ContentType.Text.EventStream.toString(),
            body = "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\ndata: [DONE]\n\n",
        ) { client ->
            val events = GeminiDirectClient.streamChatDirect(client, chatRequest("Gemini", "Gemini")).toList()
            assertSafetyBlocked(events, "SAFETY")
        }
    }

    @Test
    fun `OpenAI chat content filter emits typed safety error without content`() = runBlocking {
        withHttpClient(
            contentType = ContentType.Text.EventStream.toString(),
            body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n",
        ) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, chatRequest("OpenAI", "OpenAI")).toList()
            assertSafetyBlocked(events, "content_filter")
        }
    }

    @Test
    fun `OpenAI responses content filter emits typed safety error without content`() = runBlocking {
        withHttpClient(
            contentType = ContentType.Text.EventStream.toString(),
            body = "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}\n\n",
        ) { client ->
            val events = OpenAIResponsesClient.streamChatResponses(
                client,
                chatRequest("OpenAI", "OpenAI"),
            ).toList()
            assertSafetyBlocked(events, "content_filter")
        }
    }

    @Test
    fun `Gemini blocked image response throws safety exception`() = runBlocking {
        withHttpClient(
            contentType = ContentType.Application.Json.toString(),
            body = """{"promptFeedback":{"blockReason":"IMAGE_SAFETY"}}""",
        ) { client ->
            try {
                ImageGenerationDirectClient.generateImageGemini(
                    client = client,
                    request = ImageGenRequest(
                        model = "gemini-test-image",
                        prompt = "test",
                        apiAddress = "https://test.invalid",
                        apiKey = "test-key",
                    ),
                )
                fail("应抛出 AI 内容安全拦截异常")
            } catch (error: AiContentSafetyBlockedException) {
                assertEquals("IMAGE_SAFETY", error.providerReason)
                assertEquals(AI_CONTENT_SAFETY_BLOCKED_MESSAGE, error.message)
            }
        }
    }

    private fun assertSafetyBlocked(events: List<AppStreamEvent>, reason: String) {
        val error = events.filterIsInstance<AppStreamEvent.Error>().single()
        assertEquals(AI_CONTENT_SAFETY_ERROR_TYPE, error.type)
        assertEquals(reason, error.code)
        assertEquals(AI_CONTENT_SAFETY_BLOCKED_MESSAGE, error.message)
        assertFalse(events.any { it is AppStreamEvent.Content || it is AppStreamEvent.ContentFinal })
        assertTrue(events.any { it is AppStreamEvent.Finish })
    }

    private fun chatRequest(provider: String, channel: String) = ChatRequest(
        messages = listOf(SimpleTextApiMessage(role = "user", content = "hello")),
        provider = provider,
        channel = channel,
        apiAddress = "https://test.invalid",
        apiKey = "test-key",
        model = "test-model",
    )

    private suspend fun <T> withHttpClient(
        contentType: String,
        body: String,
        block: suspend (HttpClient) -> T,
    ): T {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(bytes),
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentLength, bytes.size.toString())
                },
            )
        })
        return try {
            block(client)
        } finally {
            client.close()
        }
    }
}
