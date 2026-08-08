package com.android.everytalk.util.storage

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.util.image.ImageHandlingLimits
import com.android.everytalk.util.image.ImagePersistenceFailure
import com.android.everytalk.util.image.ImagePersistencePolicy
import com.android.everytalk.util.image.ImagePersistenceResult
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImagePersistenceServiceTest {

    private lateinit var context: Context
    private lateinit var service: ImagePersistenceService
    private lateinit var sourceDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).deleteRecursively()
        sourceDirectory = File(context.cacheDir, "image-persistence-test").apply {
            deleteRecursively()
            mkdirs()
        }
        service = ImagePersistenceService(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).deleteRecursively()
        sourceDirectory.deleteRecursively()
    }

    @Test
    fun `用户 URI 图片按原始字节持久化且不重新编码`() = runTest {
        val originalBytes = createPngBytes(width = 3, height = 2)
        val sourceFile = File(sourceDirectory, "original.png").apply { writeBytes(originalBytes) }

        val result = service.persistUserImage(
            sourceUri = Uri.fromFile(sourceFile),
            fileName = "original.png",
            messageIdHint = "message-1",
            attachmentIndex = 0,
        )

        assertTrue(result is ImagePersistenceResult.Success)
        result as ImagePersistenceResult.Success
        assertEquals("image/png", result.mimeType)
        assertEquals(originalBytes.size.toLong(), result.sizeBytes)
        assertArrayEquals(originalBytes, File(result.filePath).readBytes())
    }

    @Test
    fun `用户图片超过上限时拒绝且不留下文件`() = runTest {
        val originalBytes = createPngBytes(width = 3, height = 2)
        val sourceFile = File(sourceDirectory, "large.png").apply { writeBytes(originalBytes) }
        val policy = ImagePersistencePolicy(
            maxBytes = originalBytes.size.toLong() - 1L,
            maxPixels = ImageHandlingLimits.MAX_IMAGE_PIXELS,
        )

        val result = service.persistUserImage(
            sourceUri = Uri.fromFile(sourceFile),
            fileName = "large.png",
            messageIdHint = "message-large",
            attachmentIndex = 0,
            policy = policy,
        )

        assertTrue(result is ImagePersistenceResult.Failure)
        assertTrue((result as ImagePersistenceResult.Failure).reason is ImagePersistenceFailure.TooLarge)
        assertFalse(File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).walkTopDown().any { it.isFile })
    }

    @Test
    fun `生成图片声明 MIME 错误时以真实文件签名为准`() = runTest {
        val originalBytes = createPngBytes(width = 2, height = 2)
        val source = "data:application/octet-stream;base64," +
            Base64.encodeToString(originalBytes, Base64.NO_WRAP)

        val result = service.persistGeneratedImage(
            source = source,
            messageIdHint = "message-mime",
            index = 0,
        )

        assertTrue(result is ImagePersistenceResult.Success)
        result as ImagePersistenceResult.Success
        assertEquals("image/png", result.mimeType)
        assertTrue(result.filePath.endsWith(".png"))
        assertArrayEquals(originalBytes, File(result.filePath).readBytes())
    }

    @Test
    fun `图片像素数超过上限时拒绝且清理临时文件`() = runTest {
        val originalBytes = createPngBytes(width = 2, height = 2)
        val source = "data:image/png;base64," + Base64.encodeToString(originalBytes, Base64.NO_WRAP)
        val policy = ImagePersistencePolicy(maxBytes = 1024L, maxPixels = 3L)

        val result = service.persistGeneratedImage(
            source = source,
            messageIdHint = "message-pixels",
            index = 0,
            policy = policy,
        )

        assertTrue(result is ImagePersistenceResult.Failure)
        assertTrue((result as ImagePersistenceResult.Failure).reason is ImagePersistenceFailure.TooManyPixels)
        assertFalse(File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).walkTopDown().any { it.isFile })
    }

    @Test
    fun `远程 URL 超限时在发起网络请求前拒绝`() = runTest {
        val source = "https://example.com/" + "a".repeat(ImageHandlingLimits.MAX_REMOTE_URL_BYTES)

        val result = service.persistGeneratedImage(
            source = source,
            messageIdHint = "message-url",
            index = 0,
        )

        assertTrue(result is ImagePersistenceResult.Failure)
        assertTrue((result as ImagePersistenceResult.Failure).reason is ImagePersistenceFailure.UrlTooLong)
    }

    private fun createPngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
