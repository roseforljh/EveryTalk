package com.android.everytalk.ui.components.image

import android.net.Uri
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatScreenImagePreviewSelectionTest {

    @Before
    fun setUp() {
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `点击地址不在候选集合时只打开被点击图片`() {
        val selection = buildImagePreviewSelection(
            clickedSource = "https://example.com/clicked.png",
            messages = listOf(
                Message(
                    id = "ai",
                    text = "",
                    sender = Sender.AI,
                    imageUrls = listOf("https://example.com/first.png"),
                )
            ),
        )

        assertEquals(listOf("https://example.com/clicked.png"), selection.candidates)
        assertEquals(0, selection.initialIndex)
    }

    @Test
    fun `候选集合按消息顺序包含用户附件和 AI 图片并去重`() {
        val userImage = "content://media/external/images/1"
        val aiImage = "/data/user/0/com.android.everytalk/files/chat_attachments/generated.png"
        val messages = listOf(
            Message(
                id = "user",
                text = "看看这张图",
                sender = Sender.User,
                attachments = listOf(
                    SelectedMediaItem.ImageFromUri(
                        uri = Uri.parse(userImage),
                        id = "attachment",
                        mimeType = "image/png",
                    )
                ),
            ),
            Message(
                id = "ai",
                text = "已生成",
                sender = Sender.AI,
                imageUrls = listOf(aiImage, aiImage),
            ),
        )

        val selection = buildImagePreviewSelection(aiImage, messages)

        assertEquals(listOf(userImage, aiImage), selection.candidates)
        assertEquals(1, selection.initialIndex)
    }

    @Test
    fun `文件 URI 与绝对路径归一化后匹配同一图片`() {
        val path = "/data/user/0/com.android.everytalk/files/chat_attachments/image 1.png"
        val selection = buildImagePreviewSelection(
            clickedSource = "file:///data/user/0/com.android.everytalk/files/chat_attachments/image%201.png",
            messages = listOf(
                Message(id = "ai", text = "", sender = Sender.AI, imageUrls = listOf(path))
            ),
        )

        assertEquals(listOf(path), selection.candidates)
        assertEquals(0, selection.initialIndex)
    }

    @Test
    fun `位图附件优先使用持久化路径加入候选集合`() {
        val bitmapPath = "/data/user/0/com.android.everytalk/files/chat_attachments/reference.png"
        val generatedImage = "/data/user/0/com.android.everytalk/files/chat_attachments/generated.png"
        val messages = listOf(
            Message(
                id = "user",
                text = "参考图",
                sender = Sender.User,
                attachments = listOf(
                    SelectedMediaItem.ImageFromBitmap(
                        bitmapData = "A".repeat(1024 * 1024),
                        id = "bitmap",
                        filePath = bitmapPath,
                    )
                ),
            ),
            Message(
                id = "ai",
                text = "已生成",
                sender = Sender.AI,
                imageUrls = listOf(generatedImage),
            ),
        )

        val selection = buildImagePreviewSelection(generatedImage, messages)

        assertEquals(listOf(bitmapPath, generatedImage), selection.candidates)
        assertEquals(1, selection.initialIndex)
    }

    @Test
    fun `位图展示源优先使用文件路径并回退到 data URI`() {
        val persisted = SelectedMediaItem.ImageFromBitmap(
            bitmapData = "QUJD",
            id = "persisted",
            mimeType = "image/png",
            filePath = "/data/user/0/com.android.everytalk/files/reference.png",
        )
        val inMemory = SelectedMediaItem.ImageFromBitmap(
            bitmapData = "REVG",
            id = "memory",
            mimeType = "image/jpeg",
        )

        assertEquals("/data/user/0/com.android.everytalk/files/reference.png", persisted.model)
        assertEquals("data:image/jpeg;base64,REVG", inMemory.model)
    }
}
