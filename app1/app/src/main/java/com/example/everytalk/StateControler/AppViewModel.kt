package com.example.everytalk.StateControler // 请确认包名与 MessageSender.kt 中的一致

import android.app.Application
import android.net.Uri // 用于 regenerateAiResponse 中的图片URI解析
import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message // 这是UI层级的Message
import com.example.everytalk.data.DataClass.Sender // 这是UI层级的Sender
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.MainScreen.chat.SelectedMedia // 确保路径正确
import com.example.everytalk.ui.screens.viewmodel.ConfigManager
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import com.example.everytalk.util.convertMarkdownToHtml
import com.example.everytalk.util.generateKatexBaseHtmlTemplateString
import com.example.everytalk.webviewpool.WebViewPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID


class AppViewModel(
    application: Application,
    private val dataSource: SharedPreferencesDataSource
) : AndroidViewModel(application) {

    private val instanceId = UUID.randomUUID().toString().take(8)
    private val TAG_APP_VIEW_MODEL = "AppViewModel[ID:$instanceId]"

    internal val stateHolder = ViewModelStateHolder()
    private val persistenceManager =
        DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    private val historyManager: HistoryManager =
        HistoryManager(
            stateHolder,
            persistenceManager,
            ::areMessageListsEffectivelyEqual
        )

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
            stateHolder,
            viewModelScope,
            historyManager,
            ::onAiMessageFullTextChanged
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

    private val messageSender: MessageSender by lazy {
        MessageSender(
            application = getApplication(),
            viewModelScope = viewModelScope,
            stateHolder = stateHolder,
            apiHandler = apiHandler,
            historyManager = historyManager,
            showSnackbar = ::showSnackbar,
            triggerScrollToBottom = ::triggerScrollToBottom
        )
    }

    val webViewPool: WebViewPool by lazy {
        Log.d(TAG_APP_VIEW_MODEL, "Initializing WebViewPool")
        WebViewPool(getApplication<Application>().applicationContext, maxSize = 8)
    }

    // --- 正确定义 _markdownChunkToAppendFlow 和其公共访问器 ---
    private val _markdownChunkToAppendFlow =
        MutableSharedFlow<Pair<String, Pair<String, String>>>(replay = 0, extraBufferCapacity = 128)
    val markdownChunkToAppendFlow: SharedFlow<Pair<String, Pair<String, String>>> = // 公共访问器
        _markdownChunkToAppendFlow.asSharedFlow()
    // --- 定义结束 ---

    val drawerState: DrawerState get() = stateHolder.drawerState
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow()
    val messages: SnapshotStateList<Message> get() = stateHolder.messages
    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow()
    val isApiCalling: StateFlow<Boolean> get() = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> get() = stateHolder.reasoningCompleteMap
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> get() = stateHolder.expandedReasoningStates
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
    private val _customProviders = MutableStateFlow<Set<String>>(emptySet())
    val allProviders: StateFlow<List<String>> = combine(
        _customProviders
    ) { customsParamArray: Array<Set<String>> ->
        val customs = customsParamArray[0]
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
    val isWebSearchEnabled: StateFlow<Boolean> get() = stateHolder._isWebSearchEnabled.asStateFlow()
    val showSourcesDialog: StateFlow<Boolean> get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>> get() = stateHolder._sourcesForDialog.asStateFlow()
    // 注意：确保 markdownChunkToAppendFlow 的公共访问器在这里，或者其私有变量 _markdownChunkToAppendFlow 定义在类级别并且在此处可见。
    // 如果你之前将公共访问器定义在类的其他地方（如截图所示的第243行），请确保 _markdownChunkToAppendFlow 的定义也在AppViewModel的类级别。
    // 为了统一，我已将其移到类的靠前位置，与其他 backing property 和 public accessor 的模式类似。


    init {
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel 初始化开始")

        persistenceManager.loadInitialData { initialConfigPresent, initialHistoryPresent ->
            Log.d(
                TAG_APP_VIEW_MODEL,
                "初始数据加载完成。配置存在: $initialConfigPresent, 历史存在: $initialHistoryPresent"
            )
            if (!initialConfigPresent) {
                viewModelScope.launch { Log.i(TAG_APP_VIEW_MODEL, "没有检测到已保存的API配置。") }
            }
            viewModelScope.launch(Dispatchers.Main.immediate) {
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Restoring reasoningCompleteMap and animation states for loaded messages..."
                )
                stateHolder.messages.forEach { msg ->
                    val hasContentOrError = msg.contentStarted || msg.isError
                    val hasReasoning = !msg.reasoning.isNullOrBlank()
                    if (msg.sender == Sender.AI && hasReasoning && !this@AppViewModel.isApiCalling.value) {
                        stateHolder.reasoningCompleteMap[msg.id] = true
                    } else if (msg.sender == Sender.AI && this@AppViewModel.isApiCalling.value && this@AppViewModel.currentStreamingAiMessageId.value == msg.id) {
                        stateHolder.reasoningCompleteMap.remove(msg.id)
                    }
                    val animationPlayedCondition =
                        hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                    if (animationPlayedCondition) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Finished restoring reasoningCompleteMap and animation states after initial load."
                )
            }
        }

        viewModelScope.launch {
            val baseTemplate = getDefaultKatexHtmlTemplate()
            try {
                withContext(Dispatchers.Main) {
                    Log.d(TAG_APP_VIEW_MODEL, "Warming up WebViewPool on Main thread...")
                    this@AppViewModel.webViewPool.warmUp(4, baseTemplate)
                }
                Log.d(TAG_APP_VIEW_MODEL, "WebViewPool warmed up.")
            } catch (e: Exception) {
                Log.e(TAG_APP_VIEW_MODEL, "Error warming up WebViewPool", e)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            ApiClient.preWarm()
            apiHandler
            configManager
            messageSender
            Log.d(
                TAG_APP_VIEW_MODEL,
                "ViewModel IO pre-warming of ApiClient and Handlers completed."
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            Log.d(TAG_APP_VIEW_MODEL, "开始后台批量预处理历史消息的 htmlContent...")
            val originalLoadedHistory = stateHolder._historicalConversations.value.toList()
            if (originalLoadedHistory.isNotEmpty()) {
                val processedHistory = originalLoadedHistory.map { conversation ->
                    conversation.map { message ->
                        if (message.sender == Sender.AI && message.text.isNotBlank() && message.htmlContent == null) {
                            message.copy(htmlContent = convertMarkdownToHtml(message.text))
                        } else {
                            message
                        }
                    }
                }
                var wasModified = false
                if (originalLoadedHistory.size == processedHistory.size) {
                    for (i in originalLoadedHistory.indices) {
                        if (originalLoadedHistory[i].size == processedHistory[i].size) {
                            for (j in originalLoadedHistory[i].indices) {
                                if (originalLoadedHistory[i][j].htmlContent == null && processedHistory[i][j].htmlContent != null) {
                                    wasModified = true
                                    break
                                }
                            }
                        }
                        if (wasModified) break
                    }
                }
                if (wasModified) {
                    Log.d(TAG_APP_VIEW_MODEL, "后台预处理完成，检测到 htmlContent 更新。")
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder._historicalConversations.value = processedHistory
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            persistenceManager.saveChatHistory(processedHistory)
                            Log.i(
                                TAG_APP_VIEW_MODEL,
                                "成功将预处理后的历史记录保存到 SharedPreferences。"
                            )
                        } catch (e: Exception) {
                            Log.e(
                                TAG_APP_VIEW_MODEL,
                                "保存预处理后的历史记录到 SharedPreferences 失败。",
                                e
                            )
                        }
                    }
                } else {
                    Log.d(TAG_APP_VIEW_MODEL, "后台预处理完成，未检测到需要更新的 htmlContent。")
                }
            } else {
                Log.d(TAG_APP_VIEW_MODEL, "后台预处理：没有历史记录可供处理。")
            }
        }
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel 初始化逻辑结束.")
    }

    private fun getDefaultKatexHtmlTemplate(): String {
        val defaultBackgroundColor = "transparent"
        val defaultTextColor = "#000000"
        val defaultErrorColor = "#CD5C5C"
        return generateKatexBaseHtmlTemplateString(
            backgroundColor = defaultBackgroundColor,
            textColor = defaultTextColor,
            errorColor = defaultErrorColor,
            throwOnError = false
        )
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
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]
            if (msg1.id != msg2.id || msg1.sender != msg2.sender || msg1.text.trim() != msg2.text.trim() ||
                msg1.reasoning?.trim() != msg2.reasoning?.trim() || msg1.isError != msg2.isError
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

    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        Log.d(TAG_APP_VIEW_MODEL, "联网搜索模式切换为: $enabled")
    }

    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentCustomProviders = _customProviders.value.toMutableSet()
                val predefinedForCheck = listOf(
                    "openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索"
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
                Log.i(TAG_APP_VIEW_MODEL, "添加了新的自定义提供商: $trimmedName")
            }
        } else {
            showSnackbar("平台名称不能为空")
        }
    }

    fun showSnackbar(message: String) {
        viewModelScope.launch {
            stateHolder._snackbarMessage.emit(message)
        }
    }

    fun setSearchActiveInDrawer(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) _searchQueryInDrawer.value = ""
        Log.d(TAG_APP_VIEW_MODEL, "抽屉内搜索状态设置为: $isActive")
    }

    fun onDrawerSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
    }

    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    fun onSendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        images: List<SelectedMedia> = emptyList()
    ) {
        messageSender.sendMessage(messageText, isFromRegeneration, images)
    }

    fun onEditDialogTextChanged(newText: String) {
        stateHolder._editDialogInputText.value = newText
    }

    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) {
            _editingMessageId.value = message.id
            stateHolder._editDialogInputText.value = message.text
            _showEditDialog.value = true
        }
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = stateHolder._editDialogInputText.value.trim()
        viewModelScope.launch {
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
            if (messageIndex != -1) {
                val originalMessage = stateHolder.messages[messageIndex]
                if (originalMessage.text != updatedText) {
                    val updatedMessage = originalMessage.copy(
                        text = updatedText,
                        timestamp = System.currentTimeMillis()
                    )
                    stateHolder.messages[messageIndex] = updatedMessage
                    if (stateHolder.messageAnimationStates[updatedMessage.id] != true) {
                        stateHolder.messageAnimationStates[updatedMessage.id] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
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
        val originalSelectedMedia = originalUserMessage.imageUrls?.mapNotNull { urlString ->
            try {
                if (urlString.startsWith("content://") || urlString.startsWith("http://") || urlString.startsWith(
                        "https://"
                    )
                ) {
                    SelectedMedia.FromUri(Uri.parse(urlString))
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

        Log.d(
            TAG_APP_VIEW_MODEL,
            "regenerateAiResponse: 用户消息 ID $originalUserMessageId, 文本: '${
                originalUserMessageText.take(50)
            }', 图片数: ${originalSelectedMedia.size}"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (userMessageIndex == -1) {
                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。"); return@launch
            }
            var currentIndexToInspect = userMessageIndex + 1
            while (currentIndexToInspect < stateHolder.messages.size) {
                if (stateHolder.messages[currentIndexToInspect].sender == Sender.AI) {
                    val aiMessageToRemove = stateHolder.messages[currentIndexToInspect]
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                        apiHandler.cancelCurrentApiJob(
                            "为消息 '${originalUserMessageId.take(4)}' 重新生成回答，取消旧AI流",
                            isNewMessageSend = true
                        )
                    }
                    stateHolder.reasoningCompleteMap.remove(aiMessageToRemove.id)
                    stateHolder.expandedReasoningStates.remove(aiMessageToRemove.id)
                    stateHolder.messageAnimationStates.remove(aiMessageToRemove.id)
                    stateHolder.messages.removeAt(currentIndexToInspect)
                } else {
                    break
                }
            }
            val messageToDelete = stateHolder.messages.getOrNull(userMessageIndex)
            if (messageToDelete?.id == originalUserMessageId) {
                stateHolder.messageAnimationStates.remove(originalUserMessageId)
                stateHolder.messages.removeAt(userMessageIndex)
            }
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            messageSender.sendMessage(
                messageText = originalUserMessageText,
                isFromRegeneration = true,
                images = originalSelectedMedia
            )
        }
    }

    fun triggerScrollToBottom() {
        viewModelScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) {
            showSnackbar("无法加载对话：无效的索引"); return
        }
        val conversationToLoad = conversationList[index]
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            val processedConversation = withContext(Dispatchers.Default) {
                conversationToLoad.map { msg ->
                    val updatedContentStarted =
                        msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                    if (msg.sender == Sender.AI && msg.text.isNotBlank() && msg.htmlContent == null) {
                        msg.copy(
                            htmlContent = convertMarkdownToHtml(msg.text),
                            contentStarted = updatedContentStarted
                        )
                    } else {
                        msg.copy(contentStarted = updatedContentStarted)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(processedConversation)
                stateHolder.messages.forEach { msg ->
                    val hasContentOrError = msg.contentStarted || msg.isError
                    val hasReasoning = !msg.reasoning.isNullOrBlank()
                    if (msg.sender == Sender.AI && hasReasoning) {
                        stateHolder.reasoningCompleteMap[msg.id] = true
                    }
                    val animationPlayedCondition =
                        hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                    if (animationPlayedCondition) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
            if (_isSearchActiveInDrawer.value) withContext(Dispatchers.Main.immediate) {
                setSearchActiveInDrawer(false)
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
            val idsInDeletedConversation =
                stateHolder._historicalConversations.value.getOrNull(indexToDelete)?.map { it.id }
                    ?: emptyList()
            historyManager.deleteConversation(indexToDelete)
            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog(); dismissSourcesDialog()
                    stateHolder.clearForNewChat(); triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            } else {
                withContext(Dispatchers.Main.immediate) {
                    idsInDeletedConversation.forEach { id ->
                        stateHolder.reasoningCompleteMap.remove(id)
                        stateHolder.expandedReasoningStates.remove(id)
                        stateHolder.messageAnimationStates.remove(id)
                    }
                }
            }
            showSnackbar("对话已删除")
        }
    }

    fun clearAllConversations() {
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat(); triggerScrollToBottom()
            }
            showSnackbar("所有对话已清除")
        }
    }

    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources; stateHolder._showSourcesDialog.value =
            true
        }
    }

    fun dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) stateHolder._showSourcesDialog.value = false
        }
    }

    fun addConfig(config: ApiConfig) = configManager.addConfig(config)
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)
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

    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
            ?: return "对话 ${index + 1}"
        val placeholderTitleMsg =
            conversation.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg
        val firstUserMsg =
            conversation.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg
        val firstAiMsg =
            conversation.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
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
            val originalConversationAtIndex = currentHistoricalConvos[index].toMutableList()
            var titleMessageUpdatedOrAdded = false
            val existingTitleIndex =
                originalConversationAtIndex.indexOfFirst { it.sender == Sender.System && it.isPlaceholderName }
            if (existingTitleIndex != -1) {
                originalConversationAtIndex[existingTitleIndex] =
                    originalConversationAtIndex[existingTitleIndex].copy(
                        text = trimmedNewName,
                        timestamp = System.currentTimeMillis()
                    )
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
                originalConversationAtIndex.add(0, titleMessage)
            }
            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList()
                .apply { this[index] = originalConversationAtIndex.toList() }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()
            withContext(Dispatchers.IO) { persistenceManager.saveChatHistory(stateHolder._historicalConversations.value) }

            if (stateHolder._loadedHistoryIndex.value == index) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    val reloadedConversation = originalConversationAtIndex.toList().map { msg ->
                        val updatedContentStarted =
                            msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                        msg.copy(contentStarted = updatedContentStarted)
                    }
                    stateHolder.messages.addAll(reloadedConversation)
                    reloadedConversation.forEach { msg ->
                        val hasContentOrError = msg.contentStarted || msg.isError
                        val hasReasoning = !msg.reasoning.isNullOrBlank()
                        if (msg.sender == Sender.AI && hasReasoning) {
                            stateHolder.reasoningCompleteMap[msg.id] = true
                        }
                        val animationPlayedCondition =
                            hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                        if (animationPlayedCondition) {
                            stateHolder.messageAnimationStates[msg.id] = true
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { dismissRenameDialog(); showSnackbar("对话已重命名") }
        }
    }

    private fun onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (messageIndex != -1) {
                if (currentFullText.isNotBlank()) {
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "onAiMessageFullTextChanged: For message $messageId, text has changed. New full text length: ${currentFullText.length}. Preparing to update HTML."
                    )
                    launch(Dispatchers.Default) {
                        val newHtml = try {
                            convertMarkdownToHtml(currentFullText)
                        } catch (e: Exception) {
                            Log.e(
                                TAG_APP_VIEW_MODEL,
                                "Error converting Markdown to HTML for message $messageId: ${e.message}",
                                e
                            )
                            "<p>⚠️ 内容渲染出错，显示原始文本:</p><pre>${
                                currentFullText.replace(
                                    "<",
                                    "&lt;"
                                ).replace(">", "&gt;")
                            }</pre>"
                        }
                        withContext(Dispatchers.Main.immediate) {
                            val freshMessageIndex =
                                stateHolder.messages.indexOfFirst { it.id == messageId }
                            if (freshMessageIndex != -1) {
                                val messageToUpdate = stateHolder.messages[freshMessageIndex]
                                if (messageToUpdate.htmlContent != newHtml) {
                                    stateHolder.messages[freshMessageIndex] =
                                        messageToUpdate.copy(htmlContent = newHtml)
                                    Log.d(
                                        TAG_APP_VIEW_MODEL,
                                        "onAiMessageFullTextChanged: Updated htmlContent for message $messageId."
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val messageToUpdate = stateHolder.messages[messageIndex]
                    if (messageToUpdate.htmlContent != null) {
                        stateHolder.messages[messageIndex] =
                            messageToUpdate.copy(htmlContent = null)
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "onAiMessageFullTextChanged: Cleared htmlContent for message $messageId as currentFullText is blank."
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        Log.d(TAG_APP_VIEW_MODEL, "onCleared 开始, 销毁 WebViewPool")
        try {
            webViewPool.destroyAll()
        } catch (e: Exception) {
            Log.e(TAG_APP_VIEW_MODEL, "Error destroying WebViewPool in onCleared", e)
        }
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared", isNewMessageSend = false)

        val finalApiConfigs = stateHolder._apiConfigs.value.toList()
        val finalSelectedConfigId = stateHolder._selectedApiConfig.value?.id
        val finalCurrentChatMessages = stateHolder.messages.toList()

        Log.i(TAG_APP_VIEW_MODEL, "onCleared: Preparing to save final states synchronously.")
        try {
            runBlocking(Dispatchers.IO) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                persistenceManager.saveLastOpenChat(finalCurrentChatMessages)
                persistenceManager.saveApiConfigs(finalApiConfigs)
                persistenceManager.saveSelectedConfigIdentifier(finalSelectedConfigId)
            }
        } catch (e: Exception) {
            Log.e(TAG_APP_VIEW_MODEL, "onCleared: Error during runBlocking save operations", e)
        }
        super.onCleared()
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel onCleared 结束.")
    }
}