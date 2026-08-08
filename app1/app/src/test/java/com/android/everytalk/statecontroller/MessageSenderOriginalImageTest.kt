package com.android.everytalk.statecontroller

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSenderOriginalImageTest {

    private lateinit var application: Application
    private lateinit var sourceDirectory: File
    private val snackbarMessages = mutableListOf<String>()

    @Before
    fun setUp() {
        stopKoin()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any<File>()) } answers {
            Uri.fromFile(thirdArg<File>())
        }
        File(application.filesDir, "chat_attachments").deleteRecursively()
        sourceDirectory = File(application.cacheDir, "sender-original-image-test").apply {
            deleteRecursively()
            mkdirs()
        }
        snackbarMessages.clear()
    }

    @After
    fun tearDown() {
        File(application.filesDir, "chat_attachments").deleteRecursively()
        sourceDirectory.deleteRecursively()
        Dispatchers.resetMain()
        unmockkAll()
        stopKoin()
    }

    @Test
    fun `文本和图像模式发送用户图片时均保持原始字节`() = runTest {
        val originalBytes = createPngBytes()

        repeat(2) { index ->
            val sourceFile = File(sourceDirectory, "source-$index.png").apply { writeBytes(originalBytes) }
            val result = createSender().processAttachments(
                attachments = listOf(
                    SelectedMediaItem.ImageFromUri(
                        uri = Uri.fromFile(sourceFile),
                        id = "image-$index",
                        mimeType = "image/png",
                        filePath = sourceFile.absolutePath.takeIf { index == 1 },
                    ),
                ),
                shouldUsePartsApiMessage = false,
                textToActuallySend = "",
            )

            assertTrue(result.success)
            val persistedPath = (result.processedAttachmentsForUi.single() as SelectedMediaItem.ImageFromUri).filePath
            assertArrayEquals(originalBytes, File(requireNotNull(persistedPath)).readBytes())
        }
        assertTrue(snackbarMessages.isEmpty())
    }

    @Test
    fun `发送阶段再次拒绝超过十六 MiB 的用户图片并显示明确提示`() = runTest {
        val sourceFile = File(sourceDirectory, "too-large.png")
        RandomAccessFile(sourceFile, "rw").use { file ->
            file.setLength(16L * 1024L * 1024L + 1L)
        }

        val result = createSender().processAttachments(
            attachments = listOf(
                SelectedMediaItem.ImageFromUri(
                    uri = Uri.fromFile(sourceFile),
                    id = "large-image",
                    mimeType = "image/png",
                ),
            ),
            shouldUsePartsApiMessage = false,
            textToActuallySend = "",
        )

        assertFalse(result.success)
        assertTrue(snackbarMessages.single().contains("超过最大 16 MiB 限制"))
        assertTrue(snackbarMessages.single().contains("too-large.png"))
    }

    private fun createSender(): MessageSender = MessageSender(
        application = application,
        viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        stateHolder = ViewModelStateHolder(),
        apiHandler = mockk(relaxed = true),
        historyManager = mockk<HistoryManager>(relaxed = true),
        showSnackbar = snackbarMessages::add,
        triggerScrollToBottom = {},
        uriToBase64Encoder = { null },
    )

    private fun createPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
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
