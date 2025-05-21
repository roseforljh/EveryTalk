package com.example.everytalk.StateControler

import android.app.Application
import android.util.Log
import androidx.compose.material3.DrawerState // Standard Compose import
import androidx.compose.runtime.snapshots.SnapshotStateList // Standard Compose import
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.ApiMessage // Using existing alias
import com.example.everytalk.data.DataClass.WebSearchResult
// import com.example.everytalk.data.DataClass.ContentPart // No longer directly used for streaming

import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.viewmodel.ConfigManager
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import com.example.everytalk.webviewpool.WebViewPool

import com.example.everytalk.util.generateKatexBaseHtmlTemplateString // Corrected import path
// parseMarkdownSegments and TextSegment are not directly used by ViewModel for streaming chunks anymore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job // Keep Job import if other parts of ViewModel still use it
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.serialization.Contextual // For ChatRequest fields
import java.util.concurrent.CancellationException

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
            ::onAiMessageChunkReceived // New callback for individual markdown chunks
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

    val webViewPool: WebViewPool by lazy {
        Log.d(TAG_APP_VIEW_MODEL, "Initializing WebViewPool")
        WebViewPool(getApplication<Application>().applicationContext, maxSize = 8)
    }

    // --- Streaming Chunk Handling ---
    // Emits Pair<MessageID, Pair<ChunkTriggerKey, MarkdownChunk>>
    private val _markdownChunkToAppendFlow = MutableSharedFlow<Pair<String, Pair<String, String>>>(replay = 0, extraBufferCapacity = 128)
    val markdownChunkToAppendFlow: SharedFlow<Pair<String, Pair<String, String>>> = _markdownChunkToAppendFlow.asSharedFlow()

    // Callback for ApiHandler to send individual markdown chunks
    private fun onAiMessageChunkReceived(messageId: String, markdownChunk: String) {
        if (markdownChunk.isNotBlank()) {
            viewModelScope.launch {
                val chunkTriggerKey = UUID.randomUUID().toString() // Unique key for this chunk
                Log.d(TAG_APP_VIEW_MODEL, "Emitting markdown chunk for msg $messageId (key ${chunkTriggerKey.take(4)}): \"${markdownChunk.take(30).replace("\n", "\\n")}\"")
                _markdownChunkToAppendFlow.tryEmit(messageId to (chunkTriggerKey to markdownChunk))
            }
        }
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

    // --- ViewModel State Properties (Getters for values in ViewModelStateHolder) ---
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
    ) { customsParamArray ->
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

    init {
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel 初始化开始")

        viewModelScope.launch(Dispatchers.IO) {
            val baseTemplate = getDefaultKatexHtmlTemplate()
            try {
                this@AppViewModel.webViewPool.warmUp(4, baseTemplate)
                Log.d(TAG_APP_VIEW_MODEL, "WebViewPool warmed up.")
            } catch (e: Exception) {
                Log.e(TAG_APP_VIEW_MODEL, "Error warming up WebViewPool", e)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            ApiClient.preWarm()
            apiHandler // Trigger lazy initialization
            configManager // Trigger lazy initialization
            Log.d(TAG_APP_VIEW_MODEL, "ViewModel IO pre-warming of ApiClient, ApiHandler, ConfigManager completed.")
        }

        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            viewModelScope.launch(Dispatchers.IO) {
                val loadedCustomProviders = dataSource.loadCustomProviders()
                _customProviders.value = loadedCustomProviders
            }

            val currentChatMessagesFromPersistence = stateHolder.messages.toList()
            val historicalConversationsFromPersistence = stateHolder._historicalConversations.value
            if (currentChatMessagesFromPersistence.isNotEmpty() && historyPresent) {
                val matchedIndex = historicalConversationsFromPersistence.indexOfFirst { historicalChat ->
                    areMessageListsEffectivelyEqual(currentChatMessagesFromPersistence, historicalChat)
                }
                stateHolder._loadedHistoryIndex.value = if (matchedIndex != -1) matchedIndex else null
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
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel 初始化逻辑结束.")
    }

    private fun areMessageListsEffectivelyEqual(list1: List<Message>?, list2: List<Message>?): Boolean {
        if (list1 == null && list2 == null) return true
        if (list1 == null || list2 == null) return false
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return false
        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
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

    // --- 【补全】之前省略的函数 ---
    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        showSnackbar("联网搜索已 ${if (enabled) "开启" else "关闭"}")
        Log.d(TAG_APP_VIEW_MODEL, "联网搜索模式切换为: $enabled")
    }

    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentCustomProviders = _customProviders.value.toMutableSet()
                val predefinedForCheck = listOf("openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索").map { it.lowercase() }
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

    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        val textToActuallySend = messageText.trim()
        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) showSnackbar("请输入消息内容")
            return
        }
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            showSnackbar("请先选择 API 配置"); return
        }
        val currentWebSearchEnabled = stateHolder._isWebSearchEnabled.value

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val newUserMessage = Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)
            if (!isFromRegeneration) stateHolder._text.value = ""
            triggerScrollToBottom()

            val apiHistoryMessages = mutableListOf<com.example.everytalk.data.DataClass.ApiMessage>()
            var historyMessageCount = 0
            val maxHistoryMessages = 20
            val messagesForHistory = stateHolder.messages.toList()
            val relevantMessages = if (messagesForHistory.size > 1) {
                messagesForHistory.subList(0, messagesForHistory.size -1)
            } else {
                emptyList()
            }
            for (msg in relevantMessages.asReversed()) {
                if (historyMessageCount >= maxHistoryMessages) break
                val apiMsgToAdd: com.example.everytalk.data.DataClass.ApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        com.example.everytalk.data.DataClass.ApiMessage(role = "user", content = msg.text.trim())
                    msg.sender == Sender.AI && !msg.isError && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) -> {
                        val contentForApi = if (msg.text.isNotBlank()) msg.text.trim() else msg.reasoning?.trim() ?: ""
                        if (contentForApi.isNotBlank()) com.example.everytalk.data.DataClass.ApiMessage(role = "assistant", content = contentForApi) else null
                    }
                    else -> null
                }
                apiMsgToAdd?.let { apiHistoryMessages.add(0, it); historyMessageCount++ }
            }
            apiHistoryMessages.add(com.example.everytalk.data.DataClass.ApiMessage(role = "user", content = textToActuallySend))
            while(apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()){
                apiHistoryMessages.removeAt(0)
            }

            var finalCustomModelParameters: Map<String, Boolean>? = null
            var finalCustomExtraBody: Map<String, Boolean>? = null
            val modelNameLower = currentConfig.model.lowercase()
            val apiAddressLower = currentConfig.address?.lowercase() ?: ""

            if (modelNameLower.contains("qwen")) {
                if (apiAddressLower.contains("api.siliconflow.cn")) {
                    val params = mutableMapOf("enable_search" to currentWebSearchEnabled)
                    if (modelNameLower.contains("qwen3")) params["enable_thinking"] = false
                    finalCustomModelParameters = params.ifEmpty { null }
                } else if (apiAddressLower.contains("dashscope.aliyuncs.com")) {
                    finalCustomModelParameters = mapOf("enable_search" to currentWebSearchEnabled).ifEmpty { null }
                } else if (currentConfig.provider.equals("openai compatible", ignoreCase = true) && !apiAddressLower.contains("api.openai.com")) {
                    val params = mutableMapOf("enable_search" to currentWebSearchEnabled)
                    if (modelNameLower.contains("qwen3")) params["enable_thinking"] = false
                    finalCustomModelParameters = params.ifEmpty { null }
                }
            }
            val topLevelUseWebSearch = if (currentConfig.provider.equals("google",ignoreCase = true) || (finalCustomModelParameters?.get("enable_search") == true)) null else currentWebSearchEnabled

            val requestBody = ChatRequest(
                messages = apiHistoryMessages,
                provider = if (currentConfig.provider.equals("google", ignoreCase = true)) "google" else "openai",
                apiAddress = currentConfig.address, apiKey = currentConfig.key, model = currentConfig.model,
                useWebSearch = topLevelUseWebSearch,
                customModelParameters = finalCustomModelParameters,
                customExtraBody = finalCustomExtraBody,
                forceGoogleReasoningPrompt = null, temperature = null, topP = null, maxTokens = null, tools = null, toolChoice = null // Ensure all fields are covered
            )
            Log.d(TAG_APP_VIEW_MODEL, "Sending ChatRequest: Provider=${requestBody.provider}, Model=${requestBody.model}, useWebSearch=${requestBody.useWebSearch}, customModelParams=${requestBody.customModelParameters}")

            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend,
                afterUserMessageId = newUserMessage.id,
                onMessagesProcessed = { viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() } }
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
                    val updatedMessage = originalMessage.copy(text = updatedText, timestamp = System.currentTimeMillis())
                    stateHolder.messages[messageIndex] = updatedMessage
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
        if (originalUserMessage.sender != Sender.User) { showSnackbar("只能为您的消息重新生成回答"); return }
        if (stateHolder._selectedApiConfig.value == null) { showSnackbar("请先选择 API 配置"); return }

        val originalUserMessageText = originalUserMessage.text
        val originalUserMessageId = originalUserMessage.id

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndex = stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (userMessageIndex == -1) { showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。"); return@launch }

            var nextIndex = userMessageIndex + 1
            // No _messageContentPartsMap to clean up for individual chunks in this model
            while(nextIndex < stateHolder.messages.size && stateHolder.messages[nextIndex].sender == Sender.AI) {
                val aiMessageToRemove = stateHolder.messages[nextIndex]
                if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                    apiHandler.cancelCurrentApiJob("为消息重新生成回答，取消旧AI流", isNewMessageSend = true)
                }
                stateHolder.messages.removeAt(nextIndex)
            }
            stateHolder.messages.removeAt(userMessageIndex)
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
        }
    }

    fun triggerScrollToBottom() {
        stateHolder._scrollToBottomEvent.tryEmit(Unit)
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作"); showSnackbar("已停止回答")
    }

    fun startNewChat() {
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                // _markdownChunkToAppendFlow does not need explicit clearing, new subscribers won't get old values (if not a StateFlow)
                triggerScrollToBottom()
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                stateHolder._loadedHistoryIndex.value = null
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) { showSnackbar("无法加载对话：无效的索引"); return }

        val conversationToLoad = conversationList[index]
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")

        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(conversationToLoad.map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                stateHolder.messages.forEach { msg -> stateHolder.messageAnimationStates[msg.id] = true }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
            // For historical messages, AiMessageContent will use message.text for initialLatexInput
            if (_isSearchActiveInDrawer.value) withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
        }
    }

    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        if (indexToDelete < 0 || indexToDelete >= stateHolder._historicalConversations.value.size) {
            showSnackbar("无法删除：无效的索引"); return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            // val chatToDelete = stateHolder._historicalConversations.value.getOrNull(indexToDelete) // Not strictly needed here

            historyManager.deleteConversation(indexToDelete)
            // No _messageContentPartsMap to clean up here

            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog(); dismissSourcesDialog()
                    stateHolder.clearForNewChat(); triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已删除") }
        }
    }

    fun clearAllConversations() {
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.clearAllHistory()
            // No _messageContentPartsMap to clear
            withContext(Dispatchers.Main.immediate) { stateHolder.clearForNewChat(); triggerScrollToBottom() }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
        }
    }

    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources; stateHolder._showSourcesDialog.value = true
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
            stateHolder.messageAnimationStates[messageId] = true
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index) ?: return "对话 ${index + 1}"
        val placeholderTitleMsg = conversation.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg
        val firstUserMsg = conversation.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg
        val firstAiMsg = conversation.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
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

            val existingTitleIndex = originalConversationAtIndex.indexOfFirst { it.sender == Sender.System && it.isPlaceholderName }
            if (existingTitleIndex != -1) {
                originalConversationAtIndex[existingTitleIndex] = originalConversationAtIndex[existingTitleIndex].copy(text = trimmedNewName, timestamp = System.currentTimeMillis())
                titleMessageUpdatedOrAdded = true
            }

            if (!titleMessageUpdatedOrAdded) {
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}", text = trimmedNewName, sender = Sender.System,
                    timestamp = System.currentTimeMillis() -1,
                    contentStarted = true, isPlaceholderName = true
                )
                originalConversationAtIndex.add(0, titleMessage)
            }

            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply { this[index] = originalConversationAtIndex.toList() }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()
            withContext(Dispatchers.IO) { persistenceManager.saveChatHistory() }

            if (stateHolder._loadedHistoryIndex.value == index) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(originalConversationAtIndex.toList().map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                    stateHolder.messages.forEach { msg -> stateHolder.messageAnimationStates[msg.id] = true }
                    // No specific content part reprocessing needed here for this streaming model
                }
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已重命名为 '$trimmedNewName'"); dismissRenameDialog() }
        }
    }

    override fun onCleared() {
        Log.d(TAG_APP_VIEW_MODEL, "onCleared 开始, 销毁 WebViewPool")
        try {
            webViewPool.destroyAll() // Accessing will initialize if not already
            Log.d(TAG_APP_VIEW_MODEL, "WebViewPool destroyed in onCleared.")
        } catch (e: Exception) {
            Log.e(TAG_APP_VIEW_MODEL, "Error destroying WebViewPool in onCleared", e)
        }
        // messageProcessingJobs was removed.
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared", isNewMessageSend = false)
        if (viewModelScope.isActive) {
            viewModelScope.launch(Dispatchers.IO) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = false)
            }
        } else {
            Log.w(TAG_APP_VIEW_MODEL, "onCleared: viewModelScope 已不活动，跳过最后的历史保存。")
        }
        super.onCleared()
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel onCleared 结束.")
    }
}