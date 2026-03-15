package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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

    // 新增：用于UI即时感知的"意图模式"（优先于内容态）
    private val _uiMode: MutableStateFlow<ModeType> = MutableStateFlow(ModeType.NONE)
    val uiModeFlow: StateFlow<ModeType> = _uiMode.asStateFlow()
    
    // 新增：模式切换提示消息
    private val _modeSwitchMessage: MutableSharedFlow<String> = MutableSharedFlow()
    val modeSwitchMessage: SharedFlow<String> = _modeSwitchMessage.asSharedFlow()

    init {
        // 初始化时根据现有内容态估算一次，避免初次进入时为 NONE
        _uiMode.value = getCurrentMode()
    }
    
    /**
     * 获取当前模式（考虑最近的模式切换）
     */
    fun getCurrentMode(): ModeType {
        // 优先使用"意图模式"（UI权威来源），避免因保留历史索引导致误判
        val intended = _uiMode.value
        if (intended != ModeType.NONE) return intended

        val hasTextContent = stateHolder.messages.isNotEmpty() || stateHolder._loadedHistoryIndex.value != null
        val hasImageContent = stateHolder.imageGenerationMessages.isNotEmpty() || stateHolder._loadedImageGenerationHistoryIndex.value != null

        return when {
            hasImageContent && !hasTextContent -> ModeType.IMAGE
            hasTextContent && !hasImageContent -> ModeType.TEXT
            !hasTextContent && !hasImageContent -> {
                // 若无内容，则回退到最近一次切换记录
                val timeSinceLastSwitch = System.currentTimeMillis() - _lastModeSwitch
                if (timeSinceLastSwitch < 5000L && _currentMode != ModeType.NONE) {
                    Log.d(TAG, "Using tracked mode: $_currentMode (${timeSinceLastSwitch}ms ago)")
                    _currentMode
                } else {
                    ModeType.NONE
                }
            }
            else -> {
                // 同时存在两种内容的极端情况，仍以 UI 意图为准；若无意图则默认 TEXT
                Log.w(TAG, "Warning: Both text and image content detected. Falling back to intended or TEXT.")
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
        
        // 关键修复1：在保存前记录图像会话的状态
        val imageHistoryIndexBeforeSave = stateHolder._loadedImageGenerationHistoryIndex.value
        val imageMessagesBeforeSave = stateHolder.imageGenerationMessages.toList()
        val hasImageContent = imageMessagesBeforeSave.isNotEmpty()
        
        Log.d(TAG, "Image state before save: index=$imageHistoryIndexBeforeSave, messages=${imageMessagesBeforeSave.size}, hasContent=$hasImageContent")
        
        
        val savedImageIndex = run {
            if (!skipSavingTextChat) {
                // 切换到文本模式时，如有文本索引会在其自身路径保存；这里不强制保存，避免误插入
                historyManager.saveCurrentChatToHistoryNow(forceSave = false, isImageGeneration = false)
            }
            if (imageHistoryIndexBeforeSave != null) {
                Log.d(TAG, "Skipping save: image conversation already in history at index $imageHistoryIndexBeforeSave")
                imageHistoryIndexBeforeSave
            } else if (hasImageContent) {
                // 先尝试指纹查找，避免重复插入
                val foundIdx = historyManager.findChatInHistory(imageMessagesBeforeSave, isImageGeneration = true)
                if (foundIdx >= 0) {
                    Log.d(TAG, "Reuse existing IMAGE conversation by fingerprint at index $foundIdx")
                    foundIdx
                } else {
                    Log.i(TAG, "Mode switch: no IMAGE history index and no fingerprint match; skip insert and keep as last-open")
                    null
                }
            } else {
                null
            }
        }
        
        Log.d(TAG, "Image index after save: $savedImageIndex")
        
        
        clearImageApiState()
        
       
        stateHolder._loadedImageGenerationHistoryIndex.value = savedImageIndex
        Log.d(TAG, "Preserved image history index: $savedImageIndex")
        
        // 保留图像消息（不清空）
        Log.d(TAG, "Preserved ${stateHolder.imageGenerationMessages.size} image messages")
        
        val currentImageSessionId = stateHolder._currentImageGenerationConversationId.value
        stateHolder.getApiHandler().clearImageChatResources(currentImageSessionId)
        
        // 如果强制新建，清除文本模式状态
        if (forceNew) {
            stateHolder.messages.clear()
            stateHolder._loadedHistoryIndex.value = null
            // 新会话是全新的、独立的：禁止任何迁移/继承
            val newId = "chat_${UUID.randomUUID()}"
            stateHolder._currentConversationId.value = newId
            stateHolder.updateOpenClawSessionId(null)
            stateHolder.systemPrompts[newId] = ""
            // 不为新会话自动回填会话参数，保持默认关闭
        }
        
        // 重置输入框
        stateHolder._text.value = ""
        
        // 验证状态切换完成 - 确保模式切换的原子性
        val currentMode = getCurrentMode()
        Log.d(TAG, "State validation - currentMode: $currentMode, isInTextMode: ${isInTextMode()}, isInImageMode: ${isInImageMode()}")
        
        Log.d(TAG, "Switched to TEXT mode successfully")

        // 仅在非 forceNew 时，才考虑自动回填"文本模式历史第一个会话"
        if (!forceNew) {
            try {
                val textHistory = stateHolder._historicalConversations.value
                val loadedIdx = stateHolder._loadedHistoryIndex.value
                if (textHistory.isNotEmpty() && loadedIdx == null && stateHolder.messages.isEmpty()) {
                    Log.d(TAG, "Auto-loading first TEXT history (index=0)")
                    // 不改变意图模式，仅填充内容
                    loadTextHistory(0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-load first TEXT history failed: ${e.message}")
            }
        }
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
        
        
        val textHistoryIndexBeforeSave = stateHolder._loadedHistoryIndex.value
        val textMessagesBeforeSave = stateHolder.messages.toList()
        val hasTextContent = textMessagesBeforeSave.isNotEmpty()
        
        Log.d(TAG, "Text state before save: index=$textHistoryIndexBeforeSave, messages=${textMessagesBeforeSave.size}, hasContent=$hasTextContent")
        
        
        val savedTextIndex = run {
            if (textHistoryIndexBeforeSave != null) {
                Log.d(TAG, "Skipping save: conversation already in history at index $textHistoryIndexBeforeSave")
                textHistoryIndexBeforeSave
            } else if (hasTextContent) {
                val foundIdx = historyManager.findChatInHistory(textMessagesBeforeSave, isImageGeneration = false)
                if (foundIdx >= 0) {
                    Log.d(TAG, "Reuse existing TEXT conversation by fingerprint at index $foundIdx")
                    foundIdx
                } else {
                    Log.i(TAG, "Mode switch: no TEXT history index and no fingerprint match; skip insert and keep as last-open")
                    null
                }
            } else {
                null
            }.also {
                // 如需，同时处理图像会话保存（少见分支，保持原意），同样同步执行以避免竞态
                if (!skipSavingImageChat) {
                    if (forceNew && stateHolder.imageGenerationMessages.isNotEmpty()) {
                        historyManager.saveCurrentChatToHistoryNow(forceSave = true, isImageGeneration = true)
                    }
                }
            }
        }
        
        Log.d(TAG, "Text index after save: $savedTextIndex")
        
        
        clearTextApiState()
        
        stateHolder._loadedHistoryIndex.value = savedTextIndex
        Log.d(TAG, "Preserved text history index: $savedTextIndex")
        
        // 保留文本消息（不清空）
        Log.d(TAG, "Preserved ${stateHolder.messages.size} text messages")
        
        val currentTextSessionId = stateHolder._currentConversationId.value
        stateHolder.getApiHandler().clearTextChatResources(currentTextSessionId)
        
        // 如果强制新建，清除图像模式状态
        if (forceNew) {
            stateHolder.imageGenerationMessages.clear()
            stateHolder._loadedImageGenerationHistoryIndex.value = null
            stateHolder._currentImageGenerationConversationId.value = "image_generation_${UUID.randomUUID()}"
        }
        
        // 重置输入框
        stateHolder._text.value = ""
        
        // 验证状态切换完成 - 确保模式切换的原子性
        val currentMode = getCurrentMode()
        Log.d(TAG, "State validation - currentMode: $currentMode, isInTextMode: ${isInTextMode()}, isInImageMode: ${isInImageMode()}")
        
        Log.d(TAG, "Switched to IMAGE mode successfully")

        // 仅在非 forceNew 时，才考虑自动回填"图像模式历史第一个会话"
        if (!forceNew) {
            try {
                val imageHistory = stateHolder._imageGenerationHistoricalConversations.value
                val loadedImgIdx = stateHolder._loadedImageGenerationHistoryIndex.value
                if (imageHistory.isNotEmpty() && loadedImgIdx == null && stateHolder.imageGenerationMessages.isEmpty()) {
                    Log.d(TAG, "Auto-loading first IMAGE history (index=0)")
                    // 不改变意图模式，仅填充内容
                    loadImageHistory(0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-load first IMAGE history failed: ${e.message}")
            }
        }
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
                // 修复：处理AI消息文本丢失问题
                if (msg.sender == Sender.AI) {
                    android.util.Log.d("SimpleModeManager", "Processing AI message ${msg.id}: text length=${msg.text.length}, parts=${msg.parts.size}, contentStarted=${msg.contentStarted}")
                    
                    if (msg.text.isBlank() && msg.parts.isNotEmpty()) {
                        // 尝试从parts重建文本内容
                        val rebuiltText = msg.parts.filterIsInstance<com.android.everytalk.ui.components.MarkdownPart.Text>()
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
                        val tempProcessor = com.android.everytalk.util.messageprocessor.MessageProcessor().apply { initialize(stableId, msg.id) }
                        tempProcessor.finalizeMessageProcessing(msg)
                    } else {
                        msg
                    }
                } else {
                    msg
                }
            }.map { msg ->
                // 修复：确保AI消息总是有 contentStarted = true，即使文本为空
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
            // 关键修复：不清除图像模式索引，保持两个模式的历史索引独立
            // stateHolder._loadedImageGenerationHistoryIndex.value = null  // 删除这行，保持图像模式索引不变
            // 保留图像消息（不在加载文本历史时清空）
            Log.d(TAG, "🔥 Preserved image generation messages (${stateHolder.imageGenerationMessages.size} messages).")
            
            stateHolder._currentConversationId.value = stableId
            stateHolder.systemPrompts[stableId] = systemPrompt
            Log.d(TAG, "🔥 Set current conversation ID and system prompt.")

            // 恢复会话使用的配置
            val savedConfigId = stateHolder.conversationApiConfigIds.value[stableId]
            if (savedConfigId != null) {
                val config = stateHolder._apiConfigs.value.find { it.id == savedConfigId }
                if (config != null) {
                    stateHolder._selectedApiConfig.value = config
                    Log.d(TAG, "🔥 Restored selected config: ${config.model}")
                } else {
                    Log.w(TAG, "🔥 Saved config ID $savedConfigId not found in current configs.")
                }
            }

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
            
            // 关键修复：在所有状态更新完成后设置索引，确保不会被清空
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
        // 保证 UI 意图模式立即切换为 IMAGE，避免与文本模式的选择状态互相干扰
        _uiMode.value = ModeType.IMAGE

        // 关键修复：与 TEXT 历史加载保持一致，这里不再强制保存任一模式的当前会话
        // - 避免在仅浏览图像历史时，意外修改文本模式的 last-open / 历史索引
        // - 图像会话如需保存，应由模式切换或显式操作路径负责
        // if (stateHolder.imageGenerationMessages.isNotEmpty()) {
        //     withContext(Dispatchers.IO) {
        //         historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = true, forceSave = false)
        //     }
        // }
        
        // 2. 验证索引
        val conversationList = stateHolder._imageGenerationHistoricalConversations.value
        if (index < 0 || index >= conversationList.size) {
            Log.e(TAG, "Invalid IMAGE history index: $index (size: ${conversationList.size})")
            return
        }
        
        // 3. 清理图像模式状态
        clearImageApiState()
        
        // 关键修复：不清除文本模式索引，保持两个模式的历史索引独立
        // stateHolder._loadedHistoryIndex.value = null  // 删除这行，保持文本模式索引不变
        // 保留文本消息（不在加载图像历史时清空）
        Log.d(TAG, "Preserved text messages (${stateHolder.messages.size} messages).")
        
        // 清理图像模式状态（仅清除加载索引，不清空消息）
        stateHolder._loadedImageGenerationHistoryIndex.value = null
        
        // 4. 加载历史对话
        val conversationToLoad = conversationList[index]
        
        // 5. 设置对话ID（必须在消息加载前设置）
        val stableId = conversationToLoad.firstOrNull()?.id ?: "image_history_${UUID.randomUUID()}"
        stateHolder._currentImageGenerationConversationId.value = stableId
        
        // 🔧 修复：恢复会话使用的图像生成配置
        val savedConfigId = stateHolder.conversationApiConfigIds.value[stableId]
        if (savedConfigId != null) {
            val config = stateHolder._imageGenApiConfigs.value.find { it.id == savedConfigId }
            if (config != null) {
                stateHolder._selectedImageGenApiConfig.value = config
                Log.d(TAG, "Restored selected image gen config: ${config.model}")
            } else {
                // 如果找不到绑定的配置（可能被删除），则清空当前选择，避免串台
                stateHolder._selectedImageGenApiConfig.value = null
                Log.w(TAG, "Saved image config ID $savedConfigId not found in current configs. Cleared selection.")
            }
        }
        
        // 6. 处理消息并更新状态
        stateHolder.imageGenerationMessages.clear()
        
        // 处理消息：设置 contentStarted 状态（包含图像URL）
        val processedMessages = conversationToLoad.map { msg ->
            // 针对图像模式，若AI消息包含图片URL，应视为已产生内容
            val hasImages = (msg.imageUrls?.isNotEmpty() == true)
            val updatedContentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError || hasImages
        
            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI) {
                if (hasImages && msg.text.isBlank() && msg.parts.isNotEmpty()) {
                    val rebuiltText = msg.parts
                        .filterIsInstance<com.android.everytalk.ui.components.MarkdownPart.Text>()
                        .joinToString("") { it.content }
                    if (rebuiltText.isNotBlank()) {
                        android.util.Log.d(TAG, "Rebuilt text for image msg ${msg.id}, images=${msg.imageUrls?.size ?: 0}")
                        msg.copy(text = rebuiltText, contentStarted = updatedContentStarted)
                    } else {
                        msg.copy(contentStarted = updatedContentStarted)
                    }
                } else {
                    msg.copy(contentStarted = updatedContentStarted)
                }
            } else {
                msg.copy(contentStarted = updatedContentStarted)
            }
        }
        
        stateHolder.imageGenerationMessages.addAll(processedMessages)
        
        // 设置推理和动画状态
        processedMessages.forEach { msg ->
            val hasContentOrError = msg.contentStarted || msg.isError
            val hasReasoning = !msg.reasoning.isNullOrBlank()
            
            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI && hasReasoning) {
                stateHolder.imageReasoningCompleteMap[msg.id] = true
            }
            
            val animationPlayedCondition = hasContentOrError || (msg.sender == com.android.everytalk.data.DataClass.Sender.AI && hasReasoning)
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
     * 主动设定"意图模式"（用于导航前/点击抽屉项时先行声明）
     * @param mode 目标模式
     * @param showToast 是否显示模式切换的Toast提示
     */
    fun setIntendedMode(mode: ModeType, showToast: Boolean = false) {
        val previousMode = _uiMode.value
        _uiMode.value = mode
        _currentMode = mode
        _lastModeSwitch = System.currentTimeMillis()
        
        if (showToast && previousMode != mode && mode != ModeType.NONE) {
            val message = when (mode) {
                ModeType.TEXT -> "已切换到文本模式"
                ModeType.IMAGE -> "已切换到图像模式"
                else -> null
            }
            if (message != null) {
                scope.launch {
                    _modeSwitchMessage.emit(message)
                }
            }
        }
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