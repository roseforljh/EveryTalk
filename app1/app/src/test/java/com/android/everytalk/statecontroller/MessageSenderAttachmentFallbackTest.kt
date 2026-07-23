package com.android.everytalk.statecontroller

import android.app.Application
import android.util.Base64
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory

class MessageSenderAttachmentFallbackTest {

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val sender = MessageSender(
        application = mockk<Application>(relaxed = true),
        viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        stateHolder = ViewModelStateHolder(),
        apiHandler = mockk(relaxed = true),
        historyManager = mockk<HistoryManager>(relaxed = true),
        showSnackbar = {},
        triggerScrollToBottom = {},
        uriToBase64Encoder = { null },
    )

    @Suppress("UNCHECKED_CAST")
    private fun ensureUserMessagePresent(
        messages: MutableList<AbstractApiMessage>,
        currentUserMessage: AbstractApiMessage,
    ): MutableList<AbstractApiMessage> {
        val method = MessageSender::class.java.getDeclaredMethod(
            "ensureUserMessagePresent",
            MutableList::class.java,
            AbstractApiMessage::class.java,
        )
        method.isAccessible = true
        return method.invoke(sender, messages, currentUserMessage) as MutableList<AbstractApiMessage>
    }

    private fun persistBitmapData(
        target: MessageSender,
        item: SelectedMediaItem.ImageFromBitmap,
    ): String? {
        val method = MessageSender::class.java.getDeclaredMethod(
            "persistBitmapData",
            SelectedMediaItem.ImageFromBitmap::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(target, item, "message", 0) as String?
    }

    @Test
    fun `attachment only user message is injected when history is empty`() {
        val currentUserMessage = PartsApiMessage(
            role = "user",
            parts = listOf(
                ApiContentPart.InlineData(
                    base64Data = "ZmFrZS1pbWFnZQ==",
                    mimeType = "image/jpeg",
                )
            )
        )
        val historyMessages = mutableListOf<AbstractApiMessage>()

        val result = ensureUserMessagePresent(historyMessages, currentUserMessage)

        assertEquals(1, result.size)
        assertSame(currentUserMessage, result.single())
    }

    @Test
    fun `空解码结果不会留下附件文件`() {
        val filesDir = createTempDirectory("everytalk-bitmap-persist").toFile()
        try {
            val application = mockk<Application>(relaxed = true)
            every { application.filesDir } returns filesDir
            val target = MessageSender(
                application = application,
                viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                stateHolder = ViewModelStateHolder(),
                apiHandler = mockk(relaxed = true),
                historyManager = mockk<HistoryManager>(relaxed = true),
                showSnackbar = {},
                triggerScrollToBottom = {},
                uriToBase64Encoder = { null },
            )
            mockkStatic(Base64::class)
            every { Base64.decode("invalid", Base64.DEFAULT) } returns byteArrayOf()

            val result = persistBitmapData(
                target,
                SelectedMediaItem.ImageFromBitmap(bitmapData = "invalid", id = "bitmap"),
            )

            assertNull(result)
            assertTrue(filesDir.walkTopDown().none { it.isFile })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `非空解码结果按原始字节持久化`() {
        val filesDir = createTempDirectory("everytalk-bitmap-persist").toFile()
        try {
            val application = mockk<Application>(relaxed = true)
            every { application.filesDir } returns filesDir
            val target = MessageSender(
                application = application,
                viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                stateHolder = ViewModelStateHolder(),
                apiHandler = mockk(relaxed = true),
                historyManager = mockk<HistoryManager>(relaxed = true),
                showSnackbar = {},
                triggerScrollToBottom = {},
                uriToBase64Encoder = { null },
            )
            val decodedBytes = byteArrayOf(1, 2, 3, 4)
            mockkStatic(Base64::class)
            every { Base64.decode("valid", Base64.DEFAULT) } returns decodedBytes

            val path = persistBitmapData(
                target,
                SelectedMediaItem.ImageFromBitmap(bitmapData = "valid", id = "bitmap"),
            )

            assertNotNull(path)
            assertArrayEquals(decodedBytes, java.io.File(requireNotNull(path)).readBytes())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `超大位图 Base64 在解码前拒绝`() {
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), Base64.DEFAULT) } answers {
            throw AssertionError("超大位图不应进入 Base64 解码")
        }

        val result = persistBitmapData(
            sender,
            SelectedMediaItem.ImageFromBitmap(
                bitmapData = "A".repeat(24 * 1024 * 1024),
                id = "bitmap-large",
            ),
        )

        assertNull(result)
    }
}
