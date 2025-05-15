package com.example.app1.StateControler

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import com.example.app1.navigation.AppView // 确保这个 import 路径正确
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 持有 AppViewModel 的核心可变状态。
 */
class ViewModelStateHolder {

    // --- 抽屉状态 ---
    val drawerState = DrawerState(DrawerValue.Closed)

    // --- 核心状态 ---
    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf() // 消息列表，oldest-first (新消息在末尾)
    val _currentView = MutableStateFlow(AppView.CurrentChat)
    val _historicalConversations =
        MutableStateFlow<List<List<Message>>>(emptyList()) // 内部列表也应是 oldest-first
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _showSettingsDialog = MutableStateFlow(false)
    val _isApiCalling = MutableStateFlow(false)
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    // --- 编辑和重命名相关的状态 ---
    val _editDialogInputText = MutableStateFlow("")
    val _renameInputText = MutableStateFlow("")

    // --- API任务跟踪 ---
    var apiJob: Job? = null

    // --- UI事件 ---
    val _snackbarMessage = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 滚动事件，用于滚动到底部
    val _scrollToBottomEvent = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun clearForNewChat() {
        messages.clear()
        _text.value = ""
        _loadedHistoryIndex.value = null
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        messageAnimationStates.clear()
        _editDialogInputText.value = ""
        _renameInputText.value = ""
        clearApiState()
    }

    fun clearApiState() {
        _isApiCalling.value = false
        _currentStreamingAiMessageId.value = null
        apiJob?.cancel()
        apiJob = null
    }

    fun clearAllUiStateForNewSession() {
        clearForNewChat()
        _historicalConversations.value = emptyList()
        _apiConfigs.value = emptyList()
        _selectedApiConfig.value = null
        _currentView.value = AppView.CurrentChat
        _showSettingsDialog.value = false
    }
}