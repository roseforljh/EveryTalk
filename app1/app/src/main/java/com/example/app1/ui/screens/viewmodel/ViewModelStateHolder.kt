package com.example.app1.ui.screens.viewmodel.state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.app1.data.models.ApiConfig // Assuming ApiConfig is in data.models now based on previous corrections
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.AppView
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

    // --- API Job Tracking ---
    var apiJob: Job? = null

    // --- UI Events ---
    val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1) // Add buffer capacity
    val _userScrolledAway = MutableStateFlow(false)

    // +++ New: Event flow to trigger scrolling +++
    // Use extraBufferCapacity=1 to ensure the event is received even if the UI is briefly not collecting
    val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    // +++ End New +++


    fun clearForNewChat() {
        messages.clear()
        _text.value = ""
        _loadedHistoryIndex.value = null
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        _userScrolledAway.value = false
        // _scrollToBottomEvent.tryEmit(Unit) // Optionally trigger scroll on new chat too
    }

    fun clearForHistoryView() {
        messages.clear()
        _text.value = ""
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        _userScrolledAway.value = false
    }

    fun clearApiState() {
        _isApiCalling.value = false
        _currentStreamingAiMessageId.value = null
        apiJob = null
    }

    fun clearAllUiStateForNewSession() {
        clearForNewChat()
        _currentView.value = AppView.CurrentChat
        _showSettingsDialog.value = false
        clearApiState()
    }
}