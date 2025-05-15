package com.example.app1.StateControler

import android.util.Log
import androidx.compose.material3.DrawerState // 假设 DrawerState 是 Material 3 的
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.DataClass.ApiConfig
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.data.network.ApiClient
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString() // ViewModel 实例的唯一ID，用于日志区分
    val viewModelInstanceIdForLogging: String get() = instanceId // 公开 ViewModel 实例ID

    // 假设 ViewModelStateHolder 内部的 _historicalConversations 是 MutableStateFlow
    // 例如: val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    private val stateHolder = ViewModelStateHolder() // 状态容器实例
    private val persistenceManager =
        DataPersistenceManager(dataSource, stateHolder, viewModelScope) // 数据持久化管理器

    // 历史记录管理器，传入比较消息列表的函数引用
    private val historyManager: HistoryManager =
        HistoryManager(
            stateHolder,
            persistenceManager,
            ::areMessageListsEffectivelyEqual // 消息列表比较函数的引用
        )

    // API处理器，懒加载
    private val apiHandler: ApiHandler by lazy {
        ApiHandler(stateHolder, viewModelScope, historyManager)
    }

    // 配置管理器，懒加载
    private val configManager: ConfigManager by lazy {
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }

    // --- 公开的StateFlow和SharedFlow，供UI观察 ---
    val drawerState: DrawerState get() = stateHolder.drawerState // 抽屉状态
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow() // 输入框文本
    val messages: MutableList<Message> get() = stateHolder.messages // 当前聊天消息列表 (最旧的在前)
    // 注意: 为使Compose UI正确响应列表的添加、删除和修改操作,
    // ViewModelStateHolder 中的 'messages' 应为 SnapshotStateList (如 mutableStateListOf())。

    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow() // 历史对话列表
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow() // 当前加载的历史对话索引
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow() // API配置列表
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow() // 当前选中的API配置
    val isApiCalling: StateFlow<Boolean> get() = stateHolder._isApiCalling.asStateFlow() // API是否正在调用中
    val currentStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentStreamingAiMessageId.asStateFlow() // 当前正在流式输出的AI消息ID
    val reasoningCompleteMap: Map<String, Boolean> get() = stateHolder.reasoningCompleteMap // 推理过程是否完成的映射表
    val expandedReasoningStates: MutableMap<String, Boolean> get() = stateHolder.expandedReasoningStates // 推理过程是否展开的映射表

    val snackbarMessage: SharedFlow<String> get() = stateHolder._snackbarMessage.asSharedFlow() // Snackbar提示消息
    val scrollToBottomEvent: SharedFlow<Unit> get() = stateHolder._scrollToBottomEvent.asSharedFlow() // 滚动到底部事件

    // 编辑消息相关的状态
    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editDialogInputText: StateFlow<String> get() = stateHolder._editDialogInputText.asStateFlow()

    // 重命名对话相关的状态
    private val _showRenameDialogState = MutableStateFlow(false)
    val showRenameDialogState: StateFlow<Boolean> = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null)
    val renamingIndexState: StateFlow<Int?> = _renamingIndexState.asStateFlow()
    val renameInputText: StateFlow<String> get() = stateHolder._renameInputText.asStateFlow()

    // 抽屉内搜索相关的状态
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
            apiHandler // 触发懒加载
            configManager // 触发懒加载
        }
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            val currentChatMessagesFromPersistence =
                stateHolder.messages.toList()
            val historicalConversationsFromPersistence =
                stateHolder._historicalConversations.value

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
                    val configToSelect =
                        stateHolder._apiConfigs.value.firstOrNull { it.isValid }
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
                Message(
                    text = textToActuallySend,
                    sender = Sender.User,
                    contentStarted = true
                )
            stateHolder.messages.add(newUserMessage)

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
            val messageIndex =
                stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
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
                Log.e(
                    "ViewModelRegen",
                    "严重错误：为重新生成移除AI消息后，未找到原始用户消息。"
                )
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
        val conversation =
            stateHolder._historicalConversations.value.getOrNull(index)

        // 1. 优先使用 Sender.System 且 isPlaceholderName=true 的消息文本 (这是重命名后设置的标题)
        val placeholderTitleMsg =
            conversation?.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg

        // 2. 其次使用第一条用户消息作为预览
        val firstUserMsg =
            conversation?.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg

        // 3. 再次使用第一条AI消息
        val firstAiMsg =
            conversation?.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
        if (!firstAiMsg.isNullOrBlank()) return firstAiMsg

        // 4. 默认预览文本
        return "对话 ${index + 1}"
    }


    fun onRenameInputTextChange(newName: String) {
        stateHolder._renameInputText.value = newName
    }

    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            val currentPreview = getConversationPreviewText(index)
            // 如果当前预览名是默认的"对话 X"格式，则输入框为空以鼓励用户输入新名称，否则显示当前名称
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

    // --- 重命名对话的核心逻辑 ---
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
            // 创建一个新的消息列表来存放修改后的对话，而不是直接修改原始列表的副本
            val newMessagesForThisConversation = mutableListOf<Message>()

            var titleMessageUpdatedOrAdded = false

            // 检查原始对话的第一条消息是否是系统占位符标题
            if (originalConversationAtIndex.isNotEmpty() &&
                originalConversationAtIndex.first().sender == Sender.System &&
                originalConversationAtIndex.first().isPlaceholderName
            ) {
                // 如果是，则更新这条消息
                newMessagesForThisConversation.add(
                    originalConversationAtIndex.first().copy(
                        text = trimmedNewName,
                        timestamp = System.currentTimeMillis() // 更新时间戳
                        // isPlaceholderName 保持 true
                    )
                )
                // 添加原始对话中除了第一条（旧标题）之外的所有其他消息
                newMessagesForThisConversation.addAll(originalConversationAtIndex.drop(1))
                titleMessageUpdatedOrAdded = true
            }

            if (!titleMessageUpdatedOrAdded) {
                // 如果原始对话没有系统占位符标题，或者第一条不是，则在最前面插入一条新的标题消息
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}", // 为标题消息生成唯一ID
                    text = trimmedNewName,
                    sender = Sender.System,
                    timestamp = System.currentTimeMillis() - 1, // 时间戳略早，以确保如果需要排序时它在前面
                    contentStarted = true, // 标记内容有效
                    isPlaceholderName = true // 明确标记为占位符名称（自定义标题）
                )
                newMessagesForThisConversation.add(titleMessage)
                // 添加所有原始对话的消息
                newMessagesForThisConversation.addAll(originalConversationAtIndex)
            }

            // 创建包含已修改对话的新的历史对话全列表
            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply {
                this[index] = newMessagesForThisConversation.toList() // 用新的对话消息列表替换指定索引处的旧列表
            }

            // 【关键】通过给 StateFlow 的 value 赋一个新的列表实例来触发UI更新
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()

            // 持久化更改
            withContext(Dispatchers.IO) {
                // 确保 persistenceManager.saveChatHistory() 保存的是 stateHolder._historicalConversations.value 的最新状态
                persistenceManager.saveChatHistory()
                if (stateHolder._loadedHistoryIndex.value == index) { // 如果重命名的是当前加载的对话
                    persistenceManager.saveLastOpenChat(newMessagesForThisConversation.toList()) // 更新“最后打开的聊天”
                }
            }
            withContext(Dispatchers.Main) {
                showSnackbar("对话已重命名为 '$trimmedNewName'")
                dismissRenameDialog() // 关闭重命名对话框
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