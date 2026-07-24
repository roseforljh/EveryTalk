package com.android.everytalk.statecontroller

import android.os.Looper
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.ConversationNameHelper
import com.android.everytalk.util.ScrollController
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
 
 class ViewModelStateHolder {
    // 🎯 Streaming message state manager for efficient UI updates
    // Provides StateFlow-based observation of streaming content
    // Requirements: 1.4, 3.4
    val streamingMessageStateManager = StreamingMessageStateManager()

    // 🎯 StreamingBuffer mapping (one buffer per message ID)
    private val streamingBuffers = mutableMapOf<String, StreamingBuffer>()

    // 新增：记录每条流式消息已提交到 UI 的长度，用于只追加增量，避免重复全量赋值造成卡顿
    private val streamingReasoningStates = mutableMapOf<String, MutableStateFlow<String>>()

    // 🎯 记录已完成同步的消息ID，避免重复同步导致UI抖动
    private val syncedMessageIds = mutableSetOf<String>()
    
    // CoroutineScope for StreamingBuffer operations (will be initialized from AppViewModel)
    private var bufferCoroutineScope: CoroutineScope? = null
    
    // 🎯 Task 11: Performance monitoring - Memory usage tracking
    // Track memory usage during long streaming sessions
    // Requirements: 1.4, 3.4
    private var lastMemoryCheckTime = 0L
    private val memoryCheckInterval = 10000L // Check every 10 seconds during streaming
    
    // Dirty flags to track conversation changes
    val isTextConversationDirty = MutableStateFlow(false)
    val isImageConversationDirty = MutableStateFlow(false)
    lateinit var scrollController: ScrollController
     val drawerState: DrawerState = DrawerState(initialValue = DrawerValue.Closed)
    
    // 置顶集合状态：文本与图像各自独立
    val pinnedTextConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedImageConversationIds = MutableStateFlow<Set<String>>(emptySet())
    
    // 分组状态
    val conversationGroups = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    
    // 分组展开/折叠状态（默认全部折叠）
    val expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    
    // 持久化回调 - 由 AppViewModel 设置，用于保存会话参数
    private var onSaveConversationParams: ((Map<String, GenerationConfig>) -> Unit)? = null
    @Volatile
    private var isTextHistoryReadyForParameterCleanup = false
    @Volatile
    private var isImageHistoryReadyForStateCleanup = false
    
    /**
     * 初始化持久化回调
     * @param saveCallback 保存会话参数的回调函数
     * @param initialParams 初始加载的会话参数（从持久化存储加载）
     */
    fun initializePersistence(
        saveCallback: (Map<String, GenerationConfig>) -> Unit,
        initialParams: Map<String, GenerationConfig> = emptyMap()
    ) {
        onSaveConversationParams = saveCallback
        if (initialParams.isNotEmpty()) {
            conversationGenerationConfigs.value = initialParams
        }
    }

    fun markTextHistoryReadyForParameterCleanup() {
        isTextHistoryReadyForParameterCleanup = true
    }

    fun markImageHistoryReadyForStateCleanup() {
        isImageHistoryReadyForStateCleanup = true
    }

    fun isConversationStateCleanupReady(): Boolean =
        isTextHistoryReadyForParameterCleanup && isImageHistoryReadyForStateCleanup

    fun getStreamingReasoning(messageId: String): StateFlow<String> {
        return streamingReasoningStates.getOrPut(messageId) { MutableStateFlow("") }.asStateFlow()
    }

    /**
     * Initialize coroutine scope for StreamingBuffer operations
     * Must be called from AppViewModel during initialization
     */
    fun initializeBufferScope(scope: CoroutineScope) {
        bufferCoroutineScope = scope
    }

    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()
    val imageGenerationMessages: SnapshotStateList<Message> = mutableStateListOf()

    // 分离的API状态
    val _isTextApiCalling = MutableStateFlow(false)
    val _isImageApiCalling = MutableStateFlow(false)
    val _isMcpEnabledForNextRequest = MutableStateFlow(false)

    val _lastSentUserMessageId = MutableStateFlow<String?>(null)
    val _lastSentImageUserMessageId = MutableStateFlow<String?>(null)

    // 分离的流式消息ID
    val _currentTextStreamingAiMessageId = MutableStateFlow<String?>(null)
    val _currentImageStreamingAiMessageId = MutableStateFlow<String?>(null)

// 全局流式暂停状态（文本/图像共用）
val _isStreamingPaused = MutableStateFlow(false)
    // 分离的API Job
    var textApiJob: Job? = null
    var imageApiJob: Job? = null

    // 分离的推理完成状态
    val textReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 每个会话独立的生成配置参数
    val conversationGenerationConfigs: MutableStateFlow<Map<String, GenerationConfig>> =
        MutableStateFlow(emptyMap())

    // 会话ID -> 使用的API配置ID的映射
    val conversationApiConfigIds: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    val conversationFunctionToggleStates: MutableStateFlow<Map<String, ConversationFunctionToggleState>> =
        MutableStateFlow(emptyMap())

    fun getCurrentConversationFunctionToggleState(): ConversationFunctionToggleState {
        return conversationFunctionToggleStates.value[_currentConversationId.value] ?: ConversationFunctionToggleState()
    }

    fun getCurrentImageConversationFunctionToggleState(): ConversationFunctionToggleState {
        return conversationFunctionToggleStates.value[_currentImageGenerationConversationId.value]
            ?: ConversationFunctionToggleState()
    }

    fun updateCurrentConversationFunctionToggleState(
        update: (ConversationFunctionToggleState) -> ConversationFunctionToggleState
    ) {
        val conversationId = _currentConversationId.value
        conversationFunctionToggleStates.update { currentMap ->
            val currentState = currentMap[conversationId] ?: ConversationFunctionToggleState()
            currentMap + (conversationId to update(currentState))
        }
    }
    
    // 获取当前会话的生成配置（仅按当前会话ID的内存映射读取）
    fun getCurrentConversationConfig(): GenerationConfig? {
        val id = _currentConversationId.value
        return conversationGenerationConfigs.value[id]
    }
    
    // 标记"空会话但已应用过参数（仅在内存映射中）"
    // 用于离开/切换会话时判定是否需要丢弃该空会话的会话参数
    fun hasPendingConversationParams(): Boolean {
        val id = _currentConversationId.value
        return messages.isEmpty() && conversationGenerationConfigs.value.containsKey(id)
    }
    
    // 更新当前会话的生成配置
    // 规则：
    // - 点击"应用"后，本会话立刻生效：总是写入当前会话ID的内存映射（UI 和请求立刻可见）
    // - 仅当会话内容不为空时才持久化；空会话不落库
    fun updateCurrentConversationConfig(config: GenerationConfig) {
        val id = _currentConversationId.value
        // 立即更新内存映射（立刻生效）
        conversationGenerationConfigs.update { currentConfigs -> currentConfigs + (id to config) }
        val currentConfigs = conversationGenerationConfigs.value
        
        // 仅非空会话才持久化
        if (messages.isNotEmpty()) {
            onSaveConversationParams?.invoke(currentConfigs)
        }
    }
    
    // 首条用户消息产生时，若当前会话存在仅内存的参数映射，则补做持久化
    fun persistPendingParamsIfNeeded(isImageGeneration: Boolean = false) {
        if (isImageGeneration) return // 当前参数系统仅绑定文本会话
        val id = _currentConversationId.value
        val cfg = conversationGenerationConfigs.value[id] ?: return
        // 写盘（如果之前未写过，也无害）
        onSaveConversationParams?.invoke(conversationGenerationConfigs.value)
    }
    
    // 放弃一个"仅应用过参数但未发消息"的空会话：
    // 清除当前会话ID在内存中的参数映射，并同步到持久化（若存在）
    fun abandonEmptyPendingConversation() {
        if (messages.isEmpty()) {
            val id = _currentConversationId.value
            val currentMap = conversationGenerationConfigs.value
            val newMap = conversationGenerationConfigs.updateAndGet { configs ->
                if (id in configs) configs - id else configs
            }
            if (newMap != currentMap) {
                onSaveConversationParams?.invoke(newMap)
            }
        }
    }
    
    // 为历史会话设置稳定的ID
    fun setConversationIdForHistory(historyIndex: Int) {
        // 使用历史索引生成稳定的ID
        _currentConversationId.value = "history_chat_$historyIndex"
        applyCurrentConversationFunctionToggleState()
        _currentOpenClawSessionId.value = "history_chat_$historyIndex"
    }

    fun setCurrentConversationId(conversationId: String) {
        _currentConversationId.value = conversationId
        applyCurrentConversationFunctionToggleState()
    }

    fun applyCurrentConversationFunctionToggleState() {
        val toggleState = getCurrentConversationFunctionToggleState()
        _isWebSearchEnabled.value = toggleState.webSearchEnabled
        _isCodeExecutionEnabled.value = toggleState.codeExecutionEnabled
        _isMcpEnabledForNextRequest.value = toggleState.mcpEnabled
    }

    fun applyCurrentImageConversationFunctionToggleState() {
        val toggleState = getCurrentImageConversationFunctionToggleState()
        _isWebSearchEnabled.value = toggleState.webSearchEnabled
        _isCodeExecutionEnabled.value = toggleState.codeExecutionEnabled
        _isMcpEnabledForNextRequest.value = toggleState.mcpEnabled
    }

    fun updateOpenClawSessionId(sessionId: String?) {
        _currentOpenClawSessionId.value = sessionId?.takeIf { it.isNotBlank() } ?: "main"
    }

    fun updateOpenClawGatewayStatus(stage: String?) {
        val previous = _openClawGatewayStatus.value
        _openClawGatewayStatus.value = when {
            stage.isNullOrBlank() -> OpenClawGatewayStatus()
            stage.startsWith("pairing_pending:") -> {
                val deviceId = stage.substringAfter(':', "").ifBlank { null }
                OpenClawGatewayStatus(
                    connectionState = OpenClawGatewayConnectionState.PAIRING_PENDING,
                    pendingDeviceId = deviceId,
                    statusText = "等待 OpenClaw 配对批准"
                )
            }
            stage == "connected" -> OpenClawGatewayStatus(
                connectionState = OpenClawGatewayConnectionState.CONNECTED,
                statusText = "OpenClaw Gateway 已连接"
            )
            previous.connectionState == OpenClawGatewayConnectionState.CONNECTED -> OpenClawGatewayStatus(
                connectionState = OpenClawGatewayConnectionState.CONNECTED,
                statusText = "远程控制中 · $stage"
            )
            else -> OpenClawGatewayStatus(
                connectionState = OpenClawGatewayConnectionState.DISCONNECTED,
                statusText = stage
            )
        }
    }
    
    // 仅保留现存历史及当前会话的参数，避免按数量裁剪仍有效的 UUID 会话。
    fun cleanupOldConversationParameters() {
        if (!isTextHistoryReadyForParameterCleanup) return
        val retainedIds = buildSet {
            _historicalConversations.value.forEach { conversation ->
                ConversationNameHelper.resolveStableId(conversation)?.let(::add)
            }
            _currentConversationId.value.takeIf { it.isNotBlank() }?.let(::add)
        }
        val currentConfigs = conversationGenerationConfigs.value
        if (currentConfigs.isEmpty()) return
        val cleanedConfigs = conversationGenerationConfigs.updateAndGet { current ->
            current.filterKeys { it in retainedIds }
        }
        if (cleanedConfigs.size != currentConfigs.size) {
            onSaveConversationParams?.invoke(cleanedConfigs)
        }
    }

    // 分离的展开推理状态
    val textExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 会话ID切换时参数迁移（仅在"尚未开始对话"的空会话场景执行）
    // 解决：用户在空会话开启参数后，内部刷新/切换会话ID导致参数丢失的问题
    fun migrateParamsOnConversationIdChange(newId: String) {
        val oldId = _currentConversationId.value
        if (oldId == newId) {
            _currentConversationId.value = newId
            return
        }
        // 切换ID
        _currentConversationId.value = newId
        // 若仍处于空会话（未开始发消息），则迁移已落库的旧ID参数到新ID；
        // 若参数尚未落库（pending），保持 pending 即可，由首次发消息时写入
        if (messages.isEmpty()) {
            var newMap = conversationGenerationConfigs.value
            var changed = false
            conversationGenerationConfigs.update { current ->
                val cfg = current[oldId]
                changed = cfg != null
                newMap = cfg?.let { current - oldId + (newId to it) } ?: current
                newMap
            }
            if (changed) {
                onSaveConversationParams?.invoke(newMap)
            }
        }
    }

    // 分离的消息动画状态
    val textMessageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageMessageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    // 分离的历史加载状态
    val _isLoadingTextHistory = MutableStateFlow(false)
    val _isLoadingImageHistory = MutableStateFlow(false)
    
    val conversationScrollStates = mutableStateMapOf<String, ConversationScrollState>()
    val systemPromptExpandedState = mutableStateMapOf<String, Boolean>()
    val systemPrompts = mutableStateMapOf<String, String>()
    // 是否将当前系统提示接入到该会话（开始/暂停）
    val systemPromptEngagedState = mutableStateMapOf<String, Boolean>()

    // 清理文本模式状态的方法 - 增强版本，确保完全隔离
    fun clearForNewTextChat() {
        _text.value = ""
        messages.clear()
        selectedMediaItems.clear()
        _isTextApiCalling.value = false
        textApiJob?.cancel()
        textApiJob = null
        _currentTextStreamingAiMessageId.value = null
        textReasoningCompleteMap.clear()
        textExpandedReasoningStates.clear()
        textMessageAnimationStates.clear()
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        _loadedHistoryIndex.value = null
        
        // 🎯 清理所有 StreamingBuffer（Requirements: 6.1, 6.2）
        streamingBuffers.values.forEach { buffer ->
            buffer.flush()
            buffer.clear()
        }
        streamingBuffers.clear()

        // 🎯 清理流式消息状态管理器（Requirements: 1.4, 3.4）
        streamingMessageStateManager.clearAll()

        // 🎯 清理同步标记
        syncedMessageIds.clear()
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for text chat")
        
        // 若当前会话为空且仅"应用未发"，按要求删除该空会话（丢弃pending、不落库）
        if (messages.isEmpty() && hasPendingConversationParams()) {
            abandonEmptyPendingConversation()
        }
        
        // 分配全新会话ID（不迁移任何旧会话参数，保持完全独立）
        _currentConversationId.value = "new_chat_${System.currentTimeMillis()}"
        applyCurrentConversationFunctionToggleState()
        _currentOpenClawSessionId.value = "main"
        
        // 新会话默认关闭参数：不做任何继承或默认值注入
        
        // Clean up old parameters periodically
        cleanupOldConversationParameters()
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        _apiHandler?.clearTextChatResources()
        isTextConversationDirty.value = false
    }

    // 清理图像模式状态的方法 - 增强版本，确保完全隔离
    fun clearForNewImageChat() {
        imageGenerationMessages.clear()
        _isImageApiCalling.value = false
        imageApiJob?.cancel()
        imageApiJob = null
        _currentImageStreamingAiMessageId.value = null
        imageReasoningCompleteMap.clear()
        imageExpandedReasoningStates.clear()
        imageMessageAnimationStates.clear()
        _loadedImageGenerationHistoryIndex.value = null
        _currentImageGenerationConversationId.value = "new_image_generation_${System.currentTimeMillis()}"
        
        // 🎯 清理所有 StreamingBuffer（Requirements: 6.1, 6.2）
        streamingBuffers.values.forEach { buffer ->
            buffer.flush()
            buffer.clear()
        }
        streamingBuffers.clear()

        // 🎯 清理流式消息状态管理器（Requirements: 1.4, 3.4）
        streamingMessageStateManager.clearAll()

        // 🎯 清理同步标记
        syncedMessageIds.clear()
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for image chat")
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        _apiHandler?.clearImageChatResources()
        isImageConversationDirty.value = false
        applyCurrentImageConversationFunctionToggleState()
    }

    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> =
        mutableStateListOf()

    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _imageGenerationHistoricalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _loadedImageGenerationHistoryIndex = MutableStateFlow<Int?>(null)
    val _isLoadingHistory = MutableStateFlow(false)
    val _historyLoadGeneration = MutableStateFlow(0L)
    
    // 图像生成错误处理状态
    val _imageGenerationRetryCount = MutableStateFlow(0)
    val _imageGenerationError = MutableStateFlow<String?>(null)
    val _shouldShowImageGenerationError = MutableStateFlow(false)
    val _isLoadingHistoryData = MutableStateFlow(false)
    val _currentConversationId = MutableStateFlow<String>("new_chat_${System.currentTimeMillis()}")
    val _currentOpenClawSessionId = MutableStateFlow<String>("main")
    val _openClawGatewayStatus = MutableStateFlow(OpenClawGatewayStatus())
    val _currentImageGenerationConversationId = MutableStateFlow<String>("new_image_generation_${System.currentTimeMillis()}")
    // 待加载的图像历史索引（用于跨页面导航时抑制"新建图像会话"）
    val _pendingImageHistoryIndex = MutableStateFlow<Int?>(null)

     val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
     val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _imageGenApiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedImageGenApiConfig = MutableStateFlow<ApiConfig?>(null)
    // 图像输出宽高比（默认 AUTO）
    val _selectedImageRatio = MutableStateFlow(com.android.everytalk.data.DataClass.ImageRatio.DEFAULT_SELECTED)
    val _gptImageQuality = MutableStateFlow(com.android.everytalk.ui.components.ImageGenCapabilities.GptImageQuality.AUTO)
    
    // ========= 语音配置状态 =========
    /** 语音后端配置列表 */
    val _voiceBackendConfigs = MutableStateFlow<List<VoiceBackendConfig>>(emptyList())
    /** 当前选中的语音配置 */
    val _selectedVoiceConfig = MutableStateFlow<VoiceBackendConfig?>(null)
 
 
     val _snackbarMessage =
         MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    // 滚动到指定消息ID的事件（用于重新回答时定位）
    val _scrollToItemEvent =
        MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    val _editDialogInputText = MutableStateFlow("")

    val _showSettingsDialog = MutableStateFlow(false)
    
    // 🎯 新增：添加配置流程相关的对话框状态
    val _showAutoFetchConfirmDialog = MutableStateFlow(false)
    val _showModelSelectionDialog = MutableStateFlow(false)
    val _pendingConfigParams = MutableStateFlow<PendingConfigParams?>(null)

    val _isWebSearchEnabled = MutableStateFlow(false)
    val _isCodeExecutionEnabled = MutableStateFlow(false)

    val _showSourcesDialog = MutableStateFlow(false)
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())

    internal val _requestScrollForReasoningBoxEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

 
     fun clearSelectedMedia() {
        selectedMediaItems.clear()
    }
fun addMessage(message: Message, isImageGeneration: Boolean = false) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "addMessage 必须在主线程调用，以保持消息写入与后续保存的顺序"
    }
    addMessageInternal(message, isImageGeneration)
}

private fun addMessageInternal(message: Message, isImageGeneration: Boolean) {
    if (isImageGeneration) {
        imageGenerationMessages.add(message)
        isImageConversationDirty.value = true
    } else {
        messages.add(message)
        isTextConversationDirty.value = true
    }
}

    fun shouldAutoScroll(): Boolean {
        return ::scrollController.isInitialized && !scrollController.userManuallyScrolledAwayFromBottom
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    fun triggerScrollToBottom() {
        _scrollToBottomEvent.tryEmit(Unit)
    }
    
    /**
     * 为消息创建流式缓冲区。
     *
     * 首个内容块立即显示，后续按时间和字符阈值合并，并同步初始化独立渲染状态。
     */
    fun createStreamingBuffer(messageId: String, isImageGeneration: Boolean = false): StreamingBuffer {
        val scope = bufferCoroutineScope ?: throw IllegalStateException(
            "Buffer coroutine scope not initialized. Call initializeBufferScope() first."
        )
        
        // Clean up any existing buffer for this message
        streamingBuffers[messageId]?.let { existingBuffer ->
            existingBuffer.flush()
            existingBuffer.clear()
        }
        
        // 🎯 Initialize StreamingMessageStateManager for this message
        // This allows UI components to observe streaming content efficiently
        // Requirements: 1.4, 3.4
        streamingMessageStateManager.startStreaming(messageId)

        streamingReasoningStates[messageId] = MutableStateFlow("")

        val buffer = StreamingBuffer(
            messageId = messageId,
            onUpdate = { _, delta ->
                if (delta.isNotEmpty()) {
                    streamingMessageStateManager.appendText(messageId, delta)
                }
                if (isImageGeneration) {
                    isImageConversationDirty.value = true
                } else {
                    isTextConversationDirty.value = true
                }
            },
            coroutineScope = scope
        )
        
        streamingBuffers[messageId] = buffer
        android.util.Log.d("ViewModelStateHolder", "Created StreamingBuffer and initialized streaming state for message: $messageId")
        
        return buffer
    }
    
    /**
     * Direct message content update (called by StreamingBuffer callback)
     * 
     * This is an internal method used by the buffer's onUpdate callback.
     * It updates the message content without going through the buffer again.
     * 
     * @param messageId Message to update
     * @param content New content to set
     * @param isImageGeneration Whether this is for image generation chat
     */
    private fun updateMessageContentDirect(messageId: String, content: String, isImageGeneration: Boolean) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = content,
                contentStarted = true,
                timestamp = System.currentTimeMillis()
            )
            messageList[index] = updatedMessage
            
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }
    }
    
    /**
     * Flush a StreamingBuffer immediately
     * 
     * This method forces the buffer to commit all pending content to the UI.
     * Also finalizes the streaming state in StreamingMessageStateManager.
     * Should be called when:
     * - Stream completes successfully
     * - Stream encounters an error
     * - Need to ensure all content is visible
     * 
     * Requirements: 1.4, 3.3, 3.4, 7.1, 7.2
     * 
     * @param messageId Message whose buffer should be flushed
     */
    fun flushStreamingBuffer(messageId: String) {
        streamingBuffers[messageId]?.let { buffer ->
            buffer.flush()

            // 这里只负责把缓冲区内容推进到 StreamingMessageStateManager，最终收尾统一由 syncStreamingMessageToList 负责
        }
    }
    
    /**
     * Clear a StreamingBuffer and remove it
     * 
     * This method clears the buffer content and removes it from the map.
     * Also clears the streaming state in StreamingMessageStateManager.
     * Should be called when:
     * - Stream is cancelled
     * - Error occurs and need to clean up
     * - Message is being removed
     * 
     * Requirements: 1.4, 3.4, 6.1, 6.2
     * 
     * @param messageId Message whose buffer should be cleared
     */
    fun clearStreamingBuffer(messageId: String) {
        streamingBuffers.remove(messageId)?.let { buffer ->
            buffer.clear()
        }

        // 🎯 清理同步标记，允许下次同步
        syncedMessageIds.remove(messageId)

        // 🎯 Clear streaming state in StreamingMessageStateManager
        streamingMessageStateManager.clearStreamingState(messageId)
        streamingReasoningStates.remove(messageId)

        android.util.Log.d("ViewModelStateHolder", "Cleared StreamingBuffer and streaming state for message: $messageId")
    }
    
    /**
     * Get a StreamingBuffer for a message (if exists)
     * 
     * @param messageId Message ID
     * @return StreamingBuffer instance or null if not found
     */
    fun getStreamingBuffer(messageId: String): StreamingBuffer? {
        return streamingBuffers[messageId]
    }
    
    /**
     * Get the count of active streaming buffers
     * Used for resource monitoring and memory pressure detection
     * 
     * @return Number of active streaming buffers
     */
    fun getStreamingBufferCount(): Int {
        return streamingBuffers.size
    }
    
    /**
     * 🎯 Task 11: Monitor memory usage during long streaming sessions
     * 
     * This method checks memory usage and logs warnings if memory pressure is detected.
     * Should be called periodically during streaming to detect potential memory issues.
     * 
     * Requirements: 1.4, 3.4
     */
    fun checkMemoryUsage() {
        val currentTime = System.currentTimeMillis()
        
        // Only check memory every 10 seconds to avoid performance impact
        if (currentTime - lastMemoryCheckTime < memoryCheckInterval) {
            return
        }
        
        lastMemoryCheckTime = currentTime
        
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val availableMemory = maxMemory - usedMemory
            val memoryUsagePercent = (usedMemory.toFloat() / maxMemory.toFloat() * 100).toInt()
            
            // Log memory stats
            android.util.Log.d("ViewModelStateHolder", 
                "Memory usage: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB " +
                "($memoryUsagePercent%), " +
                "available: ${availableMemory / 1024 / 1024}MB, " +
                "activeBuffers: ${streamingBuffers.size}, " +
                "textMessages: ${messages.size}, " +
                "imageMessages: ${imageGenerationMessages.size}")
            
            // Warn if memory usage is high (>80%)
            if (memoryUsagePercent > 80) {
                android.util.Log.w("ViewModelStateHolder", 
                    "High memory usage detected: $memoryUsagePercent%. " +
                    "Consider cleaning up resources.")
            }
            
            // Critical warning if memory usage is very high (>90%)
            if (memoryUsagePercent > 90) {
                android.util.Log.e("ViewModelStateHolder", 
                    "Critical memory usage: $memoryUsagePercent%. " +
                    "Memory pressure detected. Forcing cleanup of inactive buffers.")
                
                // Force cleanup of inactive buffers
                val activeStreamingIds = setOf(
                    _currentTextStreamingAiMessageId.value,
                    _currentImageStreamingAiMessageId.value
                ).filterNotNull().toSet()
                
                val buffersToRemove = streamingBuffers.keys.filter { it !in activeStreamingIds }
                buffersToRemove.forEach { messageId ->
                    clearStreamingBuffer(messageId)
                }
                
                if (buffersToRemove.isNotEmpty()) {
                    android.util.Log.d("ViewModelStateHolder", 
                        "Cleaned up ${buffersToRemove.size} inactive buffers due to memory pressure")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ViewModelStateHolder", "Error checking memory usage", e)
        }
    }
    
    /**
     * 🎯 Task 11: Get performance metrics for all active streaming buffers
     * 
     * Returns a summary of performance metrics across all buffers.
     * Useful for debugging and monitoring streaming performance.
     * 
     * Requirements: 1.4, 3.4
     * 
     * @return Map of performance metrics
     */
    fun getStreamingPerformanceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>()
        
        metrics["activeBufferCount"] = streamingBuffers.size
        metrics["textMessageCount"] = messages.size
        metrics["imageMessageCount"] = imageGenerationMessages.size
        
        // Aggregate buffer statistics
        var totalFlushes = 0
        var totalCharsProcessed = 0
        
        streamingBuffers.values.forEach { buffer ->
            val stats = buffer.getStats()
            totalFlushes += (stats["flushCount"] as? Int) ?: 0
            totalCharsProcessed += (stats["totalCharsProcessed"] as? Int) ?: 0
        }
        
        metrics["totalFlushes"] = totalFlushes
        metrics["totalCharsProcessed"] = totalCharsProcessed
        metrics["avgCharsPerFlush"] = if (totalFlushes > 0) totalCharsProcessed / totalFlushes else 0
        
        // Memory metrics
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            metrics["usedMemoryMB"] = usedMemory / 1024 / 1024
            metrics["maxMemoryMB"] = maxMemory / 1024 / 1024
            metrics["memoryUsagePercent"] = (usedMemory.toFloat() / maxMemory.toFloat() * 100).toInt()
        } catch (e: Exception) {
            android.util.Log.e("ViewModelStateHolder", "Error getting memory metrics", e)
        }
        
        return metrics
    }
    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        if (text.isEmpty()) return
        val state = streamingReasoningStates.getOrPut(messageId) { MutableStateFlow("") }
        state.value += text
    }

    /**
     * 追加内容到消息
     * 🎯 优化：通过 StreamingBuffer 实现节流，避免高频修改 messages 列表
     * 
     * 旧逻辑问题：
     * - 每次调用都修改 messages (SnapshotStateList)
     * - 触发 snapshotFlow 发射 → combine 重新计算所有消息
     * - 导致 LazyColumn 频繁重组（100次/10秒）
     * 
     * 新逻辑：
     * - 流式期间：通过 StreamingBuffer 路由，按 80 至 180ms 和 64 字符阈值合并
     * - 同时更新 StreamingMessageStateManager 以支持高效的 UI 观察
     * - 非流式：正常更新 messages
     * - 列表只在正文首次出现和终态同步时更新，正文增量由独立 StateFlow 驱动
     * 
     * Requirements: 1.4, 3.1, 3.2, 3.3, 3.4
     */
    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        if (text.isEmpty()) return

        // 🔧 修复竞态条件：使用 synchronized 保护对 streamingBuffers 的检查和操作
        // 避免多线程环境下 check-then-act 的竞态问题
        val buffer = synchronized(streamingBuffers) {
            streamingBuffers[messageId]
        }
        
        if (buffer != null) {
            buffer.append(text)
            return
        }

        // 仅在没有缓冲的极端回退路径，才直接更新一次（如历史重放/异常场景）
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = currentMessage.text + text,
                contentStarted = true
            )
            messageList[index] = updatedMessage
            streamingMessageStateManager.updateContent(messageId, updatedMessage.text)
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }
    }

    /**
     * 更新消息状态（用于 ToolCall 等非内容追加的更新）
     */
    fun updateMessageStatus(messageId: String, currentWebSearchStage: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                currentWebSearchStage = currentWebSearchStage
            )
            messageList[index] = updatedMessage
        }
    }

    fun clearMessageStatus(messageId: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            messageList[index] = currentMessage.copy(
                currentWebSearchStage = null,
                executionStatus = null
            )
        }
    }

    fun startLocalSlashLoading(messageId: String, isImageGeneration: Boolean = false) {
        if (isImageGeneration) {
            _currentImageStreamingAiMessageId.value = messageId
            _isImageApiCalling.value = true
        } else {
            _currentTextStreamingAiMessageId.value = messageId
            _isTextApiCalling.value = true
        }
    }

    fun finishLocalSlashLoading(
        messageId: String,
        finalText: String,
        isImageGeneration: Boolean = false
    ) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        AppLogger.debug(
            "SlashCommand",
            "finishLocalSlashLoading messageId=$messageId index=$index finalTextChars=${finalText.length}"
        )
        if (index != -1) {
            val currentMessage = messageList[index]
            messageList[index] = currentMessage.copy(
                text = finalText,
                contentStarted = finalText.isNotBlank(),
                timestamp = System.currentTimeMillis()
            )
            AppLogger.debug(
                "SlashCommand",
                "finishLocalSlashLoading updated contentStarted=${finalText.isNotBlank()} textLen=${finalText.length}"
            )
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }

        if (isImageGeneration) {
            if (_currentImageStreamingAiMessageId.value == messageId) {
                _currentImageStreamingAiMessageId.value = null
            }
            _isImageApiCalling.value = false
        } else {
            if (_currentTextStreamingAiMessageId.value == messageId) {
                _currentTextStreamingAiMessageId.value = null
            }
            _isTextApiCalling.value = false
        }
    }

    /**
     * 同步流式消息到 messages 列表
     * 🎯 在流式结束时调用，将缓冲区的文本同步到持久化存储
     * 该方法是幂等的，重复调用不会产生副作用
     */
    fun syncStreamingMessageToList(messageId: String, isImageGeneration: Boolean = false) {
        // 🎯 幂等检查：如果已经同步过，直接返回
        if (syncedMessageIds.contains(messageId)) {
            android.util.Log.d("ViewModelStateHolder", "syncStreamingMessageToList: already synced $messageId, skipping")
            return
        }

        streamingBuffers[messageId]?.flush()
        val finalText = streamingMessageStateManager.finishStreaming(messageId)
        val finalReasoning = streamingReasoningStates[messageId]?.value.orEmpty()

        android.util.Log.d("ViewModelStateHolder", "🎯 Syncing streaming message $messageId: finalText.length=${finalText.length}")

        if (finalText.isEmpty() && finalReasoning.isEmpty()) {
            android.util.Log.w("ViewModelStateHolder", "syncStreamingMessageToList: empty content for $messageId")
            return
        }

        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }

        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = finalText.ifEmpty { currentMessage.text },
                reasoning = finalReasoning.ifEmpty { currentMessage.reasoning },
                contentStarted = finalText.isNotEmpty() || currentMessage.contentStarted,
            )
            messageList[index] = updatedMessage
            android.util.Log.d("ViewModelStateHolder", "🎯 Synced message.text chars=${finalText.length}")

            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }

            // 🎯 标记为已同步，防止重复同步
            syncedMessageIds.add(messageId)

            android.util.Log.d("ViewModelStateHolder", "Synced streaming message $messageId, final length: ${finalText.length}")
        }
    }

    /**
     * 将当前流式快照写入消息列表，但不结束流，也不设置终态幂等标记。
     * 暂停后恢复显示时使用，后续增量仍可继续进入真正的终态同步。
     */
    fun syncStreamingSnapshotToList(messageId: String, isImageGeneration: Boolean = false) {
        streamingBuffers[messageId]?.flush()
        val currentText = streamingMessageStateManager.getCurrentContent(messageId)
        val currentReasoning = streamingReasoningStates[messageId]?.value.orEmpty()
        if (currentText.isEmpty() && currentReasoning.isEmpty()) return

        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val currentMessage = messageList[index]
        messageList[index] = currentMessage.copy(
            text = currentText.ifEmpty { currentMessage.text },
            reasoning = currentReasoning.ifEmpty { currentMessage.reasoning },
            contentStarted = currentText.isNotEmpty() || currentMessage.contentStarted,
        )
    }
    
    private var _apiHandler: ApiHandler? = null
    fun setApiHandler(handler: ApiHandler) {
        _apiHandler = handler
    }

    fun getApiHandler(): ApiHandler {
        return _apiHandler ?: throw IllegalStateException("ApiHandler not initialized")
    }
}
