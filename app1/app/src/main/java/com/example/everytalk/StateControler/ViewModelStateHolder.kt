package com.example.everytalk.StateControler // 你的包名

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
// --- 新增：导入 WebSearchResult ---
import com.example.everytalk.data.DataClass.WebSearchResult // 确保路径正确
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

    // --- 设置对话框显示状态 ---
    val _showSettingsDialog = MutableStateFlow(false) // 这是你已有的，用于其他设置对话框

    // --- 新增：联网搜索模式状态 ---
    val _isWebSearchEnabled = MutableStateFlow(false)

    // --- 新增：用于“查看来源”对话框的状态 ---
    /**
     * 控制网页搜索结果来源对话框的可见性。
     * true 表示显示，false 表示隐藏。
     */
    val _showSourcesDialog = MutableStateFlow(false)

    /**
     * 存储当前要在对话框中显示的网页搜索结果列表。
     */
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())
    // --- 新增状态结束 ---


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
        // --- 新增：在新聊天时也应关闭来源对话框 ---
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        // --- 新增结束 ---
    }
}