package com.example.everytalk.statecontroller.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.example.everytalk.statecontroller.ViewModelStateHolder

/**
 * 承接 AppViewModel 中与“消息内容/流式更新”强相关的职责：
 * - 提供消息的实时流式内容 StateFlow
 * - 处理 AI 消息全文变更（流式拼接后的即时落入消息列表）
 * - 追加 reasoning/content 片段并触发必要的 UI 刷新逻辑
 *
 * 该控制器不直接持有 UI 组件，只依赖 StateHolder 与外部回调（triggerScrollToBottom）。
 */
class MessageContentController(
    private val stateHolder: ViewModelStateHolder,
    private val scope: CoroutineScope,
    private val messagesMutex: Mutex,
    private val triggerScrollToBottom: () -> Unit
) {

    /**
     * 获取指定消息的流式内容（供 UI 观察）
     */
    fun getStreamingContent(messageId: String): StateFlow<String> {
        return stateHolder.streamingMessageStateManager.getOrCreateStreamingState(messageId)
    }

    /**
     * 兼容别名
     */
    fun getStreamingText(messageId: String): StateFlow<String> = getStreamingContent(messageId)

    /**
     * 当某条 AI 消息的全文内容发生变化时（通常源自流式增量合并后的结果），
     * 立即更新消息列表中的该消息文本，并标记会话为“脏”，以确保持久化。
     *
     * 与原 AppViewModel.onAiMessageFullTextChanged 等效。
     */
    fun onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        scope.launch(Dispatchers.Main.immediate) {
            messagesMutex.withLock {
                val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageId }
                if (messageIndex != -1) {
                    val messageToUpdate = stateHolder.messages[messageIndex]
                    if (messageToUpdate.text != currentFullText) {
                        com.example.everytalk.util.MessageDebugUtil.logStreamingUpdate(
                            messageId,
                            currentFullText.takeLast(50),
                            currentFullText.length
                        )

                        val updatedMessage = messageToUpdate.copy(text = currentFullText)
                        stateHolder.messages[messageIndex] = updatedMessage

                        // 一旦AI消息文本变化，立即标记会话为“脏”，确保保存
                        stateHolder.isTextConversationDirty.value = true

                        // 完整性检查（调试）
                        val issues = com.example.everytalk.util.MessageDebugUtil.checkMessageIntegrity(updatedMessage)
                        if (issues.isNotEmpty()) {
                            android.util.Log.w(
                                "MessageContentController",
                                "⚠️ Message integrity issues for $messageId: ${issues.joinToString(", ")}"
                            )
                        }

                        if (stateHolder.shouldAutoScroll()) {
                            triggerScrollToBottom()
                        }
                    }
                }
            }
        }
    }

    /**
     * 追加 reasoning 片段（文本/图像模式均可）
     */
    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendReasoningToMessage(messageId, text, isImageGeneration)
        }
    }

    /**
     * 追加 content 片段，并把合并后的全文回写到消息列表，保持 UI 与最终消息一致
     */
    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendContentToMessage(messageId, text, isImageGeneration)
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val full = messageList.find { it.id == messageId }?.text ?: ""
            onAiMessageFullTextChanged(messageId, full)
        }
    }

    /**
     * 清理资源（供外部在 ViewModel.onCleared 中调用，如果需要）
     */
    fun cleanup() {
        // 目前无内部资源需要清理；如后续添加内部作用域/任务，可在此处取消
    }
}