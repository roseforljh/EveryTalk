package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerGeneratedImageTest {

    @Test
    fun `归档图片同时进入正文和图片元数据`() {
        val message = Message(
            id = "message-1",
            text = "生成完成。",
            sender = Sender.AI,
            reasoning = "已有推理",
        )

        val result = applyGeneratedImageToMessage(
            message = message,
            persistedSource = "/data/user/0/com.android.everytalk/files/chat_attachments/image.png",
        )

        assertEquals(
            listOf("/data/user/0/com.android.everytalk/files/chat_attachments/image.png"),
            result.imageUrls,
        )
        assertTrue(result.text.contains("![Generated Image](/data/user/0/com.android.everytalk/files/chat_attachments/image.png)"))
        assertTrue(result.contentStarted)
        assertEquals("已有推理", result.reasoning)
    }

    @Test
    fun `重复图片事件不会重复追加正文或元数据`() {
        val source = "/data/user/0/com.android.everytalk/files/chat_attachments/image.png"
        val once = applyGeneratedImageToMessage(
            message = Message(id = "message-1", text = "正文", sender = Sender.AI),
            persistedSource = source,
        )

        val twice = applyGeneratedImageToMessage(once, source)

        assertEquals(once, twice)
        assertEquals(1, twice.imageUrls?.size)
        assertEquals(1, twice.text.split("![Generated Image]").size - 1)
    }
}
