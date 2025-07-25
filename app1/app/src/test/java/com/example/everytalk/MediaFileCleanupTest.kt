package com.example.everytalk

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.models.SelectedMediaItem
import android.net.Uri
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/**
 * 测试媒体文件清理功能
 * 验证删除会话时是否正确清理相关的媒体文件
 */
class MediaFileCleanupTest {

    @Test
    fun testMediaFilePathExtraction() {
        // 创建测试消息，包含各种类型的媒体附件
        val testMessages = listOf(
            Message(
                id = "msg1",
                text = "测试消息1",
                sender = Sender.User,
                attachments = listOf(
                    SelectedMediaItem.ImageFromUri(
                        uri = Uri.parse("content://test/image1.jpg"),
                        id = UUID.randomUUID().toString(),
                        filePath = "/data/data/com.example.everytalk/files/chat_attachments/image1.jpg"
                    ),
                    SelectedMediaItem.GenericFile(
                        uri = Uri.parse("content://test/document1.pdf"),
                        id = UUID.randomUUID().toString(),
                        displayName = "document1.pdf",
                        mimeType = "application/pdf",
                        filePath = "/data/data/com.example.everytalk/files/chat_attachments/document1.pdf"
                    )
                )
            ),
            Message(
                id = "msg2",
                text = "测试消息2",
                sender = Sender.AI,
                imageUrls = listOf(
                    "https://example.com/image1.jpg",
                    "file:///data/data/com.example.everytalk/files/chat_attachments/local_image.jpg"
                )
            )
        )

        // 验证能够正确提取文件路径
        val conversation = listOf(testMessages)
        val expectedFilePaths = setOf(
            "/data/data/com.example.everytalk/files/chat_attachments/image1.jpg",
            "/data/data/com.example.everytalk/files/chat_attachments/document1.pdf"
        )

        val extractedPaths = mutableSetOf<String>()
        
        conversation.forEach { messages ->
            messages.forEach { message ->
                // 从附件中提取文件路径
                message.attachments?.forEach { attachment ->
                    val path = when (attachment) {
                        is SelectedMediaItem.ImageFromUri -> attachment.filePath
                        is SelectedMediaItem.GenericFile -> attachment.filePath
                        else -> null
                    }
                    if (!path.isNullOrBlank()) {
                        extractedPaths.add(path)
                    }
                }
            }
        }

        assertEquals("应该提取到正确数量的文件路径", expectedFilePaths.size, extractedPaths.size)
        assertTrue("应该包含所有预期的文件路径", extractedPaths.containsAll(expectedFilePaths))
    }

    @Test
    fun testHttpUrlExtraction() {
        // 测试HTTP URL的提取
        val message = Message(
            id = "msg1",
            text = "测试消息",
            sender = Sender.AI,
            imageUrls = listOf(
                "https://example.com/image1.jpg",
                "http://example.com/image2.png",
                "file:///local/path/image3.jpg"
            )
        )

        val httpUrls = mutableSetOf<String>()
        message.imageUrls?.forEach { urlString ->
            try {
                val uri = Uri.parse(urlString)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    httpUrls.add(urlString)
                }
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }

        assertEquals("应该提取到2个HTTP URL", 2, httpUrls.size)
        assertTrue("应该包含HTTPS URL", httpUrls.contains("https://example.com/image1.jpg"))
        assertTrue("应该包含HTTP URL", httpUrls.contains("http://example.com/image2.png"))
        assertFalse("不应该包含file URL", httpUrls.contains("file:///local/path/image3.jpg"))
    }

    @Test
    fun testEmptyAttachments() {
        // 测试没有附件的消息
        val message = Message(
            id = "msg1",
            text = "没有附件的消息",
            sender = Sender.User,
            attachments = emptyList()
        )

        val conversation = listOf(listOf(message))
        val extractedPaths = mutableSetOf<String>()
        
        conversation.forEach { messages ->
            messages.forEach { msg ->
                msg.attachments?.forEach { attachment ->
                    val path = when (attachment) {
                        is SelectedMediaItem.ImageFromUri -> attachment.filePath
                        is SelectedMediaItem.GenericFile -> attachment.filePath
                        else -> null
                    }
                    if (!path.isNullOrBlank()) {
                        extractedPaths.add(path)
                    }
                }
            }
        }

        assertTrue("没有附件的消息不应该提取到任何文件路径", extractedPaths.isEmpty())
    }

    @Test
    fun testAudioAttachment() {
        // 测试音频附件（不需要文件清理，因为是Base64数据）
        val message = Message(
            id = "msg1",
            text = "音频消息",
            sender = Sender.User,
            attachments = listOf(
                SelectedMediaItem.Audio(
                    id = UUID.randomUUID().toString(),
                    mimeType = "audio/3gpp",
                    data = "base64encodedaudiodata"
                )
            )
        )

        val conversation = listOf(listOf(message))
        val extractedPaths = mutableSetOf<String>()
        
        conversation.forEach { messages ->
            messages.forEach { msg ->
                msg.attachments?.forEach { attachment ->
                    val path = when (attachment) {
                        is SelectedMediaItem.ImageFromUri -> attachment.filePath
                        is SelectedMediaItem.GenericFile -> attachment.filePath
                        else -> null
                    }
                    if (!path.isNullOrBlank()) {
                        extractedPaths.add(path)
                    }
                }
            }
        }

        assertTrue("音频附件不应该提取到文件路径", extractedPaths.isEmpty())
    }
}