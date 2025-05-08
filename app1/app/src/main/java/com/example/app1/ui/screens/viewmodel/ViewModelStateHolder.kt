package com.example.app1.ui.screens.viewmodel.state

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.viewmodel.AppView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 持有 AppViewModel 的核心可变状态。
 */
class ViewModelStateHolder {

    // --- 抽屉状态 ---
    val drawerState = DrawerState(DrawerValue.Closed) // 初始化为关闭状态

    // --- 核心状态 ---
    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()
    val _currentView = MutableStateFlow(AppView.CurrentChat) // 主内容视图，基本固定
    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _showSettingsDialog = MutableStateFlow(false)
    val _isApiCalling = MutableStateFlow(false)
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    // --- API任务跟踪 ---
    var apiJob: Job? = null // API任务引用

    // --- UI事件 ---
    val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val _userScrolledAway = MutableStateFlow(false)
    val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    /**
     * 为新的聊天清除状态。
     */
    fun clearForNewChat() {
        messages.clear()
        _text.value = ""
        _loadedHistoryIndex.value = null
        expandedReasoningStates.clear()
        reasoningCompleteMap.clear()
        messageAnimationStates.clear()
        _userScrolledAway.value = false
    }

    /**
     * 清除API相关的状态。
     */
    fun clearApiState() {
        _isApiCalling.value = false
        _currentStreamingAiMessageId.value = null
        apiJob = null
    }

    /**
     * 为新的会话（例如，完全重置或注销）清除所有UI状态。
     */
    fun clearAllUiStateForNewSession() {
        clearForNewChat()
        _historicalConversations.value = emptyList()
        _currentView.value = AppView.CurrentChat
        _showSettingsDialog.value = false
        clearApiState()
    }
}
