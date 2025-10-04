package com.example.everytalk.statecontroller

import android.os.Looper
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.example.everytalk.statecontroller.ApiHandler
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.foundation.lazy.LazyListState
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.DataClass.GenerationConfig
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.ui.util.ScrollController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ConversationScrollState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val userScrolledAway: Boolean = false
)
 
 class ViewModelStateHolder {
    lateinit var scrollController: ScrollController
     val drawerState: DrawerState = DrawerState(initialValue = DrawerValue.Closed)
    
    // DataSource for persistent storage - will be initialized from AppViewModel
    private var dataSource: com.example.everytalk.data.local.SharedPreferencesDataSource? = null
    
    fun initializeDataSource(source: com.example.everytalk.data.local.SharedPreferencesDataSource) {
        dataSource = source
        // Load saved parameters when initialized
        val savedParameters = source.loadConversationParameters()
        if (savedParameters.isNotEmpty()) {
            conversationGenerationConfigs.value = savedParameters
        }
        // 不在此处为当前会话ID自动回填，避免新建会话默认开启 maxTokens
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

    // 分离的API Job
    var textApiJob: Job? = null
    var imageApiJob: Job? = null

    // 分离的推理完成状态
    val textReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 每个会话独立的生成配置参数
    val conversationGenerationConfigs: MutableStateFlow<Map<String, GenerationConfig>> =
        MutableStateFlow(emptyMap())
    
    // 获取当前会话的生成配置
    fun getCurrentConversationConfig(): GenerationConfig? {
        return conversationGenerationConfigs.value[_currentConversationId.value]
    }
    
    // 更新当前会话的生成配置
    fun updateCurrentConversationConfig(config: GenerationConfig) {
        val currentConfigs = conversationGenerationConfigs.value.toMutableMap()
        currentConfigs[_currentConversationId.value] = config
        conversationGenerationConfigs.value = currentConfigs
        
        // 保存当前会话参数映射
        dataSource?.saveConversationParameters(currentConfigs)
        // 同步更新全局默认为“最近一次使用参数”，供新会话继承
        dataSource?.saveGlobalConversationDefaults(config)
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
    
    // 为当前会话ID在缺省时应用全局默认参数
    fun applyDefaultParamsForCurrentConversationIdIfMissing() {
        val currentId = _currentConversationId.value
        val hasConfig = conversationGenerationConfigs.value.containsKey(currentId)
        if (!hasConfig) {
            val global = dataSource?.loadGlobalConversationDefaults()
            if (global != null) {
                val newMap = conversationGenerationConfigs.value.toMutableMap()
                newMap[currentId] = global
                conversationGenerationConfigs.value = newMap
                dataSource?.saveConversationParameters(newMap)
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
        _currentConversationId.value = "new_chat_${System.currentTimeMillis()}"
        
        // 不为新会话自动继承全局默认，保持默认关闭的期望
        
        // Clean up old parameters periodically
        cleanupOldConversationParameters()
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        if (::_apiHandler.isInitialized) {
            _apiHandler.clearTextChatResources()
        }
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
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        if (::_apiHandler.isInitialized) {
            _apiHandler.clearImageChatResources()
        }
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

     val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
     val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _imageGenApiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedImageGenApiConfig = MutableStateFlow<ApiConfig?>(null)
    // 图像输出宽高比（默认 AUTO）
    val _selectedImageRatio = MutableStateFlow(com.example.everytalk.data.DataClass.ImageRatio.DEFAULT_SELECTED)
 
 
     val _snackbarMessage =
         MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    val _editDialogInputText = MutableStateFlow("")

    val _showSettingsDialog = MutableStateFlow(false)

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
    } else {
        messages.add(message)
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
    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                reasoning = (currentMessage.reasoning ?: "") + text
            )
            messageList[index] = updatedMessage
        }
    }

    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = currentMessage.text + text,
                contentStarted = true
            )
            messageList[index] = updatedMessage
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