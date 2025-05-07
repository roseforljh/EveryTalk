package com.example.app1.ui.screens // 确保包名正确

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.app1.data.local.SharedPreferencesDataSource // 确保路径正确
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.viewmodel.ApiHandler
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
// 不再需要 AnimationProgressState
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder


class AppViewModel(dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)
    private val historyManager = HistoryManager(stateHolder, persistenceManager, viewModelScope)
    private val apiHandler = ApiHandler(stateHolder, viewModelScope, historyManager)
    private val configManager =
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)

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

    val snackbarMessage: SharedFlow<String> = stateHolder._snackbarMessage.asSharedFlow()

    val showScrollToBottomButton: StateFlow<Boolean> =
        snapshotFlow { stateHolder.messages.size }
            .combine(stateHolder._userScrolledAway) { size, scrolledAway ->
                scrolledAway && size > 1
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

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

    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) stateHolder._userScrolledAway.value =
            scrolledAway
    }

    fun onSendMessage() {
        val userMessageText = stateHolder._text.value.trim()
        if (userMessageText.isNotEmpty() && stateHolder._selectedApiConfig.value != null) {
            stateHolder._text.value = ""
            apiHandler.streamChatResponse(userMessageText) { triggerScrollToBottom() }
        } else if (userMessageText.isEmpty()) {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("请输入消息内容") }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("请先选择 API 配置") }
        }
    }

    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(
                Unit
            )
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob(USER_CANCEL_MESSAGE) // USER_CANCEL_MESSAGE 应该是一个已定义的常量
    }

    fun navigateToHistory() {
        apiHandler.cancelCurrentApiJob("View changed to History")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        stateHolder.clearForHistoryView() // 这个会清理相关状态
        stateHolder._currentView.value = AppView.HistoryList
    }

    fun navigateToChat(fromHistory: Boolean = true) {
        apiHandler.cancelCurrentApiJob(if (fromHistory) "Returning from History" else "Starting New Chat")
        if (!fromHistory) {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            stateHolder.clearForNewChat()
            stateHolder._loadedHistoryIndex.value = null // 确保在这里重置
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

    fun loadConversationFromHistory(index: Int) {
        val conversationToLoad = stateHolder._historicalConversations.value.getOrNull(index)
        if (conversationToLoad != null) {
            apiHandler.cancelCurrentApiJob("Loading history index $index")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.messages.clear()
                val processedConversation = conversationToLoad.map { msg ->
                    if (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) msg.copy(
                        contentStarted = true
                    ) else msg
                }
                stateHolder.messages.addAll(processedConversation)
                stateHolder._text.value =
                    ""; stateHolder.reasoningCompleteMap.clear(); stateHolder.expandedReasoningStates.clear()
                stateHolder.messageAnimationStates.clear()

                processedConversation.forEach { msg ->
                    if ((msg.sender == Sender.AI || msg.sender == Sender.User) && msg.text.isNotBlank()) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    } else if (msg.sender == Sender.AI && msg.text.isBlank() && !msg.reasoning.isNullOrBlank()) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._userScrolledAway.value = false; stateHolder._loadedHistoryIndex.value =
                index; stateHolder._currentView.value = AppView.CurrentChat
                triggerScrollToBottom()
            }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("无法加载对话") }
        }
    }

    fun deleteConversation(indexToDelete: Int) {
        historyManager.deleteConversation(indexToDelete)
        // 检查是否删除的是当前加载的对话，如果是，则清除相关状态
        if (stateHolder._loadedHistoryIndex.value == indexToDelete) {
            stateHolder.messageAnimationStates.clear()
            stateHolder._loadedHistoryIndex.value = null // 清除加载的索引
        }
    }

    // REMOVED: clearAllHistory() method
    /*
    fun clearAllHistory() {
        // historyManager.clearAllHistory() // This line would call the (now removed) method in HistoryManager
        // stateHolder.messageAnimationStates.clear()
        // stateHolder._loadedHistoryIndex.value = null // Ensure loaded index is cleared
        // IMPORTANT: You need to remove or comment out the clearAllHistory method in HistoryManager
        // and its underlying persistence calls if they are no longer needed.
    }
    */

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

    fun collapseReasoning(messageId: String) {
        if (stateHolder.expandedReasoningStates.containsKey(messageId)) stateHolder.expandedReasoningStates[messageId] =
            false
    }

    fun onToggleReasoningExpand(messageId: String) {
        val current = stateHolder.expandedReasoningStates[messageId]
            ?: false; stateHolder.expandedReasoningStates[messageId] = !current
    }

    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (stateHolder.messageAnimationStates[messageId] != true) {
                stateHolder.messageAnimationStates[messageId] = true
            }
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        return stateHolder.messageAnimationStates[messageId] ?: false
    }

    override fun onCleared() {
        apiHandler.cancelCurrentApiJob("ViewModel cleared"); historyManager.saveCurrentChatToHistoryIfNeeded(); super.onCleared(); }
}
