package com.example.everytalk.StateControler // 你的包名

import android.util.Log
import androidx.compose.material3.DrawerState // 确保 DrawerState 导入
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.ApiMessage as DataClassApiMessage // 重命名导入
import com.example.everytalk.data.DataClass.WebSearchResult // 确保这个路径正确

import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.viewmodel.ConfigManager
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString() // 用于调试ViewModel实例

    // --- 依赖注入和管理器初始化 ---
    internal val stateHolder = ViewModelStateHolder()
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

    // --- UI State Flows 和可观察属性 ---
    val drawerState: DrawerState get() = stateHolder.drawerState
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow()
    val messages: SnapshotStateList<Message> get() = stateHolder.messages

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

    // 编辑消息对话框状态
    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editDialogInputText: StateFlow<String> get() = stateHolder._editDialogInputText.asStateFlow()

    // 重命名历史对话框状态
    private val _showRenameDialogState = MutableStateFlow(false)
    val showRenameDialogState: StateFlow<Boolean> = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null)
    val renamingIndexState: StateFlow<Int?> = _renamingIndexState.asStateFlow()
    val renameInputText: StateFlow<String> get() = stateHolder._renameInputText.asStateFlow()

    // 抽屉内搜索状态
    private val _isSearchActiveInDrawer = MutableStateFlow(false)
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()
    private val _searchQueryInDrawer = MutableStateFlow("")
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()

    // 模型提供商列表
    private val _customProviders = MutableStateFlow<Set<String>>(emptySet())
    val allProviders: StateFlow<List<String>> = combine(
        _customProviders
    ) { customsParam ->
        val customs = customsParam[0]
        val predefinedPlatforms =
            listOf("openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索")
        val combinedList = (predefinedPlatforms + customs.toList()).distinct()
        val predefinedOrderMap = predefinedPlatforms.withIndex().associate { it.value to it.index }
        combinedList.sortedWith(compareBy<String> { platform ->
            predefinedOrderMap[platform] ?: (predefinedPlatforms.size + customs.indexOf(platform)
                .let { if (it == -1) Int.MAX_VALUE else it })
        }.thenBy { it })
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        listOf("openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索")
    )

    // 联网搜索状态
    val isWebSearchEnabled: StateFlow<Boolean> get() = stateHolder._isWebSearchEnabled.asStateFlow()

    val showSourcesDialog: StateFlow<Boolean> get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>> get() = stateHolder._sourcesForDialog.asStateFlow()


    // --- 初始化块 ---
    init {
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化开始")
        viewModelScope.launch(Dispatchers.IO) {
            ApiClient.preWarm()
            apiHandler
            configManager
        }
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            viewModelScope.launch(Dispatchers.IO) {
                val loadedCustomProviders = dataSource.loadCustomProviders()
                _customProviders.value = loadedCustomProviders
            }

            val currentChatMessagesFromPersistence = stateHolder.messages.toList()
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
                if (matchedIndex != -1) {
                    Log.d(
                        "AppViewModelInit",
                        "警告：LastOpenChat 非空并匹配了历史索引 $matchedIndex。这不符合“总是新会话”的预期。"
                    )
                } else {
                    Log.d(
                        "AppViewModelInit",
                        "警告：LastOpenChat 非空但未匹配任何历史。这不符合“总是新会话”的预期。"
                    )
                }
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
                    (msg.sender == Sender.User || (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) || (msg.sender == Sender.System && msg.isPlaceholderName) || msg.isError)
        }.toList()
    }

    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        showSnackbar("联网搜索已 ${if (enabled) "开启" else "关闭"}")
    }

    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentCustomProviders = _customProviders.value.toMutableSet()
                val predefinedForCheck = listOf(
                    "openai compatible",
                    "google",
                    "硅基流动",
                    "阿里云百炼",
                    "火山引擎",
                    "深度求索"
                ).map { it.lowercase() }
                if (predefinedForCheck.contains(trimmedName.lowercase())) {
                    withContext(Dispatchers.Main) { showSnackbar("平台名称 '$trimmedName' 是预设名称，无法添加。") }
                    return@launch
                }
                if (currentCustomProviders.any { it.equals(trimmedName, ignoreCase = true) }) {
                    withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已存在") }
                    return@launch
                }
                currentCustomProviders.add(trimmedName)
                _customProviders.value = currentCustomProviders.toSet()
                dataSource.saveCustomProviders(currentCustomProviders.toSet())
                withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已添加") }
            }
        } else {
            showSnackbar("平台名称不能为空")
        }
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
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            showSnackbar("请先选择 API 配置")
            return
        }
        val currentWebSearchEnabled = stateHolder._isWebSearchEnabled.value

        viewModelScope.launch(Dispatchers.Main.immediate) {
            // 如果不是来自重新生成，或者即使是重新生成（因为旧的已被删除），都添加新的用户消息
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)
            Log.d(
                "AppViewModel",
                "onSendMessage: 添加了新的用户消息 ID: ${newUserMessage.id}, 内容: '${
                    textToActuallySend.take(20)
                }...'"
            )


            if (!isFromRegeneration) { // 只有非重新生成时才清空输入框
                stateHolder._text.value = ""
            }
            triggerScrollToBottom() // 确保新消息可见

            // 构建发送给API的历史消息列表
            val apiHistoryMessages = mutableListOf<DataClassApiMessage>()
            // 使用当前最新的消息列表快照来构建历史
            // 注意：此时 messagesSnapshotForHistory 已包含 newUserMessage
            val messagesSnapshotForHistory = stateHolder.messages.toList()
            var historyMessageCount = 0
            val maxHistoryMessages = 20 // 最大历史消息数量

            // 从最新消息（包含刚添加的newUserMessage）开始逆序遍历，构建API请求历史
            for (msg in messagesSnapshotForHistory.asReversed()) {
                if (historyMessageCount >= maxHistoryMessages) break

                val apiMsgToAdd: DataClassApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        DataClassApiMessage(role = "user", content = msg.text.trim())
                    // AI消息：需要有内容（文本或思考过程），且非错误，才加入历史
                    msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) && !msg.isError ->
                        DataClassApiMessage(role = "assistant", content = msg.text.trim())

                    else -> null
                }
                apiMsgToAdd?.let {
                    apiHistoryMessages.add(0, it) // 添加到列表头部，保持顺序
                    historyMessageCount++
                }
            }

            // 防御性检查：确保apiHistoryMessages的最后一条是我们刚发送的用户消息
            // （虽然上面的逻辑应该能保证，但多一层检查无妨）
            if (apiHistoryMessages.isEmpty() || apiHistoryMessages.last().role != "user" || apiHistoryMessages.last().content != textToActuallySend) {
                Log.w(
                    "AppViewModel",
                    "onSendMessage: apiHistoryMessages 构建异常，末尾不是预期的用户消息。将强制修正。"
                )
                // 如果末尾不是，则移除，并添加正确的用户消息
                if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                    apiHistoryMessages.removeAt(apiHistoryMessages.lastIndex)
                }
                apiHistoryMessages.add(
                    DataClassApiMessage(
                        role = "user",
                        content = textToActuallySend
                    )
                )
                // 再次确保不超过最大历史数量限制
                while (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                    apiHistoryMessages.removeAt(0)
                }
            }

            val requestBody = ChatRequest(
                messages = apiHistoryMessages,
                provider = if (currentConfig.provider.equals(
                        "google",
                        ignoreCase = true
                    )
                ) "google" else "openai",
                apiAddress = currentConfig.address,
                apiKey = currentConfig.key,
                model = currentConfig.model,
                useWebSearch = if (currentWebSearchEnabled) true else null
            )

            // 调用ApiHandler处理流式响应
            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend, // 用于日志
                afterUserMessageId = newUserMessage.id, // AI消息应在此用户消息之后插入
                onMessagesProcessed = {
                    // 当AI消息占位符已加入列表后，可以尝试保存历史
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

    /**
     * 为指定的用户消息重新生成AI回答。
     * @param originalUserMessage 需要重新生成回答的用户消息对象。
     */
    fun regenerateAiResponse(originalUserMessage: Message) {
        // 检查是否是用户消息
        if (originalUserMessage.sender != Sender.User) {
            showSnackbar("只能为您的消息重新生成回答")
            return
        }
        // 检查API配置是否存在
        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置")
            return
        }

        val originalUserMessageText = originalUserMessage.text
        val originalUserMessageId = originalUserMessage.id
        Log.d(
            "AppViewModel",
            "Regenerate: 开始为用户消息 ID ${originalUserMessageId.take(8)} 内容 '${
                originalUserMessageText.take(20)
            }...' 重新生成回答。"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            // 1. 找到原始用户消息在当前消息列表中的索引
            val userMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (userMessageIndex == -1) {
                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。")
                Log.w(
                    "AppViewModel",
                    "Regenerate: 未找到ID为 ${originalUserMessageId.take(8)} 的原始用户消息。"
                )
                return@launch
            }
            Log.d("AppViewModel", "Regenerate: 找到原始用户消息于索引 $userMessageIndex。")

            // 2. 移除此用户消息之后紧邻的AI回答（如果存在）
            // 注意：此时 userMessageIndex 是原始用户消息的准确索引
            val nextMessageIndex = userMessageIndex + 1
            if (nextMessageIndex < stateHolder.messages.size && stateHolder.messages[nextMessageIndex].sender == Sender.AI) {
                val aiMessageToRemove = stateHolder.messages[nextMessageIndex]
                Log.d(
                    "AppViewModel",
                    "Regenerate: 准备移除旧的AI回答 (ID: ${aiMessageToRemove.id.take(8)}) 于索引 $nextMessageIndex。"
                )
                // 如果被移除的AI消息正在流式传输，则取消它
                if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                    apiHandler.cancelCurrentApiJob(
                        "为消息 '${originalUserMessageText.take(10)}...' 重新生成回答，取消旧AI流",
                        isNewMessageSend = true // 视为新流程的一部分
                    )
                }
                stateHolder.messages.removeAt(nextMessageIndex) // 从列表中删除该AI消息
                Log.d("AppViewModel", "Regenerate: 已移除旧AI回答。")
            } else {
                Log.d("AppViewModel", "Regenerate: 原始用户消息后没有紧邻的AI回答可移除。")
            }

            // --- BUG 1 修复：删除原始的用户消息本身 ---
            // 此时，原始用户消息仍然在 userMessageIndex 位置 (因为我们只动了它后面的消息)
            Log.d(
                "AppViewModel",
                "Regenerate: 准备移除原始用户消息 (ID: ${originalUserMessageId.take(8)}) 于索引 $userMessageIndex。"
            )
            stateHolder.messages.removeAt(userMessageIndex)
            Log.d("AppViewModel", "Regenerate: 已移除原始用户消息。")
            // --- BUG 1 修复结束 ---

            // 4. （重要）在重新发送之前，保存当前聊天状态到历史记录。
            // forceSave = true 确保即使列表看起来“变短了”，这个状态也会被记录。
            // 这代表了用户在点击“重新生成”那一刻的聊天状态（旧用户消息和旧AI消息都已被移除）。
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            Log.d("AppViewModel", "Regenerate: 已强制保存移除旧消息后的聊天状态到历史。")

            // 5. 调用 onSendMessage 使用原始用户消息的文本。
            // onSendMessage 会将此文本作为一条 *新的* 用户消息添加到列表中，
            // 然后为其请求新的AI回复。ApiHandler 会将新AI回复插入到这条新用户消息之后。
            Log.d(
                "AppViewModel",
                "Regenerate: 调用 onSendMessage，文本: '${originalUserMessageText.take(20)}...', isFromRegeneration=true"
            )
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
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
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
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")

        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(conversationToLoad.map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                stateHolder.messages.forEach { msg ->
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
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
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            historyManager.deleteConversation(indexToDelete)
            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog()
                    dismissSourcesDialog()
                    stateHolder.clearForNewChat()
                    triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除")
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已删除") }
        }
    }

    fun clearAllConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
            }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
        }
    }

    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources
            stateHolder._showSourcesDialog.value = true
            Log.d("AppViewModel", "Show sources dialog requested. Sources count: ${sources.size}")
        }
    }

    fun dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) {
                stateHolder._showSourcesDialog.value = false
                Log.d("AppViewModel", "Dismiss sources dialog requested.")
            }
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
            val isDefaultPreview = currentPreview.startsWith("对话 ") && runCatching {
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
            }

            if (stateHolder._loadedHistoryIndex.value == index) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(
                        newMessagesForThisConversation.toList()
                            .map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                    stateHolder.messages.forEach { msg ->
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
            }

            withContext(Dispatchers.Main) {
                showSnackbar("对话已重命名为 '$trimmedNewName'")
                dismissRenameDialog()
            }
        }
    }

    override fun onCleared() {
        Log.d("AppViewModel", "[ID:$instanceId] onCleared 开始")
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared")

        viewModelScope.launch {
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] onCleared: 调用 historyManager.saveCurrentChatToHistoryIfNeeded"
            )
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = false)
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] onCleared: historyManager.saveCurrentChatToHistoryIfNeeded 调用完成"
            )
        }
        super.onCleared()
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel onCleared 结束.")
    }
}