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
    private val stateHolder = ViewModelStateHolder()
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    // HistoryManager 依赖于一个比较函数，确保它被正确传递
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

    // --- 初始化块 ---
    init {
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化开始")
        viewModelScope.launch(Dispatchers.IO) {
            ApiClient.preWarm() // 预热API客户端
            apiHandler // 尽早初始化ApiHandler
            configManager // 尽早初始化ConfigManager
        }
        // 从持久化存储加载初始数据
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            viewModelScope.launch(Dispatchers.IO) {
                val loadedCustomProviders = dataSource.loadCustomProviders()
                _customProviders.value = loadedCustomProviders
                // 可选：加载持久化的联网搜索偏好，如果实现了的话
                // stateHolder._isWebSearchEnabled.value = dataSource.loadWebSearchPreference()
            }

            // 检查 stateHolder.messages (由 loadInitialData 内部通过 dataSource.loadLastOpenChat() 填充)
            // 如果 loadLastOpenChat() 返回空列表 (因为我们的策略是总清空它)，
            // 那么 currentChatMessagesFromPersistence 将是空的。
            val currentChatMessagesFromPersistence = stateHolder.messages.toList()
            val historicalConversationsFromPersistence = stateHolder._historicalConversations.value

            if (currentChatMessagesFromPersistence.isNotEmpty() && historyPresent) {
                // 这段逻辑理论上不应执行，因为 currentChatMessagesFromPersistence 应该是空的
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
                // 这是预期的路径：lastOpenChat 为空，所以 loadedHistoryIndex 为 null
                stateHolder._loadedHistoryIndex.value = null
            }

            // 初始化完成后选择一个API配置（如果需要）
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

    // --- 比较消息列表的辅助函数 (用于历史记录管理) ---
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
            // 比较关键字段，忽略时间戳等易变字段，根据需要调整
            if (msg1.id != msg2.id || // 如果ID是稳定的，可以比较ID
                msg1.sender != msg2.sender ||
                msg1.text.trim() != msg2.text.trim() ||
                msg1.reasoning?.trim() != msg2.reasoning?.trim() ||
                msg1.isError != msg2.isError
            ) return false
        }
        return true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        // 过滤规则与 HistoryManager 中的 filterMessagesForSaving 保持一致或类似
        return messagesToFilter.filter { msg ->
            (msg.sender != Sender.System || msg.isPlaceholderName) &&
                    (msg.sender == Sender.User || (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) || (msg.sender == Sender.System && msg.isPlaceholderName) || msg.isError)
        }.toList()
    }


    // --- 公共方法 ---

    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        showSnackbar("联网搜索已 ${if (enabled) "开启" else "关闭"}")
        // 可选：持久化用户对联网搜索的偏好
        // viewModelScope.launch(Dispatchers.IO) { dataSource.saveWebSearchPreference(enabled) }
    }

    fun addProvider(providerName: String) {
        // ... (此方法未改变，省略以保持简洁)
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

    // 发送消息
    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        // ... (此方法内部逻辑未改变，依赖 ApiHandler 和 ChatRequest 构建，省略以保持简洁)
        // 确保 ChatRequest 正确使用了 isWebSearchEnabled
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
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)

            if (!isFromRegeneration) stateHolder._text.value = ""
            triggerScrollToBottom()

            val apiHistoryMessages = mutableListOf<DataClassApiMessage>()
            val messagesSnapshotForHistory = stateHolder.messages.toList()
            var historyMessageCount = 0
            val maxHistoryMessages = 20

            for (msg in messagesSnapshotForHistory.asReversed()) {
                if (historyMessageCount >= maxHistoryMessages) break

                val apiMsgToAdd: DataClassApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        DataClassApiMessage(role = "user", content = msg.text.trim())

                    msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) && !msg.isError ->
                        DataClassApiMessage(role = "assistant", content = msg.text.trim())

                    else -> null
                }
                apiMsgToAdd?.let {
                    apiHistoryMessages.add(0, it)
                    historyMessageCount++
                }
            }
            if (apiHistoryMessages.isEmpty() || apiHistoryMessages.last().role != "user" || apiHistoryMessages.last().content != textToActuallySend) {
                if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                    apiHistoryMessages.removeAt(apiHistoryMessages.lastIndex)
                }
                apiHistoryMessages.add(
                    DataClassApiMessage(
                        role = "user",
                        content = textToActuallySend
                    )
                )
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

            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend,
                afterUserMessageId = newUserMessage.id,
                onMessagesProcessed = {
                    // 流处理开始后，可以立即尝试保存历史（如果策略如此）
                    // HistoryManager 的 saveIfNeeded 会处理去重和是否真的需要保存的逻辑
                    viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }
                }
            )
        }
    }

    // 编辑消息相关
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
                        timestamp = System.currentTimeMillis() // 更新时间戳
                    )
                    // 强制保存到历史，因为用户明确编辑了内容
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

    // 重新生成AI回答
    fun regenerateAiResponse(originalUserMessage: Message) {
        // ... (此方法未改变，省略以保持简洁)
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
            // 移除此用户消息之后可能存在的AI回答
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
                }
            }
            // 重要：在重新发送用户消息前，应该先将当前聊天状态（移除了旧AI回复后）保存一次
            // 这样历史记录能反映出用户在重新生成前的状态。
            // forceSave=true 确保即使内容看起来没变（只是少了AI回复），也会记录这个状态。
            // historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) // 这行可能导致问题，如果用户快速操作
            // 暂时注释掉，因为onSendMessage内部的onMessagesProcessed会调用saveIfNeeded

            // 重新发送原始用户消息文本
            // 注意：我们不再移除并重新添加用户消息，而是直接基于其文本调用onSendMessage
            // onSendMessage 会将这个文本作为新消息处理（但上下文历史是基于当前messages列表的）
            // onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)

            // 修正后的重新生成逻辑：
            // 1. 保留原始用户消息。
            // 2. 删除其后的AI消息（如果存在）。
            // 3. 调用onSendMessage，它会将原始用户消息文本作为最新的用户输入。
            //    onSendMessage内部构建的API历史将包含这条用户消息作为最后一条。

            // 确保在调用onSendMessage前，当前聊天状态（删除了旧AI回复）已通过HistoryManager处理
            // （HistoryManager的saveIfNeeded会清空lastOpenChat）
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

    // 开始新聊天
    fun startNewChat() {
        dismissEditDialog() // 关闭可能打开的编辑对话框
        apiHandler.cancelCurrentApiJob("开始新聊天") // 取消正在进行的API调用
        viewModelScope.launch {
            // 1. 将当前聊天（如果有内容）保存到历史记录。
            //    HistoryManager的saveIfNeeded方法内部会处理是否真的需要保存，并最终清空“最后打开的聊天”。
            historyManager.saveCurrentChatToHistoryIfNeeded()

            // 2. 在主线程上立即清空UI状态和相关ViewModel状态。
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 清空输入框、消息列表等
                triggerScrollToBottom() // 滚动到底部（空列表的顶部）
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false) // 关闭抽屉搜索
                stateHolder._loadedHistoryIndex.value = null // 明确当前没有加载任何历史
            }
            // persistenceManager.saveLastOpenChat(emptyList()) 这一步已由 historyManager 完成
        }
    }

    // 从历史加载对话
    fun loadConversationFromHistory(index: Int) {
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) {
            showSnackbar("无法加载对话：无效的索引"); return
        }
        val conversationToLoad = conversationList[index]
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")

        viewModelScope.launch {
            // 1. 在加载新对话前，处理当前对话：将其保存到历史（如果需要），并清空“最后打开的聊天”。
            historyManager.saveCurrentChatToHistoryIfNeeded()

            // 2. 在主线程上清空当前UI，并加载历史对话。
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 清空当前UI状态
                // 加载历史消息到UI列表
                stateHolder.messages.addAll(conversationToLoad.map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                // 确保动画状态等为已播放
                stateHolder.messages.forEach { msg ->
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index // 设置当前加载的历史索引
                triggerScrollToBottom()
            }

            // 移除下面这行：不应在加载历史后将其设为“最后打开的聊天”，因为我们希望下次启动是新会话。
            // withContext(Dispatchers.IO) { persistenceManager.saveLastOpenChat(stateHolder.messages.toList()) }

            if (_isSearchActiveInDrawer.value) { // 如果抽屉搜索是激活的，关闭它
                withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
            }
        }
    }

    // 删除历史对话
    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        if (indexToDelete < 0 || indexToDelete >= stateHolder._historicalConversations.value.size) {
            showSnackbar("无法删除：无效的索引"); return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            // HistoryManager 的 deleteConversation 内部会处理历史列表的持久化
            // 以及“最后打开的聊天”的清空。
            historyManager.deleteConversation(indexToDelete)

            // 如果删除的是当前正在显示的聊天，则需要清空UI并表现为新聊天状态。
            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog()
                    stateHolder.clearForNewChat() // 清空UI
                    triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除")
                // persistenceManager.saveLastOpenChat(emptyList()) 已由 historyManager.deleteConversation 处理
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已删除") }
        }
    }

    // 清除所有历史对话
    fun clearAllConversations() {
        dismissEditDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            // HistoryManager 的 clearAllHistory 内部会处理历史列表的持久化
            // 以及“最后打开的聊天”的清空。
            historyManager.clearAllHistory()

            // 清空当前UI，表现为新聊天状态
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
            }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
            // persistenceManager.saveLastOpenChat(emptyList()) 已由 historyManager.clearAllHistory 处理
        }
    }

    // API 配置管理 (委托给 ConfigManager)
    fun addConfig(config: ApiConfig) = configManager.addConfig(config)
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)

    // 动画状态管理
    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messageAnimationStates[messageId] = true
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    // 获取历史对话预览文本
    fun getConversationPreviewText(index: Int): String {
        // ... (此方法未改变，省略以保持简洁)
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

    // 重命名历史对话相关
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

            // 构建包含新名称的对话消息列表
            var titleMessageUpdatedOrAdded = false
            if (originalConversationAtIndex.isNotEmpty() && originalConversationAtIndex.first().sender == Sender.System && originalConversationAtIndex.first().isPlaceholderName) {
                // 更新现有的标题消息
                newMessagesForThisConversation.add(
                    originalConversationAtIndex.first()
                        .copy(text = trimmedNewName, timestamp = System.currentTimeMillis())
                )
                newMessagesForThisConversation.addAll(originalConversationAtIndex.drop(1))
                titleMessageUpdatedOrAdded = true
            }
            if (!titleMessageUpdatedOrAdded) {
                // 添加新的标题消息到对话开头
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}", // 确保ID唯一
                    text = trimmedNewName,
                    sender = Sender.System,
                    timestamp = System.currentTimeMillis() - 1, // 确保比其他消息早一点
                    contentStarted = true,
                    isPlaceholderName = true
                )
                newMessagesForThisConversation.add(titleMessage)
                newMessagesForThisConversation.addAll(originalConversationAtIndex)
            }

            // 更新历史对话列表
            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply {
                this[index] = newMessagesForThisConversation.toList()
            }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()

            // 持久化历史列表的更改
            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory()
                // 不再需要下面这部分，因为我们不希望重命名操作将当前会话设为“最后打开的会话”
                // if (stateHolder._loadedHistoryIndex.value == index) {
                //     persistenceManager.saveLastOpenChat(newMessagesForThisConversation.toList())
                // }
            }

            // 如果重命名的是当前正在UI上显示的对话，则更新UI
            if (stateHolder._loadedHistoryIndex.value == index) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(
                        newMessagesForThisConversation.toList()
                            .map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                    stateHolder.messages.forEach { msg -> // 重置动画状态等
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


    // --- ViewModel 清理 ---
    override fun onCleared() {
        Log.d("AppViewModel", "[ID:$instanceId] onCleared 开始")
        dismissEditDialog() // 关闭可能存在的对话框
        apiHandler.cancelCurrentApiJob("ViewModel cleared") // 取消API调用

        // 在ViewModel销毁前，调用HistoryManager来处理当前会话的保存（如果需要）
        // 并确保“最后打开的会话”被清空。
        // 这个操作需要在协程中执行，因为它可能涉及IO。
        // 使用viewModelScope确保在ViewModel的生命周期内完成。
        viewModelScope.launch {
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] onCleared: 调用 historyManager.saveCurrentChatToHistoryIfNeeded"
            )
            // forceSave = false: 只有当内容有实际变化或当前不在历史中时才保存到历史列表。
            // 但无论如何，saveCurrentChatToHistoryIfNeeded 内部会调用 persistenceManager.saveLastOpenChat(emptyList())。
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = false)
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] onCleared: historyManager.saveCurrentChatToHistoryIfNeeded 调用完成"
            )
        }
        // 注意：上面的 launch 是异步的。如果App进程立即被杀死，这个协程可能不会执行完毕。
        // 对于关键的持久化操作，如果需要确保在进程退出前完成，可能需要更强的同步机制或依赖Android的生命周期回调如 onStop/onDestroy（但这在ViewModel中不直接可用）。
        // 然而，对于“下次打开新会话”，只要 saveLastOpenChat(emptyList()) 在多数正常退出场景下能执行即可。

        super.onCleared()
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel onCleared 结束.")
    }
}