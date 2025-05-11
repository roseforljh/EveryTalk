package com.example.app1.StateControler // 你的包名

import android.util.Log
import androidx.compose.material3.DrawerState // 确保导入
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.viewmodel.ConfigManager // 确保这些辅助类存在且路径正确
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString()
    val viewModelInstanceIdForLogging: String get() = instanceId

    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)
    private val historyManager: HistoryManager =
        HistoryManager(
            stateHolder,
            persistenceManager,
            viewModelScope,
            ::areMessageListsEffectivelyEqual
        ) // 传递比较函数
    private val apiHandler: ApiHandler by lazy {
        Log.d("AppViewModelLazyInit", "[ID:$instanceId] 正在创建 ApiHandler 实例...")
        ApiHandler(stateHolder, viewModelScope, historyManager)
    }
    private val configManager: ConfigManager by lazy {
        Log.d("AppViewModelLazyInit", "[ID:$instanceId] 正在创建 ConfigManager 实例...")
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }

    // --- UI 相关的 StateFlow 和 SharedFlow ---
    val drawerState: DrawerState = stateHolder.drawerState
    val text: StateFlow<String> = stateHolder._text.asStateFlow()
    val messages = stateHolder.messages // 这是 MutableList
    val historicalConversations: StateFlow<List<List<Message>>> =
        stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> =
        stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> =
        stateHolder._selectedApiConfig.asStateFlow()
    val isApiCalling: StateFlow<Boolean> = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> =
        stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap = stateHolder.reasoningCompleteMap
    val expandedReasoningStates = stateHolder.expandedReasoningStates
    val snackbarMessage: SharedFlow<String> =
        stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit> =
        stateHolder._scrollToBottomEvent.asSharedFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    private val _editDialogInputText = MutableStateFlow("")
    val editDialogInputText: StateFlow<String> = _editDialogInputText.asStateFlow()

    private val _showRenameDialogState = MutableStateFlow(false)
    val showRenameDialogState = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null)
    val renamingIndexState = _renamingIndexState.asStateFlow()
    private val _renameInputText = MutableStateFlow("")
    val renameInputText: StateFlow<String> = _renameInputText.asStateFlow()

    // --- 抽屉搜索相关状态 ---
    private val _isSearchActiveInDrawer = MutableStateFlow(false)
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()

    private val _searchQueryInDrawer = MutableStateFlow("")
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()
    // --- 抽屉搜索相关状态结束 ---

    // 辅助函数：比较两个消息列表是否“实质上”相等
    private fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean {
        if (list1 == null || list2 == null) return list1 == list2 // 两者都为null则相等
        if (list1.size != list2.size) return false

        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)

        if (filteredList1.size != filteredList2.size) return false

        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]
            if (msg1.id != msg2.id ||
                msg1.sender != msg2.sender ||
                msg1.text != msg2.text ||
                msg1.reasoning != msg2.reasoning
            ) {
                return false
            }
        }
        return true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            msg.sender != Sender.System &&
                    !msg.isError &&
                    (msg.sender == Sender.User || msg.contentStarted || !msg.reasoning.isNullOrBlank() || msg.text.isNotBlank())
        }.toList()
    }


    init {
        Log.d(
            "AppViewModel",
            "[ID:$instanceId] ViewModel 初始化开始, 线程: ${Thread.currentThread().name}"
        )
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("AppViewModelInit", "[ID:$instanceId] 开始预热任务 (IO线程)...")
            try {
                ApiClient.preWarm()
                apiHandler
                configManager
                Log.d("AppViewModelInit", "[ID:$instanceId] 所有主要组件预热完成。")
            } catch (e: Exception) {
                Log.e("AppViewModelInit", "[ID:$instanceId] 预热任务中发生错误", e)
            }
            Log.d("AppViewModelInit", "[ID:$instanceId] 预热任务 (IO线程) 结束。")
        }

        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            Log.d(
                "AppViewModelInit",
                "[ID:$instanceId] 初始数据加载回调: initialConfigPresent=$initialConfigPresent, historyPresent=$historyPresent"
            )
            val currentChatMessagesFromPersistence = stateHolder.messages.toList()
            val historicalConversationsFromPersistence = stateHolder._historicalConversations.value

            if (currentChatMessagesFromPersistence.isNotEmpty() && historyPresent) {
                val matchedIndex =
                    historicalConversationsFromPersistence.indexOfFirst { historicalChat ->
                        areMessageListsEffectivelyEqual(
                            filterMessagesForComparison(currentChatMessagesFromPersistence),
                            filterMessagesForComparison(historicalChat)
                        )
                    }
                if (matchedIndex != -1) {
                    stateHolder._loadedHistoryIndex.value = matchedIndex
                    Log.d(
                        "AppViewModelInit",
                        "[ID:$instanceId] 上次打开的聊天匹配到历史记录索引: $matchedIndex"
                    )
                } else {
                    stateHolder._loadedHistoryIndex.value = null
                    Log.d(
                        "AppViewModelInit",
                        "[ID:$instanceId] 上次打开的聊天未在历史记录中找到或内容不同，视为新聊天或独立聊天。"
                    )
                }
            } else {
                stateHolder._loadedHistoryIndex.value = null
                Log.d(
                    "AppViewModelInit",
                    "[ID:$instanceId] 上次聊天为空或无历史记录，loadedHistoryIndex 设置为 null。"
                )
            }

            viewModelScope.launch(Dispatchers.Main) {
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    stateHolder._snackbarMessage.tryEmit("请添加 API 配置")
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    val configToSelect = stateHolder._apiConfigs.value.firstOrNull()
                    configToSelect?.let { selectConfig(it) }
                        ?: if (stateHolder._apiConfigs.value.isEmpty() && initialConfigPresent) {
                            Log.w("AppViewModelInit", "API配置列表为空，但声称初始配置存在。")
                        } else if (!initialConfigPresent) {
                            // Snackbar 已处理
                        } else {
                            stateHolder._snackbarMessage.tryEmit("请选择一个 API 配置")
                        }
                }
            }
        }
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化逻辑结束。")
    }

    fun setSearchActiveInDrawer(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) {
            _searchQueryInDrawer.value = ""
        }
        Log.d(
            "AppViewModel",
            "[ID:$instanceId] 抽屉搜索激活状态设置为: $isActive, 查询: '${_searchQueryInDrawer.value}'"
        )
    }

    fun onDrawerSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
        Log.d("AppViewModel", "[ID:$instanceId] 抽屉搜索查询更改为: $query")
    }

    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) {
            stateHolder._userScrolledAway.value = scrolledAway
            Log.d("AppViewModel", "[ID:$instanceId] 用户滚动状态已更改为: $scrolledAway")
        }
    }

    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        val textToActuallySend = messageText.trim()
        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) {
                viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请输入消息内容") }
            } else {
                Log.w("AppViewModel", "[ID:$instanceId] 重新生成的消息文本为空，不发送。")
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
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 用户消息 (ID: ${newUserMessage.id.take(8)}, Text: '${
                    textToActuallySend.take(30)
                }', FromRegen: $isFromRegeneration) 已添加到索引 0。"
            )
            if (!isFromRegeneration && stateHolder._text.value.isNotEmpty()) {
                Log.d(
                    "AppViewModel",
                    "[ID:$instanceId] 清空 ViewModel 中的 _text。之前的值: '${stateHolder._text.value}'"
                )
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
                    historyManager.saveCurrentChatToHistoryIfNeeded()
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

    // --- 重新生成AI回复 (已修改) ---
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
            "AppViewModelRegen",
            "[ID:$instanceId] 开始为用户消息 (ID: ${originalUserMessage.id.take(8)}) 重新生成回复. 文本: '${originalUserMessageText.take(30)}'"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndexInList = stateHolder.messages.indexOfFirst { it.id == originalUserMessage.id }

            if (userMessageIndexInList == -1) {
                Log.e("AppViewModelRegen", "[ID:$instanceId] 重新生成失败: 原始用户消息 (ID: ${originalUserMessage.id}) 在当前聊天中未找到。")
                stateHolder._snackbarMessage.tryEmit("无法重新生成：原始消息未找到。")
                return@launch
            }

            Log.d("AppViewModelRegen", "[ID:$instanceId] 原始用户消息位于索引: $userMessageIndexInList. 当前消息数量: ${stateHolder.messages.size}")

            // 1. 检查并删除紧随其后的 AI 回复（如果存在）
            // 由于 messages 是倒序的，紧随用户消息之后的 AI 消息的索引应该是 userMessageIndex - 1
            val potentialAiMessageIndex = userMessageIndexInList - 1
            if (potentialAiMessageIndex >= 0 && potentialAiMessageIndex < stateHolder.messages.size) {
                val messageAfterUser = stateHolder.messages[potentialAiMessageIndex]
                if (messageAfterUser.sender == Sender.AI) {
                    val aiMessageIdToRemove = messageAfterUser.id
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageIdToRemove) {
                        apiHandler.cancelCurrentApiJob("为用户消息 ${originalUserMessage.id.take(8)} 重新生成而取消AI消息 ${aiMessageIdToRemove.take(8)}", isNewMessageSend = true)
                    }
                    Log.d("AppViewModelRegen", "[ID:$instanceId] 正在移除与用户消息关联的AI消息 (ID: ${aiMessageIdToRemove.take(8)}) 于索引 $potentialAiMessageIndex.")
                    stateHolder.messages.removeAt(potentialAiMessageIndex)
                    // 注意：由于我们先删除了AI消息，如果它存在，原始用户消息的索引现在会是 userMessageIndexInList - 1
                    // 但如果AI消息不存在，原始用户消息的索引不变。
                    // 为了简单，我们下面直接使用 removeAt(userMessageIndexInList) （如果AI没删）或 removeAt(userMessageIndexInList -1) （如果AI删了）
                    // 或者更稳妥的是在删除AI消息后重新查找用户消息的索引。
                } else {
                    Log.d("AppViewModelRegen", "[ID:$instanceId] 用户消息之后的消息 (索引 $potentialAiMessageIndex) 不是AI消息，不移除。")
                }
            } else {
                Log.d("AppViewModelRegen", "[ID:$instanceId] 用户消息之后没有其他消息，或索引无效。")
            }

            // 2. 删除原始的用户消息 (在可能移除了AI消息后，重新定位用户消息索引)
            val currentUserMessageActualIndex = stateHolder.messages.indexOfFirst { it.id == originalUserMessage.id }
            if (currentUserMessageActualIndex != -1) {
                Log.d("AppViewModelRegen", "[ID:$instanceId] 正在移除原始用户消息 (ID: ${originalUserMessage.id.take(8)}) 于当前索引 $currentUserMessageActualIndex.")
                stateHolder.messages.removeAt(currentUserMessageActualIndex)
            } else {
                Log.e("AppViewModelRegen", "[ID:$instanceId] 严重错误：在尝试删除原始用户消息时未找到它！可能已被错误移除。")
                // 即使找不到，也尝试继续发送原始文本
            }

            Log.d("AppViewModelRegen", "[ID:$instanceId] 移除用户和AI消息后，消息数量: ${stateHolder.messages.size}")

            // 3. 保存当前聊天状态到历史记录 (因为我们修改了列表)
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)

            // 4. 使用原始用户消息的文本重新发送消息
            Log.d("AppViewModelRegen", "[ID:$instanceId] 使用原始文本 '${originalUserMessageText.take(30)}' 重新发送消息进行生成。")
            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
        }
    }


    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(Unit)
            Log.d("AppViewModel", "[ID:$instanceId] 已触发滚动到底部事件 (索引 0)。")
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        Log.d("AppViewModel", "[ID:$instanceId] 开始新聊天...")
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        historyManager.saveCurrentChatToHistoryIfNeeded()
        stateHolder.clearForNewChat()
        triggerScrollToBottom()
        if (_isSearchActiveInDrawer.value) {
            setSearchActiveInDrawer(false)
        }
    }

    fun loadConversationFromHistory(index: Int) {
        Log.d("AppViewModel", "[ID:$instanceId] 从历史记录加载对话，索引: $index")
        dismissEditDialog()
        stateHolder._historicalConversations.value.getOrNull(index)?.let { conversationToLoad ->
            apiHandler.cancelCurrentApiJob("加载历史索引 $index")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(
                    conversationToLoad.map { msg ->
                        msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    }
                )
                stateHolder.messages.forEach { msg ->
                    if ((msg.sender == Sender.AI || msg.sender == Sender.User) &&
                        (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    ) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
                Log.d(
                    "AppViewModel",
                    "[ID:$instanceId] 对话 $index 已加载。消息数量: ${stateHolder.messages.size}"
                )
                persistenceManager.saveLastOpenChat(stateHolder.messages.toList())
            }
            if (_isSearchActiveInDrawer.value) {
                setSearchActiveInDrawer(false)
            }
        } ?: viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("无法加载对话") }
    }

    fun deleteConversation(indexToDelete: Int) {
        Log.d("AppViewModel", "[ID:$instanceId] 删除历史对话，索引: $indexToDelete")
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        historyManager.deleteConversation(indexToDelete)
        if (currentLoadedIndex == indexToDelete) {
            dismissEditDialog()
            stateHolder.clearForNewChat()
            triggerScrollToBottom()
            persistenceManager.saveLastOpenChat(emptyList())
        }
    }

    fun clearAllConversations() {
        Log.d("AppViewModel", "[ID:$instanceId] 清除所有历史对话...")
        viewModelScope.launch {
            dismissEditDialog()
            apiHandler.cancelCurrentApiJob("清除所有历史记录")
            historyManager.saveCurrentChatToHistoryIfNeeded()
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
            }
            persistenceManager.saveLastOpenChat(emptyList())
            Log.d("AppViewModel", "[ID:$instanceId] 所有历史对话已清除。")
        }
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
        val firstUserMessage = conversation?.firstOrNull { it.sender == Sender.User }?.text?.trim()
        val firstMessageText = conversation?.firstOrNull()?.text?.trim()
        return when {
            !firstUserMessage.isNullOrBlank() -> firstUserMessage
            !firstMessageText.isNullOrBlank() -> firstMessageText
            else -> "对话 ${index + 1}"
        }
    }

    fun onRenameInputTextChange(newName: String) {
        _renameInputText.value = newName
    }

    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            val currentPreview = getConversationPreviewText(index)
            _renameInputText.value =
                if (currentPreview.startsWith("对话 ") && currentPreview.endsWith("${index + 1}")) ""
                else currentPreview
            _showRenameDialogState.value = true
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("无法重命名：无效的对话索引") }
        }
    }

    fun dismissRenameDialog() {
        _showRenameDialogState.value = false
        _renamingIndexState.value = null
        _renameInputText.value = ""
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("新名称不能为空") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value.toMutableList()
            if (index >= 0 && index < currentHistory.size) {
                var conversationToModify = currentHistory[index].toMutableList()
                if (conversationToModify.isEmpty()) {
                    conversationToModify.add(
                        Message(
                            UUID.randomUUID().toString(),
                            trimmedNewName,
                            Sender.User,
                            System.currentTimeMillis().toString(),
                            true
                        )
                    )
                } else {
                    val originalFirstMessage = conversationToModify[0]
                    conversationToModify[0] = originalFirstMessage.copy(
                        text = trimmedNewName,
                        sender = Sender.User,
                        timestamp = System.currentTimeMillis()
                    )
                }
                currentHistory[index] = conversationToModify.toList()
                withContext(Dispatchers.Main.immediate) {
                    stateHolder._historicalConversations.value = currentHistory.toList()
                    stateHolder._snackbarMessage.tryEmit("对话已重命名为 '$trimmedNewName'")
                }
                persistenceManager.saveChatHistory()
                if (stateHolder._loadedHistoryIndex.value == index) {
                    persistenceManager.saveLastOpenChat(conversationToModify.toList())
                }
            } else {
                withContext(Dispatchers.Main) {
                    stateHolder._snackbarMessage.tryEmit("无法重命名：对话索引错误")
                }
            }
            withContext(Dispatchers.Main) {
                dismissRenameDialog()
            }
        }
    }

    override fun onCleared() {
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel onCleared 已调用。")
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared")
        val historyModified = historyManager.saveCurrentChatToHistoryIfNeeded()
        // ... (日志和可能的 loadedHistoryIndex 更新逻辑可以保留或调整) ...
        if (stateHolder.messages.isNotEmpty()) {
            persistenceManager.saveLastOpenChat(stateHolder.messages.toList())
        }
        super.onCleared()
    }
}