package com.example.app1.StateControler // 包名根据你的实际情况

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class ViewModelStateHolder {
    // --- 抽屉相关 ---
    val drawerState: DrawerState = DrawerState(initialValue = DrawerValue.Closed)

    // --- 输入和消息列表 ---
    val _text = MutableStateFlow("")
    val messages: SnapshotStateList<Message> = mutableStateListOf()

    // --- 历史记录 ---
    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)

    // --- API 配置 ---
    val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)

    // --- API 调用状态 ---
    val _isApiCalling = MutableStateFlow(false)
    var apiJob: Job? = null
    val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val reasoningCompleteMap: MutableMap<String, Boolean> = mutableMapOf()
    val expandedReasoningStates: MutableMap<String, Boolean> = mutableMapOf()
    val messageAnimationStates: MutableMap<String, Boolean> = mutableMapOf()

    // --- UI 事件 ---
    val _snackbarMessage = MutableSharedFlow<String>(replay = 0)
    val _scrollToBottomEvent = MutableSharedFlow<Unit>(replay = 0)

    // --- 编辑/重命名对话框相关 ---
    val _editDialogInputText = MutableStateFlow("")
    val _renameInputText = MutableStateFlow("")

    // --- **新增：设置对话框显示状态** ---
    val _showSettingsDialog = MutableStateFlow(false) // <--- 添加这一行
    // --- **结束新增** ---


    fun clearForNewChat() {
        _text.value = ""
        messages.clear()
        _isApiCalling.value = false
        apiJob?.cancel()
        apiJob = null
        _currentStreamingAiMessageId.value = null
        reasoningCompleteMap.clear()
        expandedReasoningStates.clear()
        // _showSettingsDialog.value = false // 可选：在新聊天时也关闭设置对话框
    }
}