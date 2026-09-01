package com.android.everytalk.data.computer

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComputerAttachmentBridgeTest {
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
