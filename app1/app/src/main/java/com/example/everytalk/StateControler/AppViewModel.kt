package com.example.everytalk.StateControler

import android.app.Application
import android.util.Log
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap // 确保导入
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.ui.screens.MainScreen.chat.SelectedMedia
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

    val webViewPool: WebViewPool by lazy {
        Log.d(TAG_APP_VIEW_MODEL, "Initializing WebViewPool")
        WebViewPool(getApplication<Application>().applicationContext, maxSize = 8)
    }

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

    val drawerState: DrawerState get() = stateHolder.drawerState
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow()
    val messages: SnapshotStateList<Message> get() = stateHolder.messages
    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow()
    val isApiCalling: StateFlow<Boolean> get() = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentStreamingAiMessageId.asStateFlow()

    // ▼▼▼ Getter for SnapshotStateMap ▼▼▼
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean> get() = stateHolder.reasoningCompleteMap
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> get() = stateHolder.expandedReasoningStates
    // ▲▲▲ Getter for SnapshotStateMap ▲▲▲

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

        persistenceManager.loadInitialData { initialConfigPresent, initialHistoryPresent ->
            Log.d(
                TAG_APP_VIEW_MODEL,
                "初始数据加载完成。配置存在: $initialConfigPresent, 历史存在: $initialHistoryPresent"
            )
            if (!initialConfigPresent) {
                viewModelScope.launch {
                    Log.i(TAG_APP_VIEW_MODEL, "没有检测到已保存的API配置。")
                }
            }

            // ▼▼▼ 新增：恢复 reasoningCompleteMap 和 messageAnimationStates ▼▼▼
            viewModelScope.launch(Dispatchers.Main.immediate) { // 确保在主线程操作SnapshotStateMap
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Restoring reasoningCompleteMap and animation states for loaded messages..."
                )
                stateHolder.messages.forEach { msg ->
                    val hasContentOrError = msg.contentStarted || msg.isError
                    val hasReasoning = !msg.reasoning.isNullOrBlank()

                    if (msg.sender == Sender.AI && hasReasoning && !this@AppViewModel.isApiCalling.value) { // 或者直接 !isApiCalling.value
                        stateHolder.reasoningCompleteMap[msg.id] = true
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "Set reasoningCompleteMap for AI Msg ${msg.id.take(4)} to true (hasReasoning=true, not streaming)"
                        )
                    } else if (msg.sender == Sender.AI && this@AppViewModel.isApiCalling.value && this@AppViewModel.currentStreamingAiMessageId.value == msg.id) { // 或者直接 isApiCalling.value 和 currentStreamingAiMessageId.value
                        stateHolder.reasoningCompleteMap.remove(msg.id)
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "Msg ${msg.id.take(4)} is currently streaming, reasoningComplete managed by ApiHandler."
                        )
                    }


                    // 恢复动画状态
                    // 如果消息已经有内容，或者有错误，或者有思考过程，就标记动画已播放
                    val animationPlayedCondition =
                        hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                    if (animationPlayedCondition) {
                        stateHolder.messageAnimationStates[msg.id] = true
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "Set messageAnimationStates for Msg ${msg.id.take(4)} to true"
                        )
                    }
                }
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Finished restoring reasoningCompleteMap and animation states after initial load."
                )
            }
            // ▲▲▲ 新增结束 ▲▲▲
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
            Log.d(
                TAG_APP_VIEW_MODEL,
                "ViewModel IO pre-warming of ApiClient, ApiHandler, ConfigManager completed."
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            Log.d(TAG_APP_VIEW_MODEL, "开始后台批量预处理历史消息的 htmlContent...")
            val originalLoadedHistory = stateHolder._historicalConversations.value.toList()

            if (originalLoadedHistory.isNotEmpty()) {
                val processedHistory = originalLoadedHistory.map { conversation ->
                    conversation.map { message ->
                        if (message.sender == Sender.AI && message.text.isNotBlank() && message.htmlContent == null) {
                            Log.d(
                                TAG_APP_VIEW_MODEL,
                                "后台预处理: 为消息 ${message.id.take(4)} 生成 htmlContent"
                            )
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
                        } else {
                            Log.w(
                                TAG_APP_VIEW_MODEL,
                                "后台预处理：历史会话 $i 的消息数量发生变化，跳过修改检测。"
                            )
                        }
                        if (wasModified) break
                    }
                } else {
                    Log.w(TAG_APP_VIEW_MODEL, "后台预处理：历史会话总数发生变化，跳过修改检测。")
                }

                if (wasModified) {
                    Log.d(TAG_APP_VIEW_MODEL, "后台预处理完成，检测到 htmlContent 更新。")
                    withContext(Dispatchers.Main.immediate) {
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

    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false, images: List<SelectedMedia> = emptyList()) {
        val textToActuallySend = messageText.trim()
        if (messageText.isBlank() && images.isEmpty()) {
            showSnackbar("请输入消息内容或选择图片")
            return
        }

        Log.d("AppViewModel", "onSendMessage called with text: '$messageText' and ${images.size} images.")
        images.forEachIndexed { index, media ->
            when(media) {
                is SelectedMedia.FromBitmap -> Log.d("AppViewModel", "Image $index: Bitmap (size: ${media.bitmap.byteCount})")
                is SelectedMedia.FromUri -> Log.d("AppViewModel", "Image $index: Uri (${media.uri})")
            }
        }

        val processedImageUrls: List<String>? = if (images.isNotEmpty()) {
            images.mapNotNull { media ->
                when (media) {
                    is SelectedMedia.FromUri -> media.uri.toString()
                    is SelectedMedia.FromBitmap -> {
                        // 如果直接使用 Bitmap，并且不需要上传，你可能需要另一种方式在 Message 中表示它
                        // 或者，在发送前总是将 Bitmap 保存为临时文件并获取其 URI
                        // 为了演示，我们假设 Bitmap 也能转换为某种可用的 "URL" 或标识符
                        // 注意：直接在Message中持久化Bitmap对象通常不是好主意
                        Log.w(TAG_APP_VIEW_MODEL, "Bitmap for message needs proper handling (e.g., upload or save to get URI)")
                        null // 暂时不处理Bitmap的 "URL"
                    }
                }
            }
        } else {
            null
        }


        val newUserMessage = Message(
            text = textToActuallySend,
            sender = Sender.User,
            contentStarted = true,
            imageUrls = processedImageUrls // <--- 使用处理后的图片URL列表
        )

        
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            showSnackbar("请先选择 API 配置"); return
        }
        val currentWebSearchEnabled = stateHolder._isWebSearchEnabled.value

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            // 在添加新用户消息时，标记其动画已播放
            stateHolder.messageAnimationStates[newUserMessage.id] = true
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
            val modelNameLower = currentConfig.model.lowercase()
            val apiAddressLower = currentConfig.address?.lowercase() ?: ""

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
                    ) && !apiAddressLower.contains("api.openai.com")
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
                useWebSearch = topLevelUseWebSearch,
                customModelParameters = finalCustomModelParameters,
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
                    // 编辑用户消息后，标记其动画已播放 (如果之前未播放)
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
                            isNewMessageSend = true
                        )
                    }
                    // 在移除前，也从状态map中移除相关条目
                    stateHolder.reasoningCompleteMap.remove(aiMessageToRemove.id)
                    stateHolder.expandedReasoningStates.remove(aiMessageToRemove.id)
                    stateHolder.messageAnimationStates.remove(aiMessageToRemove.id)
                    stateHolder.messages.removeAt(currentIndexToInspect)
                } else {
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "regenerateAiResponse: Encountered non-AI message at index $currentIndexToInspect, stopping AI message removal."
                    )
                    break
                }
            }

            val messageToDelete = stateHolder.messages.getOrNull(userMessageIndex)
            if (messageToDelete != null && messageToDelete.id == originalUserMessageId) {
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "regenerateAiResponse: Attempting to remove original user message ID $originalUserMessageId at index $userMessageIndex."
                )
                stateHolder.messageAnimationStates.remove(originalUserMessageId) // 移除动画状态
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
                showSnackbar("重新生成时移除原消息失败，可能导致显示重复。")
            }

            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            Log.d(TAG_APP_VIEW_MODEL, "regenerateAiResponse: Chat history saved after removals.")

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
            historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天（如果需要）
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 这会清空 messages, reasoningCompleteMap, messageAnimationStates 等
                triggerScrollToBottom()
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                // _loadedHistoryIndex.value 已经在 clearForNewChat 中设为 null
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
                stateHolder.clearForNewChat() // 清空当前状态
                stateHolder.messages.addAll(processedConversation)

                // ▼▼▼ 新增：恢复 reasoningCompleteMap 和 messageAnimationStates ▼▼▼
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Restoring reasoningCompleteMap and animation states for history index $index..."
                )
                stateHolder.messages.forEach { msg ->
                    val hasContentOrError = msg.contentStarted || msg.isError
                    val hasReasoning = !msg.reasoning.isNullOrBlank()

                    if (msg.sender == Sender.AI && hasReasoning) {
                        stateHolder.reasoningCompleteMap[msg.id] = true
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "Set reasoningCompleteMap for HIST AI Msg ${msg.id.take(4)} to true"
                        )
                    }

                    val animationPlayedCondition =
                        hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                    if (animationPlayedCondition) {
                        stateHolder.messageAnimationStates[msg.id] = true
                        Log.d(
                            TAG_APP_VIEW_MODEL,
                            "Set messageAnimationStates for HIST Msg ${msg.id.take(4)} to true"
                        )
                    }
                }
                Log.d(TAG_APP_VIEW_MODEL, "Finished restoring states for history index $index.")
                // ▲▲▲ 新增结束 ▲▲▲

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
            // 在删除历史记录前，获取被删除对话的消息ID列表，以便稍后清理状态map
            val idsInDeletedConversation =
                stateHolder._historicalConversations.value.getOrNull(indexToDelete)?.map { it.id }
                    ?: emptyList()

            historyManager.deleteConversation(indexToDelete) // 这会更新 _historicalConversations 和 _loadedHistoryIndex

            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog(); dismissSourcesDialog()
                    stateHolder.clearForNewChat(); triggerScrollToBottom() // 清空当前聊天状态
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            } else {
                // 如果删除的不是当前加载的聊天，但可能需要清理这些已删除消息的状态
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
            historyManager.clearAllHistory() // 这会清空 _historicalConversations 和 _loadedHistoryIndex
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat(); triggerScrollToBottom() // 清空当前聊天状态
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

    // onAnimationComplete 由 MessageBubble 调用，当它认为动画该结束了
    // (例如，非流式AI消息有内容，或流式AI消息开始有内容，或错误消息)
    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (stateHolder.messageAnimationStates[messageId] != true) {
                stateHolder.messageAnimationStates[messageId] = true
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "onAnimationComplete called for Msg $messageId, set to true."
                )
            }
        }
    }

    // hasAnimationBeenPlayed 由 MessageBubble 读取，以决定是否立即显示内容
    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        val played = stateHolder.messageAnimationStates[messageId] ?: false
        // Log.d(TAG_APP_VIEW_MODEL, "hasAnimationBeenPlayed for Msg $messageId: $played") // 可以取消注释进行调试
        return played
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
                    // 重新加载后，恢复动画和思考完成状态
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
        viewModelScope.launch(Dispatchers.Main.immediate) { // 确保 messageIndex 的初始查找在主线程
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageId }
            if (messageIndex != -1) {
                if (currentFullText.isNotBlank()) {
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "onAiMessageFullTextChanged: For message $messageId, text has changed. New full text length: ${currentFullText.length}. Preparing to update HTML."
                    )
                    // 使用后台线程进行耗时的 Markdown 到 HTML 转换
                    launch(Dispatchers.Default) { // <--- 这个协程执行转换
                        val newHtml = try {
                            convertMarkdownToHtml(currentFullText) // <<< 这是之前发生崩溃的地方
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

                        // 确保在更新UI前回主线程
                        withContext(Dispatchers.Main.immediate) {
                            val freshMessageIndex =
                                stateHolder.messages.indexOfFirst { it.id == messageId } // 再次获取索引，因为列表可能已改变
                            if (freshMessageIndex != -1) {
                                val messageToUpdate = stateHolder.messages[freshMessageIndex]
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
                                        "onAiMessageFullTextChanged: Generated HTML for $messageId is identical to existing, no UI update for htmlContent."
                                    )
                                }
                            }
                        }
                    }
                } else { // currentFullText 为空
                    val messageToUpdate = stateHolder.messages[messageIndex]
                    if (messageToUpdate.htmlContent != null) { // 如果之前有HTML内容，现在清空
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
            runBlocking(Dispatchers.IO) {
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: runBlocking(Dispatchers.IO) started for final save."
                )

                // 1. 首先，处理历史记录的保存。
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: saveCurrentChatToHistoryIfNeeded(forceSave=true) completed. (This might have cleared last open chat)"
                )

                // 2. 然后，（再次）保存当前聊天作为"最后打开的聊天"。
                persistenceManager.saveLastOpenChat(finalCurrentChatMessages)
                Log.i(
                    TAG_APP_VIEW_MODEL,
                    "onCleared: Saved last open chat (${finalCurrentChatMessages.size} messages) *after* history save."
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