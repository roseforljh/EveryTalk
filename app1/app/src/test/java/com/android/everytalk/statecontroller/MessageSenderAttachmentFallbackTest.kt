package com.android.everytalk.statecontroller

import android.app.Application
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

        val result = sender.ensureUserMessagePresentForRequest(historyMessages, currentUserMessage)

        assertEquals(1, result.size)
        assertSame(currentUserMessage, result.single())
    }

    @Test
    fun `外部图片附件会保留空 parts 用户消息`() {
        val currentUserMessage = PartsApiMessage(role = "user", parts = emptyList())
        val historyMessages = mutableListOf<AbstractApiMessage>()

        val result = sender.ensureUserMessagePresentForRequest(
            messages = historyMessages,
            currentUserMessage = currentUserMessage,
            currentUserHasAttachments = true,
        )

        assertEquals(1, result.size)
        assertSame(currentUserMessage, result.single())
    }

}
