package com.example.everytalk.statecontroller

import android.os.Looper
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.foundation.lazy.LazyListState
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.WebSearchResult
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

    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()
    val imageGenerationMessages: SnapshotStateList<Message> = mutableStateListOf()

    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> =
        mutableStateListOf()

    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _imageGenerationHistoricalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _loadedImageGenerationHistoryIndex = MutableStateFlow<Int?>(null)
    val _isLoadingHistory = MutableStateFlow(false)
    val _isLoadingHistoryData = MutableStateFlow(false) // 新增：历史数据加载状态
    val _currentConversationId = MutableStateFlow<String>("new_chat_${System.currentTimeMillis()}")
    val _currentImageGenerationConversationId = MutableStateFlow<String>("new_image_generation_${System.currentTimeMillis()}")

     val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
     val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _imageGenApiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedImageGenApiConfig = MutableStateFlow<ApiConfig?>(null)
 
     val _isApiCalling = MutableStateFlow(false)
    var apiJob: Job? = null
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val conversationScrollStates = mutableStateMapOf<String, ConversationScrollState>()
    val systemPromptExpandedState = mutableStateMapOf<String, Boolean>()
    val systemPrompts = mutableStateMapOf<String, String>()
 
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

    fun clearForNewChat() {
        _text.value = ""
        messages.clear()
        imageGenerationMessages.clear()
        selectedMediaItems.clear()
        _isApiCalling.value = false
        apiJob?.cancel()
        apiJob = null
        _currentStreamingAiMessageId.value = null
        reasoningCompleteMap.clear()
        expandedReasoningStates.clear()
        messageAnimationStates.clear()
        systemPromptExpandedState.clear()
        systemPrompts.clear()
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        _loadedHistoryIndex.value = null
        _loadedImageGenerationHistoryIndex.value = null
        _currentConversationId.value = "new_chat_${System.currentTimeMillis()}"
        _currentImageGenerationConversationId.value = "new_image_generation_${System.currentTimeMillis()}"
    }
 
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
}