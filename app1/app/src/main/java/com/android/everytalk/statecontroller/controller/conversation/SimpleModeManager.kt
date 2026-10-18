package com.android.everytalk.statecontroller

import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.controller.conversation.prepareLoadedHistoryMessages
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.ConversationNameHelper
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.android.everytalk.data.DataClass.Message

/** 后台整理的完整结果；界面一次提交正文和状态表，不暴露未修复的中间消息。 */
internal data class PreparedImageHistory(
    val messages: List<Message>,
    val reasoningCompleteStates: Map<String, Boolean>,
    val animationPlayedStates: Map<String, Boolean>,
)

internal suspend fun prepareImageHistory(messages: List<Message>, sessionId: String): PreparedImageHistory =
    withContext(Dispatchers.Default) {
        val recovered = ConversationNameHelper.withoutStoredConversationTitle(messages).map { message ->
            currentCoroutineContext().ensureActive()
            val hasImages = !message.imageUrls.isNullOrEmpty()
            val text = if (message.sender == Sender.AI && hasImages && message.text.isBlank() && message.parts.isNotEmpty()) {
                message.parts.toRecoveredMarkdown().ifBlank { message.text }
            } else message.text
            message.copy(
                text = text,
                contentStarted = text.isNotBlank() || !message.reasoning.isNullOrBlank() || message.isError || hasImages,
            )
        }
        val prepared = prepareLoadedHistoryMessages(recovered, sessionId)
        currentCoroutineContext().ensureActive()
        PreparedImageHistory(
            messages = prepared,
            reasoningCompleteStates = prepared.asSequence()
                .filter { it.sender == Sender.AI && !it.reasoning.isNullOrBlank() }
                .associate { it.id to true },
            animationPlayedStates = prepared.asSequence()
                .filter { it.contentStarted || it.isError || (it.sender == Sender.AI && !it.reasoning.isNullOrBlank()) }
                .associate { it.id to true },
        )
    }

/**
 * 简化的模式管理器 - 专门解决模式切换问题
 */
class SimpleModeManager(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope,
    private val onTextHistoryLoaded: suspend () -> Unit = {},
    private val loadHistorySession: suspend (sessionId: String) -> List<com.android.everytalk.data.DataClass.Message>? = { null },
) {
    private val TAG = "SimpleModeManager"
    
    // 增加明确的模式状态跟踪 - 解决forceNew导致的状态清空问题
    private var _currentMode: ModeType = ModeType.NONE
    private var _lastModeSwitch: Long = 0L
    // 自动回填也会直接加载历史，因此在公共入口区分先后请求，不能只依赖控制器取消。
    private var imageHistoryRequestGeneration = 0L

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
        
        
        // 模式切换不取消图像任务；后台任务继续按会话 ID运行。
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
            stateHolder.setCurrentConversationId(newId)
            stateHolder.systemPrompts[newId] = ""
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
            return
        }

        val previewConversation = conversationList[index]
        val previewStableId = ConversationNameHelper.resolveStableId(previewConversation)
            ?: "history_${UUID.randomUUID()}"
        val conversationToLoad = loadHistorySession(previewStableId) ?: previewConversation
        if (conversationToLoad !== previewConversation) {
            withContext(Dispatchers.Main.immediate) {
                stateHolder._historicalConversations.value = conversationList.toMutableList().apply {
                    this[index] = conversationToLoad
                }
            }
        }
        Log.d(TAG, "🔥 Found conversation to load with ${conversationToLoad.size} messages.")
        // 云端消息 UUID 与会话 UUID 不同，必须沿用会话身份，否则模型、标签和后续刷新都会查错键。
        val stableId = ConversationNameHelper.resolveStableId(conversationToLoad) ?: previewStableId
        // 已同步/编辑的逐会话设置优先，包括用户明确保存的空字符串；旧 System 消息仅作兼容回退。
        val systemPrompt = stateHolder.systemPrompts[stableId]
            ?: conversationToLoad.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.text.orEmpty()
        Log.d(TAG, "🔥 Stable ID: $stableId, System Prompt chars=${systemPrompt.length}")

        val (processedMessages, reasoningCompleteStates, animationPlayedStates) = withContext(Dispatchers.Default) {
            val recoveredMessages = ConversationNameHelper
                .withoutStoredConversationTitle(conversationToLoad)
                .map { msg ->
                // 修复：处理AI消息文本丢失问题
                if (msg.sender == Sender.AI) {
                    android.util.Log.d("SimpleModeManager", "Processing AI message ${msg.id}: text length=${msg.text.length}, parts=${msg.parts.size}, contentStarted=${msg.contentStarted}")
                    
                    if (msg.text.isBlank() && msg.parts.isNotEmpty()) {
                        // 尝试从parts重建文本内容
                        val rebuiltText = msg.parts.toRecoveredMarkdown()
                        
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
            val preparedMessages = prepareLoadedHistoryMessages(recoveredMessages, stableId)
            val reasoningCompleteStates = preparedMessages.asSequence()
                .filter { msg -> msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank() }
                .associate { msg -> msg.id to true }
            val animationPlayedStates = preparedMessages.asSequence()
                .filter { msg ->
                    msg.contentStarted || msg.isError ||
                        (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank())
                }
                .associate { msg -> msg.id to true }

            Triple(preparedMessages, reasoningCompleteStates, animationPlayedStates)
        }
        Log.d(TAG, "🔥 Processed ${processedMessages.size} messages.")

        withContext(Dispatchers.Main.immediate) {
            Log.d(TAG, "🔥 Updating state on Main thread...")

            // 只清理流式状态，不调用 clearForNewTextChat()（它会设置临时 conversationId 导致竞态）
            stateHolder._isTextApiCalling.value = false
            stateHolder.textApiJob?.cancel()
            stateHolder.textApiJob = null
            stateHolder._currentTextStreamingAiMessageId.value = null
            stateHolder.streamingMessageStateManager.clearAll()
            stateHolder.getApiHandler().clearTextChatResources()
            android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for text chat")

            // 关键修复：不清除图像模式索引，保持两个模式的历史索引独立
            // 保留图像消息（不在加载文本历史时清空）
            Log.d(TAG, "🔥 Preserved image generation messages (${stateHolder.imageGenerationMessages.size} messages).")
            
            stateHolder.setCurrentConversationId(stableId)
            Log.d(TAG, "🔥 Set current conversation ID and system prompt.")

            // 恢复会话使用的配置
            val savedConfigId = stateHolder.conversationApiConfigIds.value[stableId]
            if (savedConfigId != null) {
                val config = stateHolder._apiConfigs.value.find { it.id == savedConfigId }
                if (config != null) {
                    stateHolder._selectedApiConfig.value = config
                    Log.d(TAG, "🔥 Restored selected config: ${safeApiConfigSummary(config)}")
                } else {
                    Log.w(TAG, "🔥 Saved config ID $savedConfigId not found in current configs.")
                }
            }

            Snapshot.withMutableSnapshot {
                stateHolder.textReasoningCompleteMap.clear()
                stateHolder.textExpandedReasoningStates.clear()
                stateHolder.textMessageAnimationStates.clear()
                stateHolder.selectedMediaItems.clear()
                stateHolder.systemPrompts[stableId] = systemPrompt
                stateHolder.messages.clear()
                stateHolder.messages.addAll(processedMessages)
                stateHolder.textReasoningCompleteMap.putAll(reasoningCompleteStates)
                stateHolder.textMessageAnimationStates.putAll(animationPlayedStates)
            }
            Log.d(TAG, "🔥 Loaded messages into state.")
            Log.d(TAG, "🔥 Set reasoning and animation states.")
            
            // 关键修复：在所有状态更新完成后设置索引，确保不会被清空
            stateHolder._loadedHistoryIndex.value = index
            stateHolder._text.value = ""
            Log.d(TAG, "🔥 Set loaded history index to $index and cleared text input.")
            // Reset dirty flag after loading history to avoid unnecessary saves
            stateHolder.isTextConversationDirty.value = false
        
        Log.d(TAG, "🔥 [END] Loaded TEXT history successfully: ${conversationToLoad.size} messages")
        }
        onTextHistoryLoaded()
    }
    
    /**
     * 安全的历史记录加载 - 图像模式
     */
    suspend fun loadImageHistory(index: Int) {
        val requestGeneration = ++imageHistoryRequestGeneration
        Log.d(TAG, "Loading IMAGE history at index: $index")
        // 保证 UI 意图模式立即切换为 IMAGE，避免与文本模式的选择状态互相干扰
        _uiMode.value = ModeType.IMAGE

        val conversationList = stateHolder._imageGenerationHistoricalConversations.value
        val preview = conversationList.getOrNull(index) ?: return
        val sourceConversationId = stateHolder._currentImageGenerationConversationId.value
        val requestedId = ConversationNameHelper.resolveStableId(preview) ?: return
        // 读取、恢复正文和修复 parts 全部完成前保留原对话；加载失败也不会丢掉当前页面。
        val loaded = loadHistorySession(requestedId) ?: preview
        val prepared = prepareImageHistory(loaded, requestedId)

        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            // 加载期间新建/切换了会话，或者目标已删除时，不提交过时结果。
            if (requestGeneration != imageHistoryRequestGeneration ||
                _uiMode.value != ModeType.IMAGE ||
                stateHolder._currentImageGenerationConversationId.value != sourceConversationId
            ) {
                return@withContext
            }
            val latestHistory = stateHolder._imageGenerationHistoricalConversations.value
            val latestIndex = latestHistory.indexOfFirst { ConversationNameHelper.resolveStableId(it) == requestedId }
            if (latestIndex < 0) return@withContext
            val titledHistory = ConversationNameHelper.preserveStoredConversationTitle(latestHistory[latestIndex], loaded)
            // 读取历史只替换可见消息，不取消目标会话或其他会话的后台任务。
            stateHolder.imageApiJob = stateHolder.imageApiJobs[requestedId]
            stateHolder._isImageApiCalling.value = stateHolder.imageApiJob?.isActive == true
            stateHolder._currentImageStreamingAiMessageId.value = stateHolder.imageApiMessageIds[requestedId]
            Snapshot.withMutableSnapshot {
                stateHolder.imageGenerationMessages.clear()
                stateHolder.imageGenerationMessages.addAll(prepared.messages)
                stateHolder.imageReasoningCompleteMap.clear()
                stateHolder.imageReasoningCompleteMap.putAll(prepared.reasoningCompleteStates)
                stateHolder.imageExpandedReasoningStates.clear()
                stateHolder.imageMessageAnimationStates.clear()
                stateHolder.imageMessageAnimationStates.putAll(prepared.animationPlayedStates)
                stateHolder.selectedMediaItems.clear()
            }
            // 替换完成后释放旧图像会话的处理器，沿用现有资源清理入口。
            stateHolder.getApiHandler().clearImageChatResources(sourceConversationId)
            stateHolder._imageGenerationHistoricalConversations.value = latestHistory.toMutableList().apply {
                this[latestIndex] = titledHistory
            }
            stateHolder._currentImageGenerationConversationId.value = requestedId
            stateHolder.applyCurrentImageConversationFunctionToggleState()
            stateHolder.conversationApiConfigIds.value[requestedId]?.let { configId ->
                stateHolder._selectedImageGenApiConfig.value = stateHolder._imageGenApiConfigs.value.find { it.id == configId }
            }
            stateHolder._loadedImageGenerationHistoryIndex.value = latestIndex
            stateHolder._text.value = ""
            stateHolder.isImageConversationDirty.value = false
        }
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
            val message = if (mode == ModeType.TEXT) "已切换到文本模式" else "已切换到图像模式"
            scope.launch {
                _modeSwitchMessage.emit(message)
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
