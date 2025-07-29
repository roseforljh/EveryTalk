package com.example.everytalk.statecontroller

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
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
import com.example.everytalk.util.MarkdownBlock
import com.example.everytalk.util.VersionChecker
import com.example.everytalk.util.MarkdownParser
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@Keep
class LRUCache<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        return size > maxSize
    }
}

class AppViewModel(application: Application, private val dataSource: SharedPreferencesDataSource) :
        AndroidViewModel(application) {

    @Keep
    @Serializable
    private data class ExportedSettings(
            val apiConfigs: List<ApiConfig>,
            val customProviders: Set<String>
    )

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val messagesMutex = Mutex()
    private val markdownCache =
            Collections.synchronizedMap(LRUCache<String, List<MarkdownBlock>>(500))
    private val conversationPreviewCache = Collections.synchronizedMap(LRUCache<Int, String>(100))
    private val textUpdateDebouncer = mutableMapOf<String, Job>()
    internal val stateHolder = ViewModelStateHolder()
    private val imageLoader = ImageLoader.Builder(application.applicationContext).build()
    private val persistenceManager =
            DataPersistenceManager(
                    application.applicationContext,
                    dataSource,
                    stateHolder,
                    ioScope,
                    imageLoader
            )

    private val historyManager: HistoryManager =
            HistoryManager(
                    stateHolder,
                    persistenceManager,
                    ::areMessageListsEffectivelyEqual,
                    onHistoryModified = { conversationPreviewCache.clear() }
            )

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
                stateHolder,
                mainScope,
                historyManager,
                ::onAiMessageFullTextChanged,
                ::triggerScrollToBottom
        )
    }
    private val configManager: ConfigManager by lazy {
        ConfigManager(stateHolder, persistenceManager, apiHandler, ioScope)
    }

    private val messageSender: MessageSender by lazy {
        MessageSender(
                application = getApplication(),
                viewModelScope = mainScope,
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

    private val _customProviders = MutableStateFlow<Set<String>>(emptySet())

    private val predefinedPlatformsList =
            listOf("openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索", "OpenRouter")

    val allProviders: StateFlow<List<String>> =
            combine(_customProviders) { customsArray: Array<Set<String>> ->
                        val customs = customsArray[0]
                        val combinedList = (predefinedPlatformsList + customs.toList()).distinct()
                        val predefinedOrderMap =
                                predefinedPlatformsList.withIndex().associate {
                                    it.value.lowercase().trim() to it.index
                                }
                        combinedList.sortedWith(
                                compareBy<String> { platform ->
                                    predefinedOrderMap[platform.lowercase().trim()]
                                            ?: (predefinedPlatformsList.size +
                                                    customs
                                                            .indexOfFirst {
                                                                it.equals(
                                                                        platform,
                                                                        ignoreCase = true
                                                                )
                                                            }
                                                            .let {
                                                                if (it == -1) Int.MAX_VALUE else it
                                                            })
                                }
                                        .thenBy { it }
                        )
                    }
                    .stateIn(ioScope, SharingStarted.Eagerly, predefinedPlatformsList)
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
                                            val isStreamingThisMessage =
                                                    isApiCalling &&
                                                            message.id ==
                                                                    currentStreamingAiMessageId
                                            val blocks =
                                                    if (message.text.isBlank()) {
                                                        emptyList()
                                                    } else {
                                                        // Always parse the full text to get
                                                        // incremental block updates.
                                                        // Caching is used for completed messages to
                                                        // optimize performance.
                                                        if (isStreamingThisMessage) {
                                                            MarkdownParser.parse(message.text)
                                                        } else {
                                                            val cacheKey =
                                                                    "${message.id}_${message.text.hashCode()}"
                                                            synchronized(markdownCache) {
                                                                markdownCache.getOrPut(cacheKey) {
                                                                    MarkdownParser.parse(
                                                                            message.text
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                            createAiMessageItems(
                                                    message,
                                                    blocks,
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
                            scope = defaultScope,
                            started = SharingStarted.WhileSubscribed(5000),
                            initialValue = emptyList()
                    )

    init {
        ioScope.launch { _customProviders.value = dataSource.loadCustomProviders() }

        // 优化：分阶段初始化，优先加载关键配置
        persistenceManager.loadInitialData(loadLastChat = false) {
                initialConfigPresent,
                initialHistoryPresent ->
            if (!initialConfigPresent) {
                mainScope.launch {
                    // 如果没有配置，可以显示引导界面
                }
            }
            
            // 历史数据加载完成后的处理
            if (initialHistoryPresent) {
                Log.d("AppViewModel", "历史数据已加载，共 ${stateHolder._historicalConversations.value.size} 条对话")
            }
        }

        // 延迟初始化非关键组件
        ioScope.launch {
            // 确保API配置加载完成后再初始化这些组件
            delay(100) // 给UI一些时间渲染
            apiHandler
            configManager
            messageSender
        }

        // 清理任务
        mainScope.launch {
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
         ioScope.launch {
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
             blocks: List<MarkdownBlock>,
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
        val blockItems =
                blocks.mapIndexed { index, block ->
                    ChatListItem.AiMessageBlock(
                            messageId = message.id,
                            block = block,
                            blockIndex = index,
                            isFirstBlock = index == 0,
                            isLastBlock = index == blocks.size - 1,
                            hasReasoning = hasReasoning
                    )
                }

        val footerItem =
                if (!message.webSearchResults.isNullOrEmpty() &&
                                !(isApiCalling && message.id == currentStreamingAiMessageId)
                ) {
                    listOf(ChatListItem.AiMessageFooter(message))
                } else {
                    emptyList()
                }

        return reasoningItem + blockItems + footerItem
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

    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            ioScope.launch {
                val currentCustomProviders = _customProviders.value.toMutableSet()

                if (predefinedPlatformsList.any { it.equals(trimmedName, ignoreCase = true) }) {
                    withContext(Dispatchers.Main) {
                        showSnackbar("平台名称 '$trimmedName' 是预设名称或已存在，无法添加。")
                    }
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

    fun deleteProvider(providerName: String) {
        ioScope.launch {
            val trimmedProviderName = providerName.trim()

            if (predefinedPlatformsList.any { it.equals(trimmedProviderName, ignoreCase = true) }) {
                withContext(Dispatchers.Main) { showSnackbar("预设平台 '$trimmedProviderName' 不可删除。") }
                return@launch
            }

            val currentCustomProviders = _customProviders.value.toMutableSet()
            val removed =
                    currentCustomProviders.removeIf {
                        it.equals(trimmedProviderName, ignoreCase = true)
                    }

            if (removed) {
                _customProviders.value = currentCustomProviders.toSet()
                dataSource.saveCustomProviders(currentCustomProviders.toSet())

                val configsToDelete =
                        stateHolder._apiConfigs.value.filter {
                            it.provider.equals(trimmedProviderName, ignoreCase = true)
                        }
                configsToDelete.forEach { config -> configManager.deleteConfig(config) }
                withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedProviderName' 已删除") }
            } else {
                withContext(Dispatchers.Main) {
                    showSnackbar("未能删除模型平台 '$trimmedProviderName'，可能它不是一个自定义平台。")
                }
            }
        }
    }

    fun showSnackbar(message: String) {
        mainScope.launch { stateHolder._snackbarMessage.emit(message) }
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
        mainScope.launch {
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
                ioScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) }
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

        defaultScope.launch {
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
                ioScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) }
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
        mainScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    fun startNewChat() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        mainScope.launch {
            ioScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }.join()
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
        mainScope.launch {
            stateHolder._isLoadingHistory.value = true
            ioScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }.join()

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
                    withContext(defaultScope.coroutineContext) {
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
        mainScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            val idsInDeletedConversation =
                    historicalConversations.getOrNull(indexToDelete)?.map { it.id } ?: emptyList()
            
            // HistoryManager.deleteConversation 已经包含了媒体文件清理逻辑
            ioScope.launch { historyManager.deleteConversation(indexToDelete) }.join()
            
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
            conversationPreviewCache.clear()
        }
    }

    fun clearAllConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        mainScope.launch {
            // HistoryManager.clearAllHistory 已经包含了媒体文件清理逻辑
            ioScope.launch { historyManager.clearAllHistory() }.join()
            
            messagesMutex.withLock {
                stateHolder.clearForNewChat()
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
            showSnackbar("所有对话已清除")
            conversationPreviewCache.clear()
        }
    }

    fun showSourcesDialog(sources: List<WebSearchResult>) {
        mainScope.launch {
            stateHolder._sourcesForDialog.value = sources
            stateHolder._showSourcesDialog.value = true
        }
    }

    fun dismissSourcesDialog() {
        mainScope.launch {
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
        mainScope.launch {
            val fileName = "conversation_export.md"
            _exportRequest.send(fileName to text)
        }
    }

    fun addConfig(config: ApiConfig) = configManager.addConfig(config)
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun deleteConfigGroup(
            apiKey: String,
            modalityType: com.example.everytalk.data.DataClass.ModalityType
    ) {
        ioScope.launch {
            val configsToDelete =
                    stateHolder._apiConfigs.value.filter {
                        it.key == apiKey && it.modalityType == modalityType
                    }

            if (configsToDelete.isNotEmpty()) {
                configsToDelete.forEach { config -> configManager.deleteConfig(config) }
                withContext(Dispatchers.Main) { showSnackbar("配置组已删除") }
            }
        }
    }
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)
    fun clearSelectedConfig() {
        stateHolder._selectedApiConfig.value = null
        ioScope.launch { persistenceManager.saveSelectedConfigIdentifier(null) }
    }

    fun updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String) {
        ioScope.launch {
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
        mainScope.launch(Dispatchers.Main.immediate) {
            if (stateHolder.messageAnimationStates[messageId] != true) {
                stateHolder.messageAnimationStates[messageId] = true
            }
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        return stateHolder.messageAnimationStates[messageId] ?: false
    }

    fun getConversationPreviewText(index: Int): String {
        synchronized(conversationPreviewCache) {
            val cachedPreview = conversationPreviewCache[index]
            if (cachedPreview != null) return cachedPreview

            val conversation =
                    stateHolder._historicalConversations.value.getOrNull(index)
                            ?: return "对话 ${index + 1}".also {
                                conversationPreviewCache[index] = it
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

            conversationPreviewCache[index] = newPreview
            return newPreview
        }
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空")
            return
        }
        defaultScope.launch {
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

                        ioScope
                                .launch {
                                    persistenceManager.saveChatHistory(
                                            stateHolder._historicalConversations.value
                                    )
                                }
                                .join()

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
                mainScope.launch {
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
        ioScope.launch {
            val settingsToExport =
                    ExportedSettings(
                            apiConfigs = stateHolder._apiConfigs.value,
                            customProviders = _customProviders.value
                    )
            val finalJson = json.encodeToString(settingsToExport)
            _settingsExportRequest.send("eztalk_settings" to finalJson)
        }
    }

    fun importSettings(jsonContent: String) {
        ioScope.launch {
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

    fun getMessageById(id: String): Message? {
        return messages.find { it.id == id }
    }

    fun saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        if (scrollState.firstVisibleItemIndex >= 0) {
            stateHolder.conversationScrollStates[conversationId] = scrollState
        }
    }

    fun appendReasoningToMessage(messageId: String, text: String) {
        mainScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendReasoningToMessage(messageId, text)
        }
    }

    fun appendContentToMessage(messageId: String, text: String) {
        mainScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendContentToMessage(messageId, text)
            onAiMessageFullTextChanged(messageId, stateHolder.messages.find { it.id == messageId }?.text ?: "")
        }
    }

    fun getScrollState(conversationId: String): ConversationScrollState? {
        return stateHolder.conversationScrollStates[conversationId]
    }
    override fun onCleared() {
        try {
            mainScope.cancel()
            ioScope.cancel()
            defaultScope.cancel()
        } catch (e: Exception) {
            Log.e("AppViewModel", "Error during cleanup", e)
        }
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared", isNewMessageSend = false)

        val finalApiConfigs = stateHolder._apiConfigs.value.toList()
        val finalSelectedConfigId = stateHolder._selectedApiConfig.value?.id
        val finalCurrentChatMessages = stateHolder.messages.toList()

        ioScope.launch {
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
