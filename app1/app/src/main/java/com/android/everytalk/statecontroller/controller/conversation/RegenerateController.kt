package com.android.everytalk.statecontroller.controller.conversation

import android.util.Log
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * RegenerateController
 * 负责将“从某条用户消息重新生成 AI 回答”的流程从 ViewModel 中抽离：
 * - 定位用于重生的用户消息
 * - 裁剪其后的 AI 消息
 * - 取消对应的流
 * - 清理动画/推理标记与关联媒体
 * - 触发重新发送
 */
class RegenerateController(
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope,
    private val messagesMutex: Mutex,
    private val persistenceDeleteMediaFor: suspend (List<List<Message>>) -> Unit,
    private val showSnackbar: (String) -> Unit,
    private val shouldAutoScroll: () -> Boolean,
    private val triggerScrollToBottom: () -> Unit,
    private val sendMessage: (
        messageText: String,
        isFromRegeneration: Boolean,
        attachments: List<SelectedMediaItem>,
        isImageGeneration: Boolean,
        manualMessageId: String?
    ) -> Unit
) {

    fun regenerateFrom(message: Message, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages

        // 基准用户消息：若传入为 AI，则寻找其上一个用户消息；否则用自身
        val baseUserMessage = if (message.sender == Sender.AI) {
            val aiIndex = messageList.indexOfFirst { it.id == message.id }
            if (aiIndex > 0) {
                messageList.subList(0, aiIndex).findLast { it.sender == Sender.User }
            } else null
        } else {
            messageList.find { it.id == message.id }
        }

        if (baseUserMessage == null || baseUserMessage.sender != Sender.User) {
            showSnackbar("无法找到对应的用户消息来重新生成回答")
            return
        }

        if ((!isImageGeneration && stateHolder._selectedApiConfig.value == null) ||
            (isImageGeneration && stateHolder._selectedImageGenApiConfig.value == null)
        ) {
            showSnackbar("请先选择 API 配置")
            return
        }

        val originalUserMessageText = baseUserMessage.text
        val originalUserMessageId = baseUserMessage.id

        // 克隆附件以生成新 ID，便于 LazyColumn 正确重组
        val originalAttachments =
            baseUserMessage.attachments.map { att ->
                when (att) {
                    is SelectedMediaItem.ImageFromUri -> att.copy(id = UUID.randomUUID().toString())
                    is SelectedMediaItem.GenericFile -> att.copy(id = UUID.randomUUID().toString())
                    is SelectedMediaItem.ImageFromBitmap -> att.copy(id = UUID.randomUUID().toString())
                    is SelectedMediaItem.Audio -> att.copy(id = UUID.randomUUID().toString())
                }
            }

        scope.launch {
            val success = withContext(Dispatchers.Default) {
                val listRef = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                val userMsgIndex = listRef.indexOfFirst { it.id == originalUserMessageId }
                if (userMsgIndex == -1) {
                    withContext(Dispatchers.Main) {
                        showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。")
                    }
                    return@withContext false
                }

                // 收集需要删除的 AI 消息（位于用户消息之后的连续段）
                val messagesToRemove = mutableListOf<Message>()
                var cursor = userMsgIndex + 1
                while (cursor < listRef.size) {
                    val cur = listRef[cursor]
                    if (cur.sender == Sender.AI) {
                        messagesToRemove.add(cur)
                        cursor++
                    } else break
                }

                messagesMutex.withLock {
                    withContext(Dispatchers.Main.immediate) {
                        val idsToRemove = messagesToRemove.map { it.id }.toSet()
                        // 若这些消息中存在正在流式的，则取消之
                        idsToRemove.forEach { id ->
                            if ((!isImageGeneration && stateHolder._currentTextStreamingAiMessageId.value == id) ||
                                (isImageGeneration && stateHolder._currentImageStreamingAiMessageId.value == id)
                            ) {
                                apiHandler.cancelCurrentApiJob(
                                    "为消息 '${originalUserMessageId.take(4)}' 重新生成回答，取消旧AI流",
                                    isNewMessageSend = true,
                                    isImageGeneration = isImageGeneration
                                )
                            }
                        }

                        // 清理推理/展开/动画标记
                        if (isImageGeneration) {
                            stateHolder.imageReasoningCompleteMap.keys.removeAll(idsToRemove)
                            stateHolder.imageExpandedReasoningStates.keys.removeAll(idsToRemove)
                            stateHolder.imageMessageAnimationStates.keys.removeAll(idsToRemove)
                        } else {
                            stateHolder.textReasoningCompleteMap.keys.removeAll(idsToRemove)
                            stateHolder.textExpandedReasoningStates.keys.removeAll(idsToRemove)
                            stateHolder.textMessageAnimationStates.keys.removeAll(idsToRemove)
                        }

                        // 先异步删除关联媒体
                        scope.launch(Dispatchers.IO) {
                            try {
                                persistenceDeleteMediaFor(listOf(messagesToRemove))
                            } catch (e: Exception) {
                                Log.w("RegenerateController", "删除媒体失败: ${e.message}")
                            }
                        }

                        // 从列表移除 AI 消息
                        listRef.removeAll(messagesToRemove.toSet())

                        // The original user message will be removed AFTER the new message is sent
                        // to ensure the conversation history is correctly passed to the API.
                    }
                }
                true
            }

            if (success) {
                if (isImageGeneration) {
                    stateHolder.isImageConversationDirty.value = true
                } else {
                    stateHolder.isTextConversationDirty.value = true
                }

                // 强制保存历史（包含仅推理更新等情况）
                withContext(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                }

                val newUserMessageId = "user_${UUID.randomUUID()}"
                
                // 重新发送消息（函数类型禁止命名参数，改为位置参数）
                sendMessage(
                    originalUserMessageText,
                    true,
                    originalAttachments,
                    isImageGeneration,
                    newUserMessageId
                )

                // Clean up the original user message AFTER sending the new one.
                // This ensures the history is intact for the sendMessage call.
                messagesMutex.withLock {
                    withContext(Dispatchers.Main.immediate) {
                        val listRef = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                        val finalUserIndex = listRef.indexOfFirst { it.id == originalUserMessageId }
                        if (finalUserIndex != -1) {
                            if (isImageGeneration) {
                                stateHolder.imageMessageAnimationStates.remove(originalUserMessageId)
                            } else {
                                stateHolder.textMessageAnimationStates.remove(originalUserMessageId)
                            }
                            listRef.removeAt(finalUserIndex)
                        }
                    }
                }

                if (shouldAutoScroll()) {
                    // 使用精确滚动定位到新生成的User消息，而不是直接滚动到底部
                    // 这样用户可以看到User消息和正在生成的AI回复
                    stateHolder._scrollToItemEvent.tryEmit(newUserMessageId)
                }
            }
        }
    }
}