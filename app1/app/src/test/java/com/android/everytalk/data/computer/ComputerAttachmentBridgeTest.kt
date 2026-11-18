package com.android.everytalk.data.computer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComputerAttachmentBridgeTest {
    @Test
    fun `已有聊天附件可以上传且不泄露给其它会话`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = byteArrayOf(1, 2, 3)
        val file = java.io.File.createTempFile("upload-", ".bin", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val attachment = SelectedMediaItem.GenericFile(
                uri = Uri.fromFile(file), id = "existing-file", displayName = "upload.bin",
                mimeType = "application/octet-stream", filePath = file.absolutePath,
            )
            val bridge = ComputerAttachmentBridge(context, { if (it == "source") listOf(attachment) else emptyList() })
            val source = requireNotNull(bridge.resolveUpload("source", "existing-file"))
            assertEquals(bytes.size.toLong(), source.size)
            assertArrayEquals(bytes, source.openStream().use { it.readBytes() })
            assertNull(bridge.resolveUpload("other", "existing-file"))
            assertNull(bridge.resolveUpload("source", "https://test.trycloudflare.com/download"))
        } finally { file.delete() }
    }

    @Test
    fun `内存图片可按统一附件ID上传到Workspace`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = "workspace-image".toByteArray()
        val attachment = SelectedMediaItem.ImageFromBitmap(
            bitmapData = Base64.encodeToString(bytes, Base64.NO_WRAP),
            id = "image-attachment-1",
            mimeType = "image/png",
        )
        val bridge = ComputerAttachmentBridge(
            context = context,
            attachmentsForConversation = { listOf(attachment) },
        )

        val source = bridge.resolveUpload("conversation-1", attachment.id)

        assertNotNull(source)
        assertEquals(bytes.size.toLong(), source?.size)
        assertEquals("image/png", source?.mimeType)
        assertArrayEquals(bytes, source?.openStream?.invoke()?.use { it.readBytes() })
    }

}
