package com.android.everytalk.util.storage

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GeneratedImagePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "chat_attachments").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "chat_attachments").deleteRecursively()
        stopKoin()
    }

    @Test
    fun `有效 PNG 数据 URI 归档为本地文件`() = runTest {
        val source = "data:image/png;base64," +
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2lS8AAAAASUVORK5CYII="

        val result = FileManager(context).persistMessageImageSource(
            source = source,
            messageIdHint = "message-1",
            index = 0,
        )

        assertNotNull(result)
        assertTrue(File(requireNotNull(result)).isFile)
        assertTrue(!requireNotNull(result).startsWith("data:", ignoreCase = true))
    }

    @Test
    fun `数据 URI 解码后超过上限时拒绝归档`() = runTest {
        val source = "data:image/png;base64," + "A".repeat(1_024)

        val result = FileManager(context).persistMessageImageSource(
            source = source,
            messageIdHint = "message-large",
            index = 0,
            maxBytes = 16,
        )

        assertNull(result)
        assertTrue(File(context.filesDir, "chat_attachments").listFiles().isNullOrEmpty())
    }

    @Test
    fun `声明为图片但文件签名不匹配时拒绝归档`() = runTest {
        val source = "data:image/png;base64,SGVsbG8="

        val result = FileManager(context).persistMessageImageSource(
            source = source,
            messageIdHint = "message-invalid",
            index = 0,
        )

        assertNull(result)
        assertTrue(File(context.filesDir, "chat_attachments").listFiles().isNullOrEmpty())
    }

    @Test
    fun `消息 ID 含路径字符时归档文件仍限制在附件目录`() = runTest {
        val source = "data:image/png;base64," +
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2lS8AAAAASUVORK5CYII="

        val result = FileManager(context).persistMessageImageSource(
            source = source,
            messageIdHint = "../../outside/message",
            index = -1,
        )

        val savedFile = File(requireNotNull(result)).canonicalFile
        assertEquals(File(context.filesDir, "chat_attachments").canonicalFile, savedFile.parentFile)
        assertTrue(".." !in savedFile.name)
        assertTrue("_-1_" !in savedFile.name)
    }

    @Test
    fun `超大 Data URL 在 Base64 解码前拒绝`() = runTest {
        val source = "data:image/png;base64," + "A".repeat(6 * 1024 * 1024)

        assertNull(FileManager(context).loadBitmapFromDataUrl(source))
    }

    @Test
    fun `位图保存直接写入附件文件并清理临时文件`() = runTest {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val result = FileManager(context).saveBitmapToAppInternalStorage(
            bitmapToSave = bitmap,
            messageIdHint = "message-save",
            attachmentIndex = 0,
        )

        val savedFile = File(requireNotNull(result))
        assertTrue(savedFile.isFile)
        assertTrue(savedFile.length() > 0L)
        assertTrue(bitmap.isRecycled)
        assertTrue(File(context.filesDir, "chat_attachments").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
