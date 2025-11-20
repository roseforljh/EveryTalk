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
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.util.ScrollController
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ConversationScrollState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val userScrolledAway: Boolean = false
)

/**
 * 待处理的配置参数
 * 用于在添加配置流程中临时保存用户输入的参数
 */
data class PendingConfigParams(
    val provider: String,
    val address: String,
    val key: String,
    val channel: String,
    val isImageGen: Boolean,
    val enableCodeExecution: Boolean? = null,
    val toolsJson: String? = null
)
 
 class ViewModelStateHolder {
    // 🎯 Streaming message state manager for efficient UI updates
    // Provides StateFlow-based observation of streaming content
    // Requirements: 1.4, 3.4
    val streamingMessageStateManager = StreamingMessageStateManager()
    
    // 🎯 StreamingBuffer mapping (one buffer per message ID)
    private val streamingBuffers = mutableMapOf<String, StreamingBuffer>()

    // 新增：记录每条流式消息已提交到 UI 的长度，用于只追加增量，避免重复全量赋值造成卡顿
    private val streamingLastLengths = mutableMapOf<String, Int>()
    
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
    
    // DataSource for persistent storage - will be initialized from AppViewModel
    private var dataSource: com.android.everytalk.data.local.SharedPreferencesDataSource? = null
    
    fun initializeDataSource(source: com.android.everytalk.data.local.SharedPreferencesDataSource) {
        dataSource = source
        // Load saved parameters when initialized
        val savedParameters = source.loadConversationParameters()
        if (savedParameters.isNotEmpty()) {
            conversationGenerationConfigs.value = savedParameters
        }
        // 不在此处为当前会话ID自动回填，避免新建会话默认开启 maxTokens
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
    
    // 获取当前会话的生成配置（仅按当前会话ID的内存映射读取）
    fun getCurrentConversationConfig(): GenerationConfig? {
        val id = _currentConversationId.value
        return conversationGenerationConfigs.value[id]
    }
    
    // 标记“空会话但已应用过参数（仅在内存映射中）”
    // 用于离开/切换会话时判定是否需要丢弃该空会话的会话参数
    fun hasPendingConversationParams(): Boolean {
        val id = _currentConversationId.value
        return messages.isEmpty() && conversationGenerationConfigs.value.containsKey(id)
    }
    
    // 更新当前会话的生成配置
    // 规则：
    // - 点击“应用”后，本会话立刻生效：总是写入当前会话ID的内存映射（UI 和请求立刻可见）
    // - 仅当会话内容不为空时才持久化；空会话不落库
    fun updateCurrentConversationConfig(config: GenerationConfig) {
        val id = _currentConversationId.value
        // 立即更新内存映射（立刻生效）
        val currentConfigs = conversationGenerationConfigs.value.toMutableMap()
        currentConfigs[id] = config
        conversationGenerationConfigs.value = currentConfigs
        
        // 仅非空会话才持久化
        if (messages.isNotEmpty()) {
            dataSource?.saveConversationParameters(currentConfigs)
        }
    }
    
    // 首条用户消息产生时，若当前会话存在仅内存的参数映射，则补做持久化
    fun persistPendingParamsIfNeeded(isImageGeneration: Boolean = false) {
        if (isImageGeneration) return // 当前参数系统仅绑定文本会话
        val id = _currentConversationId.value
        val cfg = conversationGenerationConfigs.value[id] ?: return
        // 写盘（如果之前未写过，也无害）
        dataSource?.saveConversationParameters(conversationGenerationConfigs.value)
    }
    
    // 放弃一个“仅应用过参数但未发消息”的空会话：
    // 清除当前会话ID在内存中的参数映射，并同步到持久化（若存在）
    fun abandonEmptyPendingConversation() {
        if (messages.isEmpty()) {
            val id = _currentConversationId.value
            if (conversationGenerationConfigs.value.containsKey(id)) {
                val newMap = conversationGenerationConfigs.value.toMutableMap()
                newMap.remove(id)
                conversationGenerationConfigs.value = newMap
                dataSource?.saveConversationParameters(newMap)
            }
        }
    }
    
    // 为历史会话设置稳定的ID
    fun setConversationIdForHistory(historyIndex: Int) {
        // 使用历史索引生成稳定的ID
        _currentConversationId.value = "history_chat_$historyIndex"
    }
    
    // 清理未使用的会话参数（保留最近50个会话的参数）
    fun cleanupOldConversationParameters() {
        val currentConfigs = conversationGenerationConfigs.value
        if (currentConfigs.size > 50) {
            // Keep only the 50 most recent conversation parameters
            // For simplicity, we'll keep all history_chat_* and recent new_chat_* IDs
            val sortedKeys = currentConfigs.keys.sortedByDescending { key ->
                when {
                    key.startsWith("history_chat_") -> {
                        // Keep all history chats (they have stable IDs)
                        Long.MAX_VALUE
                    }
                    key.startsWith("new_chat_") -> {
                        // Extract timestamp from new_chat_TIMESTAMP
                        key.substringAfter("new_chat_").toLongOrNull() ?: 0L
                    }
                    else -> 0L
                }
            }
            
            val keysToKeep = sortedKeys.take(50).toSet()
            val cleanedConfigs = currentConfigs.filterKeys { it in keysToKeep }
            conversationGenerationConfigs.value = cleanedConfigs
            dataSource?.saveConversationParameters(cleanedConfigs)
        }
    }

    // 分离的展开推理状态
    val textExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 会话ID切换时参数迁移（仅在“尚未开始对话”的空会话场景执行）
    // 解决：用户在空会话开启参数后，内部刷新/切换会话ID导致参数丢失的问题
    fun migrateParamsOnConversationIdChange(newId: String) {
        val oldId = _currentConversationId.value
        if (oldId == newId) {
            _currentConversationId.value = newId
            return
        }
        val currentConfigs = conversationGenerationConfigs.value
        val cfg = currentConfigs[oldId]
        // 切换ID
        _currentConversationId.value = newId
        // 若仍处于空会话（未开始发消息），则迁移已落库的旧ID参数到新ID；
        // 若参数尚未落库（pending），保持 pending 即可，由首次发消息时写入
        if (cfg != null && messages.isEmpty()) {
            val newMap = currentConfigs.toMutableMap()
            newMap.remove(oldId)
            newMap[newId] = cfg
            conversationGenerationConfigs.value = newMap
            dataSource?.saveConversationParameters(newMap)
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
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for text chat")
        
        // 若当前会话为空且仅“应用未发”，按要求删除该空会话（丢弃pending、不落库）
        if (messages.isEmpty() && hasPendingConversationParams()) {
            abandonEmptyPendingConversation()
        }
        
        // 分配全新会话ID（不迁移任何旧会话参数，保持完全独立）
        _currentConversationId.value = "new_chat_${System.currentTimeMillis()}"
        
        // 新会话默认关闭参数：不做任何继承或默认值注入
        
        // Clean up old parameters periodically
        cleanupOldConversationParameters()
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        if (::_apiHandler.isInitialized) {
            _apiHandler.clearTextChatResources()
        }
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
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for image chat")
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        if (::_apiHandler.isInitialized) {
            _apiHandler.clearImageChatResources()
        }
        isImageConversationDirty.value = false
    }

    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> =
        mutableStateListOf()

    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _imageGenerationHistoricalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _loadedImageGenerationHistoryIndex = MutableStateFlow<Int?>(null)
    val _isLoadingHistory = MutableStateFlow(false)
    
    // 图像生成错误处理状态
    val _imageGenerationRetryCount = MutableStateFlow(0)
    val _imageGenerationError = MutableStateFlow<String?>(null)
    val _shouldShowImageGenerationError = MutableStateFlow(false)
    val _isLoadingHistoryData = MutableStateFlow(false)
    val _currentConversationId = MutableStateFlow<String>("new_chat_${System.currentTimeMillis()}")
    val _currentImageGenerationConversationId = MutableStateFlow<String>("new_image_generation_${System.currentTimeMillis()}")
    // 待加载的图像历史索引（用于跨页面导航时抑制“新建图像会话”）
    val _pendingImageHistoryIndex = MutableStateFlow<Int?>(null)

     val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
     val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _imageGenApiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedImageGenApiConfig = MutableStateFlow<ApiConfig?>(null)
    // 图像输出宽高比（默认 AUTO）
    val _selectedImageRatio = MutableStateFlow(com.android.everytalk.data.DataClass.ImageRatio.DEFAULT_SELECTED)
 
 
     val _snackbarMessage =
         MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    val _editDialogInputText = MutableStateFlow("")

    val _showSettingsDialog = MutableStateFlow(false)
    
    // 🎯 新增：添加配置流程相关的对话框状态
    val _showAutoFetchConfirmDialog = MutableStateFlow(false)
    val _showModelSelectionDialog = MutableStateFlow(false)
    val _pendingConfigParams = MutableStateFlow<PendingConfigParams?>(null)

    val _isWebSearchEnabled = MutableStateFlow(false)

    val _showSourcesDialog = MutableStateFlow(false)
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())

    internal val _requestScrollForReasoningBoxEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

 
     fun clearSelectedMedia() {
        selectedMediaItems.clear()
    }
fun addMessage(message: Message, isImageGeneration: Boolean = false) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "addMessage must be called from the main thread"
    }
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
     * Create a StreamingBuffer for a message
     * 
     * This method creates a new buffer that will accumulate streaming content
     * and trigger throttled updates to the UI. The buffer automatically handles:
     * - Time-based throttling (300ms intervals)
     * - Size-based batching (30 character threshold)
     * - Delayed flush for slow streams
     * 
     * Also initializes the StreamingMessageStateManager for efficient UI observation.
     * 
     * Requirements: 1.4, 3.1, 3.2, 3.3, 3.4
     * 
     * @param messageId Unique identifier for the message
     * @param isImageGeneration Whether this is for image generation chat
     * @return The created StreamingBuffer instance
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

        // 初始化已提交长度为0
        streamingLastLengths[messageId] = 0

        // Create new buffer with callback -> 仅追加“增量”，避免反复全量赋值
        val buffer = StreamingBuffer(
            messageId = messageId,
            updateInterval = 120L,  // 120ms 合理节流
            batchThreshold = 30,    // 至少30字符再触发一次
            onUpdate = { content ->
                // 计算增量并仅追加
                val lastLen = streamingLastLengths[messageId] ?: 0
                if (content.length > lastLen) {
                    val delta = content.substring(lastLen)
                    if (delta.isNotEmpty()) {
                        streamingMessageStateManager.appendText(messageId, delta)
                        streamingLastLengths[messageId] = content.length
                    }
                }
                // 标记会话为脏，持久化逻辑仍可感知到有增量
                if (isImageGeneration) {
                    isImageConversationDirty.value = true
                } else {
                    isTextConversationDirty.value = true
                }
                // 不在流式期间写回 messages 列表，Finish/ContentFinal 时一次性同步
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

            // 🎯 Finish streaming in StreamingMessageStateManager
            streamingMessageStateManager.finishStreaming(messageId)

            // 重置增量计数器，避免后续错误计算
            streamingLastLengths.remove(messageId)

            android.util.Log.d("ViewModelStateHolder", "Flushed StreamingBuffer and finished streaming for message: $messageId")
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

        // 清理增量计数器
        streamingLastLengths.remove(messageId)

        // 🎯 Clear streaming state in StreamingMessageStateManager
        streamingMessageStateManager.clearStreamingState(messageId)

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
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                reasoning = (currentMessage.reasoning ?: "") + text
            )
            messageList[index] = updatedMessage
            
            // 🔍 [STREAM_DEBUG] 记录reasoning更新
            android.util.Log.i("STREAM_DEBUG", "[ViewModelStateHolder] ✅ Reasoning updated: msgId=$messageId, totalLen=${updatedMessage.reasoning?.length ?: 0}")
            
            // 🎯 根因修复：推理文本更新必须标记"会话脏"，否则不会被持久化
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }
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
     * - 流式期间：通过 StreamingBuffer 路由，自动节流（300ms/30字符）
     * - 同时更新 StreamingMessageStateManager 以支持高效的 UI 观察
     * - 非流式：正常更新 messages
     * - 大幅减少状态更新频率（从 100次/10秒 → ~10次/10秒）
     * 
     * Requirements: 1.4, 3.1, 3.2, 3.3, 3.4
     */
    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        if (text.isEmpty()) return

        // 始终优先走 StreamingBuffer（节流+合并），避免对 SnapshotStateList 频繁全文写回
        streamingBuffers[messageId]?.let { buffer ->
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
    
    /**
     * 同步流式消息到 messages 列表
     * 🎯 在流式结束时调用，将缓冲区的文本同步到持久化存储
     */
    fun syncStreamingMessageToList(messageId: String, isImageGeneration: Boolean = false) {
        val finalText = streamingMessageStateManager.finishStreaming(messageId)

        android.util.Log.d("ViewModelStateHolder", "🎯 Syncing streaming message $messageId: finalText.length=${finalText.length}")

        if (finalText.isEmpty()) {
            android.util.Log.w("ViewModelStateHolder", "syncStreamingMessageToList: empty text for $messageId")
            // 同步完成后，确保清理长度记录
            streamingLastLengths.remove(messageId)
            return
        }

        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }

        if (index != -1) {
            val currentMessage = messageList[index]
            // 🎯 流式结束：将 StreamingMessageStateManager 的内容同步到 message.text
            val updatedMessage = currentMessage.copy(
                text = finalText,
                contentStarted = true
            )
            messageList[index] = updatedMessage
            android.util.Log.d("ViewModelStateHolder", "🎯 Synced message.text = ${finalText.take(100)}...")

            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }

            // 同步完成后，清理长度记录，避免后续错误增量
            streamingLastLengths.remove(messageId)

            android.util.Log.d("ViewModelStateHolder", "Synced streaming message $messageId, final length: ${finalText.length}")
        }
    }
    
    // 图像生成错误处理方法
    fun incrementImageGenerationRetryCount() {
        _imageGenerationRetryCount.value = _imageGenerationRetryCount.value + 1
    }
    
    fun resetImageGenerationRetryCount() {
        _imageGenerationRetryCount.value = 0
    }
    
    fun setImageGenerationError(error: String) {
        _imageGenerationError.value = error
    }
    
    fun showImageGenerationErrorDialog(show: Boolean) {
        _shouldShowImageGenerationError.value = show
    }
    
    fun dismissImageGenerationErrorDialog() {
        _shouldShowImageGenerationError.value = false
        _imageGenerationError.value = null
    }
    private lateinit var _apiHandler: ApiHandler
    fun setApiHandler(handler: ApiHandler) {
        _apiHandler = handler
    }

    fun getApiHandler(): ApiHandler {
        if (!::_apiHandler.isInitialized) {
            throw IllegalStateException("ApiHandler not initialized")
        }
        return _apiHandler
    }
}