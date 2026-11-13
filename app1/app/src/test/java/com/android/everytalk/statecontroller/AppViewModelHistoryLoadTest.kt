package com.android.everytalk.statecontroller

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.viewModelScope
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelHistoryLoadTest {

    @Test
    fun `列表重排后同一会话仍跳过重复加载`() {
        assertTrue(shouldSkipReloadingLoadedHistory("session-a", "session-a", true))
    }

    @Test
    fun `当前会话没有消息时仍需加载`() {
        assertFalse(shouldSkipReloadingLoadedHistory("session-a", "session-a", false))
    }

    @Test
    fun `旧索引相同但身份不同不能跳过加载`() {
        assertFalse(shouldSkipReloadingLoadedHistory("session-b", "session-a", true))
        assertFalse(shouldSkipReloadingLoadedHistory(null, "session-a", true))
    }

    @Test
    fun `保存期间目标被删除时不加载占据旧索引的另一会话`() {
        val target = listOf(Message(id = "a", text = "相同问题", sender = Sender.User, conversationId = "session-a"))
        val other = listOf(Message(id = "b", text = "相同问题", sender = Sender.User, conversationId = "session-b"))
        assertEquals(-1, resolveHistoryIndexAfterSave(0, listOf(target), listOf(other)))
    }

    @Test
    fun `only latest history load generation may commit state`() {
        assertFalse(isCurrentHistoryLoad(requestGeneration = 7L, currentGeneration = 8L))
        assertTrue(isCurrentHistoryLoad(requestGeneration = 8L, currentGeneration = 8L))
    }

    @Test
    fun `text history load job covers preparation through final commit`() {
        val appViewModelSource = source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt",
        ) + source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelConversationActions.kt",
        ) + source(
            "src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModelActions.kt",
        )
        val historyControllerSource = source(
            "src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
        )
        val textLoadFunction = historyControllerSource
            .substringAfter("suspend fun loadTextHistory(index: Int)")
            .substringBefore("fun loadImageHistory")

        assertTrue(appViewModelSource.contains("textHistoryLoadJob?.cancel()"))
        assertTrue(appViewModelSource.contains("previousHistoryLoadJob?.cancelAndJoin()"))
        assertTrue(appViewModelSource.contains("historyController.loadTextHistory(resolvedIndex)"))
        assertTrue(
            appViewModelSource
                .substringAfter("fun startNewChat()")
                .substringBefore("fun startNewImageGeneration()")
                .contains("cancelPendingTextHistoryLoad()")
        )
        assertTrue(historyControllerSource.contains("suspend fun loadTextHistory(index: Int)"))
        assertFalse(textLoadFunction.contains("scope.launch"))
    }

    @Test
    fun `text history is prepared once and committed in one snapshot`() {
        val simpleModeSource = source(
            "src/main/java/com/android/everytalk/statecontroller/controller/conversation/SimpleModeManager.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/SimpleModeManager.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/SimpleModeManager.kt",
        )
        val historyControllerSource = source(
            "src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
            "app1/app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/HistoryController.kt",
        )
        val simpleTextLoad = simpleModeSource
            .substringAfter("suspend fun loadTextHistory(index: Int)")
            .substringBefore("suspend fun loadImageHistory")
        val controllerTextLoad = historyControllerSource
            .substringAfter("suspend fun loadTextHistory(index: Int)")
            .substringBefore("fun loadImageHistory")

        assertTrue(simpleTextLoad.contains("Snapshot.withMutableSnapshot"))
        assertTrue(simpleTextLoad.contains("val reasoningCompleteStates"))
        assertTrue(simpleTextLoad.contains("val animationPlayedStates"))
        assertFalse(controllerTextLoad.contains("stateHolder.messages.clear()"))
        assertFalse(controllerTextLoad.contains("stateHolder.messages.addAll("))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Fork发送等待历史加载完成并保留分支旧上下文`() = runTest {
        val loading = Job()
        val parent = listOf(Message(id = "parent-user", text = "父会话问题", sender = Sender.User, contentStarted = true))
        val previous = Message(id = "fork-ai", text = "分支已有回答", sender = Sender.AI, contentStarted = true)
        val forkMessages = mutableStateListOf<Message>()
        val holder = mockk<ViewModelStateHolder> {
            every { _currentConversationId } returns MutableStateFlow("parent")
            every { _forkConversationIds } returns MutableStateFlow(mapOf("parent" to "fork"))
            every { textMessagesForConversation("parent") } returns mutableStateListOf(*parent.toTypedArray())
            every { textMessagesForConversation("fork") } returns forkMessages
            every { textApiMessageIds } returns mutableMapOf()
            every { forkLoadJobs } returns mutableMapOf("fork" to loading)
            every { systemPrompts } returns mutableStateMapOf()
            every { systemPromptEngagedState } returns mutableStateMapOf()
        }
        val sender = mockk<MessageSender>()
        val model = mockk<AppViewModel> {
            every { stateHolder } returns holder
            every { messageSender } returns sender
        }
        var sends = 0
        var historyAtSend: List<Message> = emptyList()
        every {
            sender.sendMessage(
                messageText = "继续", attachments = emptyList(), mimeType = null, contentParts = emptyList(),
                onUserMessageDisplayed = any(), onSendRejected = any(),
                targetConversationId = "fork", inheritedHistory = parent, computerConversationId = "parent",
            )
        } answers {
            sends++
            historyAtSend = forkMessages.toList()
        }
        // 只替换 ViewModel 的作用域，不初始化 Android Application 或发起真实模型请求。
        mockkStatic("androidx.lifecycle.ViewModelKt")
        try {
            every { model.viewModelScope } returns this@runTest
            model.sendForkMessage("继续", emptyList(), null, emptyList(), {}, {})
            runCurrent()
            assertEquals(0, sends)
            forkMessages.add(previous)
            loading.complete()
            runCurrent()
            assertEquals(1, sends)
            assertEquals(listOf(previous), historyAtSend)
        } finally {
            loading.cancel()
            unmockkStatic("androidx.lifecycle.ViewModelKt")
        }
    }

    private fun source(vararg candidates: String): String {
        val sourceFile = candidates.asSequence().map(::File).firstOrNull(File::isFile)
        requireNotNull(sourceFile) { "找不到源码文件: ${candidates.joinToString()}" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
