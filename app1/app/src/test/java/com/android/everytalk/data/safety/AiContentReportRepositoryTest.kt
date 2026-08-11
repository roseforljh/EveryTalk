package com.android.everytalk.data.safety

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AiContentReportRepositoryTest {
    private lateinit var context: Context
    private lateinit var reportDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reportDirectory = File(context.filesDir, "safety")
        reportDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        reportDirectory.deleteRecursively()
    }

    @Test
    fun `successful submission sends minimal payload and clears local sensitive text`() = runTest {
        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("reports.example.com", request.url.host)
            assertEquals("1", request.headers["X-EveryTalk-Report-Version"])
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("EveryTalk/"))
            requestBodies += (request.body as TextContent).text
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = reportClient(engine)

        try {
            val repository = AiContentReportRepository(
                context = context,
                httpClient = client,
                reportEndpoint = "https://reports.example.com/ai-content-reports",
            )
            val result = repository.submit(
                message = aiMessage(
                    text = "违规\u0000回复",
                    imageUrls = listOf("https://private.example/generated.png"),
                ),
                category = AiContentReportCategory.OTHER,
                details = "需要复核\u0001",
                isImageGeneration = true,
            )

            assertEquals(AiContentReportSubmissionResult.Submitted, result)
            assertEquals(1, requestBodies.size)
            val payload = Json.parseToJsonElement(requestBodies.single()).jsonObject
            assertEquals("违规回复", payload.getValue("messageText").jsonPrimitive.content)
            assertEquals("需要复核", payload.getValue("details").jsonPrimitive.content)
            assertEquals(1, payload.getValue("imageCount").jsonPrimitive.content.toInt())
            assertFalse(requestBodies.single().contains("private.example"))

            val stored = File(reportDirectory, "ai_content_reports.json").readText(Charsets.UTF_8)
            assertTrue(stored.contains("\"deliveryState\":\"SUBMITTED\""))
            assertFalse(stored.contains("违规回复"))
            assertFalse(stored.contains("需要复核"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `failed submission is retried and duplicate message is not sent twice`() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(
                content = "",
                status = if (requestCount == 1) {
                    HttpStatusCode.ServiceUnavailable
                } else {
                    HttpStatusCode.NoContent
                },
            )
        }
        val client = reportClient(engine)

        try {
            val repository = AiContentReportRepository(
                context = context,
                httpClient = client,
                reportEndpoint = "https://reports.example.com/ai-content-reports",
            )
            val message = aiMessage(text = "待重试内容")

            assertEquals(
                AiContentReportSubmissionResult.QueuedForRetry,
                repository.submit(message, AiContentReportCategory.HATE_HARASSMENT, "", false),
            )
            repository.retryPendingReports()
            assertEquals(2, requestCount)
            assertEquals(
                AiContentReportSubmissionResult.AlreadyReported,
                repository.submit(message, AiContentReportCategory.OTHER, "重复", false),
            )
            assertEquals(2, requestCount)

            val stored = File(reportDirectory, "ai_content_reports.json").readText(Charsets.UTF_8)
            assertTrue(stored.contains("\"deliveryState\":\"SUBMITTED\""))
            assertFalse(stored.contains("待重试内容"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `invalid or missing receiver keeps report local without a network call`() = runTest {
        var requestCount = 0
        val client = reportClient(MockEngine {
            requestCount++
            respond(content = "", status = HttpStatusCode.NoContent)
        })

        try {
            val repository = AiContentReportRepository(
                context = context,
                httpClient = client,
                reportEndpoint = "http://reports.example.com/insecure",
            )
            val result = repository.submit(
                aiMessage(text = "本地标记"),
                AiContentReportCategory.OTHER,
                "",
                false,
            )

            assertEquals(AiContentReportSubmissionResult.SavedLocally, result)
            assertEquals(0, requestCount)
            val stored = File(reportDirectory, "ai_content_reports.json").readText(Charsets.UTF_8)
            assertTrue(stored.contains("本地标记"))
            assertTrue(stored.contains("\"deliveryState\":\"PENDING\""))
        } finally {
            client.close()
        }
    }

    private fun aiMessage(
        text: String,
        imageUrls: List<String>? = null,
    ): Message = Message(
        id = "ai-message-1",
        text = text,
        sender = Sender.AI,
        imageUrls = imageUrls,
        modelName = "test-model",
        providerName = "test-provider",
    )

    private fun reportClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { encodeDefaults = true })
        }
    }
}
