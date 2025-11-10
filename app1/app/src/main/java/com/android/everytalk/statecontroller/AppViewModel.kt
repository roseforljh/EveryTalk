package com.android.everytalk.statecontroller

import android.app.Application
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
import coil3.util.DebugLogger
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.util.FileManager
import com.android.everytalk.data.DataClass.GithubRelease
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.local.SharedPreferencesDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.CacheManager
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.statecontroller.viewmodel.DrawerManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.UpdateManager
import com.android.everytalk.statecontroller.ui.MessageItemsController
import com.android.everytalk.statecontroller.controller.SystemPromptController
import com.android.everytalk.statecontroller.controller.SettingsController
import com.android.everytalk.statecontroller.controller.HistoryController
import com.android.everytalk.statecontroller.controller.MediaController
import com.android.everytalk.statecontroller.controller.MessageContentController
import com.android.everytalk.statecontroller.controller.ConversationPreviewController
import com.android.everytalk.statecontroller.controller.ModelAndConfigController
import com.android.everytalk.statecontroller.controller.RegenerateController
import com.android.everytalk.statecontroller.controller.StreamingControls
import com.android.everytalk.statecontroller.ui.UiStateFacade
import com.android.everytalk.statecontroller.controller.LifecycleCoordinator
import com.android.everytalk.statecontroller.controller.ScrollStateController
import com.android.everytalk.statecontroller.controller.AnimationStateController
import com.android.everytalk.statecontroller.controller.EditMessageController
import com.android.everytalk.statecontroller.controller.ClipboardController
import com.android.everytalk.statecontroller.controller.ConfigFacade
import com.android.everytalk.statecontroller.controller.ProviderController

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

    // 高级缓存管理器
    private val cacheManager by lazy { CacheManager.getInstance(application.applicationContext) }
    // 原图文件管理器（用于原样字节落地与下载）
    private val fileManager by lazy { FileManager(application.applicationContext) }
    
    private val messagesMutex = Mutex()
    private val historyMutex = Mutex()
    private val textConversationPreviewCache = LruCache<Int, String>(100)
    private val imageConversationPreviewCache = LruCache<Int, String>(100)
    internal val stateHolder = ViewModelStateHolder().apply {
        // Initialize with data source for persistent parameter storage
        initializeDataSource(dataSource)
    }
    
    // 🎯 手势冲突管理器（用于协调代码块滚动和抽屉手势）
    val gestureManager = com.android.everytalk.ui.components.GestureConflictManager()
    
    // 🎯 流式消息状态管理器（用于实时流式内容观察）
    val streamingMessageStateManager get() = stateHolder.streamingMessageStateManager
    
    private val imageLoader = ImageLoader.Builder(application.applicationContext)
        .logger(DebugLogger())
        .build()
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
                    },
                    scope = viewModelScope
            )
    
    // 公开的模式管理器 - 供设置界面等外部组件使用
    val simpleModeManager = SimpleModeManager(stateHolder, historyManager, viewModelScope)

    // 只读 UI 状态门面（逐步替换直接暴露的 StateFlow/Snapshot 访问）
    private val uiStateFacade by lazy { UiStateFacade(stateHolder, simpleModeManager) }
    val ui: UiStateFacade
        get() = uiStateFacade

    // 向UI层公开“意图模式”StateFlow，避免基于内容态推断造成的短暂不一致
    val uiModeFlow: StateFlow<SimpleModeManager.ModeType>
        get() = simpleModeManager.uiModeFlow

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(
                stateHolder,
                viewModelScope,
                historyManager,
                onAiMessageFullTextChanged = ::onAiMessageFullTextChanged,
                ::triggerScrollToBottom
        )
    }
    private val configManager: ConfigManager by lazy {
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }
    private val configFacade by lazy { ConfigFacade(configManager) }

    private val messageSender: MessageSender by lazy {
        MessageSender(
                application = getApplication(),
                viewModelScope = viewModelScope,
                stateHolder = stateHolder,
                apiHandler = apiHandler,
                historyManager = historyManager,
                showSnackbar = ::showSnackbar,
                triggerScrollToBottom = { triggerScrollToBottom() },
                uriToBase64Encoder = { uri -> encodeUriAsBase64(uri) }
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

    // 🎯 重构：使用管理器类来组织代码
    private val exportManager = ExportManager()
    val exportRequest: Flow<Pair<String, String>> = exportManager.exportRequest
    val settingsExportRequest: Flow<Pair<String, String>> = exportManager.settingsExportRequest

    private val dialogManager = DialogManager()
    private val editMessageController = EditMessageController(
        stateHolder = stateHolder,
        dialogManager = dialogManager,
        historyManager = historyManager,
        scope = viewModelScope,
        messagesMutex = messagesMutex
    )
    val showEditDialog: StateFlow<Boolean> = dialogManager.showEditDialog
    val editingMessage: StateFlow<Message?> = dialogManager.editingMessage
    val showSelectableTextDialog: StateFlow<Boolean> = dialogManager.showSelectableTextDialog
    val textForSelectionDialog: StateFlow<String> = dialogManager.textForSelectionDialog
    val showSystemPromptDialog: StateFlow<Boolean> = dialogManager.showSystemPromptDialog
    val showAboutDialog: StateFlow<Boolean> = dialogManager.showAboutDialog
    val showClearImageHistoryDialog: StateFlow<Boolean> = dialogManager.showClearImageHistoryDialog
    val editDialogInputText: StateFlow<String>
        get() = stateHolder._editDialogInputText.asStateFlow()
    
    // 🎯 新增:添加配置流程相关的对话框状态
    val showAutoFetchConfirmDialog: StateFlow<Boolean>
        get() = stateHolder._showAutoFetchConfirmDialog.asStateFlow()
    val showModelSelectionDialog: StateFlow<Boolean>
        get() = stateHolder._showModelSelectionDialog.asStateFlow()
    val pendingConfigParams: StateFlow<PendingConfigParams?>
        get() = stateHolder._pendingConfigParams.asStateFlow()
    
    // 剪贴板/导出 控制器
    private val clipboardController = ClipboardController(
        application = getApplication(),
        exportManager = exportManager,
        scope = viewModelScope,
        showSnackbar = ::showSnackbar
    )
    
    private val drawerManager = DrawerManager()
    val isSearchActiveInDrawer: StateFlow<Boolean> = drawerManager.isSearchActiveInDrawer
    val expandedDrawerItemIndex: StateFlow<Int?> = drawerManager.expandedDrawerItemIndex
    val searchQueryInDrawer: StateFlow<String> = drawerManager.searchQueryInDrawer

    // 滚动状态控制器
    private val scrollStateController = ScrollStateController(stateHolder)
    // 动画状态控制器
    private val animationStateController = AnimationStateController(stateHolder) { simpleModeManager.isInImageMode() }

    private val providerManager = ProviderManager(viewModelScope)
    val customProviders: StateFlow<Set<String>> = providerManager.customProviders
    val allProviders: StateFlow<List<String>> = providerManager.allProviders
    
    private val providerController = ProviderController(
        stateHolder = stateHolder,
        providerManager = providerManager,
        configManager = configManager,
        dataSource = dataSource,
        scope = viewModelScope
    )
    
    
    val isWebSearchEnabled: StateFlow<Boolean>
        get() = stateHolder._isWebSearchEnabled.asStateFlow()
    val showSourcesDialog: StateFlow<Boolean>
        get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>>
        get() = stateHolder._sourcesForDialog.asStateFlow()
    val isStreamingPaused: StateFlow<Boolean>
        get() = stateHolder._isStreamingPaused.asStateFlow()

    val systemPrompt: StateFlow<String> = stateHolder._currentConversationId.flatMapLatest { id ->
        snapshotFlow { stateHolder.systemPrompts[id] ?: "" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    // 当前会话是否“接入系统提示”（开始/暂停）
    val isSystemPromptEngaged: StateFlow<Boolean> = stateHolder._currentConversationId.flatMapLatest { id ->
        snapshotFlow { stateHolder.systemPromptEngagedState[id] ?: false }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    // SystemPrompt moved to SystemPromptController
 
   private val updateManager = UpdateManager(
       application = getApplication(),
       scope = viewModelScope,
       showSnackbar = ::showSnackbar
   )
   val latestReleaseInfo: StateFlow<GithubRelease?> = updateManager.latestReleaseInfo
   // 控制器：系统提示
   private val systemPromptController = SystemPromptController(stateHolder, dialogManager, historyManager, viewModelScope)
   // 委托到 MessageItemsController，减少 AppViewModel 体积
   private val messageItemsController = MessageItemsController(stateHolder, viewModelScope)
   val chatListItems: StateFlow<List<ChatListItem>> get() = messageItemsController.chatListItems
   val imageGenerationChatListItems: StateFlow<List<ChatListItem>> get() = messageItemsController.imageGenerationChatListItems
   private val modelFetchManager = com.android.everytalk.statecontroller.viewmodel.ModelFetchManager()
   val isFetchingModels: StateFlow<Boolean> = modelFetchManager.isFetchingModels
   val fetchedModels: StateFlow<List<String>> = modelFetchManager.fetchedModels
   val isRefreshingModels: StateFlow<Set<String>> = modelFetchManager.isRefreshingModels

   // 控制器：设置导入/导出
   private val settingsController = SettingsController(
       stateHolder = stateHolder,
       persistenceManager = persistenceManager,
       providerManager = providerManager,
       dataSource = dataSource,
       exportManager = exportManager,
       json = json,
       showSnackbar = ::showSnackbar,
       scope = viewModelScope
   )

   // 适配器：供 HistoryController 切换/加载模式
   private val simpleModeBridge = object : HistoryController.SimpleModeSwitcher {
       override fun switchToTextMode(forceNew: Boolean, skipSavingTextChat: Boolean) {
           viewModelScope.launch {
               simpleModeManager.switchToTextMode(forceNew, skipSavingTextChat)
           }
       }
       override fun switchToImageMode(forceNew: Boolean, skipSavingImageChat: Boolean) {
           viewModelScope.launch {
               simpleModeManager.switchToImageMode(forceNew, skipSavingImageChat)
           }
       }
       override suspend fun loadTextHistory(index: Int) {
           simpleModeManager.loadTextHistory(index)
       }
       override suspend fun loadImageHistory(index: Int) {
           simpleModeManager.loadImageHistory(index)
       }
       override fun isInImageMode(): Boolean = simpleModeManager.isInImageMode()
   }

   // 控制器：历史与会话管理
   private val historyController = HistoryController(
       stateHolder = stateHolder,
       historyManager = historyManager,
       cacheManager = cacheManager,
       apiHandler = apiHandler,
       scope = viewModelScope,
       showSnackbar = ::showSnackbar,
       shouldAutoScroll = { stateHolder.shouldAutoScroll() },
       triggerScrollToBottom = { triggerScrollToBottom() },
       simpleModeSwitcher = simpleModeBridge
   )

   // 控制器：媒体下载/保存
   private val mediaController = MediaController(
       application = getApplication(),
       fileManager = fileManager,
       scope = viewModelScope,
       showSnackbar = ::showSnackbar
   )

   // 控制器：消息内容/流式追加
   private val messageContentController = MessageContentController(
       stateHolder = stateHolder,
       scope = viewModelScope,
       messagesMutex = messagesMutex,
       triggerScrollToBottom = { triggerScrollToBottom() }
   )

   // 控制器：会话预览/命名
   private val conversationPreviewController = ConversationPreviewController(
       stateHolder = stateHolder,
       cacheManager = cacheManager,
       scope = viewModelScope,
       textConversationPreviewCache = textConversationPreviewCache,
       imageConversationPreviewCache = imageConversationPreviewCache
   )

   // 控制器：模型/配置 管理
   private val modelAndConfigController = ModelAndConfigController(
       stateHolder = stateHolder,
       persistenceManager = persistenceManager,
       modelFetchManager = modelFetchManager,
       configManager = configManager,
       scope = viewModelScope,
       showSnackbar = ::showSnackbar,
       emitManualModelInputRequest = { provider, address, key, channel, isImageGen ->
           // 控制器请求显示“手动输入模型”对话框时，通过 SharedFlow 通知 UI
           viewModelScope.launch {
               _showManualModelInputRequest.emit(
                   ManualModelInputRequest(provider, address, key, channel, isImageGen)
               )
           }
       }
   )

   // 控制器：从用户消息点重新生成流程
   private val regenerateController = RegenerateController(
       stateHolder = stateHolder,
       apiHandler = apiHandler,
       historyManager = historyManager,
       scope = viewModelScope,
       messagesMutex = messagesMutex,
       persistenceDeleteMediaFor = { lists ->
           withContext(Dispatchers.IO) {
               // 删除被裁剪消息关联的媒体文件
               persistenceManager.deleteMediaFilesForMessages(lists)
           }
       },
       showSnackbar = ::showSnackbar,
       shouldAutoScroll = { stateHolder.shouldAutoScroll() },
       triggerScrollToBottom = { triggerScrollToBottom() },
       sendMessage = { messageText, isFromRegeneration, attachments, isImageGeneration ->
           messageSender.sendMessage(
               messageText = messageText,
               isFromRegeneration = isFromRegeneration,
               attachments = attachments,
               isImageGeneration = isImageGeneration
           )
       }
   )

   // 控制器：统一流式暂停/恢复/flush
   private val streamingControls = StreamingControls(
       stateHolder = stateHolder,
       apiHandler = apiHandler,
       scope = viewModelScope,
       isImageModeProvider = { simpleModeManager.isInImageMode() },
       triggerScrollToBottom = { triggerScrollToBottom() },
       showSnackbar = ::showSnackbar
   )

   // 生命周期协调器：统一 save/clear/low-memory 策略
   private val lifecycleCoordinator = LifecycleCoordinator(
       stateHolder = stateHolder,
       historyManager = historyManager,
       cacheManager = cacheManager,
       conversationPreviewController = conversationPreviewController,
       scope = viewModelScope
   )

  init {
        // 加载自定义提供商
        viewModelScope.launch(Dispatchers.IO) {
            val loadedCustomProviders = dataSource.loadCustomProviders()
            providerManager.setCustomProviders(loadedCustomProviders)
        }

        // 优化：分阶段初始化，优先加载关键配置
        // 调整：启用"上次打开会话"的恢复，保证多轮上下文在重启后延续（含图像模式）
        persistenceManager.loadInitialData(loadLastChat = true) {
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
                
                // 预热缓存系统
                viewModelScope.launch(Dispatchers.Default) {
                    delay(1000) // 延迟预热，避免影响启动性能
                    initializeCacheWarmup()
                }
                
                // 加载分组信息
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val groups = persistenceManager.loadConversationGroups()
                        stateHolder.conversationGroups.value = groups
                        Log.d("AppViewModel", "分组信息已加载 - 共 ${groups.size} 个分组")
                    } catch (e: Exception) {
                        Log.e("AppViewModel", "加载分组信息失败", e)
                    }
                }
            }
            
            // 加载置顶集合
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val pinnedTextIds = persistenceManager.loadPinnedIds(isImageGeneration = false)
                    val pinnedImageIds = persistenceManager.loadPinnedIds(isImageGeneration = true)
                    stateHolder.pinnedTextConversationIds.value = pinnedTextIds
                    stateHolder.pinnedImageConversationIds.value = pinnedImageIds
                    Log.d("AppViewModel", "置顶集合已加载 - 文本: ${pinnedTextIds.size}, 图像: ${pinnedImageIds.size}")
                } catch (e: Exception) {
                    Log.e("AppViewModel", "加载置顶集合失败", e)
                }
            }
        }

        // 延迟初始化非关键组件
        viewModelScope.launch(Dispatchers.IO) {
            // 确保API配置加载完成后再初始化这些组件
            delay(100) // 给UI一些时间渲染
            apiHandler
            configManager
            messageSender
            stateHolder.setApiHandler(apiHandler)
        }
        
        // 启动缓存维护任务
        viewModelScope.launch {
            while (true) {
                delay(60_000) // 每分钟检查一次
                cacheManager.smartCleanup()
            }
        }

        // 清理任务
       if (messages.isEmpty() && imageGenerationMessages.isEmpty()) {
           startNewChat()
       }
       
       // Initialize buffer scope for StreamingBuffer operations
       stateHolder.initializeBufferScope(viewModelScope)
    }
    
    /**
     * Get streaming content for a message
     * 
     * This method provides access to the real-time streaming content for a message.
     * During streaming, it returns content from the StreamingMessageStateManager.
     * After streaming completes, it returns the final content from the message itself.
     * 
     * This enables efficient recomposition by allowing UI components to observe
     * only the streaming content changes without triggering recomposition of the
     * entire message list.
     * 
     * Requirements: 1.4, 3.4
     * 
     * @param messageId The ID of the message
     * @return StateFlow of the message content (streaming or final)
     */
    fun getStreamingContent(messageId: String): StateFlow<String> {
        return messageContentController.getStreamingContent(messageId)
    }
    
    /**
     * Alias for getStreamingContent for backward compatibility
     */
    fun getStreamingText(messageId: String): StateFlow<String> {
        return messageContentController.getStreamingText(messageId)
    }

    fun showAboutDialog() {
        dialogManager.showAboutDialog()
    }

    fun dismissAboutDialog() {
        dialogManager.dismissAboutDialog()
    }

     fun checkForUpdates() {
         updateManager.checkForUpdates()
     }

    fun clearUpdateInfo() {
        updateManager.clearUpdateInfo()
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

    // 气泡状态与 ChatListItem 构建已委托到 MessageItemsController

    private suspend fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean = withContext(Dispatchers.Default) {
        if (list1 == null && list2 == null) return@withContext true
        if (list1 == null || list2 == null) return@withContext false

        // 统一规范化：完全忽略所有 System 消息，仅比较 User/AI 的“实质内容”
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return@withContext false

        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]

            val textMatch = msg1.text.trim() == msg2.text.trim()
            val reasoningMatch = (msg1.reasoning ?: "").trim() == (msg2.reasoning ?: "").trim()
            val attachmentsMatch = msg1.attachments.size == msg2.attachments.size &&
                msg1.attachments.map {
                    when (it) {
                        is SelectedMediaItem.ImageFromUri -> it.uri
                        is SelectedMediaItem.GenericFile -> it.uri
                        is SelectedMediaItem.Audio -> it.data
                        is SelectedMediaItem.ImageFromBitmap -> it.filePath
                    }
                }.filterNotNull().toSet() ==
                msg2.attachments.map {
                    when (it) {
                        is SelectedMediaItem.ImageFromUri -> it.uri
                        is SelectedMediaItem.GenericFile -> it.uri
                        is SelectedMediaItem.Audio -> it.data
                        is SelectedMediaItem.ImageFromBitmap -> it.filePath
                    }
                }.filterNotNull().toSet()

            // 图像内容等效性：仅比较是否存在及数量，不比较签名参数等易变部分
            val imagesCount1 = msg1.imageUrls?.size ?: 0
            val imagesCount2 = msg2.imageUrls?.size ?: 0
            val imagesMatch = imagesCount1 == imagesCount2

            // 忽略 id/timestamp/动画/占位等不稳定字段，仅对“角色 + 内容”判等
            if (
                msg1.sender != msg2.sender ||
                msg1.isError != msg2.isError ||
                !textMatch ||
                !reasoningMatch ||
                !attachmentsMatch ||
                !imagesMatch
            ) {
                return@withContext false
            }
        }
        return@withContext true
    }

    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.asSequence()
            .filter { !it.isError }
            .filter { msg ->
                when (msg.sender) {
                    Sender.User -> true
                    // 仅当AI具有“实际内容”时参与比较：文本/推理/图片三者任一存在
                    Sender.AI -> msg.text.isNotBlank() ||
                                 !(msg.reasoning ?: "").isBlank() ||
                                 ((msg.imageUrls?.isNotEmpty()) == true)
                    // 完全忽略 System（含占位标题与真实系统提示），避免系统提示差异导致的误判
                    Sender.System -> false
                    else -> true
                }
            }
            .map { it.copy(text = it.text.trim(), reasoning = it.reasoning?.trim()) }
            .toList()
    }

    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
    }


    fun showSnackbar(message: String) {
        viewModelScope.launch { stateHolder._snackbarMessage.emit(message) }
    }

    fun setSearchActiveInDrawer(isActive: Boolean) {
        drawerManager.setSearchActive(isActive)
    }

    fun setExpandedDrawerItemIndex(index: Int?) {
        drawerManager.setExpandedItemIndex(index)
    }

    fun onDrawerSearchQueryChange(query: String) {
        drawerManager.onSearchQueryChange(query)
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
        if (editingMessage.value != null && isImageGeneration) {
            confirmImageGenerationMessageEdit(messageText)
        } else {
            // 仅在“接入系统提示”开启时，才把系统提示注入到本次会话
            val engaged = stateHolder.systemPromptEngagedState[stateHolder._currentConversationId.value] ?: false
            val promptToUse = if (engaged) systemPrompt.value else null
            messageSender.sendMessage(
                messageText,
                isFromRegeneration,
                attachments,
                audioBase64 = audioBase64,
                mimeType = mimeType,
                systemPrompt = promptToUse,
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
    
    // 更新当前会话的生成参数
    fun updateConversationParameters(temperature: Float, topP: Float, maxTokens: Int?) {
        val config = GenerationConfig(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens
        )
        // 1) 立即让本会话生效（UI与请求立刻可见）
        stateHolder.updateCurrentConversationConfig(config)
        // 2) 若会话非空，强制保存到历史，确保将参数映射迁移/写入稳定的 history_chat_{index} 键，避免重启后丢回默认
        if (stateHolder.messages.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
                } catch (_: Exception) {
                    // 避免影响UI流
                }
            }
        }
    }
    
    // 供外部调用的保存历史记录方法（用于语音模式等）
    fun saveCurrentChatToHistory(forceSave: Boolean = true, isImageGeneration: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = forceSave, isImageGeneration = isImageGeneration)
            } catch (e: Exception) {
                Log.e("AppViewModel", "Failed to save chat to history", e)
            }
        }
    }
    
    // 获取当前会话的生成参数
    fun getCurrentConversationParameters(): GenerationConfig? {
        // 严格按会话返回；新会话默认无配置（maxTokens 关闭）
        return stateHolder.getCurrentConversationConfig()
    }

    fun onEditDialogTextChanged(newText: String) {
        editMessageController.onEditDialogTextChanged(newText)
    }

    fun requestEditMessage(message: Message, isImageGeneration: Boolean = false) {
        editMessageController.requestEditMessage(message, isImageGeneration)
    }

    fun confirmMessageEdit() {
        editMessageController.confirmMessageEdit()
    }

    fun confirmImageGenerationMessageEdit(updatedText: String) {
        editMessageController.confirmImageGenerationMessageEdit(updatedText)
    }

    fun dismissEditDialog() {
        editMessageController.dismissEditDialog()
    }

    fun cancelEditing() {
        editMessageController.cancelEditing()
    }

    fun regenerateAiResponse(message: Message, isImageGeneration: Boolean = false) {
        regenerateController.regenerateFrom(message, isImageGeneration)
    }

   fun showSystemPromptDialog() {
       systemPromptController.showSystemPromptDialog(systemPrompt.value)
   }

   fun dismissSystemPromptDialog() {
       systemPromptController.dismissSystemPromptDialog()
   }

   fun onSystemPromptChange(newPrompt: String) {
       systemPromptController.onSystemPromptChange(newPrompt)
   }

   /**
     * 清空系统提示
     * 这个方法专门用于处理系统提示的清空操作，确保originalSystemPrompt也被正确设置
     */
    fun clearSystemPrompt() {
        systemPromptController.clearSystemPrompt()
    }

    fun saveSystemPrompt() {
        systemPromptController.saveSystemPrompt()
    }

   fun toggleSystemPromptExpanded() {
       systemPromptController.toggleSystemPromptExpanded()
   }
   
   // 切换“系统提示接入”状态（开始/暂停）
   fun toggleSystemPromptEngaged() {
       systemPromptController.toggleSystemPromptEngaged()
   }
   
   // 显式设置接入状态
   fun setSystemPromptEngaged(enabled: Boolean) {
       systemPromptController.setSystemPromptEngaged(enabled)
   }

    fun triggerScrollToBottom() {
        viewModelScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    fun onCancelAPICall() {
        // 根据当前模式取消对应的流/任务，确保图像模式可被中止
        val isImageMode = simpleModeManager.isInImageMode()
        apiHandler.cancelCurrentApiJob("用户取消操作", isNewMessageSend = false, isImageGeneration = isImageMode)
    }

    /**
     * 切换“暂停/继续”流式显示。
     * 暂停：仍然接收并解析后端数据，但不更新UI；
     * 继续：一次性将暂停期间累积的文本刷新到UI。
     */
    fun toggleStreamingPause() = streamingControls.togglePause()

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
                    if (isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
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
                // 修复：始终强制新建图像会话，避免复用上一会话
                simpleModeManager.switchToImageMode(forceNew = true)
                
                messagesMutex.withLock {
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                    if (isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error starting new image generation", e)
                showSnackbar("启动新图像生成失败: ${e.message}")
            }
        }
    }

    fun loadConversationFromHistory(index: Int) {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载文本模式历史索引 $index", isNewMessageSend = false, isImageGeneration = false)
        historyController.loadTextHistory(index)
    }

    fun loadImageGenerationConversationFromHistory(index: Int) {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载图像模式历史索引 $index", isNewMessageSend = false, isImageGeneration = true)
        historyController.loadImageHistory(index)
    }

    fun deleteConversation(indexToDelete: Int) {
        historyController.deleteConversation(indexToDelete, isImageGeneration = false)
        // 删除后清理置顶集合中已不存在的会话ID
        cleanupPinnedIds(isImageGeneration = false)
    }
    fun deleteImageGenerationConversation(indexToDelete: Int) {
        historyController.deleteConversation(indexToDelete, isImageGeneration = true)
        // 删除后清理置顶集合中已不存在的会话ID
        cleanupPinnedIds(isImageGeneration = true)
    }

    fun clearAllConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        historyController.clearAllConversations(isImageGeneration = false)
        conversationPreviewController.clearAllCaches()
        // 清空所有文本置顶
        stateHolder.pinnedTextConversationIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.savePinnedIds(emptySet(), isImageGeneration = false)
        }
    }

    fun clearAllImageGenerationConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有图像生成历史记录")
        historyController.clearAllConversations(isImageGeneration = true)
        conversationPreviewController.clearAllCaches()
        // 清空所有图像置顶
        stateHolder.pinnedImageConversationIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.savePinnedIds(emptySet(), isImageGeneration = true)
        }
    }

    fun showClearImageHistoryDialog() {
        dialogManager.showClearImageHistoryDialog()
    }

    fun dismissClearImageHistoryDialog() {
        dialogManager.dismissClearImageHistoryDialog()
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
        dialogManager.showSelectableTextDialog(text)
    }

    fun dismissSelectableTextDialog() {
        dialogManager.dismissSelectableTextDialog()
    }

    fun copyToClipboard(text: String) {
        clipboardController.copyToClipboard(text)
    }

    fun exportMessageText(text: String) {
        clipboardController.exportMessageText(text)
    }

    fun downloadImageFromMessage(message: Message) {
        mediaController.downloadImageFromMessage(message)
    }

    private fun saveBitmapToDownloads(bitmap: Bitmap) {
        mediaController.saveBitmapToDownloads(bitmap)
    }
 
    fun addConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.addConfig(config, isImageGen)

    fun addMultipleConfigs(configs: List<ApiConfig>) {
        viewModelScope.launch {
            configFacade.addMultipleConfigs(configs)
        }
    }
    fun updateConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.updateConfig(config, isImageGen)
    fun deleteConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.deleteConfig(config, isImageGen)
    fun deleteConfigGroup(
            representativeConfig: ApiConfig,
            isImageGen: Boolean = false
    ) {
        configFacade.deleteConfigGroup(representativeConfig, isImageGen)
    }
    
    fun deleteImageGenConfigGroup(
            representativeConfig: ApiConfig
    ) {
        configFacade.deleteConfigGroup(representativeConfig, isImageGen = true)
    }
    
    fun clearAllConfigs(isImageGen: Boolean = false) = configFacade.clearAllConfigs(isImageGen)
    fun selectConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.selectConfig(config, isImageGen)
    fun clearSelectedConfig(isImageGen: Boolean = false) {
        configFacade.clearSelectedConfig(isImageGen)
    }

    fun saveApiConfigs() {
        configFacade.saveApiConfigs()
    }

    fun addProvider(providerName: String) {
        providerController.addProvider(providerName)
    }

    fun deleteProvider(providerName: String) {
        providerController.deleteProvider(providerName)
    }

    fun updateConfigGroup(
        representativeConfig: ApiConfig,
        newAddress: String,
        newKey: String,
        providerToKeep: String,
        newChannel: String,
        isImageGen: Boolean? = null
    ) {
        // providerToKeep 仅为兼容保留，不参与字段更新
        configFacade.updateConfigGroup(
            representativeConfig = representativeConfig,
            newAddress = newAddress,
            newKey = newKey,
            newChannel = newChannel,
            isImageGen = isImageGen
        )
    }
    
    fun updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String, providerToKeep: String, newChannel: String) {
        updateConfigGroup(representativeConfig, newAddress, newKey, providerToKeep, newChannel, null)
    }

    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            animationStateController.onAnimationComplete(messageId)
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        return animationStateController.hasAnimationBeenPlayed(messageId)
    }

    fun getConversationPreviewText(index: Int, isImageGeneration: Boolean = false): String {
        return conversationPreviewController.getConversationPreviewText(index, isImageGeneration)
    }
    

    fun getConversationFullText(index: Int, isImageGeneration: Boolean = false): String {
        return historyController.getConversationFullText(index, isImageGeneration)
    }

    fun renameConversation(index: Int, newName: String, isImageGeneration: Boolean = false) {
        historyController.renameConversation(index, newName, isImageGeneration)
        // 通过控制器更新本地预览缓存，避免在 VM 内直接操作 LruCache
        conversationPreviewController.setCachedTitle(index, isImageGeneration, newName)
    }

    private fun onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        messageContentController.onAiMessageFullTextChanged(messageId, currentFullText)
    }

    fun exportSettings(isImageGen: Boolean = false) {
        settingsController.exportSettings(isImageGen)
    }

    fun importSettings(jsonContent: String, isImageGen: Boolean = false) {
        settingsController.importSettings(jsonContent, isImageGen)
    }

    // 应用暂停或停止时保存当前对话状态
    fun onAppStop() {
        lifecycleCoordinator.saveOnStop()
    }

    fun fetchModels(apiUrl: String, apiKey: String) {
        modelAndConfigController.fetchModels(apiUrl, apiKey)
    }

    fun clearFetchedModels() {
        modelAndConfigController.clearFetchedModels()
    }

    fun createMultipleConfigs(provider: String, address: String, key: String, modelNames: List<String>) {
        modelAndConfigController.createMultipleConfigs(provider, address, key, modelNames)
    }

    // 新增：用于通知UI显示添加模型对话框的 Flow
    private val _showManualModelInputRequest = MutableSharedFlow<ManualModelInputRequest>(replay = 0)
    val showManualModelInputRequest: SharedFlow<ManualModelInputRequest> = _showManualModelInputRequest.asSharedFlow()
    
    data class ManualModelInputRequest(
        val provider: String,
        val address: String,
        val key: String,
        val channel: String,
        val isImageGen: Boolean
    )

    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String, isImageGen: Boolean = false) {
        modelAndConfigController.createConfigAndFetchModels(provider, address, key, channel, isImageGen)
    }
    
    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String) {
        createConfigAndFetchModels(provider, address, key, channel, false)
    }

    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String, channel: String, isImageGen: Boolean = false) {
        modelAndConfigController.addModelToConfigGroup(apiKey, provider, address, modelName, channel, isImageGen)
    }
    
    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String) {
        addModelToConfigGroup(apiKey, provider, address, modelName, "OpenAI兼容", false)
    }

    fun refreshModelsForConfig(config: ApiConfig) {
        modelAndConfigController.refreshModelsForConfig(config)
    }

    fun getMessageById(id: String): Message? {
        return messages.find { it.id == id } ?: imageGenerationMessages.find { it.id == id }
    }

    fun saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        scrollStateController.saveScrollState(conversationId, scrollState)
    }

    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        messageContentController.appendReasoningToMessage(messageId, text, isImageGeneration)
    }

    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        messageContentController.appendContentToMessage(messageId, text, isImageGeneration)
    }

    fun getScrollState(conversationId: String): ConversationScrollState? {
        return scrollStateController.getScrollState(conversationId)
    }
    
    /**
     * 🎯 将URI编码为Base64字符串
     */
    private fun encodeUriAsBase64(uri: Uri): String? {
        return try {
            val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val bytes = stream.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("AppViewModel", "Failed to encode URI to Base64: $uri", e)
            null
        }
    }
    
    /**
     * 🎯 处理加载的消息列表，确保完整性
     */
    // 历史消息的完整性修复已移至 HistoryController
    
    /**
     * 初始化缓存预热
     */
    private suspend fun initializeCacheWarmup() {
        try {
            val textHistory = stateHolder._historicalConversations.value
            val imageHistory = stateHolder._imageGenerationHistoricalConversations.value
            
            // 预热会话预览缓存
            cacheManager.warmupCache(textHistory + imageHistory)
            
            Log.d("AppViewModel", "缓存预热完成 - 文本会话: ${textHistory.size}, 图像会话: ${imageHistory.size}")
        } catch (e: Exception) {
            Log.w("AppViewModel", "缓存预热失败", e)
        }
    }
    
    /**
     * 获取缓存统计信息（用于调试）
     */
    fun getCacheStats(): String {
        val stats = cacheManager.getCacheStats()
        val textStats = com.android.everytalk.ui.performance.OptimizedTextProcessor.getCacheStats()
        
        return """
            |总体缓存命中率: ${"%.1f".format(stats.overallHitRate * 100)}%
            |会话预览缓存: ${stats.conversationPreviewHits}/${stats.conversationPreviewHits + stats.conversationPreviewMisses}
            |消息内容缓存: ${stats.messageContentHits}/${stats.messageContentHits + stats.messageContentMisses}
            |Markdown缓存: ${stats.markdownHits}/${stats.markdownHits + stats.markdownMisses}
            |API响应缓存: ${stats.apiResponseSize}
            |文本处理缓存: ${textStats.textCacheSize} (命中率: ${"%.1f".format(textStats.textCacheHitRate * 100)}%)
            |总缓存条目: ${stats.totalCacheSize}
        """.trimMargin()
    }
    
    /**
     * 清理所有缓存
     */
    fun clearAllCaches() {
        lifecycleCoordinator.clearAllCaches()
    }
    
    // ===== 配置添加流程：交互逻辑 =====
    fun startAddConfigFlow(
        provider: String,
        address: String,
        key: String,
        channel: String,
        isImageGen: Boolean = false
    ) {
        stateHolder._pendingConfigParams.value = PendingConfigParams(
            provider = provider,
            address = address,
            key = key,
            channel = channel,
            isImageGen = isImageGen
        )
        stateHolder._showAutoFetchConfirmDialog.value = true
    }

    fun onConfirmAutoFetch() {
        stateHolder._showAutoFetchConfirmDialog.value = false
        val params = stateHolder._pendingConfigParams.value ?: return

        // 触发拉取
        fetchModels(params.address, params.key)

        // 等待结果并分支处理
        viewModelScope.launch {
            isFetchingModels
                .flatMapLatest { fetching ->
                    // 简单等待到false，再读取结果
                    kotlinx.coroutines.flow.flow { if (!fetching) emit(Unit) }
                }
                .first()

            val models = fetchedModels.value
            if (models.isNotEmpty()) {
                stateHolder._showModelSelectionDialog.value = true
            } else {
                showSnackbar("获取模型列表失败,请手动输入模型名称")
                onManualInput()
            }
        }
    }

    fun onManualInput() {
        stateHolder._showAutoFetchConfirmDialog.value = false
        stateHolder._showModelSelectionDialog.value = false

        val params = stateHolder._pendingConfigParams.value ?: return
        // 通知UI显示手动输入对话框（项目中已存在该Flow）
        viewModelScope.launch {
            _showManualModelInputRequest.emit(
                ManualModelInputRequest(
                    provider = params.provider,
                    address = params.address,
                    key = params.key,
                    channel = params.channel,
                    isImageGen = params.isImageGen
                )
            )
        }
        // 不清理pending，等用户真正提交或取消后清理；保持上下文
    }

    fun dismissAutoFetchConfirmDialog() {
        stateHolder._showAutoFetchConfirmDialog.value = false
    }

    fun dismissModelSelectionDialog() {
        stateHolder._showModelSelectionDialog.value = false
    }

    fun onSelectAllModels() {
        val params = stateHolder._pendingConfigParams.value ?: return
        val models = fetchedModels.value
        if (models.isEmpty()) {
            showSnackbar("没有可用的模型")
            return
        }

        createMultipleConfigs(
            provider = params.provider,
            address = params.address,
            key = params.key,
            modelNames = models
        )
        stateHolder._showModelSelectionDialog.value = false
        stateHolder._pendingConfigParams.value = null
        showSnackbar("已添加 ${models.size} 个模型配置")
    }

    fun onSelectModels(selectedModels: List<String>) {
        val params = stateHolder._pendingConfigParams.value ?: return
        if (selectedModels.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }

        createMultipleConfigs(
            provider = params.provider,
            address = params.address,
            key = params.key,
            modelNames = selectedModels
        )
        stateHolder._showModelSelectionDialog.value = false
        stateHolder._pendingConfigParams.value = null
        showSnackbar("已添加 ${selectedModels.size} 个模型配置")
    }

    override fun onCleared() {
        super.onCleared()
        // 清理消息内容控制器（若未来扩展内部资源）
        messageContentController.cleanup()
        // 统一的生命周期清理
        lifecycleCoordinator.onCleared()
    }
    
    /**
     * 🎯 低内存回调 - 清理非必要缓存
     * 在MainActivity的onTrimMemory中调用
     */
    fun onLowMemory() {
        lifecycleCoordinator.onLowMemory()
    }
    
    // ========= 置顶功能 API =========
    
    /**
     * 解析会话的稳定ID（用于置顶标识）
     * 优先使用首条User消息ID，其次非占位System消息ID，最后使用首条消息ID
     */
    private fun resolveStableConversationId(conversation: List<Message>?): String? {
        if (conversation.isNullOrEmpty()) return null
        return conversation.firstOrNull { it.sender == Sender.User }?.id
            ?: conversation.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
            ?: conversation.firstOrNull()?.id
    }
    
    /**
     * 判断指定索引的会话是否已置顶
     */
    fun isConversationPinned(index: Int, isImageGeneration: Boolean): Boolean {
        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        
        val conversation = history.getOrNull(index) ?: return false
        val stableId = resolveStableConversationId(conversation) ?: return false
        
        val pinnedSet = if (isImageGeneration) {
            stateHolder.pinnedImageConversationIds.value
        } else {
            stateHolder.pinnedTextConversationIds.value
        }
        
        return pinnedSet.contains(stableId)
    }
    
    /**
     * 切换指定索引会话的置顶状态
     */
    fun togglePinForConversation(index: Int, isImageGeneration: Boolean) {
        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        
        val conversation = history.getOrNull(index)
        val stableId = resolveStableConversationId(conversation)
        
        if (stableId == null) {
            Log.w("AppViewModel", "togglePin: 无法解析会话稳定ID, index=$index")
            return
        }
        
        val flow = if (isImageGeneration) {
            stateHolder.pinnedImageConversationIds
        } else {
            stateHolder.pinnedTextConversationIds
        }
        
        val newSet = flow.value.toMutableSet().apply {
            if (!add(stableId)) {
                remove(stableId)
            }
        }.toSet()
        
        flow.value = newSet
        
        // 持久化
        viewModelScope.launch(Dispatchers.IO) {
            try {
                persistenceManager.savePinnedIds(newSet, isImageGeneration)
                Log.d("AppViewModel", "置顶状态已更新: id=$stableId, pinned=${newSet.contains(stableId)}, mode=${if (isImageGeneration) "IMAGE" else "TEXT"}")
            } catch (e: Exception) {
                Log.e("AppViewModel", "保存置顶状态失败", e)
            }
        }
    }
    
    /**
     * 清理置顶集合中已不存在的会话ID
     * 在删除会话后调用
     */
    private fun cleanupPinnedIds(isImageGeneration: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = if (isImageGeneration) {
                    stateHolder._imageGenerationHistoricalConversations.value
                } else {
                    stateHolder._historicalConversations.value
                }
                
                // 收集所有现存会话的稳定ID
                val existingIds = history.mapNotNull { conversation ->
                    resolveStableConversationId(conversation)
                }.toSet()
                
                val flow = if (isImageGeneration) {
                    stateHolder.pinnedImageConversationIds
                } else {
                    stateHolder.pinnedTextConversationIds
                }
                
                // 仅保留仍存在的ID
                val cleanedSet = flow.value.intersect(existingIds)
                
                if (cleanedSet.size != flow.value.size) {
                    flow.value = cleanedSet
                    persistenceManager.savePinnedIds(cleanedSet, isImageGeneration)
                    Log.d("AppViewModel", "置顶集合已清理: 移除 ${flow.value.size - cleanedSet.size} 个无效ID")
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "清理置顶集合失败", e)
            }
        }
    }
    
    // ========= 分组功能 API =========
    
    fun createGroup(groupName: String) {
        val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
        if (!currentGroups.containsKey(groupName)) {
            currentGroups[groupName] = emptyList()
            stateHolder.conversationGroups.value = currentGroups
            viewModelScope.launch(Dispatchers.IO) {
                persistenceManager.saveConversationGroups(currentGroups)
            }
        }
    }

    fun renameGroup(oldName: String, newName: String) {
        val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
        if (currentGroups.containsKey(oldName) && !currentGroups.containsKey(newName)) {
            val items = currentGroups.remove(oldName)
            if (items != null) {
                currentGroups[newName] = items
                stateHolder.conversationGroups.value = currentGroups
                viewModelScope.launch(Dispatchers.IO) {
                    persistenceManager.saveConversationGroups(currentGroups)
                }
            }
        }
    }

    fun deleteGroup(groupName: String) {
        val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
        if (currentGroups.containsKey(groupName)) {
            currentGroups.remove(groupName)
            stateHolder.conversationGroups.value = currentGroups
            viewModelScope.launch(Dispatchers.IO) {
                persistenceManager.saveConversationGroups(currentGroups)
            }
        }
    }

    fun moveConversationToGroup(conversationIndex: Int, groupName: String?, isImageGeneration: Boolean) {
        val conversation = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value.getOrNull(conversationIndex)
        } else {
            stateHolder._historicalConversations.value.getOrNull(conversationIndex)
        }
        val stableId = resolveStableConversationId(conversation) ?: return

        val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
        
        // 从所有分组中移除
        currentGroups.keys.forEach { key ->
            val items = currentGroups[key]?.toMutableList()
            if (items != null && items.remove(stableId)) {
                currentGroups[key] = items
            }
        }

        // 添加到新分组
        if (groupName != null) {
            val items = currentGroups[groupName]?.toMutableList() ?: mutableListOf()
            if (!items.contains(stableId)) {
                items.add(stableId)
                currentGroups[groupName] = items
            }
        }
        
        stateHolder.conversationGroups.value = currentGroups
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.saveConversationGroups(currentGroups)
        }
    }
    
    // ========= 分组展开/折叠状态管理 =========
    
    fun toggleGroupExpanded(groupKey: String) {
        val currentExpanded = stateHolder.expandedGroups.value.toMutableSet()
        if (currentExpanded.contains(groupKey)) {
            currentExpanded.remove(groupKey)
        } else {
            currentExpanded.add(groupKey)
        }
        stateHolder.expandedGroups.value = currentExpanded
    }
    
    fun isGroupExpanded(groupKey: String): Boolean {
        return stateHolder.expandedGroups.value.contains(groupKey)
    }
    
}
