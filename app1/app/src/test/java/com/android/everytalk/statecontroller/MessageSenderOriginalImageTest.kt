package com.android.everytalk.statecontroller

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.image.ImageHandlingLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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
        val failureMessage = snackbarMessages.single()
        assertTrue(failureMessage.contains(sourceFile.name))
        val limitMiB = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES / (1024L * 1024L)
        assertTrue(
            failureMessage.contains("$limitMiB MiB"),
        )
    }

    @Test
    fun `仅图片消息会保留在页面并发起包含原图的请求`() = runTest {
        val originalBytes = createPngBytes()
        val sourceFile = File(sourceDirectory, "image-only.png").apply { writeBytes(originalBytes) }
        val stateHolder = ViewModelStateHolder().apply {
            _selectedApiConfig.value = ApiConfig(
                address = "https://example.com/v1",
                key = "",
                model = "test-model",
                provider = "OpenAI",
                id = "image-only-config",
                name = "仅图片测试",
            )
        }
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val requestSlot = slot<ChatRequest>()
        val requestStarted = CompletableDeferred<Unit>()
        coEvery {
            apiHandler.prepareStreamingAiMessage(
                modelName = any(),
                providerName = any(),
                isImageGeneration = any(),
                onNewAiMessageAdded = any(),
                afterUserMessageId = any(),
                contextUsageSnapshot = any(),
                contextCompressionState = any(),
                executionStatus = any(),
                preparationJob = any(),
            )
        } returns "ai-image-only"
        every {
            apiHandler.streamChatResponse(
                requestBody = capture(requestSlot),
                attachmentsToPassToApiClient = any(),
                applicationContextForApiClient = any(),
                userMessageTextForContext = any(),
                afterUserMessageId = any(),
                onMessagesProcessed = any(),
                onRequestFailed = any(),
                onNewAiMessageAdded = any(),
                audioBase64 = any(),
                mimeType = any(),
                isImageGeneration = any(),
                preCreatedAiMessageId = any(),
                contextUsageSnapshot = any(),
            )
        } answers {
            requestStarted.complete(Unit)
        }
        val sender = createSender(
            stateHolder = stateHolder,
            apiHandler = apiHandler,
            scope = this,
        )

        sender.sendMessage(
            messageText = "",
            attachments = listOf(
                SelectedMediaItem.ImageFromUri(
                    uri = Uri.fromFile(sourceFile),
                    id = "image-only",
                    mimeType = "image/png",
                ),
            ),
        )
        withContext(Dispatchers.IO) {
            withTimeout(10_000L) { requestStarted.await() }
        }
        advanceUntilIdle()

        val userMessages = stateHolder.messages.filter { it.sender == Sender.User }
        assertEquals(
            "messages=${stateHolder.messages.map { "${it.sender}:${it.text}" }}, snackbar=$snackbarMessages, requestCaptured=${requestSlot.isCaptured}",
            1,
            userMessages.size,
        )
        val userMessage = userMessages.single()
        assertTrue(userMessage.text.isBlank())
        assertEquals(1, userMessage.attachments.size)

        val requestMessage = requestSlot.captured.messages.last() as PartsApiMessage
        val imagePart = requestMessage.parts.filterIsInstance<ApiContentPart.InlineData>().single()
        assertArrayEquals(originalBytes, Base64.decode(imagePart.base64Data, Base64.NO_WRAP))
    }

    private fun createSender(
        stateHolder: ViewModelStateHolder = ViewModelStateHolder(),
        apiHandler: ApiHandler = mockk(relaxed = true),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ): MessageSender = MessageSender(
        application = application,
        viewModelScope = scope,
        stateHolder = stateHolder,
        apiHandler = apiHandler,
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
