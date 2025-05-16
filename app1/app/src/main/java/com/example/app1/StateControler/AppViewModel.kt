package com.example.app1.StateControler // 包名根据你的实际情况

import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshots.SnapshotStateList // 确保导入
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.data.network.ApiClient // 假设ApiClient存在
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString()
    val viewModelInstanceIdForLogging: String get() = instanceId

    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)
    private val historyManager: HistoryManager =
        HistoryManager(stateHolder, persistenceManager, ::areMessageListsEffectivelyEqual)
    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
            stateHolder,
            viewModelScope,
            historyManager
        )
    }
    private val configManager: ConfigManager by lazy {
        ConfigManager(
            stateHolder,
            persistenceManager,
            apiHandler,
            viewModelScope
        )
    }

    val drawerState: DrawerState get() = stateHolder.drawerState
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow()
    val messages: SnapshotStateList<Message> get() = stateHolder.messages // <--- 类型改变

    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow()
    val isApiCalling: StateFlow<Boolean> get() = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap: Map<String, Boolean> get() = stateHolder.reasoningCompleteMap
    val expandedReasoningStates: MutableMap<String, Boolean> get() = stateHolder.expandedReasoningStates

    val snackbarMessage: SharedFlow<String> get() = stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit> get() = stateHolder._scrollToBottomEvent.asSharedFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editDialogInputText: StateFlow<String> get() = stateHolder._editDialogInputText.asStateFlow()

    private val _showRenameDialogState = MutableStateFlow(false)
    val showRenameDialogState: StateFlow<Boolean> = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null)
    val renamingIndexState: StateFlow<Int?> = _renamingIndexState.asStateFlow()
    val renameInputText: StateFlow<String> get() = stateHolder._renameInputText.asStateFlow()

    private val _isSearchActiveInDrawer = MutableStateFlow(false)
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()
    private val _searchQueryInDrawer = MutableStateFlow("")
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()

    private fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean {
        if (list1 == null && list2 == null) return true
        if (list1 == null || list2 == null) return false
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return false
        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i];
            val msg2 = filteredList2[i]
            if (msg1.id != msg2.id ||
                msg1.sender != msg2.sender ||
                msg1.text.trim() != msg2.text.trim() ||
                msg1.reasoning?.trim() != msg2.reasoning?.trim() ||
                msg1.isError != msg2.isError
            ) return false
        }
        return true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            (msg.sender != Sender.System || msg.isPlaceholderName) &&
                    (msg.sender == Sender.User ||
                            (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) ||
                            (msg.sender == Sender.System && msg.isPlaceholderName) ||
                            msg.isError)
        }.toList()
    }

    init {
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化开始")
        viewModelScope.launch(Dispatchers.IO) {
            ApiClient.preWarm()
            apiHandler
            configManager
        }
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            val currentChatMessagesFromPersistence =
                stateHolder.messages.toList() // toList() is fine here
            val historicalConversationsFromPersistence = stateHolder._historicalConversations.value

            if (currentChatMessagesFromPersistence.isNotEmpty() && historyPresent) {
                val matchedIndex =
                    historicalConversationsFromPersistence.indexOfFirst { historicalChat ->
                        areMessageListsEffectivelyEqual(
                            currentChatMessagesFromPersistence,
                            historicalChat
                        )
                    }
                stateHolder._loadedHistoryIndex.value =
                    if (matchedIndex != -1) matchedIndex else null
            } else {
                stateHolder._loadedHistoryIndex.value = null
            }

            viewModelScope.launch(Dispatchers.Main) {
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    showSnackbar("请添加 API 配置")
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    val configToSelect = stateHolder._apiConfigs.value.firstOrNull { it.isValid }
                        ?: stateHolder._apiConfigs.value.firstOrNull()
                    configToSelect?.let { selectConfig(it) }
                }
            }
        }
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化逻辑结束.")
    }

    fun showSnackbar(message: String) {
        stateHolder._snackbarMessage.tryEmit(message)
    }

    fun setSearchActiveInDrawer(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) _searchQueryInDrawer.value = ""
    }

    fun onDrawerSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
    }

    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        val textToActuallySend = messageText.trim()
        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) showSnackbar("请输入消息内容")
            return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置")
            return
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage) // 操作 SnapshotStateList

            if (!isFromRegeneration) stateHolder._text.value = ""
            triggerScrollToBottom()

            apiHandler.streamChatResponse(
                userMessageTextForContext = textToActuallySend,
                afterUserMessageId = newUserMessage.id,
                onMessagesProcessed = {
                    viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }
                }
            )
        }
    }

    fun onEditDialogTextChanged(newText: String) {
        stateHolder._editDialogInputText.value = newText
    }

    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) {
            _editingMessageId.value = message.id
            stateHolder._editDialogInputText.value = message.text
            _showEditDialog.value = true
        } else {
            showSnackbar("只能编辑您发送的消息")
        }
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = stateHolder._editDialogInputText.value.trim()
        if (updatedText.isBlank()) {
            showSnackbar("消息内容不能为空"); return
        }
        viewModelScope.launch {
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
            if (messageIndex != -1) {
                val originalMessage = stateHolder.messages[messageIndex]
                if (originalMessage.text != updatedText) {
                    stateHolder.messages[messageIndex] = originalMessage.copy(
                        text = updatedText,
                        timestamp = System.currentTimeMillis()
                    )
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                    showSnackbar("消息已更新")
                }
            }
            withContext(Dispatchers.Main.immediate) { dismissEditDialog() }
        }
    }

    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        stateHolder._editDialogInputText.value = ""
    }

    fun regenerateAiResponse(originalUserMessage: Message) {
        if (originalUserMessage.sender != Sender.User) {
            showSnackbar("只能为您的消息重新生成回答"); return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置"); return
        }
        val originalUserMessageText = originalUserMessage.text
        val originalUserMessageId = originalUserMessage.id

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (userMessageIndex == -1) {
                showSnackbar("无法重新生成：原始用户消息未找到。"); return@launch
            }

            if (userMessageIndex < stateHolder.messages.size - 1) {
                val nextMessageIndex = userMessageIndex + 1
                if (stateHolder.messages[nextMessageIndex].sender == Sender.AI) {
                    val aiMessageToRemove = stateHolder.messages[nextMessageIndex]
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                        apiHandler.cancelCurrentApiJob(
                            "正在重新生成，取消旧的AI流",
                            isNewMessageSend = true
                        )
                    }
                    stateHolder.messages.removeAt(nextMessageIndex)
                    Log.d("ViewModelRegen", "为重新生成移除了旧的AI消息。")
                }
            }

            val finalUserMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (finalUserMessageIndex != -1) {
                stateHolder.messages.removeAt(finalUserMessageIndex)
                Log.d("ViewModelRegen", "为重新生成移除了原始用户消息。")
            } else {
                Log.e("ViewModelRegen", "严重错误：为重新生成移除AI消息后，未找到原始用户消息。")
            }
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
        }
    }

    fun triggerScrollToBottom() {
        stateHolder._scrollToBottomEvent.tryEmit(Unit)
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.IO) { persistenceManager.saveLastOpenChat(emptyList()) }
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                stateHolder._loadedHistoryIndex.value = null
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) {
            showSnackbar("无法加载对话：无效的索引"); return
        }
        val conversationToLoad = conversationList[index]

        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(
                    conversationToLoad.map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) }
                )
                stateHolder.messages.forEach { msg ->
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
            withContext(Dispatchers.IO) { persistenceManager.saveLastOpenChat(stateHolder.messages.toList()) }
            if (_isSearchActiveInDrawer.value) {
                withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
            }
        }
    }

    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        if (indexToDelete < 0 || indexToDelete >= stateHolder._historicalConversations.value.size) {
            showSnackbar("无法删除：无效的索引"); return
        }
        viewModelScope.launch {
            historyManager.deleteConversation(indexToDelete)
            if (currentLoadedIndex == indexToDelete) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog(); stateHolder.clearForNewChat(); triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除")
                withContext(Dispatchers.IO) { persistenceManager.saveLastOpenChat(emptyList()) }
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已删除") }
        }
    }

    fun clearAllConversations() {
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat(); triggerScrollToBottom()
            }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
        }
    }

    fun addConfig(config: ApiConfig) = configManager.addConfig(config)
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)

    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messageAnimationStates[messageId] = true
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
        val placeholderTitleMsg =
            conversation?.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg
        val firstUserMsg =
            conversation?.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg
        val firstAiMsg =
            conversation?.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
        if (!firstAiMsg.isNullOrBlank()) return firstAiMsg
        return "对话 ${index + 1}"
    }

    fun onRenameInputTextChange(newName: String) {
        stateHolder._renameInputText.value = newName
    }

    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            val currentPreview = getConversationPreviewText(index)
            val isDefaultPreview = currentPreview.startsWith("对话 ") &&
                    runCatching {
                        currentPreview.substringAfter("对话 ").toIntOrNull() == index + 1
                    }.getOrElse { false }
            stateHolder._renameInputText.value = if (isDefaultPreview) "" else currentPreview
            _showRenameDialogState.value = true
        } else {
            showSnackbar("无法重命名：无效的对话索引")
        }
    }

    fun dismissRenameDialog() {
        _showRenameDialogState.value = false
        _renamingIndexState.value = null
        stateHolder._renameInputText.value = ""
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空"); return
        }
        viewModelScope.launch {
            val currentHistoricalConvos = stateHolder._historicalConversations.value
            if (index < 0 || index >= currentHistoricalConvos.size) {
                withContext(Dispatchers.Main) { showSnackbar("无法重命名：对话索引错误") }; return@launch
            }
            val originalConversationAtIndex = currentHistoricalConvos[index]
            val newMessagesForThisConversation = mutableListOf<Message>()
            var titleMessageUpdatedOrAdded = false

            if (originalConversationAtIndex.isNotEmpty() && originalConversationAtIndex.first().sender == Sender.System && originalConversationAtIndex.first().isPlaceholderName) {
                newMessagesForThisConversation.add(
                    originalConversationAtIndex.first()
                        .copy(text = trimmedNewName, timestamp = System.currentTimeMillis())
                )
                newMessagesForThisConversation.addAll(originalConversationAtIndex.drop(1))
                titleMessageUpdatedOrAdded = true
            }

            if (!titleMessageUpdatedOrAdded) {
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}",
                    text = trimmedNewName,
                    sender = Sender.System,
                    timestamp = System.currentTimeMillis() - 1,
                    contentStarted = true,
                    isPlaceholderName = true
                )
                newMessagesForThisConversation.add(titleMessage)
                newMessagesForThisConversation.addAll(originalConversationAtIndex)
            }

            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply {
                this[index] = newMessagesForThisConversation.toList()
            }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()

            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory()
                if (stateHolder._loadedHistoryIndex.value == index) {
                    persistenceManager.saveLastOpenChat(newMessagesForThisConversation.toList())
                }
            }
            withContext(Dispatchers.Main) {
                showSnackbar("对话已重命名为 '$trimmedNewName'")
                dismissRenameDialog()
            }
        }
    }

    override fun onCleared() {
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared")
        viewModelScope.launch {
            val currentMessages = stateHolder.messages.toList()
            if (currentMessages.isNotEmpty()) {
                val isCurrentChatInHistory =
                    stateHolder._loadedHistoryIndex.value != null || historyManager.findChatInHistory(
                        currentMessages
                    ) != -1
                if (!isCurrentChatInHistory) {
                    historyManager.saveCurrentChatToHistoryIfNeeded()
                }
            } else if (stateHolder._loadedHistoryIndex.value == null) {
                withContext(Dispatchers.IO) { persistenceManager.saveLastOpenChat(emptyList()) }
            }
        }
        super.onCleared()
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel onCleared.")
    }
}