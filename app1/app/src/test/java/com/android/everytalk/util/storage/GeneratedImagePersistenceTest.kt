package com.android.everytalk.util.storage

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.util.image.ImageHandlingLimits
import com.android.everytalk.util.image.ImagePersistenceFailure
import com.android.everytalk.util.image.ImagePersistencePolicy
import com.android.everytalk.util.image.ImagePersistenceResult
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class GeneratedImagePersistenceTest {

    private lateinit var context: Context
    private lateinit var service: ImagePersistenceService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).deleteRecursively()
        service = ImagePersistenceService(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).deleteRecursively()
    }

    @Test
    fun `有效 PNG 数据 URI 持久化为本地文件`() = runTest {
        val source = createPngDataUri()

        val result = service.persistGeneratedImage(source, "message-1", 0)

        assertTrue(result is ImagePersistenceResult.Success)
        val success = result as ImagePersistenceResult.Success
        assertTrue(File(success.filePath).isFile)
        assertEquals("image/png", success.mimeType)
    }

    @Test
    fun `数据 URI 解码后超过上限时返回明确原因`() = runTest {
        val source = "data:image/png;base64," + "A".repeat(1_024)
        val policy = ImagePersistencePolicy(
            maxBytes = 16,
            maxPixels = ImageHandlingLimits.MAX_IMAGE_PIXELS,
        )

        val result = service.persistGeneratedImage(source, "message-large", 0, policy)

        assertTrue(result is ImagePersistenceResult.Failure)
        assertTrue((result as ImagePersistenceResult.Failure).reason is ImagePersistenceFailure.TooLarge)
        assertFalse(attachmentFiles().any())
    }

    @Test
    fun `伪造图片声明时返回无效图片原因`() = runTest {
        val result = service.persistGeneratedImage(
            source = "data:image/png;base64,SGVsbG8=",
            messageIdHint = "message-invalid",
            index = 0,
        )

        assertTrue(result is ImagePersistenceResult.Failure)
        assertEquals(ImagePersistenceFailure.InvalidImage, (result as ImagePersistenceResult.Failure).reason)
        assertFalse(attachmentFiles().any())
    }

    @Test
    fun `消息 ID 含路径字符时文件仍限制在附件目录`() = runTest {
        val result = service.persistGeneratedImage(
            source = createPngDataUri(),
            messageIdHint = "../../outside/message",
            index = -1,
        ) as ImagePersistenceResult.Success

        val savedFile = File(result.filePath).canonicalFile
        assertEquals(File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).canonicalFile, savedFile.parentFile)
        assertTrue(".." !in savedFile.name)
        assertTrue("_-1_" !in savedFile.name)
    }

    private fun attachmentFiles(): Sequence<File> =
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).walkTopDown().filter(File::isFile)

    private fun createPngDataUri(): String {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }
}
