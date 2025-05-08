package com.example.app1

import android.util.Log
import androidx.compose.material3.DrawerState // 确保 DrawerState 导入正确
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateListOf // For messages list
import androidx.compose.runtime.snapshotFlow // 用于将 Compose State 转换为 Flow
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.viewmodel.ApiHandler
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// 常量
// const val USER_CANCEL_MESSAGE_VM = "用户取消"

class AppViewModel(dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    // --- 初始化顺序修正 ---
    private val historyManager: HistoryManager =
        HistoryManager(stateHolder, persistenceManager, viewModelScope)
    private val apiHandler: ApiHandler = ApiHandler(
        stateHolder,
        viewModelScope,
        historyManager
    ) // Pass historyManager if needed by ApiHandler
    private val configManager: ConfigManager =
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    // --- 修正结束 ---


    // --- 暴露的状态 ---
    val drawerState: DrawerState = stateHolder.drawerState
    val text: StateFlow<String> = stateHolder._text.asStateFlow()
    val messages = stateHolder.messages // SnapshotStateList
    val historicalConversations: StateFlow<List<List<Message>>> =
        stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> = stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> = stateHolder._selectedApiConfig.asStateFlow()
    val showSettingsDialog: StateFlow<Boolean> =
        stateHolder._showSettingsDialog.asStateFlow() // Intent to show settings
    val isApiCalling: StateFlow<Boolean> = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> =
        stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap = stateHolder.reasoningCompleteMap // SnapshotStateMap
    val expandedReasoningStates = stateHolder.expandedReasoningStates // SnapshotStateMap
    val messageAnimationStates = stateHolder.messageAnimationStates // SnapshotStateMap
    val snackbarMessage: SharedFlow<String> = stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit> = stateHolder._scrollToBottomEvent.asSharedFlow()

    // --- *** 修正/添加 showScrollToBottomButton *** ---
    val showScrollToBottomButton: StateFlow<Boolean> =
        snapshotFlow { stateHolder._userScrolledAway.value } // Listen to user scrolled away state
            .combine(snapshotFlow { stateHolder.messages.size > 1 }) { scrolledAway, hasMultipleMessages ->
                // Show button if user scrolled away AND there's more than one message
                scrolledAway && hasMultipleMessages
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = false // Initially hidden
            )
    // --- *** 修正/添加结束 *** ---

    // --- 重命名对话框状态 ---
    private val _showRenameDialogState = MutableStateFlow(false)
    val showRenameDialogState = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null)
    val renamingIndexState = _renamingIndexState.asStateFlow()
    private val _renameInputText = MutableStateFlow("")
    val renameInputText = _renameInputText.asStateFlow()


    init {
        Log.d("AppViewModel", "ViewModel 初始化...")
        persistenceManager.loadInitialData { initialConfigPresent, _ ->
            viewModelScope.launch(Dispatchers.Main) {
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    stateHolder._snackbarMessage.tryEmit("请添加 API 配置")
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    stateHolder._apiConfigs.value.firstOrNull()?.let { selectConfig(it) }
                        ?: stateHolder._snackbarMessage.tryEmit("请选择一个 API 配置")
                }
            }
        }
    }

    // --- 聊天相关方法 ---
    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    // Called by ChatScreen to update the ViewModel's state about user scrolling
    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) {
            stateHolder._userScrolledAway.value = scrolledAway
            Log.d("AppViewModel", "User scrolled away state updated to: $scrolledAway")
        }
    }

    fun onSendMessage() {
        val userMessageText = stateHolder._text.value.trim()
        Log.d("AppViewModel", "onSendMessage called. Text: '$userMessageText'")
        if (userMessageText.isNotEmpty() && stateHolder._selectedApiConfig.value != null) {
            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                text = userMessageText,
                sender = Sender.User, // Ensure correct enum value
                timestamp = System.currentTimeMillis(),
                contentStarted = true
            )
            viewModelScope.launch(Dispatchers.Main.immediate) {
                // Add to the beginning for reverseLayout=true
                stateHolder.messages.add(0, userMessage)
                Log.d(
                    "AppViewModel",
                    "User message added to index 0. List size: ${stateHolder.messages.size}."
                )
                stateHolder._userScrolledAway.value = false // Reset scroll state on send
                triggerScrollToBottom()
            }
            stateHolder._text.value = ""
            // Ensure ApiHandler also adds AI messages to index 0 or updates near index 0
            apiHandler.streamChatResponse(userMessageText) {
                triggerScrollToBottom() // Trigger scroll after AI placeholder might be added
            }
        } else if (userMessageText.isEmpty()) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请输入消息内容") }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择 API 配置") }
        }
    }

    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(Unit)
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    // --- 导航和历史记录加载 ---
    fun startNewChat() {
        Log.d("AppViewModel", "方法 startNewChat 被调用")
        apiHandler.cancelCurrentApiJob("开始新聊天")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        stateHolder.clearForNewChat()
        triggerScrollToBottom()
    }

    fun loadConversationFromHistory(index: Int) {
        Log.d("AppViewModel", "方法 loadConversationFromHistory 被调用, 索引: $index")
        stateHolder._historicalConversations.value.getOrNull(index)?.let { conversationToLoad ->
            apiHandler.cancelCurrentApiJob("加载历史索引 $index")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                // Add all messages from history (assuming old->new order)
                stateHolder.messages.addAll(conversationToLoad.map { msg ->
                    msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                })
                // Mark animations as played for loaded messages
                stateHolder.messages.forEach { msg ->
                    if ((msg.sender == Sender.AI || msg.sender == Sender.User) &&
                        (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    ) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom() // Scroll to bottom (index 0 for reverseLayout)
                Log.d(
                    "AppViewModel",
                    "历史对话 $index 已加载, 消息数: ${stateHolder.messages.size}"
                )
            }
        } ?: viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("无法加载对话") }
    }

    // --- 其他方法 (deleteConversation, clearAllConversations, 设置, API配置, 推理, 重命名等保持不变) ---
    fun deleteConversation(indexToDelete: Int) {
        Log.d("AppViewModel", "方法 deleteConversation 被调用, 索引: $indexToDelete")
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        historyManager.deleteConversation(indexToDelete)
        if (currentLoadedIndex == indexToDelete) {
            stateHolder._loadedHistoryIndex.value = null
            stateHolder.clearForNewChat()
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            Log.d("AppViewModel", "正在清空所有对话...")
            historyManager.clearAllHistory()
            if (stateHolder._loadedHistoryIndex.value != null) {
                stateHolder.clearForNewChat()
                stateHolder._loadedHistoryIndex.value = null
            }
            Log.d("AppViewModel", "所有对话已清空。")
        }
    }

    fun showSettingsScreen() {
        stateHolder._showSettingsDialog.value = true
    }

    fun dismissSettingsScreenIntent() {
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
        if (stateHolder.expandedReasoningStates.containsKey(messageId)) {
            stateHolder.expandedReasoningStates[messageId] = false
        }
    }

    fun onToggleReasoningExpand(messageId: String) {
        stateHolder.expandedReasoningStates[messageId] =
            !(stateHolder.expandedReasoningStates[messageId] ?: false)
    }

    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (stateHolder.messageAnimationStates[messageId] != true) {
                stateHolder.messageAnimationStates[messageId] = true
            }
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
        val firstUserMsg = conversation?.firstOrNull { it.sender == Sender.User }?.text?.trim()
        val firstAiMsg = conversation?.firstOrNull { it.sender == Sender.AI }?.text?.trim()
        val firstMsg = conversation?.firstOrNull()?.text?.trim()
        return when {
            !firstUserMsg.isNullOrBlank() -> firstUserMsg; !firstAiMsg.isNullOrBlank() -> firstAiMsg; !firstMsg.isNullOrBlank() -> firstMsg; else -> "对话 ${index + 1}"
        }
    }

    fun onRenameInputTextChange(newName: String) {
        _renameInputText.value = newName
    }

    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index; _renameInputText.value =
                ""; _showRenameDialogState.value = true; } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("无法重命名：无效的对话索引") }
        }
    }

    fun dismissRenameDialog() {
        _showRenameDialogState.value = false; _renamingIndexState.value =
            null; _renameInputText.value = ""
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("新名称不能为空") }
            return
        }
        Log.w(
            "AppViewModel",
            "[占位符重命名] 尝试将索引 $index 重命名为 '$trimmedNewName' (通过修改首条用户消息)"
        )
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value
            if (index >= 0 && index < currentHistory.size) {
                val conversationToModify = currentHistory[index].toMutableList()
                val firstUserMessageIndex =
                    conversationToModify.indexOfFirst { it.sender == Sender.User }
                if (firstUserMessageIndex != -1) {
                    val originalMessage = conversationToModify[firstUserMessageIndex]
                    val updatedMessage = originalMessage.copy(text = trimmedNewName)
                    conversationToModify[firstUserMessageIndex] = updatedMessage
                    val updatedHistory = currentHistory.toMutableList()
                    updatedHistory[index] = conversationToModify.toList()
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder._historicalConversations.value = updatedHistory.toList()
                        if (stateHolder._loadedHistoryIndex.value == index) {
                            Log.d("AppViewModel", "当前加载的对话已被重命名")
                        }
                        stateHolder._snackbarMessage.tryEmit("对话已重命名为 '$trimmedNewName'")
                    }
                    persistenceManager.saveChatHistory()
                } else {
                    withContext(Dispatchers.Main) { stateHolder._snackbarMessage.tryEmit("无法重命名：对话中无用户消息可作标题") }
                }
            } else {
                withContext(Dispatchers.Main) { stateHolder._snackbarMessage.tryEmit("无法重命名：对话索引错误") }
            }
            withContext(Dispatchers.Main) { dismissRenameDialog() }
        }
    }

    // --- ViewModel 清理 ---
    override fun onCleared() {
        Log.d("AppViewModel", "ViewModel onCleared")
        apiHandler.cancelCurrentApiJob("ViewModel cleared")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        super.onCleared()
    }
}