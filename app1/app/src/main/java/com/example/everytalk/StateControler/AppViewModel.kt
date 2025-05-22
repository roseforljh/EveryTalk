package com.example.everytalk.StateControler

import android.app.Application
import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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

    // ApiHandler's callback is now onAiMessageChunkReceived
    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
            stateHolder,
            viewModelScope,
            historyManager,
            ::onAiMessageFullTextChanged // ★★★ 将这里改为新的回调函数引用 ★★★
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
        WebViewPool(getApplication<Application>().applicationContext, maxSize = 8) // Your value
    }

    // --- Streaming Raw Markdown Chunk Handling ---
    // Emits Pair<MessageID, Pair<ChunkTriggerKey, RawMarkdownChunk>>
    private val _markdownChunkToAppendFlow =
        MutableSharedFlow<Pair<String, Pair<String, String>>>(replay = 0, extraBufferCapacity = 128)
    val markdownChunkToAppendFlow: SharedFlow<Pair<String, Pair<String, String>>> =
        _markdownChunkToAppendFlow.asSharedFlow()


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

    init {
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel 初始化开始")

        // ★★★ 修改开始：调用 loadInitialData 加载持久化的数据 ★★★
        persistenceManager.loadInitialData { initialConfigPresent, initialHistoryPresent ->
            Log.d(
                TAG_APP_VIEW_MODEL,
                "初始数据加载完成。配置存在: $initialConfigPresent, 历史存在: $initialHistoryPresent"
            )
            // 你可以在这里根据加载结果执行一些额外操作，如果需要的话
            // 例如，如果 !initialConfigPresent，可以提示用户添加配置
            if (!initialConfigPresent) {
                viewModelScope.launch {
                    // 避免在 ViewModel init 过于早期就直接显示 Snackbar，
                    // 除非有特定机制确保 UI 此时已准备好接收。
                    // 此处仅作日志记录或延迟提示。
                    Log.i(TAG_APP_VIEW_MODEL, "没有检测到已保存的API配置。")
                    // showSnackbar("请添加您的第一个API配置") // 示例：如果需要，可以延迟执行或通过事件通知UI
                }
            }
        }
        // ★★★ 修改结束 ★★★

        viewModelScope.launch { // 这个 launch 默认就在主调度器上
            val baseTemplate = getDefaultKatexHtmlTemplate()
            try {
                // 如果 warmUp 内部创建 WebView 不是 suspend，可以直接调用
                // 如果 warmUp 是 suspend 且内部切换线程，也没问题
                // 最保险的是确保 WebView 的 new WebView() 调用在主线程
                withContext(Dispatchers.Main) { // 强制在主线程
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
            apiHandler // 确保 apiHandler 和 configManager 被及早初始化 (如果它们是 lazy 且有IO操作)
            configManager
            Log.d(
                TAG_APP_VIEW_MODEL,
                "ViewModel IO pre-warming of ApiClient, ApiHandler, ConfigManager completed."
            )
        }

        viewModelScope.launch(Dispatchers.Default) { // 使用 Dispatchers.Default 进行CPU密集型转换
            Log.d(TAG_APP_VIEW_MODEL, "开始后台批量预处理历史消息的 htmlContent...")
            // 注意: 此时 _historicalConversations 可能尚未被 loadInitialData 完全填充
            // 如果这个预处理依赖于 loadInitialData 完成后的 _historicalConversations.value，
            // 那么这个 launch 应该在 loadInitialData 的回调中，或者观察 _historicalConversations 的变化。
            // 不过，由于 loadInitialData 也是异步的，并且会更新 _historicalConversations，
            // 这里的逻辑如果依赖最新的历史记录，需要小心时序。
            // 鉴于后台预处理通常可以处理 "当前已知" 的数据，这里暂时保持原样，但需注意其执行时机。

            val originalLoadedHistory =
                stateHolder._historicalConversations.value.toList() // 获取当前快照

            if (originalLoadedHistory.isNotEmpty()) { // 仅当有历史记录时才处理
                val processedHistory = originalLoadedHistory.map { conversation ->
                    conversation.map { message ->
                        if (message.sender == Sender.AI && message.text.isNotBlank() && message.htmlContent == null) {
                            Log.d(
                                TAG_APP_VIEW_MODEL,
                                "后台预处理: 为消息 ${message.id.take(4)} 生成 htmlContent"
                            )
                            message.copy(
                                htmlContent = convertMarkdownToHtml(message.text)
                                // contentStarted 应该在 message 从 SharedPreferences 加载时就已经根据 text/reasoning 设置好了
                                // 如果没有，确保在 message.copy 时也正确传递或更新它
                            )
                        } else {
                            message
                        }
                    }
                }

                var wasModified = false
                if (originalLoadedHistory.size == processedHistory.size) { // 确保维度一致
                    for (i in originalLoadedHistory.indices) {
                        if (originalLoadedHistory[i].size == processedHistory[i].size) { // 确保子列表维度一致
                            for (j in originalLoadedHistory[i].indices) {
                                if (originalLoadedHistory[i][j].htmlContent == null && processedHistory[i][j].htmlContent != null) {
                                    wasModified = true
                                    break
                                }
                            }
                        } else {
                            // 维度不一致，可能意味着在处理过程中历史记录发生了变化，保守起见认为可能已修改或跳过
                            Log.w(
                                TAG_APP_VIEW_MODEL,
                                "后台预处理：历史会话 $i 的消息数量发生变化，跳过修改检测。"
                            )
                            // wasModified = true; // 或者根据业务逻辑决定是否标记为修改
                        }
                        if (wasModified) break
                    }
                } else {
                    Log.w(TAG_APP_VIEW_MODEL, "后台预处理：历史会话总数发生变化，跳过修改检测。")
                    // wasModified = true;
                }


                if (wasModified) {
                    Log.d(TAG_APP_VIEW_MODEL, "后台预处理完成，检测到 htmlContent 更新。")
                    withContext(Dispatchers.Main.immediate) {
                        // 再次检查，确保更新的是同一个版本的 historicalConversations
                        // 如果 stateHolder._historicalConversations.value 在此期间已被 loadInitialData 再次修改，
                        // 这里的更新可能基于旧数据。更安全的做法是让 loadInitialData 也负责一部分预处理，
                        // 或者这里的逻辑在 loadInitialData 完成后，基于其结果进行。
                        // 为简单起见，先直接更新。
                        stateHolder._historicalConversations.value = processedHistory
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "已更新内存中的 _historicalConversations StateFlow。"
                        )
                    }

                    withContext(Dispatchers.IO) {
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "后台预处理后，正在将更新后的历史记录保存回 SharedPreferences..."
                        )
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
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)
            if (!isFromRegeneration) stateHolder._text.value = ""
            triggerScrollToBottom()

            val apiHistoryMessages =
                mutableListOf<com.example.everytalk.data.DataClass.ApiMessage>()
            var historyMessageCount = 0
            val maxHistoryMessages = 20
            val messagesForHistory = stateHolder.messages.toList()
            val relevantMessages = if (messagesForHistory.size > 1) {
                messagesForHistory.subList(0, messagesForHistory.size - 1)
            } else {
                emptyList()
            }
            for (msg in relevantMessages.asReversed()) {
                if (historyMessageCount >= maxHistoryMessages) break
                val apiMsgToAdd: com.example.everytalk.data.DataClass.ApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        com.example.everytalk.data.DataClass.ApiMessage(
                            role = "user",
                            content = msg.text.trim()
                        )

                    msg.sender == Sender.AI && !msg.isError && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) -> {
                        val contentForApi =
                            if (msg.text.isNotBlank()) msg.text.trim() else msg.reasoning?.trim()
                                ?: ""
                        if (contentForApi.isNotBlank()) com.example.everytalk.data.DataClass.ApiMessage(
                            role = "assistant",
                            content = contentForApi
                        ) else null
                    }

                    else -> null
                }
                apiMsgToAdd?.let { apiHistoryMessages.add(0, it); historyMessageCount++ }
            }
            apiHistoryMessages.add(
                com.example.everytalk.data.DataClass.ApiMessage(
                    role = "user",
                    content = textToActuallySend
                )
            )
            while (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                apiHistoryMessages.removeAt(0)
            }

            var finalCustomModelParameters: Map<String, Boolean>? = null
            // var finalCustomExtraBody: Map<String, Boolean>? = null // 如果这个字段没用，可以考虑移除
            val modelNameLower = currentConfig.model.lowercase()
            val apiAddressLower = currentConfig.address?.lowercase() ?: ""

            // 保留 Qwen 特定的 customModelParameters 逻辑，因为有些 Qwen 服务可能依赖这个
            // 而不是顶层的 useWebSearch。如果你的后端统一处理 useWebSearch，
            // 并且不再需要 customModelParameters 中的 enable_search，则可以移除下面的 if 块。
            // 但为了兼容性，暂时保留它可能是个好主意，除非你确定后端不再看它。
            if (modelNameLower.contains("qwen")) {
                if (apiAddressLower.contains("api.siliconflow.cn")) {
                    val params = mutableMapOf("enable_search" to currentWebSearchEnabled)
                    if (modelNameLower.contains("qwen3")) params["enable_thinking"] = false
                    finalCustomModelParameters = params.ifEmpty { null }
                } else if (apiAddressLower.contains("dashscope.aliyuncs.com")) {
                    finalCustomModelParameters =
                        mapOf("enable_search" to currentWebSearchEnabled).ifEmpty { null }
                } else if (currentConfig.provider.equals(
                        "openai compatible",
                        ignoreCase = true
                    ) && !apiAddressLower.contains("api.openai.com") // 假设这些兼容服务也可能看 qwen 的参数
                ) {
                    val params = mutableMapOf("enable_search" to currentWebSearchEnabled)
                    if (modelNameLower.contains("qwen3")) params["enable_thinking"] = false
                    finalCustomModelParameters = params.ifEmpty { null }
                }
            }


            val topLevelUseWebSearch = if (currentWebSearchEnabled) true else null

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
                useWebSearch = topLevelUseWebSearch, // 使用新的 topLevelUseWebSearch
                customModelParameters = finalCustomModelParameters, // Qwen 的 enable_search 仍然会在这里
                forceGoogleReasoningPrompt = null,
                temperature = null,
                topP = null,
                maxTokens = null,
                tools = null,
                toolChoice = null
            )
            Log.d(
                TAG_APP_VIEW_MODEL,
                "Sending ChatRequest: Provider=${requestBody.provider}, Model=${requestBody.model}, useWebSearch=${requestBody.useWebSearch}, customModelParams=${requestBody.customModelParameters}"
            )

            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend,
                afterUserMessageId = newUserMessage.id,
                onMessagesProcessed = {
                    viewModelScope.launch {
                        historyManager.saveCurrentChatToHistoryIfNeeded()
                    }

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
        }
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = stateHolder._editDialogInputText.value.trim()
        if (updatedText.isBlank()) {
            return
        }
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

    // 在 AppViewModel.kt 中

    fun regenerateAiResponse(originalUserMessage: Message) {
        if (originalUserMessage.sender != Sender.User) {
            showSnackbar("只能为您的消息重新生成回答"); return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置"); return
        }

        val originalUserMessageText = originalUserMessage.text
        val originalUserMessageId = originalUserMessage.id
        Log.d(
            TAG_APP_VIEW_MODEL,
            "regenerateAiResponse: Called for user message ID $originalUserMessageId, Text: '${
                originalUserMessageText.take(50)
            }'"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            Log.d(
                TAG_APP_VIEW_MODEL,
                "regenerateAiResponse: Index of original user message: $userMessageIndex"
            )

            if (userMessageIndex == -1) {
                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。");
                Log.e(
                    TAG_APP_VIEW_MODEL,
                    "regenerateAiResponse: Original user message ID $originalUserMessageId not found in current messages."
                )
                return@launch
            }

            // --- 修正并明确移除后续AI消息的逻辑 ---
            // 目的是移除紧跟在当前用户消息之后的所有连续的AI消息
            var currentIndexToInspect = userMessageIndex + 1
            while (currentIndexToInspect < stateHolder.messages.size) {
                if (stateHolder.messages[currentIndexToInspect].sender == Sender.AI) {
                    val aiMessageToRemove = stateHolder.messages[currentIndexToInspect]
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "regenerateAiResponse: Removing subsequent AI message at index $currentIndexToInspect, ID ${aiMessageToRemove.id}"
                    )
                    if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                        apiHandler.cancelCurrentApiJob(
                            "为消息 '${originalUserMessageId.take(4)}' 重新生成回答，取消旧AI流",
                            isNewMessageSend = true // 标记为新消息发送，以正确处理流状态
                        )
                    }
                    stateHolder.messages.removeAt(currentIndexToInspect)
                    // 注意：当从列表中移除一个元素时，后续元素的索引会减1。
                    // 因为我们总是检查新的 currentIndexToInspect，所以不需要显式递减它，
                    // 下一次循环的 messages.size 会变小，并且 messages[currentIndexToInspect] 会是新的元素。
                } else {
                    // 如果遇到的不是AI消息（比如是另一个用户消息），则停止移除。
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "regenerateAiResponse: Encountered non-AI message at index $currentIndexToInspect, stopping AI message removal."
                    )
                    break
                }
            }
            // --- AI消息移除结束 ---

            // 移除原始的用户消息
            // 在移除前再次确认ID，以防万一
            val messageToDelete = stateHolder.messages.getOrNull(userMessageIndex)
            if (messageToDelete != null && messageToDelete.id == originalUserMessageId) {
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "regenerateAiResponse: Attempting to remove original user message ID $originalUserMessageId at index $userMessageIndex."
                )
                stateHolder.messages.removeAt(userMessageIndex)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "regenerateAiResponse: Original user message ID $originalUserMessageId successfully removed from list model."
                )
            } else {
                Log.e(
                    TAG_APP_VIEW_MODEL,
                    "regenerateAiResponse: Failed to remove original user message. Expected ID $originalUserMessageId at index $userMessageIndex, but found ${messageToDelete?.id}. List size: ${stateHolder.messages.size}"
                )
                showSnackbar("重新生成时移除原消息失败，可能导致显示重复。") // 提示用户潜在问题
            }

            // 保存移除了AI回复和（理想情况下）原始用户消息之后的状态
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            Log.d(TAG_APP_VIEW_MODEL, "regenerateAiResponse: Chat history saved after removals.")

            // 使用原始文本重新发送消息（这将添加一个新的用户消息）
            Log.d(
                TAG_APP_VIEW_MODEL,
                "regenerateAiResponse: Calling onSendMessage to resend user text: '${
                    originalUserMessageText.take(50)
                }'"
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
        dismissEditDialog(); dismissSourcesDialog()
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
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")

        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            val processedConversation = withContext(Dispatchers.Default) { // 后台处理转换
                conversationToLoad.map { msg ->
                    if (msg.sender == Sender.AI && msg.text.isNotBlank() && msg.htmlContent == null) {
                        msg.copy(
                            htmlContent = convertMarkdownToHtml(msg.text), // 假设 convertMarkdownToHtml 在 ViewModel 或 util 中可访问
                            contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                        )
                    } else {
                        msg.copy(
                            contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError
                        )
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(processedConversation)

                stateHolder.messages.forEach { msg ->
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
            if (_isSearchActiveInDrawer.value) withContext(Dispatchers.Main.immediate) {
                setSearchActiveInDrawer(
                    false
                )
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
                    dismissEditDialog(); dismissSourcesDialog()
                    stateHolder.clearForNewChat(); triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            }
            withContext(Dispatchers.Main) {  }
        }
    }

    fun clearAllConversations() {
        dismissEditDialog(); dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) { stateHolder.clearForNewChat(); triggerScrollToBottom() }
            withContext(Dispatchers.Main) {  }
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
            stateHolder.messageAnimationStates[messageId] = true
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

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
                    stateHolder.messages.addAll(
                        originalConversationAtIndex.toList()
                            .map { msg -> msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError) })
                    stateHolder.messages.forEach { msg ->
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
            }
            withContext(Dispatchers.Main) { dismissRenameDialog() }
        }
    }

    // In AppViewModel.kt
    private fun onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (messageIndex != -1) {
                // ApiHandler has already updated stateHolder.messages[messageIndex].text to currentFullText.
                // The message object reflects the latest text.

                if (currentFullText.isNotBlank()) {
                    // Since ApiHandler calls this only when text has actually changed,
                    // we should proceed to generate/update HTML.
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "onAiMessageFullTextChanged: For message $messageId, text has changed. New full text length: ${currentFullText.length}. Preparing to update HTML."
                    )
                    launch(Dispatchers.Default) { // Background thread for HTML generation
                        val newHtml = convertMarkdownToHtml(currentFullText)
                        withContext(Dispatchers.Main.immediate) { // Switch back to main thread to update StateFlow
                            val freshMessageIndex =
                                stateHolder.messages.indexOfFirst { it.id == messageId }
                            if (freshMessageIndex != -1) {
                                val messageToUpdate = stateHolder.messages[freshMessageIndex]
                                // Only update if the newly generated HTML is different from the existing one
                                // or if existing HTML is null. This avoids unnecessary recompositions.
                                if (messageToUpdate.htmlContent != newHtml) {
                                    stateHolder.messages[freshMessageIndex] =
                                        messageToUpdate.copy(htmlContent = newHtml)
                                    Log.d(
                                        TAG_APP_VIEW_MODEL,
                                        "onAiMessageFullTextChanged: Updated htmlContent for message $messageId."
                                    )
                                } else {
                                    Log.d(
                                        TAG_APP_VIEW_MODEL,
                                        "onAiMessageFullTextChanged: Generated HTML for $messageId is identical to existing, no view update for htmlContent."
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Full text is now blank, clear the HTML content
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
            } else {
                Log.w(
                    TAG_APP_VIEW_MODEL,
                    "onAiMessageFullTextChanged: Message ID $messageId not found to update htmlContent."
                )
            }
        }
    }

    override fun onCleared() {
        Log.d(TAG_APP_VIEW_MODEL, "onCleared 开始, 销毁 WebViewPool")
        try {
            webViewPool.destroyAll()
            Log.d(TAG_APP_VIEW_MODEL, "WebViewPool destroyed in onCleared.")
        } catch (e: Exception) {
            Log.e(TAG_APP_VIEW_MODEL, "Error destroying WebViewPool in onCleared", e)
        }

        dismissEditDialog()
        dismissSourcesDialog()

        apiHandler.cancelCurrentApiJob("ViewModel cleared", isNewMessageSend = false)
        Log.d(TAG_APP_VIEW_MODEL, "onCleared: API job cancellation requested.")

        val finalApiConfigs = stateHolder._apiConfigs.value.toList()
        val finalSelectedConfigId = stateHolder._selectedApiConfig.value?.id
        val finalCurrentChatMessages = stateHolder.messages.toList()

        Log.i(TAG_APP_VIEW_MODEL, "onCleared: Preparing to save final states synchronously.")
        Log.i(
            TAG_APP_VIEW_MODEL,
            "onCleared: Final configs count: ${finalApiConfigs.size}, Selected ID: $finalSelectedConfigId, Current chat size: ${finalCurrentChatMessages.size}"
        )

        try {
            runBlocking(Dispatchers.IO) { // 在 IO 线程上阻塞执行
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: runBlocking(Dispatchers.IO) started for final save."
                )

                // 1. 保存当前聊天作为"最后打开的聊天"
                persistenceManager.saveLastOpenChat(finalCurrentChatMessages)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: Saved last open chat (${finalCurrentChatMessages.size} messages)."
                )

                // 2. 保存聊天历史
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: saveCurrentChatToHistoryIfNeeded(forceSave=true) completed."
                )

                // 3. 保存API配置列表
                persistenceManager.saveApiConfigs(finalApiConfigs)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: Saved API configs list (${finalApiConfigs.size} configs)."
                )

                // 4. 保存选中的API配置ID
                persistenceManager.saveSelectedConfigIdentifier(finalSelectedConfigId)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: Saved selected API config ID ('$finalSelectedConfigId')."
                )

                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: runBlocking(Dispatchers.IO) for final save completed."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG_APP_VIEW_MODEL, "onCleared: Error during runBlocking save operations", e)
        }

        super.onCleared()
        Log.d(TAG_APP_VIEW_MODEL, "ViewModel onCleared 结束.")
    }
}