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
    // Dirty flags to track conversation changes
    val isTextConversationDirty = MutableStateFlow(false)
    val isImageConversationDirty = MutableStateFlow(false)
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
    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                reasoning = (currentMessage.reasoning ?: "") + text
            )
            messageList[index] = updatedMessage
            // 🎯 根因修复：推理文本更新必须标记“会话脏”，否则不会被持久化
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
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
        if (isImageGeneration) {
            isImageConversationDirty.value = true
        } else {
            isTextConversationDirty.value = true
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