package com.example.everytalk.statecontroller.controller

import android.util.Log
import com.example.everytalk.statecontroller.ApiHandler
import com.example.everytalk.statecontroller.ViewModelStateHolder
import com.example.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import com.example.everytalk.util.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager

/**
 * HistoryController
 * 负责：
 * - 加载文本/图像历史
 * - 删除/清空会话（文本/图像）
 * - 预览/完整名称生成
 * - 重命名会话
 * - 辅助：修复历史消息 parts 与完整性处理
 */
class HistoryController(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val cacheManager: CacheManager,
    private val apiHandler: ApiHandler,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val shouldAutoScroll: () -> Boolean,
    private val triggerScrollToBottom: () -> Unit,
    private val simpleModeSwitcher: SimpleModeSwitcher
) {
    interface SimpleModeSwitcher {
        fun switchToTextMode(forceNew: Boolean = false, skipSavingTextChat: Boolean = false)
        fun switchToImageMode(forceNew: Boolean = false, skipSavingImageChat: Boolean = false)
        suspend fun loadTextHistory(index: Int)
        suspend fun loadImageHistory(index: Int)
        fun isInImageMode(): Boolean
    }

    private val messagesMutex = Mutex()

    // 名称预览缓存由上层持有，这里只负责使用 cacheManager 生成高质量名称
    fun getConversationPreviewText(index: Int, isImageGeneration: Boolean): String {
        val conversationList = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        val conversation = conversationList.getOrNull(index) ?: return getDefaultConversationName(index, isImageGeneration)
        val preview = generateQuickPreview(conversation, isImageGeneration, index)

        scope.launch {
            try {
                val cacheKey = "${if (isImageGeneration) "img" else "txt"}_$index"
                val highQuality = cacheManager.getConversationPreview(cacheKey, conversation, isImageGeneration)
                // 上层 ViewModel 维护的 LruCache 将被更新（此处不直接操作，以避免双重状态）
            } catch (_: Exception) {
                // 忽略异常
            }
        }
        return preview
    }

    fun getConversationFullText(index: Int, isImageGeneration: Boolean): String {
        val conversationList = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        val conversation = conversationList.getOrNull(index) ?: return getDefaultConversationName(index, isImageGeneration)
        val firstUser = conversation.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }
        val raw = firstUser?.text?.trim() ?: return getDefaultConversationName(index, isImageGeneration)
        return com.example.everytalk.util.ConversationNameHelper.cleanAndTruncateText(raw, 100)
    }

    fun renameConversation(index: Int, newName: String, isImageGeneration: Boolean) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            showSnackbar("新名称不能为空")
            return
        }
        scope.launch {
            val success = withContext(Dispatchers.Default) {
                val currentHistorical = if (isImageGeneration)
                    stateHolder._imageGenerationHistoricalConversations.value
                else
                    stateHolder._historicalConversations.value
                if (index < 0 || index >= currentHistorical.size) {
                    withContext(Dispatchers.Main) { showSnackbar("无法重命名：对话索引错误") }
                    return@withContext false
                }
                val original = currentHistorical[index].toMutableList()
                var updatedOrAdded = false
                val existingTitleIndex = original.indexOfFirst { it.sender == Sender.System && it.isPlaceholderName }
                if (existingTitleIndex != -1) {
                    original[existingTitleIndex] = original[existingTitleIndex].copy(
                        text = trimmed,
                        timestamp = System.currentTimeMillis()
                    )
                    updatedOrAdded = true
                }
                if (!updatedOrAdded) {
                    val titleMessage = Message(
                        id = "title_${java.util.UUID.randomUUID()}",
                        text = trimmed,
                        sender = Sender.System,
                        timestamp = System.currentTimeMillis() - 1,
                        contentStarted = true,
                        isPlaceholderName = true
                    )
                    original.add(0, titleMessage)
                }
                val updatedList = currentHistorical.toMutableList().apply { this[index] = original.toList() }
                withContext(Dispatchers.Main.immediate) {
                    if (isImageGeneration) {
                        stateHolder._imageGenerationHistoricalConversations.value = updatedList.toList()
                    } else {
                        stateHolder._historicalConversations.value = updatedList.toList()
                    }
                }
                // 持久化交由上层 HistoryManager/持久化管线统一处理，避免直接依赖内部私有字段
                // 这里不直接写盘，以减少耦合并避免非法访问
                val loadedIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex.value
                else stateHolder._loadedHistoryIndex.value
                if (loadedIndex == index) {
                    val reloaded = original.toList().map { msg ->
                        val contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                        msg.copy(contentStarted = contentStarted)
                    }
                    messagesMutex.withLock {
                        withContext(Dispatchers.Main.immediate) {
                            if (isImageGeneration) {
                                stateHolder.imageGenerationMessages.clear()
                                stateHolder.imageGenerationMessages.addAll(reloaded)
                            } else {
                                stateHolder.messages.clear()
                                stateHolder.messages.addAll(reloaded)
                            }
                            reloaded.forEach { msg ->
                                val hasContentOrError = msg.contentStarted || msg.isError
                                val hasReasoning = !msg.reasoning.isNullOrBlank()
                                if (msg.sender == Sender.AI && hasReasoning) {
                                    if (isImageGeneration) stateHolder.imageReasoningCompleteMap[msg.id] = true
                                    else stateHolder.textReasoningCompleteMap[msg.id] = true
                                }
                                val animationPlayed = hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                                if (animationPlayed) {
                                    if (isImageGeneration) stateHolder.imageMessageAnimationStates[msg.id] = true
                                    else stateHolder.textMessageAnimationStates[msg.id] = true
                                }
                            }
                        }
                    }
                }
                true
            }
            if (success) {
                withContext(Dispatchers.Main) { showSnackbar("对话已重命名") }
            }
        }
    }

    fun loadTextHistory(index: Int) {
        scope.launch {
            stateHolder._isLoadingHistory.value = true
            try {
                simpleModeSwitcher.loadTextHistory(index)
                // Drawer 搜索状态由上层 UI/DrawerManager 负责，这里不直接修改
            } catch (e: Exception) {
                Log.e("HistoryController", "Error loading text history", e)
                showSnackbar("加载文本历史对话失败: ${e.message}")
            } finally {
                stateHolder._isLoadingHistory.value = false
            }
        }
    }

    fun loadImageHistory(index: Int) {
        scope.launch {
            stateHolder._isLoadingImageHistory.value = true
            try {
                simpleModeSwitcher.loadImageHistory(index)
                val processed = processLoadedMessages(stateHolder.imageGenerationMessages.toList())
                val repaired = repairHistoryMessageParts(processed)
                stateHolder.imageGenerationMessages.clear()
                stateHolder.imageGenerationMessages.addAll(repaired)
                // Drawer 搜索状态由上层 UI/DrawerManager 负责，这里不直接修改
            } catch (e: Exception) {
                Log.e("HistoryController", "IMAGE ERROR", e)
                showSnackbar("加载图像历史失败: ${e.message}")
            } finally {
                stateHolder._isLoadingImageHistory.value = false
            }
        }
    }

    fun deleteConversation(indexToDelete: Int, isImageGeneration: Boolean = false) {
        val currentLoadedIndex = if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex.value
        else stateHolder._loadedHistoryIndex.value
        val conversations = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations.value
        else stateHolder._historicalConversations.value
        if (indexToDelete < 0 || indexToDelete >= conversations.size) {
            showSnackbar("无法删除：无效的索引")
            return
        }
        scope.launch {
            val wasCurrentDeleted = (currentLoadedIndex == indexToDelete)
            val idsInDeleted = conversations.getOrNull(indexToDelete)?.map { it.id } ?: emptyList()

            withContext(Dispatchers.IO) { historyManager.deleteConversation(indexToDelete, isImageGeneration) }

            if (wasCurrentDeleted) {
                if (isImageGeneration) simpleModeSwitcher.switchToImageMode(forceNew = true, skipSavingImageChat = true)
                else simpleModeSwitcher.switchToTextMode(forceNew = true, skipSavingTextChat = true)
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            }

            if (idsInDeleted.isNotEmpty()) {
                if (isImageGeneration) {
                    stateHolder.imageReasoningCompleteMap.keys.removeAll(idsInDeleted)
                    stateHolder.imageExpandedReasoningStates.keys.removeAll(idsInDeleted)
                    stateHolder.imageMessageAnimationStates.keys.removeAll(idsInDeleted)
                } else {
                    stateHolder.textReasoningCompleteMap.keys.removeAll(idsInDeleted)
                    stateHolder.textExpandedReasoningStates.keys.removeAll(idsInDeleted)
                    stateHolder.textMessageAnimationStates.keys.removeAll(idsInDeleted)
                }
            }

            // 强制触发 StateFlow 更新
            if (isImageGeneration) {
                stateHolder._imageGenerationHistoricalConversations.value =
                    stateHolder._imageGenerationHistoricalConversations.value.toList()
            } else {
                stateHolder._historicalConversations.value = stateHolder._historicalConversations.value.toList()
            }
        }
    }

    fun clearAllConversations(isImageGeneration: Boolean = false) {
        scope.launch {
            if (!isImageGeneration) {
                withContext(Dispatchers.IO) { historyManager.clearAllHistory() }
                messagesMutex.withLock {
                    stateHolder.clearForNewTextChat()
                    if (shouldAutoScroll()) triggerScrollToBottom()
                }
                showSnackbar("所有对话已清除")
            } else {
                withContext(Dispatchers.IO) { historyManager.clearAllHistory(isImageGeneration = true) }
                messagesMutex.withLock {
                    stateHolder.clearForNewImageChat()
                    if (shouldAutoScroll()) triggerScrollToBottom()
                }
                showSnackbar("所有图像生成对话已清除")
            }
        }
    }

    private fun processLoadedMessages(messages: List<Message>): List<Message> {
        return messages.map { message ->
            if (message.sender == Sender.AI && message.text.isNotBlank()) {
                message.copy(contentStarted = true)
            } else {
                message
            }
        }
    }

    private fun repairHistoryMessageParts(messages: List<Message>): List<Message> {
        return messages.map { message ->
            if (message.sender == Sender.AI &&
                message.text.isNotBlank() &&
                (message.parts.isEmpty() || !hasValidParts(message.parts))) {
                Log.d("HistoryController", "Repairing message parts for messageId=${message.id}")
                try {
                    val sessionId = stateHolder._currentConversationId.value
                    val tempProcessor = com.example.everytalk.util.messageprocessor.MessageProcessor().apply {
                        initialize(sessionId, message.id)
                    }
                    val repaired = tempProcessor.finalizeMessageProcessing(message)
                    Log.d("HistoryController", "Repaired parts: ${repaired.parts.size}")
                    repaired
                } catch (e: Exception) {
                    Log.w("HistoryController", "Failed to repair parts for ${message.id}: ${e.message}")
                    message
                }
            } else {
                message
            }
        }
    }

    private fun hasValidParts(parts: List<com.example.everytalk.ui.components.MarkdownPart>): Boolean {
        return parts.any { part ->
            when (part) {
                is com.example.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                is com.example.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
                else -> true
            }
        }
    }

    private fun generateQuickPreview(conversation: List<Message>, isImageGeneration: Boolean, index: Int): String {
        val firstUserMessage = conversation.firstOrNull {
            it.sender == com.example.everytalk.data.DataClass.Sender.User && it.text.isNotBlank()
        }
        val rawText = firstUserMessage?.text?.trim()
        if (rawText.isNullOrBlank()) {
            return getDefaultConversationName(index, isImageGeneration)
        }
        return com.example.everytalk.util.ConversationNameHelper.cleanAndTruncateText(rawText, 40)
    }

    private fun getDefaultConversationName(index: Int, isImageGeneration: Boolean): String {
        return com.example.everytalk.util.ConversationNameHelper.getDefaultConversationName(index, isImageGeneration)
    }
}