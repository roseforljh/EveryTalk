package com.example.everytalk.StateControler // 你的包名

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf // <<< 新增导入
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap // <<< 新增导入
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.WebSearchResult
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

    // ▼▼▼ 修改这些为 SnapshotStateMap ▼▼▼
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val messageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    // ▲▲▲ 修改完成 ▲▲▲

    // --- UI 事件 ---
    val _snackbarMessage =
        MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1) // 增加 extraBufferCapacity
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1) // 增加 extraBufferCapacity

    // --- 编辑/重命名对话框相关 ---
    val _editDialogInputText = MutableStateFlow("")
    val _renameInputText = MutableStateFlow("")

    // --- 设置对话框显示状态 ---
    val _showSettingsDialog = MutableStateFlow(false)

    // --- 联网搜索模式状态 ---
    val _isWebSearchEnabled = MutableStateFlow(false)

    // --- “查看来源”对话框的状态 ---
    val _showSourcesDialog = MutableStateFlow(false)
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())

    fun clearForNewChat() {
        _text.value = ""
        messages.clear()
        _isApiCalling.value = false
        apiJob?.cancel() // 取消正在进行的API调用
        apiJob = null
        _currentStreamingAiMessageId.value = null
        reasoningCompleteMap.clear()
        expandedReasoningStates.clear()
        messageAnimationStates.clear() // 清除动画状态
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        _loadedHistoryIndex.value = null // 确保新聊天时没有加载的历史索引
    }
}