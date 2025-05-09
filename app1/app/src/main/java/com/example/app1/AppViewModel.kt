package com.example.app1 // 确保包名正确

import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.ui.screens.ApiHandler
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    private val historyManager: HistoryManager =
        HistoryManager(stateHolder, persistenceManager, viewModelScope)

    private val apiHandler: ApiHandler = ApiHandler(
        stateHolder,
        viewModelScope,
        historyManager
    )
    private val configManager: ConfigManager =
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)

    val drawerState: DrawerState = stateHolder.drawerState
    val text: StateFlow<String> = stateHolder._text.asStateFlow()
    val messages = stateHolder.messages
    val historicalConversations: StateFlow<List<List<Message>>> =
        stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> =
        stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> =
        stateHolder._selectedApiConfig.asStateFlow()
    val showSettingsDialog: StateFlow<Boolean> =
        stateHolder._showSettingsDialog.asStateFlow()
    val isApiCalling: StateFlow<Boolean> = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> =
        stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap = stateHolder.reasoningCompleteMap
    val expandedReasoningStates = stateHolder.expandedReasoningStates
    val messageAnimationStates = stateHolder.messageAnimationStates
    val snackbarMessage: SharedFlow<String> =
        stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit> =
        stateHolder._scrollToBottomEvent.asSharedFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    private val _editDialogInputText = MutableStateFlow("")
    val editDialogInputText: StateFlow<String> = _editDialogInputText.asStateFlow()
    val userScrolledAway: StateFlow<Boolean> =
        stateHolder._userScrolledAway.asStateFlow()

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

    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) {
            stateHolder._userScrolledAway.value = scrolledAway
        }
    }

    fun onSendMessage(messageText: String = stateHolder._text.value, isFromRegeneration: Boolean = false) {
        val textToActuallySend = if (isFromRegeneration) messageText.trim() else stateHolder._text.value.trim()

        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) {
                viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请输入消息内容") }
            } else {
                Log.w("AppViewModel", "Regenerated message text was empty, not sending.")
            }
            return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择 API 配置") }
            return
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val newUserMessage = Message(
                id = UUID.randomUUID().toString(),
                text = textToActuallySend,
                sender = Sender.User,
                timestamp = System.currentTimeMillis(),
                contentStarted = true
            )
            stateHolder.messages.add(0, newUserMessage)
            Log.d("AppViewModel", "User message (ID: ${newUserMessage.id}, FromRegen: $isFromRegeneration) added at index 0.")

            if (!isFromRegeneration) {
                stateHolder._text.value = ""
            }

            stateHolder._userScrolledAway.value = false
            triggerScrollToBottom()

            apiHandler.streamChatResponse(
                userMessageTextForContext = textToActuallySend,
                onMessagesProcessed = {
                    if (!stateHolder._userScrolledAway.value) {
                        triggerScrollToBottom()
                    }
                }
            )
        }
    }

    fun onEditDialogTextChanged(newText: String) {
        _editDialogInputText.value = newText
    }

    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) {
            _editingMessageId.value = message.id
            _editDialogInputText.value = message.text
            _showEditDialog.value = true
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("只能编辑您发送的消息") }
        }
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = _editDialogInputText.value.trim()
        if (updatedText.isBlank()) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("消息内容不能为空") }
            return
        }
        val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
        if (messageIndex != -1) {
            val originalMessage = stateHolder.messages[messageIndex]
            if (originalMessage.text != updatedText) {
                stateHolder.messages[messageIndex] = originalMessage.copy(
                    text = updatedText,
                    timestamp = System.currentTimeMillis()
                )
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            }
        }
        dismissEditDialog()
    }

    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        _editDialogInputText.value = ""
    }

    fun regenerateAiResponse(originalUserMessage: Message) {
        if (originalUserMessage.sender != Sender.User) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("只能为您的消息重新生成回答") }
            return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择 API 配置") }
            return
        }

        val originalUserMessageText = originalUserMessage.text
        Log.d(
            "AppViewModel",
            "Regenerate for user msg: '${originalUserMessageText.take(30)}' (ID: ${originalUserMessage.id})"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageInitialIndex = stateHolder.messages.indexOfFirst { it.id == originalUserMessage.id }

            if (userMessageInitialIndex == -1) {
                Log.e("AppViewModel", "Regenerate failed: Original user message (ID: ${originalUserMessage.id}) not found.")
                stateHolder._snackbarMessage.tryEmit("无法重新生成：原始消息未找到。")
                return@launch
            }
            Log.d("AppViewModel", "Original user message (ID: ${originalUserMessage.id}) found at index: $userMessageInitialIndex.")

            var messagesRemovedCount = 0

            // Step 1: Remove the original user message itself.
            // It's important to do this first to correctly identify the starting point for AI message removal.
            stateHolder.messages.removeAt(userMessageInitialIndex)
            messagesRemovedCount++
            Log.d("AppViewModel", "Removed original user message (ID: ${originalUserMessage.id}) at original index $userMessageInitialIndex.")

            // Step 2: Remove AI messages that were logically "after" (newer than, i.e., smaller index) the original user message.
            // After the user message (originally at `userMessageInitialIndex`) is removed,
            // an AI message that was at `userMessageInitialIndex - 1` is now still at `userMessageInitialIndex - 1`
            // (if `userMessageInitialIndex` was > 0, relative to the new list).
            // We iterate from what *would have been* the position immediately "after" the user message,
            // which is `userMessageInitialIndex - 1` (if the user message was not at index 0).
            // We iterate downwards (towards index 0), removing only AI messages.

            var indexToCheckForAi = userMessageInitialIndex - 1 // Start checking from the index logically "after" (newer) the user message's original position.

            while (indexToCheckForAi >= 0) {
                // Check if the index is still valid for the current (potentially shrunk) list
                if (indexToCheckForAi < stateHolder.messages.size) {
                    val messageAtCurrentIndex = stateHolder.messages[indexToCheckForAi]
                    if (messageAtCurrentIndex.sender == Sender.AI) {
                        Log.d("AppViewModel", "Removing subsequent AI message (ID: ${messageAtCurrentIndex.id}) at current data index $indexToCheckForAi.")
                        stateHolder.messages.removeAt(indexToCheckForAi)
                        messagesRemovedCount++
                        // After removing an AI message at `indexToCheckForAi`, the item that was
                        // originally at `indexToCheckForAi - 1` is now at `indexToCheckForAi - 1`
                        // (relative to the new list structure). So, we continue by decrementing indexToCheckForAi.
                        indexToCheckForAi--
                    } else {
                        // Encountered a non-AI message (e.g., an older user message), stop.
                        // This means we've cleared all contiguous AI messages after the original user message.
                        Log.d("AppViewModel", "Stopping AI removal at data index $indexToCheckForAi, encountered ${messageAtCurrentIndex.sender}.")
                        break
                    }
                } else {
                    // Index is no longer valid (e.g., we've removed all items up to index 0 or list became empty)
                    Log.d("AppViewModel", "Stopping AI removal as index $indexToCheckForAi is out of bounds for current list size ${stateHolder.messages.size}.")
                    break
                }
            }
            // --- END OF CORRECTED DELETION LOGIC ---

            Log.d("AppViewModel", "Total $messagesRemovedCount messages removed (original user + subsequent AIs).")

            if (messagesRemovedCount > 0) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            }

            Log.d("AppViewModel", "Sending new message with original text: '${originalUserMessageText.take(30)}'")
            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
        }
    }

    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(Unit)
            Log.d("AppViewModel", "Triggered scroll to bottom event (index 0).")
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        Log.d("AppViewModel", "Starting new chat...")
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        stateHolder.clearForNewChat()
        triggerScrollToBottom()
    }

    fun loadConversationFromHistory(index: Int) {
        Log.d("AppViewModel", "Loading conversation from history, index: $index")
        dismissEditDialog()
        stateHolder._historicalConversations.value.getOrNull(index)?.let { conversationToLoad ->
            apiHandler.cancelCurrentApiJob("加载历史索引 $index")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(
                    conversationToLoad.asReversed().map { msg ->
                        msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    })
                stateHolder.messages.forEach { msg ->
                    if ((msg.sender == Sender.AI || msg.sender == Sender.User) &&
                        (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    ) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
                Log.d("AppViewModel", "Conversation $index loaded. Message count: ${stateHolder.messages.size}")
            }
        } ?: viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("无法加载对话") }
    }

    fun deleteConversation(indexToDelete: Int) {
        Log.d("AppViewModel", "Deleting conversation at index: $indexToDelete")
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        historyManager.deleteConversation(indexToDelete)
        if (currentLoadedIndex == indexToDelete) {
            dismissEditDialog()
            stateHolder._loadedHistoryIndex.value = null
            stateHolder.clearForNewChat()
        }
    }

    fun clearAllConversations() {
        Log.d("AppViewModel", "Clearing all conversations...")
        viewModelScope.launch {
            dismissEditDialog()
            historyManager.clearAllHistory()
            if (stateHolder._loadedHistoryIndex.value != null) {
                stateHolder.clearForNewChat()
                stateHolder._loadedHistoryIndex.value = null
            }
            Log.d("AppViewModel", "All conversations cleared.")
        }
    }

    fun showSettingsScreen() { stateHolder._showSettingsDialog.value = true }
    fun dismissSettingsScreenIntent() { stateHolder._showSettingsDialog.value = false }
    fun addConfig(configToAdd: ApiConfig) { configManager.addConfig(configToAdd) }
    fun updateConfig(configToUpdate: ApiConfig) { configManager.updateConfig(configToUpdate) }
    fun deleteConfig(configToDelete: ApiConfig) { configManager.deleteConfig(configToDelete) }
    fun clearAllConfigs() { configManager.clearAllConfigs() }
    fun selectConfig(config: ApiConfig) { configManager.selectConfig(config) }

    fun collapseReasoning(messageId: String) { if (stateHolder.expandedReasoningStates.containsKey(messageId)) { stateHolder.expandedReasoningStates[messageId] = false } }
    fun onToggleReasoningExpand(messageId: String) { stateHolder.expandedReasoningStates[messageId] = !(stateHolder.expandedReasoningStates[messageId] ?: false) }
    fun onAnimationComplete(messageId: String) { viewModelScope.launch(Dispatchers.Main.immediate) { if (stateHolder.messageAnimationStates[messageId] != true) { stateHolder.messageAnimationStates[messageId] = true } } }
    fun hasAnimationBeenPlayed(messageId: String): Boolean = stateHolder.messageAnimationStates[messageId] ?: false

    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
        val firstUserMsg = conversation?.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        val firstAiMsg = conversation?.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
        val firstMsg = conversation?.firstOrNull { it.text.isNotBlank() }?.text?.trim()
        return when { !firstUserMsg.isNullOrBlank() -> firstUserMsg; !firstAiMsg.isNullOrBlank() -> firstAiMsg; !firstMsg.isNullOrBlank() -> firstMsg; else -> "对话 ${index + 1}" }
    }
    fun onRenameInputTextChange(newName: String) { _renameInputText.value = newName }
    fun showRenameDialog(index: Int) { if (index >= 0 && index < stateHolder._historicalConversations.value.size) { _renamingIndexState.value = index; _renameInputText.value = getConversationPreviewText(index).takeIf { it != "对话 ${index + 1}" } ?: ""; _showRenameDialogState.value = true; } else { viewModelScope.launch { stateHolder._snackbarMessage.emit("无法重命名：无效的对话索引") } } }
    fun dismissRenameDialog() { _showRenameDialogState.value = false; _renamingIndexState.value = null; _renameInputText.value = "" }
    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) { viewModelScope.launch { stateHolder._snackbarMessage.emit("新名称不能为空") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value.toMutableList()
            if (index >= 0 && index < currentHistory.size) {
                var conversationToModify = currentHistory[index].toMutableList()
                val firstMessageIsUser = conversationToModify.firstOrNull()?.sender == Sender.User
                if (conversationToModify.isNotEmpty() && firstMessageIsUser) {
                    val originalFirstMessage = conversationToModify[0]
                    if (originalFirstMessage.text != trimmedNewName) { conversationToModify[0] = originalFirstMessage.copy(text = trimmedNewName, timestamp = System.currentTimeMillis()) }
                } else {
                    if (conversationToModify.isNotEmpty()) {
                        val originalFirstMessage = conversationToModify[0]
                        if (originalFirstMessage.text != trimmedNewName || originalFirstMessage.sender != Sender.User) { conversationToModify[0] = originalFirstMessage.copy(text = trimmedNewName, sender = Sender.User, timestamp = System.currentTimeMillis()) }
                    } else {
                        conversationToModify.add(Message(id = UUID.randomUUID().toString(), text = trimmedNewName, sender = Sender.User, timestamp = System.currentTimeMillis()))
                    }
                }
                currentHistory[index] = conversationToModify.toList()
                withContext(Dispatchers.Main.immediate) { stateHolder._historicalConversations.value = currentHistory.toList(); stateHolder._snackbarMessage.tryEmit("对话已重命名为 '$trimmedNewName'") }
                persistenceManager.saveChatHistory()
            } else { withContext(Dispatchers.Main) { stateHolder._snackbarMessage.tryEmit("无法重命名：对话索引错误") } }
            withContext(Dispatchers.Main) { dismissRenameDialog() }
        }
    }

    override fun onCleared() {
        Log.d("AppViewModel", "ViewModel onCleared")
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        super.onCleared()
    }
}