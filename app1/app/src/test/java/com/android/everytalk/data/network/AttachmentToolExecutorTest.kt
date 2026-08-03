package com.android.everytalk.data.network

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.models.ATTACHMENT_CONTENT_PAGE_MARKER
import com.android.everytalk.models.ATTACHMENT_MANIFEST_MARKER
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.models.toAttachmentContextParts
import com.android.everytalk.statecontroller.compressOversizedLatestUserTurn
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentToolExecutorTest {
    private lateinit var context: Context
    private lateinit var attachmentDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        attachmentDir = File(context.filesDir, "chat_attachments").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        attachmentDir.deleteRecursively()
    }

    @Test
    fun `8KB HTML按附件ID完整读取`() = runTest {
        val html = "<html><body>" + "正文内容".repeat(1_020) + "</body></html>"
        val file = File(attachmentDir, "small.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-1",
            displayName = "small.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )

        val result = AttachmentToolExecutor.execute(
            context = context,
            attachments = listOf(attachment),
            arguments = buildJsonObject {
                put("attachment_id", JsonPrimitive(attachment.id))
                put("max_chars", JsonPrimitive(10_000))
            },
        )

        assertEquals(true, result["ok"]?.jsonPrimitive?.boolean)
        assertEquals(html, result["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(file.length(), result["source_size_bytes"]?.jsonPrimitive?.contentOrNull?.toLong())
        assertNull(result["next_offset"])
    }

    @Test
    fun `超长单行HTML使用nextOffset无损续读`() = runTest {
        val html = "<html>" + "中文😀内容".repeat(20) + "</html>"
        val file = File(attachmentDir, "long.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-long",
            displayName = "long.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )
        val first = AttachmentToolExecutor.execute(
            context,
            listOf(attachment),
            buildJsonObject {
                put("attachment_id", JsonPrimitive(attachment.id))
                put("max_chars", JsonPrimitive(20))
            },
        )
        val nextOffset = first["next_offset"]?.jsonPrimitive?.contentOrNull?.toLong()
        val second = AttachmentToolExecutor.execute(
            context,
            listOf(attachment),
            buildJsonObject {
                put("attachment_id", JsonPrimitive(attachment.id))
                put("offset", JsonPrimitive(checkNotNull(nextOffset)))
                put("max_chars", JsonPrimitive(10_000))
            },
        )

        assertEquals(
            html,
            first["content"]?.jsonPrimitive?.contentOrNull +
                second["content"]?.jsonPrimitive?.contentOrNull,
        )
        assertNull(second["next_offset"])
    }

    @Test
    fun `超过20万字符HTML可分多页完整还原`() = runTest {
        val html = "<html>" + "中文😀内容".repeat(50_000) + "</html>"
        val file = File(attachmentDir, "over-200k.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-over-200k",
            displayName = "over-200k.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )
        val restored = StringBuilder()
        var offset = 0L
        var pageCount = 0

        while (true) {
            val result = AttachmentToolExecutor.execute(
                context,
                listOf(attachment),
                buildJsonObject {
                    put("attachment_id", JsonPrimitive(attachment.id))
                    put("offset", JsonPrimitive(offset))
                    put("max_chars", JsonPrimitive(60_000))
                },
            )
            assertEquals(true, result["ok"]?.jsonPrimitive?.boolean)
            restored.append(result["content"]?.jsonPrimitive?.contentOrNull)
            pageCount++
            val nextOffset = result["next_offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: break
            assertTrue(nextOffset > offset)
            offset = nextOffset
            assertTrue(pageCount < 100)
        }

        assertTrue(pageCount > 3)
        assertEquals(html, restored.toString())
    }

    @Test
    fun `maxChars超过上限时限制为安全页大小`() = runTest {
        val content = "a".repeat(100_100)
        val file = File(attachmentDir, "max-chars.txt").apply { writeText(content, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-max-chars",
            displayName = "max-chars.txt",
            mimeType = "text/plain",
            filePath = file.absolutePath,
        )

        val result = AttachmentToolExecutor.execute(
            context,
            listOf(attachment),
            buildJsonObject {
                put("attachment_id", JsonPrimitive(attachment.id))
                put("max_chars", JsonPrimitive(Long.MAX_VALUE))
            },
        )

        assertEquals(12_000, result["content"]?.jsonPrimitive?.contentOrNull?.length)
        assertEquals("12000", result["next_offset"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `拒绝读取当前会话之外的附件ID`() = runTest {
        val result = AttachmentToolExecutor.execute(
            context,
            attachments = emptyList(),
            arguments = buildJsonObject {
                put("attachment_id", JsonPrimitive("other-session-attachment"))
            },
        )

        assertEquals(false, result["ok"]?.jsonPrimitive?.boolean)
        assertTrue(result["error"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("当前会话"))
    }

    @Test
    fun `拒绝非整数分页参数`() = runTest {
        val result = AttachmentToolExecutor.execute(
            context,
            attachments = emptyList(),
            arguments = buildJsonObject {
                put("attachment_id", JsonPrimitive("attachment-1"))
                put("offset", JsonObject(emptyMap()))
            },
        )

        assertEquals(false, result["ok"]?.jsonPrimitive?.boolean)
        assertTrue(result["error"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("整数"))
    }

    @Test
    fun `拒绝读取附件目录之外的文件`() = runTest {
        val outsideFile = File(context.filesDir, "outside-attachment.html")
            .apply { writeText("敏感内容", Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(outsideFile),
            id = "attachment-outside",
            displayName = outsideFile.name,
            mimeType = "text/html",
            filePath = outsideFile.absolutePath,
        )

        try {
            val result = AttachmentToolExecutor.execute(
                context,
                listOf(attachment),
                buildJsonObject { put("attachment_id", JsonPrimitive(attachment.id)) },
            )

            assertEquals(false, result["ok"]?.jsonPrimitive?.boolean)
            assertTrue(result["error"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("不可访问"))
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun `当前8KB HTML全文和可续读句柄同时进入请求`() = runTest {
        val html = "<html><body>" + "分析材料".repeat(1_000) + "</body></html>"
        val file = File(attachmentDir, "request.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-request",
            displayName = "request.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )

        val result = buildDirectMultimodalRequest(
            request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "分析这个")),
                provider = "test",
                channel = "OpenAI兼容",
                apiAddress = "https://example.com/v1",
                apiKey = "",
                model = "test-model",
            ),
            attachments = listOf(attachment),
            context = context,
        )
        val content = (result.messages.single() as PartsApiMessage).parts
            .filterIsInstance<ApiContentPart.Text>()
            .joinToString("\n") { it.text }

        assertEquals(true, html in content)
        assertEquals(true, "attachment_id: ${attachment.id}" in content)
        assertEquals(true, "read_attachment" in content)
    }

    @Test
    fun `当前消息已有附件清单时不会重复注入`() = runTest {
        val file = File(attachmentDir, "deduplicate.html").apply {
            writeText("<html>去重测试</html>", Charsets.UTF_8)
        }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-deduplicate",
            displayName = "deduplicate.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )
        val existingManifest = attachment.toAttachmentContextParts().single()
        val result = buildDirectMultimodalRequest(
            request = ChatRequest(
                messages = listOf(
                    PartsApiMessage(
                        role = "user",
                        parts = listOf(
                            ApiContentPart.Text("分析附件"),
                            ApiContentPart.Text(existingManifest),
                        ),
                    )
                ),
                provider = "test",
                channel = "OpenAI兼容",
                apiAddress = "https://example.com/v1",
                apiKey = "",
                model = "test-model",
            ),
            attachments = listOf(attachment),
            context = context,
        )

        val manifests = (result.messages.single() as PartsApiMessage).parts
            .filterIsInstance<ApiContentPart.Text>()
            .count { it.text.startsWith(ATTACHMENT_MANIFEST_MARKER) }
        assertEquals(1, manifests)
    }

    @Test
    fun `长HTML首轮只注入安全大小的内容页`() = runTest {
        val html = "<html>" + "附件正文".repeat(20_000) + "</html>"
        val file = File(attachmentDir, "initial-page.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-initial-page",
            displayName = "initial-page.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )
        val result = buildDirectMultimodalRequest(
            request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "分析附件")),
                provider = "test",
                channel = "OpenAI兼容",
                apiAddress = "https://example.com/v1",
                apiKey = "",
                model = "test-model",
            ),
            attachments = listOf(attachment),
            context = context,
        )
        val contentPage = (result.messages.single() as PartsApiMessage).parts
            .filterIsInstance<ApiContentPart.Text>()
            .single { it.text.startsWith(ATTACHMENT_CONTENT_PAGE_MARKER) }
            .text

        assertTrue(html.take(12_000) in contentPage)
        assertTrue(html.take(12_001) !in contentPage)
        assertTrue("next_offset: 12000" in contentPage)
    }

    @Test
    fun `超长HTML自动压缩后保留读取句柄且不触发二十万字符错误`() = runTest {
        val html = "<html><body>" + "超长附件内容".repeat(40_000) + "</body></html>"
        val file = File(attachmentDir, "compression.html").apply { writeText(html, Charsets.UTF_8) }
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.fromFile(file),
            id = "attachment-compression",
            displayName = "compression.html",
            mimeType = "text/html",
            filePath = file.absolutePath,
        )
        val request = buildDirectMultimodalRequest(
            request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "分析附件")),
                provider = "test",
                channel = "OpenAI兼容",
                apiAddress = "https://example.com/v1",
                apiKey = "",
                model = "test-model",
            ),
            attachments = listOf(attachment),
            context = context,
        )

        val compressed = compressOversizedLatestUserTurn(
            messages = request.messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 256, maxContextTokens = 6_000),
        ) { _, _ -> "分析超长附件" }
        val textParts = (compressed.single() as PartsApiMessage).parts
            .filterIsInstance<ApiContentPart.Text>()
            .map(ApiContentPart.Text::text)

        assertTrue(textParts.any { it.startsWith(ATTACHMENT_MANIFEST_MARKER) })
        assertTrue(textParts.none { it.startsWith(ATTACHMENT_CONTENT_PAGE_MARKER) })
        assertTrue(textParts.any { it.contains("分析超长附件") })
    }
}
