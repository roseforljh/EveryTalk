package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryControllerRenameTest {

    @Test
    fun `再次编辑会话名称时读取最近保存的自定义名称`() {
        val stateHolder = ViewModelStateHolder().apply {
            _historicalConversations.value = listOf(
                listOf(
                    Message(
                        id = "title-1",
                        text = "最近修改的名称",
                        sender = Sender.System,
                        isPlaceholderName = true,
                    ),
                    Message(id = "user-1", text = "最初的问题", sender = Sender.User),
                ),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = HistoryController(
            stateHolder = stateHolder,
            historyManager = mockk<HistoryManager>(relaxed = true),
            apiHandler = mockk<ApiHandler>(relaxed = true),
            scope = scope,
            showSnackbar = {},
            shouldAutoScroll = { false },
            triggerScrollToBottom = {},
            simpleModeSwitcher = mockk<HistoryController.SimpleModeSwitcher>(relaxed = true),
        )

        try {
            assertEquals("最近修改的名称", controller.getConversationFullText(0, false))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `历史加载时标题元数据不会进入聊天消息`() {
        val messages = listOf(
            Message(
                id = "title-1",
                text = "会话名称",
                sender = Sender.System,
                isPlaceholderName = true,
            ),
            Message(id = "user-1", text = "问题", sender = Sender.User),
        )

        val loaded = prepareLoadedHistoryMessages(messages, sessionId = "user-1")

        assertEquals(listOf("user-1"), loaded.map(Message::id))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `重命名当前会话只更新历史元数据且不会创建幽灵气泡`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val stateHolder = ViewModelStateHolder().apply {
            _historicalConversations.value = listOf(
                listOf(Message(id = "user-1", text = "最初的问题", sender = Sender.User)),
            )
            _loadedHistoryIndex.value = 0
            messages.add(Message(id = "user-1", text = "最初的问题", sender = Sender.User))
        }
        val historyManager = mockk<HistoryManager>(relaxed = true)
        coEvery { historyManager.persistHistoryListDirectly(false) } returns Unit
        val controller = HistoryController(
            stateHolder = stateHolder,
            historyManager = historyManager,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            scope = this,
            showSnackbar = {},
            shouldAutoScroll = { false },
            triggerScrollToBottom = {},
            simpleModeSwitcher = mockk<HistoryController.SimpleModeSwitcher>(relaxed = true),
        )

        try {
            controller.renameConversation(0, "最近修改的名称", false)
            advanceUntilIdle()

            assertEquals("最近修改的名称", stateHolder._historicalConversations.value.single().first().text)
            assertFalse(stateHolder.messages.any(Message::isPlaceholderName))
            assertEquals(listOf("user-1"), stateHolder.messages.map(Message::id))
        } finally {
            Dispatchers.resetMain()
        }
    }
}
