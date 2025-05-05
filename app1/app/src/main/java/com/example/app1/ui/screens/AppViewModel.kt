package com.example.app1.ui.screens // 确保包名正确

// Standard Android & Lifecycle Imports
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// import android.os.SystemClock // 可选：用于防抖

// Compose Runtime State Imports
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow

// Coroutine & Flow Imports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Project-specific Data/Model Imports
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message

// Project-specific ViewModel Component Imports
import com.example.app1.ui.screens.viewmodel.ApiHandler
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder

// --- Constants and Enum ---
// 确保这些在 ViewModelConstants.kt 中定义或已正确导入
// import com.example.app1.ui.screens.AppView
// import com.example.app1.ui.screens.ERROR_VISUAL_PREFIX
// import com.example.app1.ui.screens.USER_CANCEL_MESSAGE

class AppViewModel(dataSource: SharedPreferencesDataSource) : ViewModel() {

    // --- State Holder ---
    private val stateHolder = ViewModelStateHolder() // 假设已包含 expandedReasoningStates

    // --- Managers and Handlers ---
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    // **** 确保 HistoryManager 在 ApiHandler 之前创建 ****
    private val historyManager = HistoryManager(stateHolder, persistenceManager, viewModelScope)
    private val apiHandler =
        ApiHandler(stateHolder, viewModelScope, historyManager) // ApiHandler 需要 HistoryManager
    private val configManager =
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)

    // --- 可选：防抖状态 ---
    // private val lastToggleTimestamp = mutableMapOf<String, Long>()
    // private val debounceThresholdMs = 300L

    // --- Expose State to UI (代码保持不变) ---
    val text: StateFlow<String> = stateHolder._text.asStateFlow()
    val messages = stateHolder.messages
    val currentView: StateFlow<AppView> = stateHolder._currentView.asStateFlow()
    val historicalConversations: StateFlow<List<List<Message>>> =
        stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> = stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> = stateHolder._selectedApiConfig.asStateFlow()
    val showSettingsDialog: StateFlow<Boolean> = stateHolder._showSettingsDialog.asStateFlow()
    val isApiCalling: StateFlow<Boolean> = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> =
        stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap = stateHolder.reasoningCompleteMap
    val scrollToBottomEvent: SharedFlow<Unit> = stateHolder._scrollToBottomEvent.asSharedFlow()
    val expandedReasoningStates = stateHolder.expandedReasoningStates

    // --- Expose UI Events (代码保持不变) ---
    val snackbarMessage: SharedFlow<String> = stateHolder._snackbarMessage.asSharedFlow()

    // --- Derived State (代码保持不变) ---
    val showScrollToBottomButton: StateFlow<Boolean> =
        snapshotFlow { stateHolder.messages.size }
            .combine(stateHolder._userScrolledAway) { size: Int, scrolledAway: Boolean ->
                scrolledAway && size > 1
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    // init block (代码保持不变)
    init {
        println("AppViewModel: Initializing...")
        persistenceManager.loadInitialData { initialConfigPresent, initialHistoryPresent ->
            viewModelScope.launch(Dispatchers.Main) {
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    stateHolder._snackbarMessage.emit("请添加 API 配置")
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    stateHolder._snackbarMessage.emit("请选择一个 API 配置")
                }
            }
        }
    }

    // --- Basic UI Event Handlers (代码保持不变) ---
    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) {
            stateHolder._userScrolledAway.value = scrolledAway
        }
    }

    // --- API Call Actions (代码保持不变) ---
    fun onSendMessage() {
        val userMessageText = stateHolder._text.value.trim()
        if (userMessageText.isNotEmpty() && stateHolder._selectedApiConfig.value != null) {
            stateHolder._text.value = ""
            apiHandler.streamChatResponse(userMessageText) {
                triggerScrollToBottom() // 发送后触发滚动
            }
        } else if (userMessageText.isEmpty()) {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("请输入消息内容") }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("请先选择 API 配置") }
        }
    }

    // 变为 public
    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(Unit)
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob(USER_CANCEL_MESSAGE)
    }

    // --- Navigation (代码保持不变, 确保调用 saveCurrentChatToHistoryIfNeeded) ---
    fun navigateToHistory() {
        apiHandler.cancelCurrentApiJob("View changed to History")
        historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天
        stateHolder.clearForHistoryView()
        stateHolder._currentView.value = AppView.HistoryList
    }

    fun navigateToChat(fromHistory: Boolean = true) {
        apiHandler.cancelCurrentApiJob(if (fromHistory) "Returning from History" else "Starting New Chat")
        if (!fromHistory) {
            historyManager.saveCurrentChatToHistoryIfNeeded() // 保存之前的聊天
            stateHolder.clearForNewChat()
        } else {
            stateHolder._userScrolledAway.value = false
            stateHolder._text.value = ""
        }
        stateHolder._currentView.value = AppView.CurrentChat
        if (stateHolder.messages.isNotEmpty()) {
            triggerScrollToBottom()
        }
    }

    fun startNewChat() {
        navigateToChat(fromHistory = false)
    }

    // --- History Interaction (代码保持不变) ---
    fun loadConversationFromHistory(index: Int) {
        val conversationToLoad = stateHolder._historicalConversations.value.getOrNull(index)
        if (conversationToLoad != null) {
            apiHandler.cancelCurrentApiJob("Loading history index $index")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.messages.clear(); stateHolder.messages.addAll(conversationToLoad)
                stateHolder._text.value = ""; stateHolder.reasoningCompleteMap.clear()
                stateHolder.expandedReasoningStates.clear(); stateHolder._userScrolledAway.value =
                false
                stateHolder._loadedHistoryIndex.value = index; stateHolder._currentView.value =
                AppView.CurrentChat
                triggerScrollToBottom()
            }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("无法加载对话") }
        }
    }

    fun deleteConversation(indexToDelete: Int) {
        historyManager.deleteConversation(indexToDelete)
    }

    fun clearAllHistory() {
        historyManager.clearAllHistory()
    }

    // --- Settings Dialog and Config Management (代码保持不变) ---
    fun showSettingsDialog() {
        stateHolder._showSettingsDialog.value = true
    }

    fun dismissSettingsDialog() {
        stateHolder._showSettingsDialog.value = false
    }

    fun addConfig(configToAdd: ApiConfig) {
        configManager.addConfig(configToAdd)
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        configManager.updateConfig(configToUpdate)
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        configManager.deleteConfig(configToDelete)
    }

    fun clearAllConfigs() {
        configManager.clearAllConfigs()
    }

    fun selectConfig(config: ApiConfig) {
        configManager.selectConfig(config)
    }

    // --- Message Interaction (代码保持不变) ---

    // 由 ChatScreen 调用以自动收起推理框
    fun collapseReasoning(messageId: String) {
        if (stateHolder.expandedReasoningStates.containsKey(messageId)) {
            stateHolder.expandedReasoningStates[messageId] = false
            println("AppViewModel: Collapsed reasoning for message $messageId")
        }
    }

    // 处理用户点击展开/收起按钮
    fun onToggleReasoningExpand(messageId: String) {
        // 可选防抖逻辑
        val current = stateHolder.expandedReasoningStates[messageId] ?: false
        val newState = !current
        stateHolder.expandedReasoningStates[messageId] = newState
        println("AppViewModel: Toggled reasoning expansion for message $messageId to $newState")
    }

    // --- ViewModel Lifecycle (代码保持不变) ---
    override fun onCleared() {
        println("AppViewModel: onCleared called.")
        apiHandler.cancelCurrentApiJob("ViewModel cleared")
        historyManager.saveCurrentChatToHistoryIfNeeded() // 最后尝试保存
        super.onCleared()
        println("AppViewModel: Cleared.")
    }
}