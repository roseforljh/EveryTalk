package com.android.everytalk.data.DataClass

import android.content.Context
import android.net.Uri
import com.android.everytalk.models.SelectedMediaItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MessageBitmapApiConversionTest {

    @Test
    fun `位图附件直接复用原始 Base64`() {
        val bitmapData = "QUJDREVGRw=="
        val message = Message(
            text = "",
            sender = Sender.User,
            attachments = listOf(
                SelectedMediaItem.ImageFromBitmap(
                    bitmapData = bitmapData,
                    id = "bitmap",
                    mimeType = "image/png",
                )
            ),
        )

        val apiMessages = listOf(
            message.toApiMessage(uriEncoder = { error("不应编码 URI") }) as PartsApiMessage,
            message.toApiMessage(
                uriEncoder = { error("不应编码 URI") },
                context = mockk<Context>(relaxed = true),
            ) as PartsApiMessage,
        )

        apiMessages.forEach { apiMessage ->
            val inlineData = apiMessage.parts.single() as ApiContentPart.InlineData
            assertEquals(bitmapData, inlineData.base64Data)
            assertEquals("image/png", inlineData.mimeType)
        }
    }

    @Test
    fun `空位图数据从持久化文件恢复`() {
        val imageFile = Files.createTempFile("message-bitmap", ".png").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val encodedUris = mutableListOf<Uri>()
        val uriEncoder: (Uri) -> String? = { uri ->
            encodedUris += uri
            "AQID"
        }
        val message = Message(
            text = "",
            sender = Sender.User,
            attachments = listOf(
                SelectedMediaItem.ImageFromBitmap(
                    bitmapData = "",
                    id = "legacy-file-only",
                    filePath = imageFile.absolutePath,
                )
            ),
        )

        try {
            val apiMessages = listOf(
                message.toApiMessage(uriEncoder = uriEncoder) as PartsApiMessage,
                message.toApiMessage(
                    uriEncoder = uriEncoder,
                    context = mockk<Context>(relaxed = true),
                ) as PartsApiMessage,
            )

            apiMessages.forEach { apiMessage ->
                val inlineData = apiMessage.parts.single() as ApiContentPart.InlineData
                assertEquals("AQID", inlineData.base64Data)
                assertEquals("image/png", inlineData.mimeType)
            }
            assertEquals(listOf(Uri.fromFile(imageFile), Uri.fromFile(imageFile)), encodedUris)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `空位图数据且文件无效时不会生成图片 part`() {
        val message = Message(
            text = "正文",
            sender = Sender.User,
            attachments = listOf(
                SelectedMediaItem.ImageFromBitmap(
                    bitmapData = "",
                    id = "legacy-file-only",
                    filePath = "/data/user/0/com.android.everytalk/files/legacy.png",
                )
            ),
        )

        val apiMessages = listOf(
            message.toApiMessage(uriEncoder = { error("不应编码 URI") }) as PartsApiMessage,
            message.toApiMessage(
                uriEncoder = { error("不应编码 URI") },
                context = mockk<Context>(relaxed = true),
            ) as PartsApiMessage,
        )

        apiMessages.forEach { apiMessage ->
            assertEquals(listOf(ApiContentPart.Text("正文")), apiMessage.parts)
            assertTrue(apiMessage.parts.none { it is ApiContentPart.InlineData })
        }
    }
}
