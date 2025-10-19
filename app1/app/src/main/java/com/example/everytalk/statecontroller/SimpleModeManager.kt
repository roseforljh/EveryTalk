package com.example.everytalk.statecontroller

import android.util.Log
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 简化的模式管理器 - 专门解决模式切换问题
 */
class SimpleModeManager(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope
) {
    private val TAG = "SimpleModeManager"
    
    // 增加明确的模式状态跟踪 - 解决forceNew导致的状态清空问题
    private var _currentMode: ModeType = ModeType.NONE
    private var _lastModeSwitch: Long = 0L

    // 新增：用于UI即时感知的“意图模式”（优先于内容态）
    private val _uiMode: MutableStateFlow<ModeType> = MutableStateFlow(ModeType.NONE)
    val uiModeFlow: StateFlow<ModeType> = _uiMode.asStateFlow()

    init {
        // 初始化时根据现有内容态估算一次，避免初次进入时为 NONE
        _uiMode.value = getCurrentMode()
    }
    
    /**
     * 获取当前模式（考虑最近的模式切换）
     */
    fun getCurrentMode(): ModeType {
        val hasTextContent = stateHolder.messages.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null
        val hasImageContent = stateHolder.imageGenerationMessages.isNotEmpty() || stateHolder._loadedImageGenerationHistoryIndex.value != null
        
        return when {
            hasImageContent && !hasTextContent -> ModeType.IMAGE
            hasTextContent && !hasImageContent -> ModeType.TEXT
            !hasTextContent && !hasImageContent -> {
                // 如果没有内容，但有最近的模式切换记录，使用记录的模式
                val timeSinceLastSwitch = System.currentTimeMillis() - _lastModeSwitch
                if (timeSinceLastSwitch < 5000L && _currentMode != ModeType.NONE) {
                    Log.d(TAG, "Using tracked mode: $_currentMode (${timeSinceLastSwitch}ms ago)")
                    _currentMode
                } else {
                    ModeType.NONE
                }
            }
            else -> {
                // 异常情况：同时有两种模式的内容，记录警告并默认返回文本模式
                Log.w(TAG, "Warning: Both text and image content detected. Defaulting to TEXT mode.")
                ModeType.TEXT
            }
        }
    }
    
    /**
     * 安全的模式切换到文本模式
     */
    suspend fun switchToTextMode(forceNew: Boolean = false, skipSavingTextChat: Boolean = false) {
        Log.d(TAG, "Switching to TEXT mode (forceNew: $forceNew, skipSavingTextChat: $skipSavingTextChat)")
        
        // 跟踪模式切换（立即更新意图模式，供UI使用）
        _currentMode = ModeType.TEXT
        _lastModeSwitch = System.currentTimeMillis()
        _uiMode.value = ModeType.TEXT
        
        // 若当前文本会话为空且仅"应用了参数未发消息"，按要求删除该空会话（丢弃pending）
        if (stateHolder.messages.isEmpty() && stateHolder.hasPendingConversationParams()) {
            stateHolder.abandonEmptyPendingConversation()
        }
        
        // 1. 同步保存两种模式的当前状态 - 确保状态切换的原子性
        // 1. 保存当前会话，这是关键的第一步
        withContext(Dispatchers.IO) {
            if (!skipSavingTextChat) {
                historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false, forceSave = true)
            }
            historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true, forceSave = true)
        }
        
        // 2. 然后再清理状态
        clearImageApiState()
        
        // 🔥 添加调试日志，诊断会话切换问题
        Log.d(TAG, "=== MODE SWITCH DEBUG (TO TEXT) ===")
        Log.d(TAG, "Current image messages before clear: ${stateHolder.imageGenerationMessages.map { "ID:${it.id}, Text:'${it.text.take(20)}...', ContentStarted:${it.contentStarted}" }}")
        Log.d(TAG, "Current loaded image history index before clear: ${stateHolder._loadedImageGenerationHistoryIndex.value}")
        Log.d(TAG, "Current image conversation ID before clear: ${stateHolder._currentImageGenerationConversationId.value}")
        
        // 🔥 修复：移到清空消息列表之后调用，避免清理正在使用的处理器
        // val currentImageSessionId = stateHolder._currentImageGenerationConversationId.value
        // stateHolder.getApiHandler().clearImageChatResources(currentImageSessionId)
        
        // 3. 强制清除图像模式的历史记录索引，确保完全独立
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        Log.d(TAG, "Cleared loaded image history index")
        
        // 保留消息列表（不再在模式切换时清空）
        val imageMessagesBeforeClear = stateHolder.imageGenerationMessages.toList()
        Log.d(TAG, "Preserved ${imageMessagesBeforeClear.size} image messages (no clear on mode switch)")
        
        // 🔥 修复：在清空消息列表后才清理资源，这样不会清理当前文本模式的消息处理器
        val currentImageSessionId = stateHolder._currentImageGenerationConversationId.value
        Log.d(TAG, "Calling clearImageChatResources with session ID: $currentImageSessionId")
        stateHolder.getApiHandler().clearImageChatResources(currentImageSessionId)
        Log.d(TAG, "=== END MODE SWITCH DEBUG ===")
        
        // 4. 如果强制新建，清除文本模式状态
        if (forceNew) {
            stateHolder.messages.clear()
            stateHolder._loadedHistoryIndex.value = null
            // 新会话是全新的、独立的：禁止任何迁移/继承
            val newId = "chat_${UUID.randomUUID()}"
            stateHolder._currentConversationId.value = newId
            stateHolder.systemPrompts[newId] = ""
            // 不为新会话自动回填会话参数，保持默认关闭
        }
        
        // 5. 重置输入框
        stateHolder._text.value = ""
        
        // 6. 验证状态切换完成 - 确保模式切换的原子性
        val currentMode = getCurrentMode()
        Log.d(TAG, "State validation - currentMode: $currentMode, isInTextMode: ${isInTextMode()}, isInImageMode: ${isInImageMode()}")
        
        Log.d(TAG, "Switched to TEXT mode successfully")
    }
    
    /**
     * 安全的模式切换到图像模式
     */
    suspend fun switchToImageMode(forceNew: Boolean = false, skipSavingImageChat: Boolean = false) {
        Log.d(TAG, "Switching to IMAGE mode (forceNew: $forceNew, skipSavingImageChat: $skipSavingImageChat)")
        
        // 跟踪模式切换（立即更新意图模式，供UI使用）
        _currentMode = ModeType.IMAGE
        _lastModeSwitch = System.currentTimeMillis()
        _uiMode.value = ModeType.IMAGE
        
        // 若当前文本会话为空且仅"应用了参数未发消息"，按要求删除该空会话（丢弃pending）
        if (stateHolder.messages.isEmpty() && stateHolder.hasPendingConversationParams()) {
            stateHolder.abandonEmptyPendingConversation()
        }
        
        // 1. 同步保存两种模式的当前状态 - 确保状态切换的原子性
        withContext(Dispatchers.IO) {
            // 保存文本模式的当前会话
            historyManager.saveCurrentChatToHistoryIfNeeded(
                isImageGeneration = false,
                forceSave = true
            )
            // 如果forceNew为true，也要保存图像模式的当前会话
            if (!skipSavingImageChat) {
                if (forceNew && stateHolder.imageGenerationMessages.isNotEmpty()) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(
                        isImageGeneration = true,
                        forceSave = true
                    )
                }
            }
        }
        
        // 2. 清理文本模式状态
        clearTextApiState()
        
        // 🔥 添加调试日志，诊断会话切换问题
        Log.d(TAG, "=== MODE SWITCH DEBUG (TO IMAGE) ===")
        Log.d(TAG, "Current text messages before clear: ${stateHolder.messages.map { "ID:${it.id}, Text:'${it.text.take(20)}...', ContentStarted:${it.contentStarted}" }}")
        Log.d(TAG, "Current loaded history index before clear: ${stateHolder._loadedHistoryIndex.value}")
        Log.d(TAG, "Current conversation ID before clear: ${stateHolder._currentConversationId.value}")
        
        // 🔥 修复：移到清空消息列表之后调用，避免清理正在使用的处理器
        // val currentTextSessionId = stateHolder._currentConversationId.value
        // stateHolder.getApiHandler().clearTextChatResources(currentTextSessionId)
        
        // 3. 强制清除文本模式的历史记录索引，确保完全独立
        stateHolder._loadedHistoryIndex.value = null
        Log.d(TAG, "Cleared loaded history index")
        
        // 保留文本消息列表（不再在模式切换时清空）
        val messagesBeforeClear = stateHolder.messages.toList()
        Log.d(TAG, "Preserved ${messagesBeforeClear.size} text messages (no clear on mode switch)")
        
        // 🔥 修复：在清空消息列表后才清理资源，这样不会清理当前图像模式的消息处理器
        val currentTextSessionId = stateHolder._currentConversationId.value
        Log.d(TAG, "Calling clearTextChatResources with session ID: $currentTextSessionId")
        stateHolder.getApiHandler().clearTextChatResources(currentTextSessionId)
        Log.d(TAG, "=== END MODE SWITCH DEBUG ===")
        
        // 4. 如果强制新建，清除图像模式状态
        if (forceNew) {
            stateHolder.imageGenerationMessages.clear()
            stateHolder._loadedImageGenerationHistoryIndex.value = null
            stateHolder._currentImageGenerationConversationId.value = "image_generation_${UUID.randomUUID()}"
        }
        
        // 5. 重置输入框
        stateHolder._text.value = ""
        
        // 6. 验证状态切换完成 - 确保模式切换的原子性
        val currentMode = getCurrentMode()
        Log.d(TAG, "State validation - currentMode: $currentMode, isInTextMode: ${isInTextMode()}, isInImageMode: ${isInImageMode()}")
        
        Log.d(TAG, "Switched to IMAGE mode successfully")
    }
    
    /**
     * 安全的历史记录加载 - 文本模式（完全独立加载）
     */
    suspend fun loadTextHistory(index: Int) {
        Log.d(TAG, "🔥 [START] Loading TEXT history at index: $index")
        _uiMode.value = ModeType.TEXT // 立即更新意图

        // 关键修复：在加载历史之前，不再强制保存当前会话，避免索引和状态错乱
        // if (stateHolder.messages.isNotEmpty() || stateHolder.hasPendingConversationParams()) {
        //     withContext(Dispatchers.IO) {
        //         historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false, forceSave = true)
        //     }
        // }
        
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) {
            Log.e(TAG, "🔥 [ERROR] Invalid TEXT history index: $index (size: ${conversationList.size})")
            stateHolder._isLoadingHistory.value = false
            return
        }

        val conversationToLoad = conversationList[index]
        Log.d(TAG, "🔥 Found conversation to load with ${conversationToLoad.size} messages.")
        val stableId = conversationToLoad.firstOrNull { it.sender == Sender.User }?.id
            ?: conversationToLoad.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
            ?: conversationToLoad.firstOrNull()?.id
            ?: "history_${UUID.randomUUID()}"
        val systemPrompt = conversationToLoad.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.text ?: ""
        Log.d(TAG, "🔥 Stable ID: $stableId, System Prompt: '$systemPrompt'")

        val processedMessages = withContext(Dispatchers.Default) {
            conversationToLoad.map { msg ->
                // 🔥 修复：处理AI消息文本丢失问题
                if (msg.sender == Sender.AI) {
                    android.util.Log.d("SimpleModeManager", "Processing AI message ${msg.id}: text length=${msg.text.length}, parts=${msg.parts.size}, contentStarted=${msg.contentStarted}")
                    
                    if (msg.text.isBlank() && msg.parts.isNotEmpty()) {
                        // 尝试从parts重建文本内容
                        val rebuiltText = msg.parts.filterIsInstance<com.example.everytalk.ui.components.MarkdownPart.Text>()
                            .joinToString("") { it.content }
                        
                        if (rebuiltText.isNotBlank()) {
                            android.util.Log.d("SimpleModeManager", "Rebuilt AI message text from parts: ${rebuiltText.take(50)}...")
                            msg.copy(text = rebuiltText, contentStarted = true)
                        } else if (msg.contentStarted && msg.text.isBlank()) {
                            // 如果contentStarted=true但文本为空，至少保留占位符
                            android.util.Log.w("SimpleModeManager", "AI message ${msg.id} has contentStarted=true but empty text, using placeholder")
                            msg.copy(text = "...", contentStarted = true)
                        } else {
                            msg
                        }
                    } else if (msg.parts.isEmpty() && msg.text.isNotBlank()) {
                        val tempProcessor = com.example.everytalk.util.messageprocessor.MessageProcessor().apply { initialize(stableId, msg.id) }
                        tempProcessor.finalizeMessageProcessing(msg)
                    } else {
                        msg
                    }
                } else {
                    msg
                }
            }.map { msg ->
                // 🔥 修复：确保AI消息总是有 contentStarted = true，即使文本为空
                val updatedContentStarted = when {
                    msg.sender == Sender.AI -> true  // AI消息始终设置为true
                    else -> msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                }
                msg.copy(contentStarted = updatedContentStarted)
            }
        }
        Log.d(TAG, "🔥 Processed ${processedMessages.size} messages.")

        withContext(Dispatchers.Main.immediate) {
            Log.d(TAG, "🔥 Updating state on Main thread...")
            clearTextApiState()
            stateHolder._loadedImageGenerationHistoryIndex.value = null
            // 保留图像消息（不在加载文本历史时清空）
            Log.d(TAG, "🔥 Preserved image generation messages (${stateHolder.imageGenerationMessages.size} messages).")
            
            stateHolder._currentConversationId.value = stableId
            stateHolder.systemPrompts[stableId] = systemPrompt
            Log.d(TAG, "🔥 Set current conversation ID and system prompt.")

            stateHolder.messages.clear()
            stateHolder.messages.addAll(processedMessages)
            Log.d(TAG, "🔥 Loaded messages into state.")
            
            processedMessages.forEach { msg ->
                val hasContentOrError = msg.contentStarted || msg.isError
                val hasReasoning = !msg.reasoning.isNullOrBlank()
                if (msg.sender == Sender.AI && hasReasoning) stateHolder.textReasoningCompleteMap[msg.id] = true
                if (hasContentOrError || (msg.sender == Sender.AI && hasReasoning)) stateHolder.textMessageAnimationStates[msg.id] = true
            }
            Log.d(TAG, "🔥 Set reasoning and animation states.")
            
            stateHolder._loadedHistoryIndex.value = index
            stateHolder._text.value = ""
            Log.d(TAG, "🔥 Set loaded history index to $index and cleared text input.")
            // Reset dirty flag after loading history to avoid unnecessary saves
            stateHolder.isTextConversationDirty.value = false
        
        Log.d(TAG, "🔥 [END] Loaded TEXT history successfully: ${conversationToLoad.size} messages")
    }
    }
    
    /**
     * 安全的历史记录加载 - 图像模式
     */
    suspend fun loadImageHistory(index: Int) {
        Log.d(TAG, "Loading IMAGE history at index: $index")
        
        // 同步保存当前状态 - 确保状态切换的一致性
        // 1. 先保存所有模式的当前状态
        // 关键修复：必须先保存当前会话，再清理状态以加载新会话
        // 关键修复：必须先保存当前会话，再清理状态以加载新会话
        // 关键修复：必须先保存当前会话，再清理状态以加载新会话
        withContext(Dispatchers.IO) {
            historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false, forceSave = true)
            historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true, forceSave = true)
        }
        
        // 2. 验证索引
        val conversationList = stateHolder._imageGenerationHistoricalConversations.value
        if (index < 0 || index >= conversationList.size) {
            Log.e(TAG, "Invalid IMAGE history index: $index (size: ${conversationList.size})")
            return
        }
        
        // 3. 清理图像模式状态
        clearImageApiState()
        
        // 关键修复：强制清除文本模式索引，确保图像模式历史记录选择完全独立
        stateHolder._loadedHistoryIndex.value = null
        // 保留文本消息（不在加载图像历史时清空）
        Log.d(TAG, "Preserved text messages (${stateHolder.messages.size} messages).")
        
        // 清理图像模式状态（仅清除加载索引，不清空消息）
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        
        // 4. 加载历史对话
        val conversationToLoad = conversationList[index]
        
        // 5. 设置对话ID（必须在消息加载前设置）
        val stableId = conversationToLoad.firstOrNull()?.id ?: "image_history_${UUID.randomUUID()}"
        stateHolder._currentImageGenerationConversationId.value = stableId
        
        // 6. 处理消息并更新状态
        stateHolder.imageGenerationMessages.clear()
        
        // 处理消息：设置 contentStarted 状态
        val processedMessages = conversationToLoad.map { msg ->
            val updatedContentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
            msg.copy(contentStarted = updatedContentStarted)
        }
        
        stateHolder.imageGenerationMessages.addAll(processedMessages)
        
        // 设置推理和动画状态
        processedMessages.forEach { msg ->
            val hasContentOrError = msg.contentStarted || msg.isError
            val hasReasoning = !msg.reasoning.isNullOrBlank()
            
            if (msg.sender == com.example.everytalk.data.DataClass.Sender.AI && hasReasoning) {
                stateHolder.imageReasoningCompleteMap[msg.id] = true
            }
            
            val animationPlayedCondition = hasContentOrError || (msg.sender == com.example.everytalk.data.DataClass.Sender.AI && hasReasoning)
            if (animationPlayedCondition) {
                stateHolder.imageMessageAnimationStates[msg.id] = true
            }
        }
        
        stateHolder._loadedImageGenerationHistoryIndex.value = index
        
        // 7. 重置输入框
        stateHolder._text.value = ""
        
        Log.d(TAG, "Loaded IMAGE history successfully: ${conversationToLoad.size} messages")
    }
    
    /**
     * 清理文本模式API相关状态
     */
    private fun clearTextApiState() {
        stateHolder.clearForNewTextChat()
    }
    
    /**
     * 清理图像模式API相关状态
     */
    private fun clearImageApiState() {
        stateHolder.clearForNewImageChat()
    }
    
    /**
     * 获取当前是否在文本模式
     */
    fun isInTextMode(): Boolean {
        return _uiMode.value == ModeType.TEXT
    }
    
    /**
     * 获取当前是否在图像模式
     */
    fun isInImageMode(): Boolean {
        return _uiMode.value == ModeType.IMAGE
    }
    
    enum class ModeType {
        TEXT, IMAGE, NONE
    }
    
    /**
     * 获取当前模式的消息数量
     */
    fun getCurrentModeMessageCount(): Int {
        return when {
            isInTextMode() -> stateHolder.messages.size
            isInImageMode() -> stateHolder.imageGenerationMessages.size
            else -> 0
        }
    }
}