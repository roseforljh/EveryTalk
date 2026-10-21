package com.android.everytalk.data.computer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.models.SelectedMediaItem
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComputerAttachmentBridgeTest {
    @Before
    fun setUp() {
        DownloadTestProvider.install(ApplicationProvider.getApplicationContext())
        // Windows 下的 Robolectric 文件路径与 Android FileProvider 根路径解析不同；
        // 此处只替换 URI 生成，磁盘写入、原子落盘和后续上传读取仍走真实实现。
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any<java.io.File>()) } answers {
            Uri.fromFile(thirdArg<java.io.File>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun `源服务器下载附件可在同会话转到另一服务器且不泄露到其他会话`() = runTest {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val context = ComputerRequestContext("transfer-session", "a", "wa", runId = "transfer-run",
            selectedTargets = listOf(ComputerTargetBinding("a", "wa", "A"), ComputerTargetBinding("b", "wb", "B")))
        val stored = mutableListOf<SelectedMediaItem>()
        val bridge = ComputerAttachmentBridge(appContext,
            attachmentsForConversation = { id -> if (id == context.conversationId) stored.toList() else emptyList() },
            onDownloaded = { conversationId, runId, attachment ->
                assertEquals(context.conversationId, conversationId)
                assertEquals(context.runId, runId)
                stored.add(attachment)
            })
        // 两个方向共用同一个附件桥接器，目的服务器由本次工具调用独立路由。
        for ((sourceId, destinationId) in listOf("a" to "b", "b" to "a")) {
            val source = requireNotNull(resolveComputerToolContext(ComputerToolNames.DOWNLOAD,
                buildJsonObject { put("computer_id", sourceId) }, context))
            val destination = requireNotNull(resolveComputerToolContext(ComputerToolNames.UPLOAD,
                buildJsonObject { put("computer_id", destinationId) }, context))
            val bytes = "binary payload $sourceId->$destinationId\u0000".toByteArray()
            val downloaded = bridge.receiveDownload(source.conversationId, requireNotNull(source.runId), "transfer.bin") { output ->
                output.write(bytes)
                ComputerStreamTransferResult("transfer.bin", bytes.size.toLong(), "test-digest")
            }
            try {
                val upload = requireNotNull(bridge.resolveUpload(destination.conversationId, downloaded.attachment.id))
                assertEquals("w$destinationId", destination.workspaceId)
                org.junit.Assert.assertTrue(downloaded.savedDownload.path.startsWith("Download/App/AppTalk/"))
                assertEquals(bytes.size.toLong(), upload.size)
                assertArrayEquals(bytes, upload.openStream().use { it.readBytes() })
                assertNull(bridge.resolveUpload("another-session", downloaded.attachment.id))
            } finally {
                downloaded.attachment.filePath?.let { java.io.File(it).delete() }
            }
        }
    }

    @Test
    fun `绑定聊天失败时回滚公共副本和私有附件`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = DownloadTestProvider.install(context)
        val directory = java.io.File(context.filesDir, com.android.everytalk.util.storage.FileManager.CHAT_ATTACHMENTS_DIR)
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val bridge = ComputerAttachmentBridge(context, { emptyList() }, onDownloaded = { _, _, _ -> error("消息已删除") })
        val error = runCatching {
            bridge.receiveDownload("conversation", "run", "../报告.txt") { output ->
                output.write(byteArrayOf(1, 2, 3))
                ComputerStreamTransferResult("report", 3, "digest")
            }
        }.exceptionOrNull()
        assertEquals("消息已删除", error?.message)
        org.junit.Assert.assertTrue(provider.rows.isEmpty())
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
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

    @Test
    fun `传输长度不符时不发布下载副本也不添加附件`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = DownloadTestProvider.install(context)
        var attached = false
        val bridge = ComputerAttachmentBridge(context, { emptyList() }, onDownloaded = { _, _, _ -> attached = true })
        val error = runCatching {
            bridge.receiveDownload("conversation", "run", "report.txt") { output ->
                output.write(byteArrayOf(1))
                ComputerStreamTransferResult("report", 2, "digest")
            }
        }.exceptionOrNull()
        org.junit.Assert.assertTrue(error is ComputerException)
        org.junit.Assert.assertFalse(attached)
        org.junit.Assert.assertTrue(provider.rows.isEmpty())
    }
}
