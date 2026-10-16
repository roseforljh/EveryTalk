package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.prepareImageHistory
import com.android.everytalk.ui.components.MarkdownPart
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalCoroutinesApi::class)
class ImageHistoryLoadingTest {
    @Before
    fun mockAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(android.util.Log::class)
    }

    private fun user(id: String) = Message(id = id, text = id, sender = Sender.User)

    @Test
    fun `云端会话打开后按会话身份恢复模型标签提示词且不随首条问题改变`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sessionId = "cloud:session-uuid"
            val config = com.android.everytalk.data.DataClass.ApiConfig(
                address = "https://example.test", key = "", model = "gpt", provider = "test", name = "会话模型")
            val state = ViewModelStateHolder().apply {
                setApiHandler(mockk(relaxed = true))
                _apiConfigs.value = listOf(config)
                conversationApiConfigIds.value = mapOf(sessionId to config.id)
                conversationFunctionToggleStates.value = mapOf(sessionId to
                    com.android.everytalk.statecontroller.ConversationFunctionToggleState(mcpEnabled = true, agentEnabled = true))
                systemPrompts[sessionId] = "会话提示词"
            }
            for (messageId in listOf("cloud:first-question", "cloud:edited-question")) {
                val message = user(messageId).copy(conversationId = sessionId)
                state._historicalConversations.value = listOf(listOf(message))
                val manager = SimpleModeManager(state, mockk(relaxed = true), this,
                    loadHistorySession = { assertEquals(sessionId, it); listOf(message) })
                manager.loadTextHistory(0)
                assertEquals(sessionId, state._currentConversationId.value)
                assertEquals(config.id, state._selectedApiConfig.value?.id)
                assertTrue(state._isMcpEnabledForNextRequest.value)
                assertTrue(state._isAgentEnabled.value)
                assertEquals("会话提示词", state.systemPrompts[state._currentConversationId.value])
                assertEquals(sessionId, state.messages.single().conversationId)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `打开文本历史优先逐会话提示词且保留显式清空`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (savedPrompt in listOf("修改后的提示词", "")) {
                val state = ViewModelStateHolder().apply {
                    setApiHandler(mockk(relaxed = true))
                    _historicalConversations.value = listOf(listOf(
                        Message(id = "system", sender = Sender.System, text = "旧的提示词"), user("conversation")))
                    systemPrompts["conversation"] = savedPrompt
                }
                val manager = SimpleModeManager(state, mockk(relaxed = true), this)
                manager.loadTextHistory(0)
                assertEquals(savedPrompt, state.systemPrompts["conversation"])
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `恢复正文和状态表在后台执行并保留原消息附件`() = runTest {
        val callerThread = Thread.currentThread()
        val readThreads = ConcurrentLinkedQueue<Thread>()
        val attachment = SelectedMediaItem.ImageFromBitmap("base64", "photo")
        val originals = listOf(
            user("u1").copy(attachments = listOf(attachment)),
            Message(id = "a1", sender = Sender.AI, text = "", reasoning = "原因", imageUrls = listOf("file:///photo.png"),
                parts = listOf(MarkdownPart.Text(id = "part-a1", content = "已画好"))),
        )
        val tracked = object : AbstractList<Message>() {
            override val size: Int get() = originals.size
            override fun get(index: Int): Message {
                readThreads.add(Thread.currentThread())
                return originals[index]
            }
        }
        val result = prepareImageHistory(tracked, "u1")
        assertTrue(readThreads.isNotEmpty())
        assertTrue(readThreads.all { it !== callerThread })
        assertEquals("", originals.last().text)
        assertEquals("已画好", result.messages.last().text)
        assertEquals(listOf(attachment), result.messages.first().attachments)
        assertEquals(listOf("file:///photo.png"), result.messages.last().imageUrls)
        assertEquals(mapOf("a1" to true), result.reasoningCompleteStates)
        assertEquals(mapOf("u1" to true, "a1" to true), result.animationPlayedStates)
    }

    @Test
    fun `慢加载期间保留旧对话且只提交完整修复后的消息`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val old = user("old")
            val target = user("target")
            val loaded = CompletableDeferred<List<Message>>()
            val state = ViewModelStateHolder().apply {
                setApiHandler(mockk(relaxed = true))
                _currentImageGenerationConversationId.value = "old"
                imageGenerationMessages.add(old)
                _imageGenerationHistoricalConversations.value = listOf(listOf(target))
                imageReasoningCompleteMap["stale"] = true
            }
            val manager = SimpleModeManager(state, mockk(relaxed = true), this, loadHistorySession = { loaded.await() })
            val job = launch { manager.loadImageHistory(0) }
            runCurrent()
            assertEquals(listOf(old), state.imageGenerationMessages.toList())
            assertEquals("old", state._currentImageGenerationConversationId.value)
            loaded.complete(listOf(target, Message(id = "a1", text = "<think>\n分析\n</think>\n结果", sender = Sender.AI)))
            job.join()
            assertEquals(listOf("target", "a1"), state.imageGenerationMessages.map { it.id })
            assertEquals("结果", state.imageGenerationMessages.last().text)
            assertEquals("分析", state.imageGenerationMessages.last().reasoning)
            assertEquals(mapOf("a1" to true), state.imageReasoningCompleteMap.toMap())
            assertEquals("target", state._currentImageGenerationConversationId.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `读取失败或等待期间切到新会话不会清掉或覆盖消息`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (fail in listOf(true, false)) {
                val old = user("old")
                val target = user("target")
                val loaded = CompletableDeferred<List<Message>>()
                val state = ViewModelStateHolder().apply {
                    _currentImageGenerationConversationId.value = "old"
                    imageGenerationMessages.add(old)
                    _imageGenerationHistoricalConversations.value = listOf(listOf(target))
                }
                val manager = SimpleModeManager(state, mockk(relaxed = true), this, loadHistorySession = { loaded.await() })
                var failure: Throwable? = null
                val job = launch { try { manager.loadImageHistory(0) } catch (e: IllegalStateException) { failure = e } }
                runCurrent()
                if (fail) loaded.completeExceptionally(IllegalStateException("读取失败")) else {
                    state._currentImageGenerationConversationId.value = "new"
                    loaded.complete(listOf(target))
                }
                job.join()
                assertEquals(listOf(old), state.imageGenerationMessages.toList())
                assertEquals(if (fail) "old" else "new", state._currentImageGenerationConversationId.value)
                assertEquals(fail, failure != null)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `自动回填与手动加载重叠时只允许最后请求提交`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = user("first")
            val latest = user("latest")
            val firstResult = CompletableDeferred<List<Message>>()
            val latestResult = CompletableDeferred<List<Message>>()
            val state = ViewModelStateHolder().apply {
                setApiHandler(mockk(relaxed = true))
                _currentImageGenerationConversationId.value = "old"
                imageGenerationMessages.add(user("old"))
                _imageGenerationHistoricalConversations.value = listOf(listOf(first), listOf(latest))
            }
            val manager = SimpleModeManager(state, mockk(relaxed = true), this, loadHistorySession = {
                if (it == "first") firstResult.await() else latestResult.await()
            })
            val firstJob = launch { manager.loadImageHistory(0) }
            runCurrent()
            val latestJob = launch { manager.loadImageHistory(1) }
            runCurrent()
            firstResult.complete(listOf(first))
            firstJob.join()
            assertEquals("old", state.imageGenerationMessages.single().id)
            latestResult.complete(listOf(latest))
            latestJob.join()
            assertEquals("latest", state.imageGenerationMessages.single().id)
            assertEquals("latest", state._currentImageGenerationConversationId.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `快速切换取消旧加载且旧任务不能提前关闭新加载标记`() = runTest {
        val state = ViewModelStateHolder()
        val bridge = mockk<HistoryController.SimpleModeSwitcher>()
        var oldCancelled = false
        val latest = CompletableDeferred<Unit>()
        coEvery { bridge.loadImageHistory(0) } coAnswers {
            try { awaitCancellation() } finally { oldCancelled = true }
        }
        coEvery { bridge.loadImageHistory(1) } coAnswers { latest.await() }
        val controller = HistoryController(state, mockk(relaxed = true), mockk(relaxed = true), this, {}, { false }, {}, bridge)
        controller.loadImageHistory(0)
        runCurrent()
        controller.loadImageHistory(1)
        runCurrent()
        assertTrue(oldCancelled)
        assertTrue(state._isLoadingImageHistory.value)
        latest.complete(Unit)
        advanceUntilIdle()
        assertFalse(state._isLoadingImageHistory.value)
        controller.loadImageHistory(0)
        runCurrent()
        controller.cancelPendingImageHistoryLoad()
        advanceUntilIdle()
        assertFalse(state._isLoadingImageHistory.value)
    }
}
