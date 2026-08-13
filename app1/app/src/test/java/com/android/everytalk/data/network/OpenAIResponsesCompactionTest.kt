package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OpenAIResponsesCompactionTest {
    @Before
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun restoreState() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `服务端compaction及后续输出作为权威状态发布`() = runBlocking {
        val compaction =
            "{\"id\":\"cmp-1\",\"type\":\"compaction\",\"encrypted_content\":\"opaque\"}"
        val message =
            "{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[]}"
        val body = buildString {
            appendResponsesEvent("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$compaction}")
            appendResponsesEvent("{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":$message}")
            appendResponsesEvent(
                "{\"type\":\"response.completed\",\"response\":{\"output\":[$compaction,$message],\"usage\":{\"input_tokens\":900,\"output_tokens\":20,\"total_tokens\":920}}}"
            )
            append("data: [DONE]\n\n")
        }

        withClient { _ -> success(body) }.use { client ->
            val events = OpenAIResponsesClient.streamChatResponses(client, request()).toList()
            val native = events.filterIsInstance<AppStreamEvent.NativeContextCompaction>().single()
            val authoritative = Json.parseToJsonElement(native.inputJson).jsonArray

            assertEquals(listOf("compaction", "message"), authoritative.map {
                it.jsonObject.getValue("type").jsonPrimitive.content
            })
            assertEquals("cmp-1", native.compactionItemId)
            assertTrue(native.estimatedTokens > 0)
            assertEquals("turn_complete", events.filterIsInstance<AppStreamEvent.Finish>().single().reason)
        }
    }

    @Test
    fun `原生字段不兼容时本轮降级并清除旧权威状态`() = runBlocking {
        val requestCount = AtomicInteger()
        val payloads = mutableListOf<String>()
        val completed = buildString {
            appendResponsesEvent("""{"type":"response.completed","response":{"output":[]}}""")
            append("data: [DONE]\n\n")
        }
        val client = withClient { requestData ->
            payloads += (requestData.body as TextContent).text
            if (requestCount.incrementAndGet() == 1) {
                response(
                    status = HttpStatusCode.BadRequest,
                    body = """{"error":{"message":"Unknown parameter: context_management"}}""",
                    contentType = ContentType.Application.Json,
                )
            } else {
                success(completed)
            }
        }

        client.use {
            val events = OpenAIResponsesClient.streamChatResponses(
                it,
                request(restoredState = restoredState()),
            ).toList()
            val reset = events.filterIsInstance<AppStreamEvent.NativeContextCompaction>().single()

            assertTrue(payloads[0].contains("context_management"))
            assertFalse(payloads[1].contains("context_management"))
            assertTrue(payloads.all { payload -> payload.contains("\"store\":false") })
            assertTrue(reset.reset)
            assertFalse(events.any { event -> event is AppStreamEvent.Error })
            assertEquals(2, requestCount.get())
        }
    }

    private fun request(
        restoredState: ContextCompressionState? = null,
    ): ChatRequest = ChatRequest(
        messages = listOf(SimpleTextApiMessage(id = "user-new", role = "user", content = "继续")),
        provider = "OpenAI",
        channel = "codex",
        apiAddress = "https://api.openai.com",
        apiKey = "test-key",
        model = "gpt-5.6",
        contextManagement = RequestContextManagement(
            configId = "config-1",
            maxContextTokens = 100_000,
            reservedOutputTokens = 10_000,
            compactThresholdTokens = 90_000,
            autoCompressionEnabled = true,
            restoredState = restoredState,
        ),
    )

    private fun restoredState(): ContextCompressionState = ContextCompressionState(
        configId = "config-1",
        provider = "OpenAI",
        channel = "codex",
        model = "gpt-5.6",
        windowId = "window-1",
        openAiResponsesInputJson =
            "[{\"id\":\"cmp-old\",\"type\":\"compaction\",\"encrypted_content\":\"old\"}]",
        openAiResponsesThroughMessageId = "assistant-old",
        openAiResponsesEstimatedTokens = 100,
    )

    private fun withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(HttpTimeout)
    }

    private fun MockRequestHandleScope.success(body: String) = response(
        status = HttpStatusCode.OK,
        body = body,
        contentType = ContentType.Text.EventStream,
    )

    private fun MockRequestHandleScope.response(
        status: HttpStatusCode,
        body: String,
        contentType: ContentType,
    ) = respond(
        content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
        status = status,
        headers = Headers.build { append(HttpHeaders.ContentType, contentType.toString()) },
    )

    private fun StringBuilder.appendResponsesEvent(json: String) {
        append("data: ")
        append(json)
        append("\n\n")
    }
}
