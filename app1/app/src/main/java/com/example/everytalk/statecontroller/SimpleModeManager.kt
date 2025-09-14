package com.example.everytalk.statecontroller

import android.util.Log
import com.example.everytalk.data.DataClass.Message
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
    suspend fun switchToTextMode(forceNew: Boolean = false) {
        Log.d(TAG, "Switching to TEXT mode (forceNew: $forceNew)")
        
        // 跟踪模式切换（立即更新意图模式，供UI使用）
        _currentMode = ModeType.TEXT
        _lastModeSwitch = System.currentTimeMillis()
        _uiMode.value = ModeType.TEXT
        
        // 1. 同步保存图像模式的当前状态 - 确保状态切换的原子性
        withContext(Dispatchers.IO) {
            historyManager.saveCurrentChatToHistoryIfNeeded(
                isImageGeneration = true,
                forceSave = true
            )
        }
        
        // 2. 清理图像模式状态
        clearImageApiState()
        
        // 3. 强制清除图像模式的历史记录索引，确保完全独立
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        stateHolder.imageGenerationMessages.clear()
        
        // 4. 如果强制新建，清除文本模式状态
        if (forceNew) {
            stateHolder.messages.clear()
            stateHolder._loadedHistoryIndex.value = null
            stateHolder._currentConversationId.value = "chat_${UUID.randomUUID()}"
            stateHolder.systemPrompts[stateHolder._currentConversationId.value] = ""
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
    suspend fun switchToImageMode(forceNew: Boolean = false) {
        Log.d(TAG, "Switching to IMAGE mode (forceNew: $forceNew)")
        
        // 跟踪模式切换（立即更新意图模式，供UI使用）
        _currentMode = ModeType.IMAGE
        _lastModeSwitch = System.currentTimeMillis()
        _uiMode.value = ModeType.IMAGE
        
        // 1. 同步保存文本模式的当前状态 - 确保状态切换的原子性
        withContext(Dispatchers.IO) {
            historyManager.saveCurrentChatToHistoryIfNeeded(
                isImageGeneration = false,
                forceSave = true
            )
        }
        
        // 2. 清理文本模式状态
        clearTextApiState()
        
        // 3. 强制清除文本模式的历史记录索引，确保完全独立
        stateHolder._loadedHistoryIndex.value = null
        stateHolder.messages.clear()
        
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
        
        // 同步保存当前状态 - 确保状态切换的一致性
        withContext(Dispatchers.IO) {
            historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false, forceSave = true)
            historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true, forceSave = true)
        }

        // 1. 完全重置文本模式状态 - 确保独立加载
        Log.d(TAG, "🔥 [STEP 1] 完全重置文本模式状态...")
        clearTextApiState()
        
        // 关键修复：强制清除图像模式索引，确保文本模式历史记录选择完全独立
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        stateHolder.imageGenerationMessages.clear()
        
        // 清空文本模式消息列表和索引
        stateHolder.messages.clear()
        stateHolder._loadedHistoryIndex.value = null
        
        Log.d(TAG, "🔥 [STEP 1] 状态重置完成")
        
        // 2. 验证索引
        Log.d(TAG, "🔥 [STEP 2] Validating index...")
        val conversationList = stateHolder._historicalConversations.value
        Log.d(TAG, "🔥 [STEP 2] ConversationList size: ${conversationList.size}")
        if (index < 0 || index >= conversationList.size) {
            Log.e(TAG, "🔥 [ERROR] Invalid TEXT history index: $index (size: ${conversationList.size})")
            return
        }
        
        // 3. 再次确保文本模式状态清理
        Log.d(TAG, "🔥 [STEP 3] 再次确保文本模式状态清理...")
        clearTextApiState()
        Log.d(TAG, "🔥 [STEP 3] 状态清理完成")
        
        // 4. 加载历史对话
        Log.d(TAG, "🔥 [STEP 4] Loading conversation...")
        val conversationToLoad = conversationList[index]
        Log.d(TAG, "🔥 [STEP 4] Conversation loaded, size: ${conversationToLoad.size}")
        conversationToLoad.forEachIndexed { i, msg ->
            Log.d(TAG, "🔥 [STEP 4] Message $i: sender=${msg.sender}, text='${msg.text.take(50)}...', id=${msg.id}")
        }
        
        // 5. 设置对话ID和系统提示（必须在消息加载前设置）
        Log.d(TAG, "🔥 [STEP 5] Setting conversation ID...")
        val stableId = conversationToLoad.firstOrNull()?.id ?: "history_${UUID.randomUUID()}"
        Log.d(TAG, "🔥 [STEP 5] StableId: $stableId")
        stateHolder._currentConversationId.value = stableId
        Log.d(TAG, "🔥 [STEP 5] ConversationId set to: ${stateHolder._currentConversationId.value}")
        
        val systemPrompt = conversationToLoad
            .firstOrNull { it.sender == com.example.everytalk.data.DataClass.Sender.System && !it.isPlaceholderName }?.text ?: ""
        stateHolder.systemPrompts[stableId] = systemPrompt
        Log.d(TAG, "🔥 [STEP 5] SystemPrompt set: '$systemPrompt'")
        
        // 6. 处理消息并更新状态
        Log.d(TAG, "🔥 [STEP 6] Processing and updating message states...")
        Log.d(TAG, "🔥 [STEP 6] Before clear - messages.size: ${stateHolder.messages.size}")
        stateHolder.messages.clear()
        Log.d(TAG, "🔥 [STEP 6] After clear - messages.size: ${stateHolder.messages.size}")
        
        // 处理消息：设置 contentStarted 状态并添加到列表
        val processedMessages = conversationToLoad.map { msg ->
            val updatedContentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
            msg.copy(contentStarted = updatedContentStarted)
        }
        
        // 添加处理后的消息
        stateHolder.messages.addAll(processedMessages)
        Log.d(TAG, "🔥 [STEP 6] Added ${processedMessages.size} processed messages - total size: ${stateHolder.messages.size}")
        
        // 设置推理和动画状态
        processedMessages.forEach { msg ->
            val hasContentOrError = msg.contentStarted || msg.isError
            val hasReasoning = !msg.reasoning.isNullOrBlank()
            
            if (msg.sender == com.example.everytalk.data.DataClass.Sender.AI && hasReasoning) {
                stateHolder.textReasoningCompleteMap[msg.id] = true
            }
            
            val animationPlayedCondition = hasContentOrError || (msg.sender == com.example.everytalk.data.DataClass.Sender.AI && hasReasoning)
            if (animationPlayedCondition) {
                stateHolder.textMessageAnimationStates[msg.id] = true
            }
        }
        
        Log.d(TAG, "🔥 [STEP 6] Before setting loadedHistoryIndex: ${stateHolder._loadedHistoryIndex.value}")
        stateHolder._loadedHistoryIndex.value = index
        Log.d(TAG, "🔥 [STEP 6] After setting loadedHistoryIndex: ${stateHolder._loadedHistoryIndex.value}")
        
        // 7. 最终确认图像模式状态清空
        Log.d(TAG, "🔥 [STEP 7] 最终确认图像模式状态清空...")
        stateHolder.imageGenerationMessages.clear()
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        Log.d(TAG, "🔥 [STEP 7] 图像模式状态已清空")
        
        // 8. 重置输入框
        Log.d(TAG, "🔥 [STEP 8] Resetting input text...")
        Log.d(TAG, "🔥 [STEP 8] Before reset - text: '${stateHolder._text.value}'")
        stateHolder._text.value = ""
        Log.d(TAG, "🔥 [STEP 8] After reset - text: '${stateHolder._text.value}'")
        
        Log.d(TAG, "🔥 [END] Loaded TEXT history successfully: ${conversationToLoad.size} messages")
        Log.d(TAG, "🔥 [FINAL STATE] messages.size=${stateHolder.messages.size}, loadedIndex=${stateHolder._loadedHistoryIndex.value}, conversationId=${stateHolder._currentConversationId.value}")
    }
    
    /**
     * 安全的历史记录加载 - 图像模式
     */
    suspend fun loadImageHistory(index: Int) {
        Log.d(TAG, "Loading IMAGE history at index: $index")
        
        // 同步保存当前状态 - 确保状态切换的一致性
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
        stateHolder.messages.clear()
        
        // 清理图像模式状态
        stateHolder.imageGenerationMessages.clear()
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
        return stateHolder.messages.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null
    }
    
    /**
     * 获取当前是否在图像模式
     */
    fun isInImageMode(): Boolean {
        return stateHolder.imageGenerationMessages.isNotEmpty() || stateHolder._loadedImageGenerationHistoryIndex.value != null
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