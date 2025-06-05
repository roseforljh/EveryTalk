package com.example.everytalk.StateControler

import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.model.SelectedMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class ViewModelStateHolder {
    val drawerState: DrawerState = DrawerState(initialValue = DrawerValue.Closed)

    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()

    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> =
        mutableStateListOf()

    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)

    val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)

    val _isApiCalling = MutableStateFlow(false)
    var apiJob: Job? = null
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    val _snackbarMessage =
        MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    val _editDialogInputText = MutableStateFlow("")
    val _renameInputText = MutableStateFlow("")

    val _showSettingsDialog = MutableStateFlow(false)

    val _isWebSearchEnabled = MutableStateFlow(false)

    val _showSourcesDialog = MutableStateFlow(false)
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())

    fun clearForNewChat() {
        _text.value = ""
        messages.clear()
        selectedMediaItems.clear()
        _isApiCalling.value = false
        apiJob?.cancel()
        apiJob = null
        _currentStreamingAiMessageId.value = null
        reasoningCompleteMap.clear()
        expandedReasoningStates.clear()
        messageAnimationStates.clear()
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        _loadedHistoryIndex.value = null
    }

    fun clearSelectedMedia() {
        selectedMediaItems.clear()
        Log.d("ViewModelStateHolder", "已选媒体已清除 (使用 mutableStateListOf)")
    }
}