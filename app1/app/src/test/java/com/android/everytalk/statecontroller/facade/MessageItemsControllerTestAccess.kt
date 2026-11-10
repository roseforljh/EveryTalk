package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.StreamingMessageStateManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal object MessageItemsControllerTestAccess {
    private val controllers = mutableListOf<MessageItemsControllerForTest>()

    fun newController(): MessageItemsControllerForTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return MessageItemsControllerForTest(
            stateHolder = ViewModelStateHolder(),
            streamingMessageStateManager = StreamingMessageStateManager(),
            scope = scope
        ).also { controllers += it }
    }

    fun closeAll() {
        controllers.forEach { it.close() }
        controllers.clear()
    }
}

internal class MessageItemsControllerForTest(
    val stateHolder: ViewModelStateHolder,
    streamingMessageStateManager: StreamingMessageStateManager,
    private val scope: CoroutineScope
) : MessageItemsController(stateHolder, streamingMessageStateManager, scope) {
    fun normalizeStatusTextForTest(message: Message): String = normalizeStatusText(message)
    fun resolveStreamingStageTextForTest(message: Message, elapsedMs: Long): String? =
        super.debugResolveStreamingStageText(message, elapsedMs, reasoningComplete = false)

    fun computeBubbleStateForTest(
        message: Message,
        isApiCalling: Boolean,
        currentStreamingAiMessageId: String?,
        isImageGeneration: Boolean = false
    ): com.android.everytalk.ui.state.AiBubbleState =
        super.debugComputeBubbleState(message, isApiCalling, currentStreamingAiMessageId, isImageGeneration)

    fun seedStreamingRenderContent(messageId: String, content: String) {
        streamingMessageStateManager.startStreaming(messageId)
        streamingMessageStateManager.updateContent(messageId, content)
    }

    fun chatListItemsForTest(): List<com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem> = runBlocking {
        // 生成失败时 flow 不会发射，无限等待会让整个测试挂死；超时后直接失败并保留原因。
        kotlinx.coroutines.withTimeout(10_000) { chatListItems.first { it.isNotEmpty() } }
    }

    fun close() {
        scope.cancel()
    }
}
