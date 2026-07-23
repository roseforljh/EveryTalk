package com.android.everytalk.ui.screens.viewmodel

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class DataPersistenceInlineImageMigrationTest {

    @Test
    fun `引用扫描覆盖图片地址和持久化附件路径且忽略内联音频`() {
        val messages = listOf(
            Message(
                id = "message-1",
                text = "附件",
                sender = Sender.User,
                imageUrls = listOf("file:///files/chat_attachments/url.png", "https://example.com/a.png"),
                attachments = listOf(
                    SelectedMediaItem.GenericFile(
                        uri = Uri.parse("content://document/file"),
                        id = "file",
                        displayName = "file.pdf",
                        mimeType = "application/pdf",
                        filePath = "/files/chat_attachments/file.pdf",
                    ),
                    SelectedMediaItem.ImageFromUri(
                        uri = Uri.parse("content://image/1"),
                        id = "uri-image",
                        filePath = "/files/chat_attachments/uri.png",
                    ),
                    SelectedMediaItem.ImageFromBitmap(
                        bitmapData = "",
                        id = "bitmap",
                        filePath = "/files/chat_attachments/bitmap.png",
                    ),
                    SelectedMediaItem.Audio(
                        id = "audio",
                        mimeType = "audio/wav",
                        data = "/files/chat_attachments/audio.wav",
                    ),
                ),
            )
        )

        val expectedPaths = setOf(
                "/files/chat_attachments/url.png",
                "/files/chat_attachments/file.pdf",
                "/files/chat_attachments/uri.png",
                "/files/chat_attachments/bitmap.png",
            ).mapTo(mutableSetOf()) { File(it).canonicalPath }

        assertEquals(
            expectedPaths,
            collectReferencedAttachmentPaths(messages),
        )
    }

    @Test
    fun `图像配置去重后会话绑定迁移到保留配置ID`() {
        val mapping = mapOf(
            "text-history" to "text-config",
            "image-history-a" to "duplicate-image-config",
            "image-history-b" to "retained-image-config",
        )

        assertEquals(
            mapOf(
                "text-history" to "text-config",
                "image-history-a" to "retained-image-config",
                "image-history-b" to "retained-image-config",
            ),
            migrateApiConfigIds(
                mapping,
                mapOf("duplicate-image-config" to "retained-image-config"),
            ),
        )
    }

    @Test
    fun `旧图片来源按消息去重归档并替换正文元数据和 parts`() = runTest {
        val source = "data:image/png;base64,QUJDRA=="
        val original = listOf(
            Message(
                id = "message-1",
                text = "结果：\n\n![旧图]($source)",
                sender = Sender.AI,
                imageUrls = listOf(source),
                parts = listOf(
                    MarkdownPart.Text(id = "text", content = "结果：\n\n![旧图]($source)"),
                    MarkdownPart.InlineImage(id = "image", mimeType = "image/png", base64Data = "QUJDRA=="),
                ),
            )
        )
        val persistedSources = mutableListOf<String>()

        val result = migrateConversationInlineImages(
            messages = original,
            persistSource = { incoming, _, _ ->
                persistedSources += incoming
                "/files/chat_attachments/migrated.png"
            },
        )

        assertFalse(result.failed)
        assertTrue(result.changed)
        assertEquals(listOf(source), persistedSources)
        assertFalse(result.messages.single().text.contains("data:image", ignoreCase = true))
        assertEquals(listOf("/files/chat_attachments/migrated.png"), result.messages.single().imageUrls)
        assertTrue(result.messages.single().parts.none { it is MarkdownPart.InlineImage })
    }

    @Test
    fun `任一图片迁移失败时返回原会话并清理本轮文件`() = runTest {
        val first = "data:image/png;base64,QUJDRA=="
        val second = "data:image/png;base64,RUZHSA=="
        val original = listOf(
            Message(
                id = "message-1",
                text = "![一]($first)\n![二]($second)",
                sender = Sender.AI,
            )
        )
        val deleted = mutableListOf<String>()

        val result = migrateConversationInlineImages(
            messages = original,
            persistSource = { source, _, _ ->
                if (source == first) "/files/chat_attachments/first.png" else null
            },
            deletePersistedSource = deleted::add,
        )

        assertTrue(result.failed)
        assertEquals(original, result.messages)
        assertEquals(listOf("/files/chat_attachments/first.png"), deleted)
    }

    @Test
    fun `迁移被取消时清理已生成文件并继续传播取消`() = runTest {
        val first = "data:image/png;base64,QUJDRA=="
        val second = "data:image/png;base64,RUZHSA=="
        val original = listOf(
            Message(
                id = "message-1",
                text = "![一]($first)\n![二]($second)",
                sender = Sender.AI,
            )
        )
        val deleted = mutableListOf<String>()
        var cancellationPropagated = false

        try {
            migrateConversationInlineImages(
                messages = original,
                persistSource = { source, _, _ ->
                    if (source == first) "/files/chat_attachments/first.png"
                    else throw CancellationException("取消迁移")
                },
                deletePersistedSource = deleted::add,
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(listOf("/files/chat_attachments/first.png"), deleted)
    }

    @Test
    fun `已迁移会话再次加载保持不变`() = runTest {
        val original = listOf(
            Message(
                id = "message-1",
                text = "![图](/files/chat_attachments/image.png)",
                sender = Sender.AI,
                imageUrls = listOf("/files/chat_attachments/image.png"),
            )
        )
        var persistCalls = 0

        val result = migrateConversationInlineImages(
            messages = original,
            persistSource = { _, _, _ ->
                persistCalls++
                null
            },
        )

        assertFalse(result.failed)
        assertFalse(result.changed)
        assertEquals(original, result.messages)
        assertEquals(0, persistCalls)
    }
}
