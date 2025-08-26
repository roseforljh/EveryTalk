package com.example.everytalk.statecontroller

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.collection.LruCache
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.GithubRelease
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.data.DataClass.WebSearchResult
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.example.everytalk.ui.screens.viewmodel.ConfigManager
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import com.example.everytalk.util.VersionChecker
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppViewModel(application: Application, private val dataSource: SharedPreferencesDataSource) :
        AndroidViewModel(application) {

    @Keep
    @Serializable
    private data class ExportedSettings(
            val apiConfigs: List<ApiConfig>,
            val customProviders: Set<String> = emptySet()
    )

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val messagesMutex = Mutex()
    private val conversationPreviewCache = LruCache<Int, String>(100)
    private val textUpdateDebouncer = mutableMapOf<String, Job>()
    internal val stateHolder = ViewModelStateHolder()
    private val imageLoader = ImageLoader.Builder(application.applicationContext).build()
    private val persistenceManager =
            DataPersistenceManager(
                    application.applicationContext,
                    dataSource,
                    stateHolder,
                    viewModelScope,
                    imageLoader
             )

    private val historyManager: HistoryManager =
            HistoryManager(
                    stateHolder,
                    persistenceManager,
                    ::areMessageListsEffectivelyEqual,
                    onHistoryModified = { conversationPreviewCache.evictAll() }
            )

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
                stateHolder,
                viewModelScope,
                historyManager,
                ::onAiMessageFullTextChanged,
                ::triggerScrollToBottom
        )
    }
    private val configManager: ConfigManager by lazy {
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }

    private val messageSender: MessageSender by lazy {
        MessageSender(
                application = getApplication(),
                viewModelScope = viewModelScope,
                stateHolder = stateHolder,
                apiHandler = apiHandler,
                historyManager = historyManager,
                showSnackbar = ::showSnackbar,
                triggerScrollToBottom = { triggerScrollToBottom() }
        )
    }

    private val _markdownChunkToAppendFlow =
            MutableSharedFlow<Pair<String, Pair<String, String>>>(
                    replay = 0,
                    extraBufferCapacity = 128
            )
    @Suppress("unused")
    val markdownChunkToAppendFlow: SharedFlow<Pair<String, Pair<String, String>>> =
             _markdownChunkToAppendFlow.asSharedFlow()

    val drawerState: DrawerState
        get() = stateHolder.drawerState
    val text: StateFlow<String>
        get() = stateHolder._text.asStateFlow()
    val messages: SnapshotStateList<Message>
        get() = stateHolder.messages
    val historicalConversations: StateFlow<List<List<Message>>>
        get() = stateHolder._historicalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?>
        get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val isLoadingHistory: StateFlow<Boolean>
        get() = stateHolder._isLoadingHistory.asStateFlow()
    val isLoadingHistoryData: StateFlow<Boolean>
        get() = stateHolder._isLoadingHistoryData.asStateFlow()
    val currentConversationId: StateFlow<String>
        get() = stateHolder._currentConversationId.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>>
        get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?>
        get() = stateHolder._selectedApiConfig.asStateFlow()
    val isApiCalling: StateFlow<Boolean>
        get() = stateHolder._isApiCalling.asStateFlow()
    val currentStreamingAiMessageId: StateFlow<String?>
        get() = stateHolder._currentStreamingAiMessageId.asStateFlow()
    val reasoningCompleteMap: SnapshotStateMap<String, Boolean>
        get() = stateHolder.reasoningCompleteMap
    @Suppress("unused")
    val expandedReasoningStates: SnapshotStateMap<String, Boolean>
        get() = stateHolder.expandedReasoningStates
    val snackbarMessage: SharedFlow<String>
        get() = stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit>
        get() = stateHolder._scrollToBottomEvent.asSharedFlow()
    val selectedMediaItems: SnapshotStateList<SelectedMediaItem>
        get() = stateHolder.selectedMediaItems

    private val _exportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val exportRequest: Flow<Pair<String, String>> = _exportRequest.receiveAsFlow()

    private val _settingsExportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val settingsExportRequest: Flow<Pair<String, String>> = _settingsExportRequest.receiveAsFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editDialogInputText: StateFlow<String>
        get() = stateHolder._editDialogInputText.asStateFlow()
    private val _isSearchActiveInDrawer = MutableStateFlow(false)
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()
    private val _searchQueryInDrawer = MutableStateFlow("")
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()

    private val predefinedPlatformsList =
            listOf("openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索", "OpenRouter")

    private val _customProviders = MutableStateFlow<Set<String>>(emptySet())
    val customProviders: StateFlow<Set<String>> = _customProviders.asStateFlow()

    val allProviders: StateFlow<List<String>> = combine(
        _customProviders
    ) { customProvidersArray ->
        predefinedPlatformsList + customProvidersArray[0].toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = predefinedPlatformsList
    )
    val isWebSearchEnabled: StateFlow<Boolean>
        get() = stateHolder._isWebSearchEnabled.asStateFlow()
    val showSourcesDialog: StateFlow<Boolean>
        get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>>
        get() = stateHolder._sourcesForDialog.asStateFlow()

    private val _showSelectableTextDialog = MutableStateFlow(false)
    val showSelectableTextDialog: StateFlow<Boolean> = _showSelectableTextDialog.asStateFlow()
    private val _textForSelectionDialog = MutableStateFlow("")
    val textForSelectionDialog: StateFlow<String> = _textForSelectionDialog.asStateFlow()

   private val _showAboutDialog = MutableStateFlow(false)
   val showAboutDialog: StateFlow<Boolean> = _showAboutDialog.asStateFlow()

   private val _latestReleaseInfo = MutableStateFlow<GithubRelease?>(null)
   val latestReleaseInfo: StateFlow<GithubRelease?> = _latestReleaseInfo.asStateFlow()

     val chatListItems: StateFlow<List<ChatListItem>> =
             combine(
                             snapshotFlow { messages.toList() },
                            isApiCalling,
                            currentStreamingAiMessageId
                    ) { messages, isApiCalling, currentStreamingAiMessageId ->
                        messages
                                .map { message ->
                                    when (message.sender) {
                                        Sender.AI -> {
                                            createAiMessageItems(
                                                    message,
                                                    isApiCalling,
                                                    currentStreamingAiMessageId
                                            )
                                        }
                                        else -> createOtherMessageItems(message)
                                    }
                                }
                                .flatten()
                    }
                    .flowOn(Dispatchers.Default)
                    .stateIn(
                            scope = viewModelScope,
                            started = SharingStarted.WhileSubscribed(5000),
                            initialValue = emptyList()
                    )
    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()

    init {
        // 加载自定义提供商
        viewModelScope.launch(Dispatchers.IO) {
            val loadedCustomProviders = dataSource.loadCustomProviders()
            _customProviders.value = loadedCustomProviders
        }

        // 优化：分阶段初始化，优先加载关键配置
        persistenceManager.loadInitialData(loadLastChat = false) {
                initialConfigPresent,
                initialHistoryPresent ->
            if (!initialConfigPresent) {
                viewModelScope.launch {
                    // 如果没有配置，可以显示引导界面
                }
            }

            // 历史数据加载完成后的处理
            if (initialHistoryPresent) {
                Log.d("AppViewModel", "历史数据已加载，共 ${stateHolder._historicalConversations.value.size} 条对话")
            }
        }

        // 延迟初始化非关键组件
        viewModelScope.launch(Dispatchers.IO) {
            // 确保API配置加载完成后再初始化这些组件
            delay(100) // 给UI一些时间渲染
            apiHandler
            configManager
            messageSender
        }

        // 清理任务
        viewModelScope.launch {
            while (isActive) {
                delay(30_000) // 每 30 秒
                textUpdateDebouncer.entries.removeIf { !it.value.isActive }
            }
        }
    }
 
    fun showAboutDialog() {
        _showAboutDialog.value = true
    }

    fun dismissAboutDialog() {
        _showAboutDialog.value = false
    }

     fun checkForUpdates() {
         viewModelScope.launch(Dispatchers.IO) {
             try {
                 val latestRelease = ApiClient.getLatestRelease()
                 val currentVersion = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName
                if (currentVersion != null && VersionChecker.isNewVersionAvailable(currentVersion, latestRelease.tagName)) {
                     _latestReleaseInfo.value = latestRelease
                } else {
                    withContext(Dispatchers.Main) {
                        showSnackbar("当前已是最新版本")
                    }
                 }
             } catch (e: Exception) {
                 Log.e("AppViewModel", "Failed to check for updates", e)
                withContext(Dispatchers.Main) {
                    showSnackbar("检查更新失败: ${e.message}")
                }
             }
         }
     }

    fun clearUpdateInfo() {
        _latestReleaseInfo.value = null
    }

     private fun createAiMessageItems(
             message: Message,
            isApiCalling: Boolean,
            currentStreamingAiMessageId: String?
    ): List<ChatListItem> {
        val showLoading =
                isApiCalling &&
                        message.id == currentStreamingAiMessageId &&
                        message.text.isBlank() &&
                        message.reasoning.isNullOrBlank() &&
                        !message.contentStarted

        if (showLoading) {
            return listOf(ChatListItem.LoadingIndicator(message.id))
        }

        val reasoningItem =
                if (!message.reasoning.isNullOrBlank()) {
                    listOf(ChatListItem.AiMessageReasoning(message))
                } else {
                    emptyList()
                }

        val hasReasoning = reasoningItem.isNotEmpty()
        
        // 简化处理：直接使用消息文本，不进行复杂的 Markdown 解析
        val messageItem = if (message.text.isNotBlank()) {
            when (message.outputType) {
                "math" -> listOf(ChatListItem.AiMessageMath(message.id, message.text, hasReasoning))
                "code" -> listOf(ChatListItem.AiMessageCode(message.id, message.text, hasReasoning))
                // "json" an so on
                else -> listOf(ChatListItem.AiMessage(message.id, message.text, hasReasoning))
            }
        } else {
            emptyList()
        }

        val footerItem =
                if (!message.webSearchResults.isNullOrEmpty() &&
                                !(isApiCalling && message.id == currentStreamingAiMessageId)
                ) {
                    listOf(ChatListItem.AiMessageFooter(message))
                } else {
                    emptyList()
                }

        return reasoningItem + messageItem + footerItem
    }

    private fun createOtherMessageItems(message: Message): List<ChatListItem> {
        return when {
            message.sender == Sender.User ->
                    listOf(
                            ChatListItem.UserMessage(
                                    messageId = message.id,
                                    text = message.text,
                                    attachments = message.attachments
                            )
                    )
            message.isError ->
                    listOf(ChatListItem.ErrorMessage(messageId = message.id, text = message.text))
            else -> emptyList()
        }
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
            if (msg1.id != msg2.id ||
                            msg1.sender != msg2.sender ||
                            msg1.text.trim() != msg2.text.trim() ||
                            msg1.reasoning?.trim() != msg2.reasoning?.trim() ||
                            msg1.isError != msg2.isError
            )
                    return false
        }
        return true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter
                .filter { msg ->
                    (msg.sender != Sender.System || msg.isPlaceholderName) &&
                            (msg.sender == Sender.User ||
                                    (msg.sender == Sender.AI &&
                                            (msg.contentStarted ||
                                                    msg.text.isNotBlank() ||
                                                    !msg.reasoning.isNullOrBlank())) ||
                                    (msg.sender == Sender.System && msg.isPlaceholderName) ||
                                    msg.isError)
                }
                .toList()
    }

    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
    }


    fun showSnackbar(message: String) {
        viewModelScope.launch { stateHolder._snackbarMessage.emit(message) }
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

    fun onSendMessage(
            messageText: String,
            isFromRegeneration: Boolean = false,
            attachments: List<SelectedMediaItem> = emptyList(),
            audioBase64: String? = null,
            mimeType: String? = null
    ) {
        messageSender.sendMessage(
                messageText,
                isFromRegeneration,
                attachments,
                audioBase64 = audioBase64,
                mimeType = mimeType
        )
    }

    fun addMediaItem(item: SelectedMediaItem) {
        stateHolder.selectedMediaItems.add(item)
    }

    fun removeMediaItemAtIndex(index: Int) {
        if (index >= 0 && index < stateHolder.selectedMediaItems.size) {
            stateHolder.selectedMediaItems.removeAt(index)
        }
    }

    fun clearMediaItems() {
        stateHolder.clearSelectedMedia()
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
            var needsHistorySave = false
            messagesMutex.withLock {
                val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
                if (messageIndex != -1) {
                    val originalMessage = stateHolder.messages[messageIndex]
                    if (originalMessage.text != updatedText) {
                        val updatedMessage =
                                originalMessage.copy(
                                        text = updatedText,
                                        timestamp = System.currentTimeMillis()
                                )
                        stateHolder.messages[messageIndex] = updatedMessage
                        if (stateHolder.messageAnimationStates[updatedMessage.id] != true) {
                            stateHolder.messageAnimationStates[updatedMessage.id] = true
                        }
                        needsHistorySave = true
                    }
                }
            }
            if (needsHistorySave) {
                viewModelScope.launch(Dispatchers.IO) { historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) }
            }
            withContext(Dispatchers.Main.immediate) { dismissEditDialog() }
        }
    }

    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        stateHolder._editDialogInputText.value = ""
    }

    fun regenerateAiResponse(message: Message) {
        val messageToRegenerateFrom =
                if (message.sender == Sender.AI) {
                    val aiMessageIndex = messages.indexOf(message)
                    if (aiMessageIndex > 0) {
                        messages.subList(0, aiMessageIndex).findLast { it.sender == Sender.User }
                    } else {
                        null
                    }
                } else {
                    message
                }

        if (messageToRegenerateFrom == null || messageToRegenerateFrom.sender != Sender.User) {
            showSnackbar("无法找到对应的用户消息来重新生成回答")
            return
        }

        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置")
            return
        }

        val originalUserMessageText = messageToRegenerateFrom.text
        val originalUserMessageId = messageToRegenerateFrom.id

        val originalAttachments =
                messageToRegenerateFrom.attachments.mapNotNull {
                    // We need to create new instances with new UUIDs because the underlying
                    // LazyColumn uses the ID as a key.
                    // If we reuse the same ID, Compose might not recompose the item correctly.
                    when (it) {
                        is SelectedMediaItem.ImageFromUri ->
                            it.copy(id = UUID.randomUUID().toString())
                        is SelectedMediaItem.GenericFile ->
                            it.copy(id = UUID.randomUUID().toString())
                        is SelectedMediaItem.ImageFromBitmap ->
                            it.copy(id = UUID.randomUUID().toString())
                        is SelectedMediaItem.Audio ->
                            it.copy(id = UUID.randomUUID().toString())
                    }
                }
                        ?: emptyList()

        viewModelScope.launch {
            val success =
                    withContext(Dispatchers.Default) {
                        val userMessageIndex =
                                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
                        if (userMessageIndex == -1) {
                            withContext(Dispatchers.Main) {
                                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。")
                            }
                            return@withContext false
                        }

                        val messagesToRemove = mutableListOf<Message>()
                        var currentIndexToInspect = userMessageIndex + 1
                        while (currentIndexToInspect < stateHolder.messages.size) {
                            val message = stateHolder.messages[currentIndexToInspect]
                            if (message.sender == Sender.AI) {
                                messagesToRemove.add(message)
                                currentIndexToInspect++
                            } else {
                                break
                            }
                        }

                        messagesMutex.withLock {
                            withContext(Dispatchers.Main.immediate) {
                                val idsToRemove = messagesToRemove.map { it.id }.toSet()
                                idsToRemove.forEach { id ->
                                    if (stateHolder._currentStreamingAiMessageId.value == id) {
                                        apiHandler.cancelCurrentApiJob(
                                                "为消息 '${originalUserMessageId.take(4)}' 重新生成回答，取消旧AI流",
                                                isNewMessageSend = true
                                        )
                                    }
                                }
                                stateHolder.reasoningCompleteMap.keys.removeAll(idsToRemove)
                                stateHolder.expandedReasoningStates.keys.removeAll(idsToRemove)
                                stateHolder.messageAnimationStates.keys.removeAll(idsToRemove)

                                stateHolder.messages.removeAll(messagesToRemove.toSet())

                                val finalUserMessageIndex =
                                        stateHolder.messages.indexOfFirst {
                                            it.id == originalUserMessageId
                                        }
                                if (finalUserMessageIndex != -1) {
                                    stateHolder.messageAnimationStates.remove(originalUserMessageId)
                                    stateHolder.messages.removeAt(finalUserMessageIndex)
                                }
                            }
                        }
                        true
                    }

            if (success) {
                viewModelScope.launch(Dispatchers.IO) { historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) }
                onSendMessage(
                        messageText = originalUserMessageText,
                        isFromRegeneration = true,
                        attachments = originalAttachments
                )
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
        }
    }

    fun triggerScrollToBottom() {
        viewModelScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyManager.saveCurrentChatToHistoryIfNeeded() }
            messagesMutex.withLock {
                stateHolder.clearForNewChat()
                // Ensure new chats get a unique ID from the start.
                stateHolder._currentConversationId.value = "chat_${UUID.randomUUID()}"
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")
        viewModelScope.launch {
            stateHolder._isLoadingHistory.value = true
            withContext(Dispatchers.IO) { historyManager.saveCurrentChatToHistoryIfNeeded() }

            val conversationList = stateHolder._historicalConversations.value
            if (index < 0 || index >= conversationList.size) {
                showSnackbar("无法加载对话：无效的索引")
                stateHolder._isLoadingHistory.value = false
                return@launch
            }
            val conversationToLoad = conversationList[index]
            val stableId = conversationToLoad.firstOrNull()?.id ?: "history_${UUID.randomUUID()}"
            stateHolder._currentConversationId.value = stableId
            yield() // Allow UI to recompose with new ID and loading state before proceeding

            val (processedConversation, newReasoningMap, newAnimationStates) =
                    withContext(Dispatchers.Default) {
                        val newReasoning = mutableMapOf<String, Boolean>()
                        val newAnimation = mutableMapOf<String, Boolean>()

                        val processed =
                                conversationToLoad.map { msg: Message ->
                                    val updatedContentStarted =
                                            msg.text.isNotBlank() ||
                                                    !msg.reasoning.isNullOrBlank() ||
                                                    msg.isError
                                    msg.copy(contentStarted = updatedContentStarted)
                                }

                        processed.forEach { msg ->
                            val hasContentOrError = msg.contentStarted || msg.isError
                            val hasReasoning = !msg.reasoning.isNullOrBlank()
                            if (msg.sender == Sender.AI && hasReasoning) {
                                newReasoning[msg.id] = true
                            }
                            val animationPlayedCondition =
                                    hasContentOrError || (msg.sender == Sender.AI && hasReasoning)
                            if (animationPlayedCondition) {
                                newAnimation[msg.id] = true
                            }
                        }
                        Triple(processed, newReasoning, newAnimation)
                    }

            messagesMutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(processedConversation)
                    stateHolder.reasoningCompleteMap.clear()
                    stateHolder.reasoningCompleteMap.putAll(newReasoningMap)
                    stateHolder.messageAnimationStates.clear()
                    stateHolder.messageAnimationStates.putAll(newAnimationStates)

                    stateHolder._loadedHistoryIndex.value = index
                }
            }

            if (_isSearchActiveInDrawer.value)
                    withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
            // Crucially, set loading to false AFTER all state has been updated.
            stateHolder._isLoadingHistory.value = false
        }
    }

    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        val historicalConversations = stateHolder._historicalConversations.value
        if (indexToDelete < 0 || indexToDelete >= historicalConversations.size) {
            showSnackbar("无法删除：无效的索引")
            return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            val idsInDeletedConversation =
                    historicalConversations.getOrNull(indexToDelete)?.map { it.id } ?: emptyList()

            // HistoryManager.deleteConversation 已经包含了媒体文件清理逻辑
            withContext(Dispatchers.IO) { historyManager.deleteConversation(indexToDelete) }

            if (wasCurrentChatDeleted) {
                messagesMutex.withLock {
                    dismissEditDialog()
                    dismissSourcesDialog()
                    stateHolder.clearForNewChat()
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            } else {
                val idsToRemove = idsInDeletedConversation.toSet()
                stateHolder.reasoningCompleteMap.keys.removeAll(idsToRemove)
                stateHolder.expandedReasoningStates.keys.removeAll(idsToRemove)
                stateHolder.messageAnimationStates.keys.removeAll(idsToRemove)
            }
            showSnackbar("对话已删除")
            conversationPreviewCache.evictAll()
        }
    }

    fun clearAllConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            // HistoryManager.clearAllHistory 已经包含了媒体文件清理逻辑
            withContext(Dispatchers.IO) { historyManager.clearAllHistory() }

            messagesMutex.withLock {
                stateHolder.clearForNewChat()
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
            showSnackbar("所有对话已清除")
            conversationPreviewCache.evictAll()
        }
    }

    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources
            stateHolder._showSourcesDialog.value = true
        }
    }

    fun dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) stateHolder._showSourcesDialog.value = false
        }
    }

    fun showSelectableTextDialog(text: String) {
        _textForSelectionDialog.value = text
        _showSelectableTextDialog.value = true
    }

    fun dismissSelectableTextDialog() {
        _showSelectableTextDialog.value = false
        _textForSelectionDialog.value = ""
    }

    fun copyToClipboard(text: String) {
        val clipboard =
                getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as
                        ClipboardManager
        val clip = android.content.ClipData.newPlainText("Copied Text", text)
        clipboard.setPrimaryClip(clip)
        showSnackbar("已复制到剪贴板")
    }

    fun exportMessageText(text: String) {
        viewModelScope.launch {
            val fileName = "conversation_export.md"
            _exportRequest.send(fileName to text)
        }
    }


    fun addConfig(config: ApiConfig) = configManager.addConfig(config)

    fun addMultipleConfigs(configs: List<ApiConfig>) {
        viewModelScope.launch {
            val distinctConfigs = configs.distinctBy { it.model }
            distinctConfigs.forEach { config ->
                configManager.addConfig(config)
            }
        }
    }
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun deleteConfigGroup(
            apiKey: String,
            modalityType: com.example.everytalk.data.DataClass.ModalityType
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val originalConfigs = stateHolder._apiConfigs.value
            val configsToKeep = originalConfigs.filterNot {
                it.key == apiKey && it.modalityType == modalityType
            }

            if (originalConfigs.size != configsToKeep.size) {
                stateHolder._apiConfigs.value = configsToKeep
                persistenceManager.saveApiConfigs(configsToKeep)
            }
        }
    }
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)
    fun clearSelectedConfig() {
        stateHolder._selectedApiConfig.value = null
        viewModelScope.launch(Dispatchers.IO) { persistenceManager.saveSelectedConfigIdentifier(null) }
    }

    fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.saveApiConfigs(stateHolder._apiConfigs.value)
        }
    }

    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank() && !predefinedPlatformsList.contains(trimmedName)) {
            val currentCustomProviders = _customProviders.value
            if (!currentCustomProviders.contains(trimmedName)) {
                _customProviders.value = currentCustomProviders + trimmedName
                viewModelScope.launch(Dispatchers.IO) {
                    dataSource.saveCustomProviders(_customProviders.value)
                }
            }
        }
    }

    fun deleteProvider(providerName: String) {
        val currentCustomProviders = _customProviders.value
        if (currentCustomProviders.contains(providerName)) {
            // 删除使用此提供商的所有配置
            val configsToDelete = stateHolder._apiConfigs.value.filter { it.provider == providerName }
            configsToDelete.forEach { config ->
                configManager.deleteConfig(config)
            }
            
            // 从自定义提供商列表中移除
            _customProviders.value = currentCustomProviders - providerName
            viewModelScope.launch(Dispatchers.IO) {
                dataSource.saveCustomProviders(_customProviders.value)
            }
        }
    }

    fun updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedAddress = newAddress.trim()
            val trimmedKey = newKey.trim()

            val originalKey = representativeConfig.key
            val modality = representativeConfig.modalityType

            val currentConfigs = stateHolder._apiConfigs.value
            val newConfigs =
                    currentConfigs.map { config ->
                        if (config.key == originalKey && config.modalityType == modality) {
                            config.copy(address = trimmedAddress, key = trimmedKey)
                        } else {
                            config
                        }
                    }
            if (currentConfigs != newConfigs) {
                stateHolder._apiConfigs.value = newConfigs
                persistenceManager.saveApiConfigs(newConfigs)

                val currentSelectedConfig = stateHolder._selectedApiConfig.value
                if (currentSelectedConfig != null &&
                                currentSelectedConfig.key == originalKey &&
                                currentSelectedConfig.modalityType == modality
                ) {
                    val newSelectedConfig =
                            currentSelectedConfig.copy(address = trimmedAddress, key = trimmedKey)
                    stateHolder._selectedApiConfig.value = newSelectedConfig
                }

                withContext(Dispatchers.Main) { showSnackbar("配置已更新") }
            }
        }
    }

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
        val cachedPreview = conversationPreviewCache.get(index)
        if (cachedPreview != null) return cachedPreview

        val conversation =
                stateHolder._historicalConversations.value.getOrNull(index)
                        ?: return "对话 ${index + 1}".also {
                            conversationPreviewCache.put(index, it)
                        }

        val newPreview =
                (conversation
                        .firstOrNull {
                            it.sender == Sender.System &&
                                    it.isPlaceholderName &&
                                    it.text.isNotBlank()
                        }
                        ?.text
                        ?.trim()
                        ?: conversation
                                .firstOrNull {
                                    it.sender == Sender.User && it.text.isNotBlank()
                                }
                                ?.text
                                ?.trim()
                                ?: conversation
                                .firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }
                                ?.text
                                ?.trim()
                                ?: "对话 ${index + 1}")

        conversationPreviewCache.put(index, newPreview)
        return newPreview
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空")
            return
        }
        viewModelScope.launch {
            val success =
                    withContext(Dispatchers.Default) {
                        val currentHistoricalConvos = stateHolder._historicalConversations.value
                        if (index < 0 || index >= currentHistoricalConvos.size) {
                            withContext(Dispatchers.Main) { showSnackbar("无法重命名：对话索引错误") }
                            return@withContext false
                        }

                        val originalConversationAtIndex =
                                currentHistoricalConvos[index].toMutableList()
                        var titleMessageUpdatedOrAdded = false
                        val existingTitleIndex =
                                originalConversationAtIndex.indexOfFirst {
                                    it.sender == Sender.System && it.isPlaceholderName
                                }

                        if (existingTitleIndex != -1) {
                            originalConversationAtIndex[existingTitleIndex] =
                                    originalConversationAtIndex[existingTitleIndex].copy(
                                            text = trimmedNewName,
                                            timestamp = System.currentTimeMillis()
                                    )
                            titleMessageUpdatedOrAdded = true
                        }

                        if (!titleMessageUpdatedOrAdded) {
                            val titleMessage =
                                    Message(
                                            id = "title_${UUID.randomUUID()}",
                                            text = trimmedNewName,
                                            sender = Sender.System,
                                            timestamp = System.currentTimeMillis() - 1,
                                            contentStarted = true,
                                            isPlaceholderName = true
                                    )
                            originalConversationAtIndex.add(0, titleMessage)
                        }

                        val updatedHistoricalConversationsList =
                                currentHistoricalConvos.toMutableList().apply {
                                    this[index] = originalConversationAtIndex.toList()
                                }

                        withContext(Dispatchers.Main.immediate) {
                            stateHolder._historicalConversations.value =
                                    updatedHistoricalConversationsList.toList()
                        }

                        withContext(Dispatchers.IO) {
                            persistenceManager.saveChatHistory(
                                    stateHolder._historicalConversations.value
                            )
                        }

                        if (stateHolder._loadedHistoryIndex.value == index) {
                            val reloadedConversation =
                                    originalConversationAtIndex.toList().map { msg ->
                                        val updatedContentStarted =
                                                msg.text.isNotBlank() ||
                                                        !msg.reasoning.isNullOrBlank() ||
                                                        msg.isError
                                        msg.copy(contentStarted = updatedContentStarted)
                                    }
                            messagesMutex.withLock {
                                withContext(Dispatchers.Main.immediate) {
                                    stateHolder.messages.clear()
                                    stateHolder.messages.addAll(reloadedConversation)
                                    reloadedConversation.forEach { msg ->
                                        val hasContentOrError = msg.contentStarted || msg.isError
                                        val hasReasoning = !msg.reasoning.isNullOrBlank()
                                        if (msg.sender == Sender.AI && hasReasoning) {
                                            stateHolder.reasoningCompleteMap[msg.id] = true
                                        }
                                        val animationPlayedCondition =
                                                hasContentOrError ||
                                                        (msg.sender == Sender.AI && hasReasoning)
                                        if (animationPlayedCondition) {
                                            stateHolder.messageAnimationStates[msg.id] = true
                                        }
                                    }
                                }
                            }
                        }
                        true
                    }
            if (success) {
                withContext(Dispatchers.Main) { showSnackbar("对话已重命名") }
                conversationPreviewCache.put(index, trimmedNewName)
            }
        }
    }

    private fun onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        textUpdateDebouncer[messageId]?.cancel()
        textUpdateDebouncer[messageId] =
                viewModelScope.launch {
                    delay(120)
                    messagesMutex.withLock {
                        val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageId }
                        if (messageIndex != -1) {
                            val messageToUpdate = stateHolder.messages[messageIndex]
                            if (messageToUpdate.text != currentFullText) {
                                stateHolder.messages[messageIndex] =
                                        messageToUpdate.copy(text = currentFullText)

                                if (stateHolder.shouldAutoScroll()) {
                                    triggerScrollToBottom()
                                }
                            }
                        }
                    }
                    textUpdateDebouncer.remove(messageId)
                }
    }

    fun exportSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val settingsToExport =
                    ExportedSettings(
                            apiConfigs = stateHolder._apiConfigs.value
                    )
            val finalJson = json.encodeToString(settingsToExport)
            _settingsExportRequest.send("eztalk_settings" to finalJson)
        }
    }

    fun importSettings(jsonContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Try parsing the new format first
                try {
                    val parsedNewSettings = json.decodeFromString<ExportedSettings>(jsonContent)
                    if (parsedNewSettings.apiConfigs.none {
                                it.id.isBlank() || it.provider.isBlank()
                            }
                    ) {
                        stateHolder._apiConfigs.value = parsedNewSettings.apiConfigs
                        _customProviders.value = parsedNewSettings.customProviders
                        val firstConfig = parsedNewSettings.apiConfigs.firstOrNull()
                        stateHolder._selectedApiConfig.value = firstConfig

                        persistenceManager.saveApiConfigs(parsedNewSettings.apiConfigs)
                        dataSource.saveCustomProviders(parsedNewSettings.customProviders)
                        persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)

                        withContext(Dispatchers.Main) { showSnackbar("配置已成功导入") }
                        return@launch
                    }
                } catch (e: Exception) {
                    // Fall through to try the old format
                }

                // Try parsing the old format (List<ApiConfig>)
                try {
                    val parsedOldConfigs = json.decodeFromString<List<ApiConfig>>(jsonContent)
                    if (parsedOldConfigs.none { it.id.isBlank() || it.provider.isBlank() }) {
                        stateHolder._apiConfigs.value = parsedOldConfigs
                        _customProviders.value = emptySet() // Old format has no custom providers
                        val firstConfig = parsedOldConfigs.firstOrNull()
                        stateHolder._selectedApiConfig.value = firstConfig

                        persistenceManager.saveApiConfigs(parsedOldConfigs)
                        dataSource.saveCustomProviders(emptySet())
                        persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)

                        withContext(Dispatchers.Main) { showSnackbar("旧版配置已成功导入") }
                        return@launch
                    }
                } catch (e: Exception) {
                    // Fall through to the final error
                }

                // If both fail, show error
                throw IllegalStateException("JSON content does not match any known valid format.")
            } catch (e: Exception) {
                Log.e("AppViewModel", "Settings import failed", e)
                withContext(Dispatchers.Main) { showSnackbar("导入失败: 文件内容或格式无效") }
            }
        }
    }

    fun fetchModels(apiUrl: String, apiKey: String) {
        viewModelScope.launch {
            _isFetchingModels.value = true
            _fetchedModels.value = emptyList()
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(apiUrl, apiKey)
                }
                _fetchedModels.value = models
                withContext(Dispatchers.Main) { showSnackbar("获取到 ${models.size} 个模型") }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Failed to fetch models", e)
                withContext(Dispatchers.Main) { showSnackbar("获取模型失败: ${e.message}") }
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun clearFetchedModels() {
        _fetchedModels.value = emptyList()
        _isFetchingModels.value = false
    }

    fun createMultipleConfigs(provider: String, address: String, key: String, modelNames: List<String>) {
        if (modelNames.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }
        
        viewModelScope.launch {
            val successfulConfigs = mutableListOf<String>()
            val failedConfigs = mutableListOf<String>()
            
            modelNames.forEach { modelName ->
                try {
                    val config = ApiConfig(
                        address = address.trim(),
                        key = key.trim(),
                        model = modelName,
                        provider = provider,
                        name = modelName, // 使用模型名作为配置名
                        id = java.util.UUID.randomUUID().toString(),
                        isValid = true,
                        modalityType = com.example.everytalk.data.DataClass.ModalityType.TEXT
                    )
                    configManager.addConfig(config)
                    successfulConfigs.add(modelName)
                } catch (e: Exception) {
                    Log.e("AppViewModel", "Failed to create config for model: $modelName", e)
                    failedConfigs.add(modelName)
                }
            }
            
            // 显示创建结果
            if (successfulConfigs.isNotEmpty()) {
                showSnackbar("成功创建 ${successfulConfigs.size} 个配置")
            }
            if (failedConfigs.isNotEmpty()) {
                showSnackbar("${failedConfigs.size} 个配置创建失败")
            }
        }
    }

    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String) {
        viewModelScope.launch {
            // 1. 创建一个临时的配置以立即更新UI
            val tempId = UUID.randomUUID().toString()
            val tempConfig = ApiConfig(
                id = tempId,
                name = "正在获取模型...",
                provider = provider,
                address = address,
                key = key,
                model = "temp_model_placeholder",
                modalityType = com.example.everytalk.data.DataClass.ModalityType.TEXT,
                channel = channel
            )
            configManager.addConfig(tempConfig)

            // 2. 在后台获取模型
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(address, key)
                }
                
                // 3. 删除临时配置
                configManager.deleteConfig(tempConfig)

                // 4. 添加获取到的新配置
                if (models.isNotEmpty()) {
                    val newConfigs = models.map { modelName ->
                        ApiConfig(
                            address = address.trim(),
                            key = key.trim(),
                            model = modelName,
                            provider = provider,
                            name = modelName,
                            id = UUID.randomUUID().toString(),
                            isValid = true,
                            modalityType = com.example.everytalk.data.DataClass.ModalityType.TEXT,
                            channel = channel
                        )
                    }
                    addMultipleConfigs(newConfigs)
                } else {
                     // 如果没有获取到模型，仍然创建一个空的占位配置
                    val placeholderConfig = tempConfig.copy(
                        id = UUID.randomUUID().toString(),
                        name = provider,
                        model = "",
                        channel = channel
                    )
                    configManager.addConfig(placeholderConfig)
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "获取模型失败", e)
                // 获取失败，更新临时配置以提示用户
                 val errorConfig = tempConfig.copy(
                    name = provider,
                    model = "",
                    channel = channel
                )
                configManager.updateConfig(errorConfig)
            }
        }
    }

    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String) {
        viewModelScope.launch {
            val newConfig = ApiConfig(
                id = UUID.randomUUID().toString(),
                name = modelName,
                provider = provider,
                address = address,
                key = apiKey,
                model = modelName,
                modalityType = com.example.everytalk.data.DataClass.ModalityType.TEXT
            )
            configManager.addConfig(newConfig)
        }
    }

    fun getMessageById(id: String): Message? {
        return messages.find { it.id == id }
    }

    fun saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        if (scrollState.firstVisibleItemIndex >= 0) {
            stateHolder.conversationScrollStates[conversationId] = scrollState
        }
    }

    fun appendReasoningToMessage(messageId: String, text: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendReasoningToMessage(messageId, text)
        }
    }

    fun appendContentToMessage(messageId: String, text: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendContentToMessage(messageId, text)
            onAiMessageFullTextChanged(messageId, stateHolder.messages.find { it.id == messageId }?.text ?: "")
        }
    }

    fun getScrollState(conversationId: String): ConversationScrollState? {
        return stateHolder.conversationScrollStates[conversationId]
    }
    override fun onCleared() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared", isNewMessageSend = false)

        val finalApiConfigs = stateHolder._apiConfigs.value.toList()
        val finalSelectedConfigId = stateHolder._selectedApiConfig.value?.id
        val finalCurrentChatMessages = stateHolder.messages.toList()

        // Use a final blocking launch for critical cleanup if needed, but viewModelScope handles cancellation.
        // For saving, it's better to do it in onPause/onStop of the Activity.
        // However, to keep the logic, we'll do a final launch.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                persistenceManager.saveLastOpenChat(finalCurrentChatMessages)
                persistenceManager.saveApiConfigs(finalApiConfigs)
                persistenceManager.saveSelectedConfigIdentifier(finalSelectedConfigId)
                dataSource.saveCustomProviders(_customProviders.value)
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error saving state onCleared", e)
            }
        }
        super.onCleared()
    }
}
