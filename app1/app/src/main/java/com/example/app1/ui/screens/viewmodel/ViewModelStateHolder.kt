package com.example.app1.ui.screens.viewmodel.state // 确保包名正确

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.AppView // 确保 AppView 的导入路径正确
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds the core mutable state for the AppViewModel.
 * This makes the main ViewModel cleaner by centralizing state declarations.
 */
class ViewModelStateHolder {

    // --- Core State ---
    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()
    val _currentView = MutableStateFlow(AppView.CurrentChat)
    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _showSettingsDialog = MutableStateFlow(false)
    val _isApiCalling = MutableStateFlow(false)
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    // Stores whether the main text animation has completed for a message ID.
    // Key: message.id, Value: true if animation completed, false otherwise.
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()


    // --- API Job Tracking ---
    var apiJob: Job? = null

    // --- UI Events ---
    val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val _userScrolledAway = MutableStateFlow(false)
    val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    fun clearForNewChat() {
        messages.clear()
        _text.value = ""
        _loadedHistoryIndex.value = null
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        messageAnimationStates.clear() // Clear animation states for a new chat
        _userScrolledAway.value = false
    }

    fun clearForHistoryView() {
        messages.clear()
        _text.value = ""
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        messageAnimationStates.clear() // Clear animation states when viewing history list
        _userScrolledAway.value = false
    }

    fun clearApiState() {
        _isApiCalling.value = false
        _currentStreamingAiMessageId.value = null
        apiJob = null
    }

    fun clearAllUiStateForNewSession() {
        clearForNewChat() // This already clears messageAnimationStates
        _currentView.value = AppView.CurrentChat
        _showSettingsDialog.value = false
        clearApiState()
    }
}