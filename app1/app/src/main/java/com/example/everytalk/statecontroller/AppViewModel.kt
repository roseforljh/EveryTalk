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
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import coil3.request.ImageRequest
import coil3.size.Size
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import java.io.File
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val historyMutex = Mutex()
    private val textConversationPreviewCache = LruCache<Int, String>(100)
    private val imageConversationPreviewCache = LruCache<Int, String>(100)
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
                    onHistoryModified = { 
                        textConversationPreviewCache.evictAll()
                        imageConversationPreviewCache.evictAll()
                    }
            )
    
    // 公开的模式管理器 - 供设置界面等外部组件使用
    val simpleModeManager = SimpleModeManager(stateHolder, historyManager, viewModelScope)

    // 向UI层公开“意图模式”StateFlow，避免基于内容态推断造成的短暂不一致
    val uiModeFlow: StateFlow<SimpleModeManager.ModeType>
        get() = simpleModeManager.uiModeFlow

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
                stateHolder,
                viewModelScope,
                historyManager,
                onAiMessageFullTextChanged = { _, _ -> },
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
                triggerScrollToBottom = { triggerScrollToBottom() },
                uriToBase64Encoder = ::encodeUriAsBase64
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
    val imageGenerationMessages: SnapshotStateList<Message>
        get() = stateHolder.imageGenerationMessages
    val historicalConversations: StateFlow<List<List<Message>>>
        get() = stateHolder._historicalConversations.asStateFlow()
    val imageGenerationHistoricalConversations: StateFlow<List<List<Message>>>
        get() = stateHolder._imageGenerationHistoricalConversations.asStateFlow()
    val loadedHistoryIndex: StateFlow<Int?>
        get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val loadedImageGenerationHistoryIndex: StateFlow<Int?>
        get() = stateHolder._loadedImageGenerationHistoryIndex.asStateFlow()
    val isLoadingHistory: StateFlow<Boolean>
        get() = stateHolder._isLoadingHistory.asStateFlow()
    val isLoadingHistoryData: StateFlow<Boolean>
        get() = stateHolder._isLoadingHistoryData.asStateFlow()
    val currentConversationId: StateFlow<String>
        get() = stateHolder._currentConversationId.asStateFlow()
    val currentImageGenerationConversationId: StateFlow<String>
        get() = stateHolder._currentImageGenerationConversationId.asStateFlow()
    val apiConfigs: StateFlow<List<ApiConfig>>
        get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?>
        get() = stateHolder._selectedApiConfig.asStateFlow()
   val imageGenApiConfigs: StateFlow<List<ApiConfig>>
       get() = stateHolder._imageGenApiConfigs.asStateFlow()
   val selectedImageGenApiConfig: StateFlow<ApiConfig?>
       get() = stateHolder._selectedImageGenApiConfig.asStateFlow()
    val isTextApiCalling: StateFlow<Boolean>
        get() = stateHolder._isTextApiCalling.asStateFlow()
    val isImageApiCalling: StateFlow<Boolean>
        get() = stateHolder._isImageApiCalling.asStateFlow()
    val currentTextStreamingAiMessageId: StateFlow<String?>
        get() = stateHolder._currentTextStreamingAiMessageId.asStateFlow()
    val currentImageStreamingAiMessageId: StateFlow<String?>
        get() = stateHolder._currentImageStreamingAiMessageId.asStateFlow()
    
    // 图像生成错误处理状态
    val shouldShowImageGenerationError: StateFlow<Boolean>
        get() = stateHolder._shouldShowImageGenerationError.asStateFlow()
    val imageGenerationError: StateFlow<String?>
        get() = stateHolder._imageGenerationError.asStateFlow()
    
    val textReasoningCompleteMap: SnapshotStateMap<String, Boolean>
        get() = stateHolder.textReasoningCompleteMap
    val imageReasoningCompleteMap: SnapshotStateMap<String, Boolean>
        get() = stateHolder.imageReasoningCompleteMap
    val textExpandedReasoningStates: SnapshotStateMap<String, Boolean>
        get() = stateHolder.textExpandedReasoningStates
    val imageExpandedReasoningStates: SnapshotStateMap<String, Boolean>
        get() = stateHolder.imageExpandedReasoningStates
    val snackbarMessage: SharedFlow<String>
        get() = stateHolder._snackbarMessage.asSharedFlow()
    val scrollToBottomEvent: SharedFlow<Unit>
        get() = stateHolder._scrollToBottomEvent.asSharedFlow()
    val selectedMediaItems: SnapshotStateList<SelectedMediaItem>
        get() = stateHolder.selectedMediaItems

    val systemPromptExpandedState: SnapshotStateMap<String, Boolean>
        get() = stateHolder.systemPromptExpandedState

    private val _exportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val exportRequest: Flow<Pair<String, String>> = _exportRequest.receiveAsFlow()

    private val _settingsExportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val settingsExportRequest: Flow<Pair<String, String>> = _settingsExportRequest.receiveAsFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> = _editingMessage.asStateFlow()
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

    private val _showSystemPromptDialog = MutableStateFlow(false)
    val showSystemPromptDialog: StateFlow<Boolean> = _showSystemPromptDialog.asStateFlow()

    val systemPrompt: StateFlow<String> = stateHolder._currentConversationId.flatMapLatest { id ->
        snapshotFlow { stateHolder.systemPrompts[id] ?: "" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    private var originalSystemPrompt: String? = null
 
   private val _showAboutDialog = MutableStateFlow(false)
   val showAboutDialog: StateFlow<Boolean> = _showAboutDialog.asStateFlow()
 
   private val _latestReleaseInfo = MutableStateFlow<GithubRelease?>(null)
   val latestReleaseInfo: StateFlow<GithubRelease?> = _latestReleaseInfo.asStateFlow()

   private val _showClearImageHistoryDialog = MutableStateFlow(false)
   val showClearImageHistoryDialog: StateFlow<Boolean> = _showClearImageHistoryDialog.asStateFlow()
     val chatListItems: StateFlow<List<ChatListItem>> =
             combine(
                             snapshotFlow { messages.toList() },
                            isTextApiCalling,
                            currentTextStreamingAiMessageId
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

    val imageGenerationChatListItems: StateFlow<List<ChatListItem>> =
        combine(
            snapshotFlow { imageGenerationMessages.toList() },
            isImageApiCalling,
            currentImageStreamingAiMessageId
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
    private val _isRefreshingModels = MutableStateFlow<Set<String>>(emptySet())
    val isRefreshingModels: StateFlow<Set<String>> = _isRefreshingModels.asStateFlow()
    init {
        // 加载自定义提供商
        viewModelScope.launch(Dispatchers.IO) {
            val loadedCustomProviders = dataSource.loadCustomProviders()
            _customProviders.value = loadedCustomProviders
        }

        // 优化：分阶段初始化，优先加载关键配置
        // 修改：不自动恢复上次会话，启动进入欢迎页
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
       if (messages.isEmpty() && imageGenerationMessages.isEmpty()) {
           startNewChat()
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
    
    // 模式状态检测方法 - 供设置界面等外部组件使用
    fun getCurrentMode(): SimpleModeManager.ModeType {
        return simpleModeManager.getCurrentMode()
    }
    
    fun isInImageMode(): Boolean {
        return simpleModeManager.isInImageMode()
    }
    
    fun isInTextMode(): Boolean {
        return simpleModeManager.isInTextMode()
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
        val messageItem = if (message.text.isNotBlank() || !message.imageUrls.isNullOrEmpty()) {
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

    private suspend fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean = withContext(Dispatchers.Default) {
        if (list1 == null && list2 == null) return@withContext true
        if (list1 == null || list2 == null) return@withContext false
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return@withContext false

        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]

            val textMatch = msg1.text.trim() == msg2.text.trim()
            val reasoningMatch = msg1.reasoning?.trim() == msg2.reasoning?.trim()
            val attachmentsMatch = msg1.attachments.size == msg2.attachments.size &&
                    msg1.attachments.map {
                        when (it) {
                            is SelectedMediaItem.ImageFromUri -> it.uri
                            is SelectedMediaItem.GenericFile -> it.uri
                            else -> null
                        }
                    }.filterNotNull().toSet() == msg2.attachments.map {
                        when (it) {
                            is SelectedMediaItem.ImageFromUri -> it.uri
                            is SelectedMediaItem.GenericFile -> it.uri
                            else -> null
                        }
                    }.filterNotNull().toSet()

            if (msg1.id != msg2.id ||
                msg1.sender != msg2.sender ||
                !textMatch ||
                !reasoningMatch ||
                msg1.isError != msg2.isError ||
                !attachmentsMatch
            ) {
                return@withContext false
            }
        }
        return@withContext true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter
                .filter { msg ->
                    (!msg.isError) &&
                    (
                        (msg.sender == Sender.User) ||
                        (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) ||
                        (msg.sender == Sender.System)
                    )
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
        mimeType: String? = null,
        isImageGeneration: Boolean = false
    ) {
        if (_editingMessage.value != null && isImageGeneration) {
            confirmImageGenerationMessageEdit(messageText)
        } else {
            messageSender.sendMessage(
                messageText,
                isFromRegeneration,
                attachments,
                audioBase64 = audioBase64,
                mimeType = mimeType,
                systemPrompt = systemPrompt.value,
                isImageGeneration = isImageGeneration
            )
        }
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

    fun requestEditMessage(message: Message, isImageGeneration: Boolean = false) {
        if (message.sender == Sender.User) {
            if (isImageGeneration) {
                _editingMessage.value = message
                stateHolder._text.value = message.text
            } else {
                _editingMessageId.value = message.id
                stateHolder._editDialogInputText.value = message.text
                _showEditDialog.value = true
            }
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
                        if (stateHolder.textMessageAnimationStates[updatedMessage.id] != true)
                        {
                            stateHolder.textMessageAnimationStates[updatedMessage.id] = true
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

    fun confirmImageGenerationMessageEdit(updatedText: String) {
        val messageToEdit = _editingMessage.value ?: return
        viewModelScope.launch {
            var needsHistorySave = false
            messagesMutex.withLock {
                val messageIndex = imageGenerationMessages.indexOfFirst { it.id == messageToEdit.id }
                if (messageIndex != -1) {
                    val originalMessage = imageGenerationMessages[messageIndex]
                    if (originalMessage.text != updatedText) {
                        val updatedMessage = originalMessage.copy(
                            text = updatedText,
                            timestamp = System.currentTimeMillis()
                        )
                        imageGenerationMessages[messageIndex] = updatedMessage
                        needsHistorySave = true
                    }
                }
            }
            if (needsHistorySave) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
            }
            _editingMessage.value = null
            stateHolder._text.value = ""
        }
    }

    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        stateHolder._editDialogInputText.value = ""
    }

    fun cancelEditing() {
        _editingMessage.value = null
        stateHolder._text.value = ""
    }

    fun regenerateAiResponse(message: Message, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else messages
        val messageToRegenerateFrom =
            (if (message.sender == Sender.AI) {
                val aiMessageIndex = messageList.indexOfFirst { it.id == message.id }
                if (aiMessageIndex > 0) {
                    messageList.subList(0, aiMessageIndex).findLast { it.sender == Sender.User }
                } else {
                    null
                }
            } else {
                messageList.find { it.id == message.id }
            })

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
                                messageList.indexOfFirst { it.id == originalUserMessageId }
                        if (userMessageIndex == -1) {
                            withContext(Dispatchers.Main) {
                                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。")
                            }
                            return@withContext false
                        }

                        val messagesToRemove = mutableListOf<Message>()
                        var currentIndexToInspect = userMessageIndex + 1
                        while (currentIndexToInspect < messageList.size) {
                            val message = messageList[currentIndexToInspect]
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
                                    if (stateHolder._currentTextStreamingAiMessageId.value == id) {
                                        apiHandler.cancelCurrentApiJob(
                                                "为消息 '${originalUserMessageId.take(4)}' 重新生成回答，取消旧AI流",
                                                isNewMessageSend = true
                                        )
                                    }
                                }
                                stateHolder.textReasoningCompleteMap.keys.removeAll(idsToRemove)
                                stateHolder.textExpandedReasoningStates.keys.removeAll(idsToRemove)
                                stateHolder.textMessageAnimationStates.keys.removeAll(idsToRemove)

                                // 删除旧消息之前，先删除关联的媒体文件
                                viewModelScope.launch(Dispatchers.IO) {
                                    persistenceManager.deleteMediaFilesForMessages(listOf(messagesToRemove))
                                }

                                messageList.removeAll(messagesToRemove.toSet())
 
                                val finalUserMessageIndex =
                                        messageList.indexOfFirst {
                                            it.id == originalUserMessageId
                                        }
                                if (finalUserMessageIndex != -1) {
                                    stateHolder.textMessageAnimationStates.remove(originalUserMessageId)
                                    messageList.removeAt(finalUserMessageIndex)
                                }
                            }
                        }
                        true
                    }

            if (success) {
                viewModelScope.launch(Dispatchers.IO) { historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration) }
                onSendMessage(
                        messageText = originalUserMessageText,
                        isFromRegeneration = true,
                        attachments = originalAttachments,
                        isImageGeneration = isImageGeneration
                )
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
        }
    }

   fun showSystemPromptDialog() {
       originalSystemPrompt = systemPrompt.value
       _showSystemPromptDialog.value = true
   }

   fun dismissSystemPromptDialog() {
       _showSystemPromptDialog.value = false
       originalSystemPrompt?.let {
           val conversationId = stateHolder._currentConversationId.value
           stateHolder.systemPrompts[conversationId] = it
       }
       originalSystemPrompt = null
       val conversationId = stateHolder._currentConversationId.value
       stateHolder.systemPromptExpandedState[conversationId] = false
   }

   fun onSystemPromptChange(newPrompt: String) {
       val conversationId = stateHolder._currentConversationId.value
       stateHolder.systemPrompts[conversationId] = newPrompt
   }

   /**
     * 清空系统提示
     * 这个方法专门用于处理系统提示的清空操作，确保originalSystemPrompt也被正确设置
     */
    fun clearSystemPrompt() {
        val conversationId = stateHolder._currentConversationId.value
        stateHolder.systemPrompts[conversationId] = ""
        originalSystemPrompt = "" // 特别设置originalSystemPrompt为空字符串，防止dismiss时恢复
        saveSystemPrompt()
    }

    fun saveSystemPrompt() {
         val conversationId = stateHolder._currentConversationId.value
         val newPrompt = stateHolder.systemPrompts[conversationId] ?: ""

         _showSystemPromptDialog.value = false
         originalSystemPrompt = null
         stateHolder.systemPromptExpandedState[conversationId] = false

         viewModelScope.launch {
             historyMutex.withLock {
                 var modifiedMessages: List<Message>? = null
                 messagesMutex.withLock {
                     val currentMessages = stateHolder.messages.toMutableList()
                     val systemMessageIndex =
                         currentMessages.indexOfFirst { it.sender == Sender.System && !it.isPlaceholderName }

                     var changed = false
                     if (systemMessageIndex != -1) {
                         val oldPrompt = currentMessages[systemMessageIndex].text
                         if (newPrompt.isNotBlank()) {
                             if (oldPrompt != newPrompt) {
                                 currentMessages[systemMessageIndex] =
                                     currentMessages[systemMessageIndex].copy(text = newPrompt)
                                 changed = true
                             }
                         } else {
                             currentMessages.removeAt(systemMessageIndex)
                             changed = true
                         }
                     } else if (newPrompt.isNotBlank()) {
                         val systemMessage = Message(
                             id = "system_${conversationId}",
                             text = newPrompt,
                             sender = Sender.System,
                             timestamp = System.currentTimeMillis(),
                             contentStarted = true
                         )
                         currentMessages.add(0, systemMessage)
                         changed = true
                     }

                     if (changed) {
                         modifiedMessages = currentMessages.toList()
                         stateHolder.messages.clear()
                         stateHolder.messages.addAll(modifiedMessages!!)
                     }
                 }

                 if (modifiedMessages != null) {
                     val loadedIndex = stateHolder._loadedHistoryIndex.value
                     if (loadedIndex != null) {
                         val currentHistory = stateHolder._historicalConversations.value.toMutableList()
                         if (loadedIndex >= 0 && loadedIndex < currentHistory.size) {
                             currentHistory[loadedIndex] = modifiedMessages!!
                             stateHolder._historicalConversations.value = currentHistory.toList()
                             textConversationPreviewCache.remove(loadedIndex)
                         }
                     }
                     historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                 }
             }
         }
    }

   fun toggleSystemPromptExpanded() {
       val conversationId = stateHolder._currentConversationId.value
       val currentState = stateHolder.systemPromptExpandedState[conversationId] ?: false
       stateHolder.systemPromptExpandedState[conversationId] = !currentState
   }

    fun triggerScrollToBottom() {
        viewModelScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    fun onCancelAPICall() {
        // 根据当前模式取消对应的流/任务，确保图像模式可被中止
        val isImageMode = simpleModeManager.isInImageMode()
        apiHandler.cancelCurrentApiJob("用户取消操作", isNewMessageSend = false, isImageGeneration = isImageMode)
    }

    fun startNewChat() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            try {
                // 使用新的模式管理器
                simpleModeManager.switchToTextMode(forceNew = true)
                
                messagesMutex.withLock {
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                    if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error starting new chat", e)
                showSnackbar("启动新聊天失败: ${e.message}")
            }
        }
    }

    fun startNewImageGeneration() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新的图像生成")
        viewModelScope.launch {
            try {
                // 使用新的模式管理器
                simpleModeManager.switchToImageMode(forceNew = true)
                
                messagesMutex.withLock {
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                    if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error starting new image generation", e)
                showSnackbar("启动新图像生成失败: ${e.message}")
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        Log.d("AppViewModel", "🚀 [START] loadConversationFromHistory called with index: $index")
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载文本模式历史索引 $index", isNewMessageSend = false, isImageGeneration = false)

        viewModelScope.launch {
            stateHolder._isLoadingTextHistory.value = true
            
            try {
                // 完全委托给 SimpleModeManager，使用独立的文本模式逻辑
                Log.d("AppViewModel", "🚀 [TEXT] Delegating to SimpleModeManager...")
                simpleModeManager.loadTextHistory(index)
                Log.d("AppViewModel", "🚀 [TEXT] SimpleModeManager completed successfully")

                if (_isSearchActiveInDrawer.value) {
                    withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
                }
                
            } catch (e: Exception) {
                Log.e("AppViewModel", "🚀 [TEXT ERROR] Error loading text history", e)
                showSnackbar("加载文本历史对话失败: ${e.message}")
            } finally {
                stateHolder._isLoadingTextHistory.value = false
            }
        }
    }

    fun loadImageGenerationConversationFromHistory(index: Int) {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载图像模式历史索引 $index", isNewMessageSend = false, isImageGeneration = true)

        viewModelScope.launch {
            stateHolder._isLoadingImageHistory.value = true

            try {
                // 完全委托给 SimpleModeManager，使用独立的图像模式逻辑
                Log.d("AppViewModel", "🖼️ [IMAGE] Delegating to SimpleModeManager...")
                simpleModeManager.loadImageHistory(index)
                Log.d("AppViewModel", "🖼️ [IMAGE] SimpleModeManager completed successfully")

                if (_isSearchActiveInDrawer.value) {
                    withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
                }
                
            } catch (e: Exception) {
                Log.e("AppViewModel", "🖼️ [IMAGE ERROR] Error loading image history", e)
                showSnackbar("加载图像历史失败: ${e.message}")
            } finally {
                stateHolder._isLoadingImageHistory.value = false
            }
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
                simpleModeManager.switchToTextMode(forceNew = true)
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            }
            textConversationPreviewCache.evictAll()
        }
    }
    fun deleteImageGenerationConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedImageGenerationHistoryIndex.value
        val historicalConversations = stateHolder._imageGenerationHistoricalConversations.value
        if (indexToDelete < 0 || indexToDelete >= historicalConversations.size) {
            showSnackbar("无法删除：无效的索引")
            return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            withContext(Dispatchers.IO) { historyManager.deleteConversation(indexToDelete, isImageGeneration = true) }

            if (wasCurrentChatDeleted) {
                simpleModeManager.switchToImageMode(forceNew = true)
                apiHandler.cancelCurrentApiJob("当前图像生成聊天(#$indexToDelete)被删除，开始新聊天")
            }
            imageConversationPreviewCache.evictAll()
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
                stateHolder.clearForNewTextChat()
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
            showSnackbar("所有对话已清除")
            textConversationPreviewCache.evictAll()
            imageConversationPreviewCache.evictAll()
        }
    }

    fun clearAllImageGenerationConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有图像生成历史记录")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyManager.clearAllHistory(isImageGeneration = true) }
            messagesMutex.withLock {
                stateHolder.clearForNewImageChat()
                if (stateHolder.shouldAutoScroll()) {
                    triggerScrollToBottom()
                }
            }
            showSnackbar("所有图像生成对话已清除")
            imageConversationPreviewCache.evictAll()
        }
    }

    fun showClearImageHistoryDialog() {
       _showClearImageHistoryDialog.value = true
   }

   fun dismissClearImageHistoryDialog() {
       _showClearImageHistoryDialog.value = false
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

    fun downloadImageFromMessage(message: Message) {
        viewModelScope.launch {
            val imageUrl = message.imageUrls?.firstOrNull() ?: run {
                showSnackbar("没有可下载的图片")
                return@launch
            }

            try {
                // 兼容多种来源：http(s)、content://、file://、本地绝对路径、data:image;base64
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    val s = imageUrl
                    val uri = try { Uri.parse(s) } catch (_: Exception) { null }
                    val scheme = uri?.scheme?.lowercase()

                    fun decodeHttp(url: String): Bitmap? {
                        val client = OkHttpClient()
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            val body = response.body ?: return null
                            val bytes = body.bytes()
                            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }

                    fun decodeContent(u: Uri): Bitmap? {
                        val cr = getApplication<Application>().contentResolver
                        val input = cr.openInputStream(u) ?: return null
                        input.use { stream ->
                            return BitmapFactory.decodeStream(stream)
                        }
                    }

                    fun decodeFile(path: String?): Bitmap? {
                        if (path.isNullOrBlank()) return null
                        val f = File(path)
                        if (!f.exists()) return null
                        return BitmapFactory.decodeFile(path)
                    }

                    return@withContext when {
                        s.startsWith("data:image", ignoreCase = true) -> {
                            val base64Part = s.substringAfter(",", missingDelimiterValue = "")
                            if (base64Part.isNotBlank()) {
                                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                        }
                        scheme == "http" || scheme == "https" -> {
                            decodeHttp(s)
                        }
                        scheme == "content" -> {
                            decodeContent(uri!!)
                        }
                        scheme == "file" -> {
                            decodeFile(uri?.path)
                        }
                        uri?.scheme.isNullOrBlank() -> {
                            // 无 scheme，当作本地绝对路径
                            decodeFile(s)
                        }
                        else -> {
                            // 兜底：尝试 content，再尝试文件路径
                            (uri?.let { decodeContent(it) }) ?: decodeFile(uri?.path)
                        }
                    }
                }

                if (bitmap != null) {
                    saveBitmapToDownloads(bitmap)
                    showSnackbar("图片已保存到相册")
                } else {
                    showSnackbar("无法加载图片，请重试")
                }
            } catch (e: Exception) {
                Log.e("DownloadImage", "下载图片失败", e)
                showSnackbar("下载失败: ${e.message}")
            }
        }
    }

    private fun saveBitmapToDownloads(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val contentResolver = context.contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentDetails = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EveryTalk_Image_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = contentResolver.insert(imageCollection, contentDetails)
        imageUri?.let {
            try {
                contentResolver.openOutputStream(it).use { outputStream ->
                    if (outputStream != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    } else {
                        throw Exception("无法打开输出流")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentDetails.clear()
                    contentDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, contentDetails, null, null)
                }
            } catch (e: Exception) {
                Log.e("SaveBitmap", "保存图片失败", e)
                contentResolver.delete(it, null, null) // 清理失败的条目
                throw e
            }
        } ?: throw Exception("无法创建MediaStore条目")
    }
 
    fun addConfig(config: ApiConfig, isImageGen: Boolean = false) = configManager.addConfig(config, isImageGen)

    fun addMultipleConfigs(configs: List<ApiConfig>) {
        viewModelScope.launch {
            val distinctConfigs = configs.distinctBy { it.model }
            distinctConfigs.forEach { config ->
                configManager.addConfig(config)
            }
        }
    }
    fun updateConfig(config: ApiConfig, isImageGen: Boolean = false) = configManager.updateConfig(config, isImageGen)
    fun deleteConfig(config: ApiConfig, isImageGen: Boolean = false) = configManager.deleteConfig(config, isImageGen)
    fun deleteConfigGroup(
            apiKey: String,
            modalityType: com.example.everytalk.data.DataClass.ModalityType,
            isImageGen: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val originalConfigs = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value
            val configsToKeep = originalConfigs.filterNot {
                it.key == apiKey && it.modalityType == modalityType
            }

            if (originalConfigs.size != configsToKeep.size) {
                if (isImageGen) {
                    stateHolder._imageGenApiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep, isImageGen = true)
                } else {
                    stateHolder._apiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep)
                }
            }
        }
    }
    
    fun deleteImageGenConfigGroup(
            apiKey: String,
            modalityType: com.example.everytalk.data.DataClass.ModalityType
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val originalConfigs = stateHolder._imageGenApiConfigs.value
            val configsToKeep = originalConfigs.filterNot {
                it.key == apiKey && it.modalityType == modalityType
            }

            if (originalConfigs.size != configsToKeep.size) {
                stateHolder._imageGenApiConfigs.value = configsToKeep
                persistenceManager.saveApiConfigs(configsToKeep, isImageGen = true)
            }
        }
    }
    
    fun clearAllConfigs(isImageGen: Boolean = false) = configManager.clearAllConfigs(isImageGen)
    fun selectConfig(config: ApiConfig, isImageGen: Boolean = false) = configManager.selectConfig(config, isImageGen)
    fun clearSelectedConfig(isImageGen: Boolean = false) {
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

    fun updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String, providerToKeep: String, newChannel: String, isImageGen: Boolean? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedAddress = newAddress.trim()
            val trimmedKey = newKey.trim()
            val trimmedChannel = newChannel.trim()

            val originalKey = representativeConfig.key
            val modality = representativeConfig.modalityType
            
            // 根据模态类型选择正确的配置列表和保存方法
            val useImageGen = isImageGen ?: (modality == com.example.everytalk.data.DataClass.ModalityType.IMAGE)
            
            Log.d("AppViewModel", "=== UPDATE CONFIG GROUP DEBUG ===")
            Log.d("AppViewModel", "Original config - Model: ${representativeConfig.model}, Provider: ${representativeConfig.provider}, Channel: ${representativeConfig.channel}")
            Log.d("AppViewModel", "New values - Address: $trimmedAddress, Key: ${trimmedKey.take(10)}..., Provider: $providerToKeep, Channel: $trimmedChannel")
            Log.d("AppViewModel", "IsImageGen: $useImageGen, Modality: $modality")
            
            if (useImageGen) {
                // 图像生成配置
                val currentConfigs = stateHolder._imageGenApiConfigs.value
                Log.d("AppViewModel", "Current image configs count: ${currentConfigs.size}")
                val newConfigs =
                        currentConfigs.map { config ->
                            if (config.key == originalKey && config.modalityType == modality) {
                                val updatedConfig = config.copy(address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                                Log.d("AppViewModel", "Updated config - Model: ${updatedConfig.model}, Provider: ${updatedConfig.provider}, Channel: ${updatedConfig.channel}")
                                updatedConfig
                            } else {
                                config
                            }
                        }
                if (currentConfigs != newConfigs) {
                    stateHolder._imageGenApiConfigs.value = newConfigs
                    persistenceManager.saveApiConfigs(newConfigs, isImageGen = true)

                    val currentSelectedConfig = stateHolder._selectedImageGenApiConfig.value
                    Log.d("AppViewModel", "Current selected config: ${currentSelectedConfig?.model}, Channel: ${currentSelectedConfig?.channel}")
                    if (currentSelectedConfig != null &&
                                    currentSelectedConfig.key == originalKey &&
                                    currentSelectedConfig.modalityType == modality
                    ) {
                        val newSelectedConfig =
                                currentSelectedConfig.copy(address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                        stateHolder._selectedImageGenApiConfig.value = newSelectedConfig
                        Log.d("AppViewModel", "Updated selected config - Model: ${newSelectedConfig.model}, Channel: ${newSelectedConfig.channel}")
                    }

                    withContext(Dispatchers.Main) { showSnackbar("图像配置已更新") }
                }
            } else {
                // 文本生成配置
                val currentConfigs = stateHolder._apiConfigs.value
                val newConfigs =
                        currentConfigs.map { config ->
                            if (config.key == originalKey && config.modalityType == modality) {
                                config.copy(address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
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
                                currentSelectedConfig.copy(address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                        stateHolder._selectedApiConfig.value = newSelectedConfig
                    }

                    withContext(Dispatchers.Main) { showSnackbar("配置已更新") }
                }
            }
        }
    }
    
    fun updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String, providerToKeep: String, newChannel: String) {
        updateConfigGroup(representativeConfig, newAddress, newKey, providerToKeep, newChannel, null)
    }

    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val animationMap = if (simpleModeManager.isInImageMode()) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
            if (animationMap[messageId] != true) {
                animationMap[messageId] = true
            }
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        val animationMap = if (simpleModeManager.isInImageMode()) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
        return animationMap[messageId] ?: false
    }

    fun getConversationPreviewText(index: Int, isImageGeneration: Boolean = false): String {
        val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache
        val cachedPreview = cache.get(index)
        if (cachedPreview != null) return cachedPreview

        val conversationList = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }

        val conversation = conversationList.getOrNull(index)
            ?: return "对话 ${index + 1}".also {
                cache.put(index, it)
            }

        val newPreview = conversation.firstOrNull { it.text.isNotBlank() }?.text?.trim() ?: "对话 ${index + 1}"

        cache.put(index, newPreview)
        return newPreview
    }

    fun renameConversation(index: Int, newName: String, isImageGeneration: Boolean = false) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空")
            return
        }
        viewModelScope.launch {
            val success =
                    withContext(Dispatchers.Default) {
                        val currentHistoricalConvos = if (isImageGeneration)
                            stateHolder._imageGenerationHistoricalConversations.value
                        else
                            stateHolder._historicalConversations.value
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
                            if (isImageGeneration) {
                                stateHolder._imageGenerationHistoricalConversations.value =
                                    updatedHistoricalConversationsList.toList()
                            } else {
                                stateHolder._historicalConversations.value =
                                    updatedHistoricalConversationsList.toList()
                            }
                        }
    
                        withContext(Dispatchers.IO) {
                            persistenceManager.saveChatHistory(
                                    if (isImageGeneration)
                                        stateHolder._imageGenerationHistoricalConversations.value
                                    else
                                        stateHolder._historicalConversations.value,
                                    isImageGeneration = isImageGeneration
                            )
                        }
    
                        val loadedIndex =
                            if (isImageGeneration) stateHolder._loadedImageGenerationHistoryIndex.value
                            else stateHolder._loadedHistoryIndex.value
                        if (loadedIndex == index) {
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
                                    if (isImageGeneration) {
                                        stateHolder.imageGenerationMessages.clear()
                                        stateHolder.imageGenerationMessages.addAll(reloadedConversation)
                                    } else {
                                        stateHolder.messages.clear()
                                        stateHolder.messages.addAll(reloadedConversation)
                                    }
                                    reloadedConversation.forEach { msg ->
                                        val hasContentOrError = msg.contentStarted || msg.isError
                                        val hasReasoning = !msg.reasoning.isNullOrBlank()
                                        if (msg.sender == Sender.AI && hasReasoning) {
                                            if (isImageGeneration) {
                                                stateHolder.imageReasoningCompleteMap[msg.id] = true
                                            } else {
                                                stateHolder.textReasoningCompleteMap[msg.id] = true
                                            }
                                        }
                                        val animationPlayedCondition =
                                                hasContentOrError ||
                                                        (msg.sender == Sender.AI && hasReasoning)
                                        if (animationPlayedCondition) {
                                            if (isImageGeneration) {
                                                stateHolder.imageMessageAnimationStates[msg.id] = true
                                            } else {
                                                stateHolder.textMessageAnimationStates[msg.id] = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        true
                    }
            if (success) {
                withContext(Dispatchers.Main) { showSnackbar("对话已重命名") }
                val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache
                cache.put(index, trimmedNewName)
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

    fun exportSettings(isImageGen: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val settingsToExport = if (isImageGen) {
                ExportedSettings(
                    apiConfigs = stateHolder._imageGenApiConfigs.value
                )
            } else {
                ExportedSettings(
                    apiConfigs = stateHolder._apiConfigs.value
                )
            }
            val finalJson = json.encodeToString(settingsToExport)
            val fileName = if (isImageGen) "eztalk_image_settings" else "eztalk_settings"
            _settingsExportRequest.send(fileName to finalJson)
        }
    }

    fun importSettings(jsonContent: String, isImageGen: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Try parsing the new format first
                try {
                    val parsedNewSettings = json.decodeFromString<ExportedSettings>(jsonContent)
                    if (parsedNewSettings.apiConfigs.none {
                                it.id.isBlank() || it.provider.isBlank()
                            }
                    ) {
                        if (isImageGen) {
                            stateHolder._imageGenApiConfigs.value = parsedNewSettings.apiConfigs
                            val firstConfig = parsedNewSettings.apiConfigs.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedNewSettings.apiConfigs, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            stateHolder._apiConfigs.value = parsedNewSettings.apiConfigs
                            _customProviders.value = parsedNewSettings.customProviders
                            val firstConfig = parsedNewSettings.apiConfigs.firstOrNull()
                            stateHolder._selectedApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedNewSettings.apiConfigs)
                            dataSource.saveCustomProviders(parsedNewSettings.customProviders)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)
                        }

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
                        if (isImageGen) {
                            stateHolder._imageGenApiConfigs.value = parsedOldConfigs
                            val firstConfig = parsedOldConfigs.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedOldConfigs, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            stateHolder._apiConfigs.value = parsedOldConfigs
                            _customProviders.value = emptySet() // Old format has no custom providers
                            val firstConfig = parsedOldConfigs.firstOrNull()
                            stateHolder._selectedApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedOldConfigs)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)
                        }
                        dataSource.saveCustomProviders(emptySet())
                        val firstConfig = parsedOldConfigs.firstOrNull()
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

    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String, isImageGen: Boolean = false) {
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
                modalityType = if (isImageGen) com.example.everytalk.data.DataClass.ModalityType.IMAGE else com.example.everytalk.data.DataClass.ModalityType.TEXT,
                channel = channel
            )
            configManager.addConfig(tempConfig, isImageGen)

            // 2. 在后台获取模型
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(address, key)
                }
                
                // 3. 删除临时配置
                configManager.deleteConfig(tempConfig, isImageGen)

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
                            modalityType = if (isImageGen) com.example.everytalk.data.DataClass.ModalityType.IMAGE else com.example.everytalk.data.DataClass.ModalityType.TEXT,
                            channel = channel
                        )
                    }
                    newConfigs.forEach { config ->
                        configManager.addConfig(config, isImageGen)
                    }
                } else {
                     // 如果没有获取到模型，仍然创建一个空的占位配置
                    val placeholderConfig = tempConfig.copy(
                        id = UUID.randomUUID().toString(),
                        name = provider,
                        model = "",
                        channel = channel
                    )
                    configManager.addConfig(placeholderConfig, isImageGen)
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "获取模型失败", e)
                // 获取失败，更新临时配置以提示用户
                 val errorConfig = tempConfig.copy(
                    name = provider,
                    model = "",
                    channel = channel
                )
                configManager.updateConfig(errorConfig, isImageGen)
            }
        }
    }
    
    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String) {
        createConfigAndFetchModels(provider, address, key, channel, false)
    }

    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String, isImageGen: Boolean = false) {
        viewModelScope.launch {
            val newConfig = ApiConfig(
                id = UUID.randomUUID().toString(),
                name = modelName,
                provider = provider,
                address = address,
                key = apiKey,
                model = modelName,
                modalityType = if (isImageGen) com.example.everytalk.data.DataClass.ModalityType.IMAGE else com.example.everytalk.data.DataClass.ModalityType.TEXT
            )
            configManager.addConfig(newConfig, isImageGen)
        }
    }
    
    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String) {
        addModelToConfigGroup(apiKey, provider, address, modelName, false)
    }

    fun refreshModelsForConfig(config: ApiConfig) {
        val refreshId = "${config.key}-${config.modalityType}"
        viewModelScope.launch {
            _isRefreshingModels.update { it + refreshId }
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(config.address, config.key)
                }

                // 1. 删除与此API密钥和模态类型匹配的所有现有配置
                val currentConfigs = stateHolder._apiConfigs.value
                val configsToKeep = currentConfigs.filterNot {
                    it.key == config.key && it.modalityType == config.modalityType
                }

                // 2. 根据获取的模型创建新配置
                val newConfigs = models.map { modelName ->
                    ApiConfig(
                        address = config.address,
                        key = config.key,
                        model = modelName,
                        provider = config.provider,
                        name = modelName,
                        id = UUID.randomUUID().toString(),
                        isValid = true,
                        modalityType = config.modalityType,
                        channel = config.channel
                    )
                }

                val finalConfigs = configsToKeep + newConfigs

                // 3. 更新配置状态并保存
                stateHolder._apiConfigs.value = finalConfigs
                persistenceManager.saveApiConfigs(finalConfigs)

                // 4. 更新选中的配置（如果需要）
                val currentSelectedConfig = stateHolder._selectedApiConfig.value
                if (currentSelectedConfig != null &&
                    currentSelectedConfig.key == config.key &&
                    currentSelectedConfig.modalityType == config.modalityType &&
                    !finalConfigs.any { it.id == currentSelectedConfig.id }
                ) {
                    val newSelection = finalConfigs.firstOrNull {
                        it.key == config.key && it.modalityType == config.modalityType
                    }
                    stateHolder._selectedApiConfig.value = newSelection
                    persistenceManager.saveSelectedConfigIdentifier(newSelection?.id)
                }

                showSnackbar("刷新成功，获取到 ${models.size} 个模型")
            } catch (e: Exception) {
                Log.e("AppViewModel", "刷新模型失败", e)
                showSnackbar("刷新模型失败: ${e.message}")
            } finally {
                _isRefreshingModels.update { it - refreshId }
            }
        }
    }

    fun getMessageById(id: String): Message? {
        return messages.find { it.id == id } ?: imageGenerationMessages.find { it.id == id }
    }

    fun saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        if (scrollState.firstVisibleItemIndex >= 0) {
            stateHolder.conversationScrollStates[conversationId] = scrollState
        }
    }

    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendReasoningToMessage(messageId, text, isImageGeneration)
        }
    }

    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.appendContentToMessage(messageId, text, isImageGeneration)
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            onAiMessageFullTextChanged(messageId, messageList.find { it.id == messageId }?.text ?: "")
        }
    }

    fun getScrollState(conversationId: String): ConversationScrollState? {
        return stateHolder.conversationScrollStates[conversationId]
    }
    fun onAppStop() {
       viewModelScope.launch(Dispatchers.IO) {
           val textMessages = stateHolder.messages.toList()
           if (textMessages.isNotEmpty()) {
               persistenceManager.saveLastOpenChat(textMessages, isImageGeneration = false)
           }

           val imageGenMessages = stateHolder.imageGenerationMessages.toList()
           if (imageGenMessages.isNotEmpty()) {
               persistenceManager.saveLastOpenChat(imageGenMessages, isImageGeneration = true)
           }
       }
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
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
                persistenceManager.saveApiConfigs(finalApiConfigs)
                persistenceManager.saveSelectedConfigIdentifier(finalSelectedConfigId)
                dataSource.saveCustomProviders(_customProviders.value)
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error saving state onCleared", e)
            }
        }
        super.onCleared()
    }

    private fun encodeUriAsBase64(uri: Uri): String? {
        return try {
            val contentResolver = getApplication<Application>().contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("AppViewModel", "Failed to encode URI to Base64", e)
            null
        }
    }
    
    // 图像生成错误处理方法
    fun dismissImageGenerationErrorDialog() {
        stateHolder.dismissImageGenerationErrorDialog()
    }
    
    fun showImageGenerationError(error: String) {
        stateHolder.setImageGenerationError(error)
        stateHolder.showImageGenerationErrorDialog(true)
    }

}
