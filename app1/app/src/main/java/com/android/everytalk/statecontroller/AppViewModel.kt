package com.android.everytalk.statecontroller

import android.app.Application
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.network.openclaw.OpenClawRuntimeStatusService
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.statecontroller.viewmodel.DrawerManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.UpdateManager
import com.android.everytalk.statecontroller.facade.MessageItemsController
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCategory
import com.android.everytalk.statecontroller.controller.systemprompt.SystemPromptController
import com.android.everytalk.statecontroller.controller.config.SettingsController
import com.android.everytalk.statecontroller.controller.conversation.HistoryController
import com.android.everytalk.statecontroller.controller.media.MediaController
import com.android.everytalk.statecontroller.controller.conversation.MessageContentController
import com.android.everytalk.ui.components.streaming.StreamingRenderState
import com.android.everytalk.statecontroller.controller.conversation.ConversationPreviewController
import com.android.everytalk.statecontroller.controller.config.ModelAndConfigController
import com.android.everytalk.statecontroller.controller.conversation.RegenerateController
import com.android.everytalk.statecontroller.controller.conversation.StreamingControls
import com.android.everytalk.statecontroller.facade.UiStateFacade
import com.android.everytalk.statecontroller.controller.lifecycle.LifecycleCoordinator
import com.android.everytalk.statecontroller.controller.conversation.ScrollStateController
import com.android.everytalk.statecontroller.controller.conversation.AnimationStateController
import com.android.everytalk.statecontroller.controller.conversation.EditMessageController
import com.android.everytalk.statecontroller.controller.media.ClipboardController
import com.android.everytalk.statecontroller.controller.config.ConfigFacade
import com.android.everytalk.statecontroller.controller.config.ProviderController
import com.android.everytalk.statecontroller.viewmodel.McpManager
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpStatus
import com.android.everytalk.data.network.GeminiDirectClient
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import com.android.everytalk.data.network.ExternalWebSearchService
import com.android.everytalk.data.network.JinaSearchService
import com.android.everytalk.data.network.OpenAIDirectClient
import com.android.everytalk.data.network.OpenAIResponsesClient
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.WebFetchToolExecutor
import com.android.everytalk.util.storage.readAtMost
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Constructor changed: removed dataSource
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    internal val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    internal val _externalWebSearchConfigs =
        MutableStateFlow<Map<String, ExternalWebSearchProviderConfig>>(emptyMap())
    val externalWebSearchConfigs: StateFlow<Map<String, ExternalWebSearchProviderConfig>>
        get() = _externalWebSearchConfigs.asStateFlow()

    internal val _selectedExternalWebSearchProviderId = MutableStateFlow<String?>(null)
    val selectedExternalWebSearchProviderId: StateFlow<String?>
        get() = _selectedExternalWebSearchProviderId.asStateFlow()
    val selectedExternalWebSearchProvider: ExternalWebSearchProvider?
        get() = ExternalWebSearchProvider.fromId(_selectedExternalWebSearchProviderId.value)

    val selectedExternalWebSearchProviderApiKey: String
        get() = selectedExternalWebSearchProvider
            ?.let { provider -> _externalWebSearchConfigs.value[provider.providerId]?.apiKey.orEmpty() }
            .orEmpty()

    internal val fileManager: FileManager by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get()
    }
    internal val mathJaxSvgRenderer: MathJaxSvgRenderer by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get()
    }

    internal val messagesMutex = Mutex()
    internal val historyMutex = Mutex()
    internal val stateHolder = ViewModelStateHolder()
    internal val conversationPreviewController = ConversationPreviewController(stateHolder)

    val gestureManager = com.android.everytalk.ui.components.GestureConflictManager()

    val streamingMessageStateManager get() = stateHolder.streamingMessageStateManager

    internal val imageLoader = application.applicationContext.imageLoader
    val persistenceManager =
            DataPersistenceManager(
                    application.applicationContext,
                    stateHolder,
                    viewModelScope,
                    imageLoader
             )

    internal val historyManager: HistoryManager =
            HistoryManager(
                    stateHolder,
                    persistenceManager,
                    ::areMessageListsEffectivelyEqual,
                    onHistoryModified = {
                        conversationPreviewController.clearAllCaches()
                    },
                    scope = viewModelScope
            )

    val simpleModeManager = SimpleModeManager(stateHolder, historyManager, viewModelScope)

    // MCP Manager
    val mcpManager = McpManager(application.applicationContext)
    val mcpServerStates = mcpManager.serverStates
    val allMcpConfigs: StateFlow<Map<String, McpServerState>> = mcpManager.getAllConfigs()
        .map { configs: List<McpServerConfig> ->
            configs.associate { config: McpServerConfig ->
                val status = if (config.enabled) McpStatus.Connecting else McpStatus.Idle
                config.id to McpServerState(
                    config = config,
                    status = status,
                    tools = emptyList()
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    internal val uiStateFacade by lazy { UiStateFacade(stateHolder, simpleModeManager) }
    val ui: UiStateFacade
        get() = uiStateFacade

    val uiModeFlow: StateFlow<SimpleModeManager.ModeType>
        get() = simpleModeManager.uiModeFlow

    internal val apiHandler: ApiHandler by lazy {
        ApiHandler(
                stateHolder,
                viewModelScope,
                historyManager,
                onAiMessageFullTextChanged = ::onAiMessageFullTextChanged,
                triggerScrollToBottom = ::triggerScrollToBottom,
        )
    }
    internal val configManager: ConfigManager by lazy {
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }
    internal val configFacade by lazy { ConfigFacade(configManager) }

    internal val messageSender: MessageSender by lazy {
        MessageSender(
                application = getApplication(),
                viewModelScope = viewModelScope,
                stateHolder = stateHolder,
                apiHandler = apiHandler,
                historyManager = historyManager,
                showSnackbar = ::showSnackbar,
                triggerScrollToBottom = { triggerScrollToBottom() },
                uriToBase64Encoder = { uri -> encodeUriAsBase64(uri) },
                getMcpDispatchCandidates = { mcpManager.getDispatchCandidates() },
                getSelectedExternalWebSearchProvider = { selectedExternalWebSearchProvider },
                getSelectedExternalWebSearchProviderApiKey = { selectedExternalWebSearchProviderApiKey }
        )
    }

    internal val openClawRuntimeStatusService by lazy {
        OpenClawRuntimeStatusService(
            context = getApplication(),
            httpClient = org.koin.java.KoinJavaComponent.getKoin().get(),
            json = org.koin.java.KoinJavaComponent.getKoin().get()
        )
    }

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
    val historyLoadGeneration: StateFlow<Long>
        get() = stateHolder._historyLoadGeneration.asStateFlow()
    val isLoadingHistoryData: StateFlow<Boolean>
        get() = stateHolder._isLoadingHistoryData.asStateFlow()
    val currentConversationId: StateFlow<String>
        get() = stateHolder._currentConversationId.asStateFlow()
    val currentOpenClawSessionId: StateFlow<String>
        get() = stateHolder._currentOpenClawSessionId.asStateFlow()
    val openClawGatewayStatus: StateFlow<OpenClawGatewayStatus>
        get() = stateHolder._openClawGatewayStatus.asStateFlow()
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
    val lastSentUserMessageId: StateFlow<String?>
        get() = stateHolder._lastSentUserMessageId.asStateFlow()
    val lastSentImageUserMessageId: StateFlow<String?>
        get() = stateHolder._lastSentImageUserMessageId.asStateFlow()
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
    val scrollToItemEvent: SharedFlow<String>
        get() = stateHolder._scrollToItemEvent.asSharedFlow()
    val selectedMediaItems: SnapshotStateList<SelectedMediaItem>
        get() = stateHolder.selectedMediaItems

    val systemPromptExpandedState: SnapshotStateMap<String, Boolean>
        get() = stateHolder.systemPromptExpandedState

    // 重构：使用管理器类来组织代码
    internal val exportManager = ExportManager()
    val exportRequest: Flow<Pair<String, String>> = exportManager.exportRequest
    val settingsExportRequest: Flow<Pair<String, String>> = exportManager.settingsExportRequest
    internal var pendingSettingsExport: Pair<String, String>? = null

    internal val dialogManager = DialogManager()
    internal val editMessageController = EditMessageController(
        stateHolder = stateHolder,
        dialogManager = dialogManager,
        historyManager = historyManager,
        scope = viewModelScope,
        messagesMutex = messagesMutex,
        clearMessageCache = ::clearMessageCache
    )
    val showEditDialog: StateFlow<Boolean> = dialogManager.showEditDialog
    val editingMessage: StateFlow<Message?> = dialogManager.editingMessage
    val showSystemPromptDialog: StateFlow<Boolean> = dialogManager.showSystemPromptDialog
    val showAboutDialog: StateFlow<Boolean> = dialogManager.showAboutDialog
    val showClearImageHistoryDialog: StateFlow<Boolean> = dialogManager.showClearImageHistoryDialog
    val editDialogInputText: StateFlow<String>
        get() = stateHolder._editDialogInputText.asStateFlow()

    // 新增:添加配置流程相关的对话框状态
    val showAutoFetchConfirmDialog: StateFlow<Boolean>
        get() = stateHolder._showAutoFetchConfirmDialog.asStateFlow()
    val showModelSelectionDialog: StateFlow<Boolean>
        get() = stateHolder._showModelSelectionDialog.asStateFlow()
    val pendingConfigParams: StateFlow<PendingConfigParams?>
        get() = stateHolder._pendingConfigParams.asStateFlow()

    // 剪贴板/导出 控制器
    internal val clipboardController = ClipboardController(
        application = getApplication(),
        exportManager = exportManager,
        scope = viewModelScope,
        showSnackbar = ::showSnackbar
    )

    internal val drawerManager = DrawerManager()
    val isSearchActiveInDrawer: StateFlow<Boolean> = drawerManager.isSearchActiveInDrawer
    val expandedDrawerItemIndex: StateFlow<Int?> = drawerManager.expandedDrawerItemIndex
    val searchQueryInDrawer: StateFlow<String> = drawerManager.searchQueryInDrawer

    // 滚动状态控制器
    internal val scrollStateController = ScrollStateController(stateHolder)
    // 动画状态控制器
    internal val animationStateController = AnimationStateController(stateHolder) { simpleModeManager.isInImageMode() }

    internal val providerManager = ProviderManager(viewModelScope)
    val customProviders: StateFlow<Set<String>> = providerManager.customProviders
    val allProviders: StateFlow<List<String>> = providerManager.allProviders

    internal val providerController = ProviderController(
        stateHolder = stateHolder,
        providerManager = providerManager,
        configManager = configManager,
        persistenceManager = persistenceManager, // Use persistenceManager
        scope = viewModelScope
    )

    val isWebSearchEnabled: StateFlow<Boolean>
        get() = stateHolder._isWebSearchEnabled.asStateFlow()
    val isCodeExecutionEnabled: StateFlow<Boolean>
        get() = stateHolder._isCodeExecutionEnabled.asStateFlow()
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

   internal val updateManager = UpdateManager(
       application = getApplication(),
       scope = viewModelScope,
       showSnackbar = ::showSnackbar
   )
   val latestReleaseInfo: StateFlow<GitHubRelease?> = updateManager.latestReleaseInfo
   val updateInfo: StateFlow<com.android.everytalk.data.DataClass.VersionUpdateInfo?> = updateManager.updateInfo
   // 控制器：系统提示
   internal val systemPromptController = SystemPromptController(stateHolder, dialogManager, historyManager, viewModelScope)
   // 委托到 MessageItemsController，减少 AppViewModel 体积
   internal val messageItemsController = MessageItemsController(
       stateHolder = stateHolder,
       streamingMessageStateManager = streamingMessageStateManager,
       scope = viewModelScope,
   )
   val chatListItems: StateFlow<List<ChatListItem>> get() = messageItemsController.chatListItems
   val imageGenerationChatListItems: StateFlow<List<ChatListItem>> get() = messageItemsController.imageGenerationChatListItems
    internal val modelFetchManager = com.android.everytalk.statecontroller.viewmodel.ModelFetchManager()
    val fetchedModels: StateFlow<List<String>> = modelFetchManager.fetchedModels
   val isRefreshingModels: StateFlow<Set<String>> = modelFetchManager.isRefreshingModels

   // 控制器：设置导入/导出
   internal val settingsController = SettingsController(
       context = application.applicationContext,
       stateHolder = stateHolder,
       persistenceManager = persistenceManager,
       historyManager = historyManager,
       providerManager = providerManager,
       exportManager = exportManager,
       json = json,
       showSnackbar = { showToast(it) },
       scope = viewModelScope
   )

   // 适配器：供 HistoryController 切换/加载模式
   internal val simpleModeBridge = object : HistoryController.SimpleModeSwitcher {
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
   internal val historyController = HistoryController(
       stateHolder = stateHolder,
       historyManager = historyManager,
       apiHandler = apiHandler,
       scope = viewModelScope,
       showSnackbar = ::showSnackbar,
       shouldAutoScroll = { stateHolder.shouldAutoScroll() },
       triggerScrollToBottom = { triggerScrollToBottom() },
       simpleModeSwitcher = simpleModeBridge
   )
   internal var textHistoryLoadJob: Job? = null

   internal fun cancelPendingTextHistoryLoad() {
       textHistoryLoadJob?.cancel()
       textHistoryLoadJob = null
       stateHolder._isLoadingHistory.value = false
   }

   // 控制器：媒体下载/保存
   internal val mediaController = MediaController(
       application = getApplication(),
       fileManager = fileManager,
       scope = viewModelScope,
       showSnackbar = ::showSnackbar
   )

   // 控制器：消息内容/流式追加
   internal val messageContentController = MessageContentController(
       stateHolder = stateHolder,
       scope = viewModelScope,
       messagesMutex = messagesMutex,
       triggerScrollToBottom = { triggerScrollToBottom() }
   )

   // 控制器：模型/配置 管理
   internal val modelAndConfigController = ModelAndConfigController(
       stateHolder = stateHolder,
       persistenceManager = persistenceManager,
       modelFetchManager = modelFetchManager,
       configManager = configManager,
       scope = viewModelScope,
       showSnackbar = { showToast(it) },
   )

   // 控制器：从用户消息点重新生成流程
   internal val regenerateController = RegenerateController(
       stateHolder = stateHolder,
       apiHandler = apiHandler,
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
       sendMessage = { messageText, isFromRegeneration, attachments, isImageGeneration, manualMessageId ->
           messageSender.sendMessage(
               messageText = messageText,
               isFromRegeneration = isFromRegeneration,
               attachments = attachments,
               isImageGeneration = isImageGeneration,
               manualMessageId = manualMessageId
           )
       }
   )

   // 控制器：统一流式暂停/恢复/flush
   internal val streamingControls = StreamingControls(
       stateHolder = stateHolder,
       apiHandler = apiHandler,
       scope = viewModelScope,
       isImageModeProvider = { simpleModeManager.isInImageMode() },
       triggerScrollToBottom = { triggerScrollToBottom() },
       showSnackbar = ::showSnackbar
   )

   // 生命周期协调器：统一 save/clear/low-memory 策略
   internal val lifecycleCoordinator = LifecycleCoordinator(
       stateHolder = stateHolder,
       historyManager = historyManager,
       conversationPreviewController = conversationPreviewController,
       persistScrollStates = {
           if (scrollStatesInitialized) {
               persistenceManager.saveConversationScrollStates(stateHolder.conversationScrollStates.toMap())
           }
       },
       scope = viewModelScope
   )

  private val mcpToolExecutorOwner = Any()

  init {
         GeminiDirectClient.setMcpToolExecutor(mcpToolExecutorOwner) { toolName, arguments, updateStatus ->
            executeSharedToolCall(
                toolName = toolName,
                arguments = arguments,
                updateStatus = updateStatus,
                mcpWebFetchFallback = buildMcpWebFetchFallback(),
                localWebSearchExecutor = buildLocalWebSearchExecutor(),
                fallbackExecutor = { fallbackToolName, fallbackArguments ->
                    mcpManager.callTool(fallbackToolName, fallbackArguments)
                }
            )
         }
         OpenAIDirectClient.setMcpToolExecutor(mcpToolExecutorOwner) { toolName, arguments, updateStatus ->
            executeSharedToolCall(
                toolName = toolName,
                arguments = arguments,
                updateStatus = updateStatus,
                mcpWebFetchFallback = buildMcpWebFetchFallback(),
                localWebSearchExecutor = buildLocalWebSearchExecutor(),
                fallbackExecutor = { fallbackToolName, fallbackArguments ->
                    mcpManager.callTool(fallbackToolName, fallbackArguments)
                }
            )
         }
         OpenAIResponsesClient.setMcpToolExecutor(mcpToolExecutorOwner) { toolName, arguments, updateStatus ->
            executeSharedToolCall(
                toolName = toolName,
                arguments = arguments,
                updateStatus = updateStatus,
                mcpWebFetchFallback = buildMcpWebFetchFallback(),
                localWebSearchExecutor = buildLocalWebSearchExecutor(),
                fallbackExecutor = { fallbackToolName, fallbackArguments ->
                    mcpManager.callTool(fallbackToolName, fallbackArguments)
                }
            )
         }

        // 初始化 StateHolder 的持久化回调
        viewModelScope.launch(Dispatchers.IO) {
            // 加载初始会话参数
            val initialParams = persistenceManager.loadConversationParameters()
            val initialToggleStates = persistenceManager.loadConversationFunctionToggleStates()

            withContext(Dispatchers.Main) {
                stateHolder.initializePersistence(
                    saveCallback = { params ->
                        viewModelScope.launch(Dispatchers.IO) {
                            persistenceManager.saveConversationParameters(params)
                        }
                    },
                    initialParams = initialParams
                )
                stateHolder.conversationFunctionToggleStates.value = initialToggleStates
            }
        }

        // 加载自定义提供商
        viewModelScope.launch(Dispatchers.IO) {
            // Use persistenceManager instead of dataSource
            val loadedCustomProviders = persistenceManager.loadCustomProviders()
            providerManager.setCustomProviders(loadedCustomProviders)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val configs = persistenceManager.loadExternalWebSearchConfigs()
                    .associateBy { it.providerId }
                val selectedProviderId = persistenceManager.loadSelectedExternalWebSearchProviderId()
                    ?: ExternalWebSearchProvider.defaultProvider.providerId

                withContext(Dispatchers.Main) {
                    _externalWebSearchConfigs.value = configs
                    _selectedExternalWebSearchProviderId.value = selectedProviderId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "加载外部联网搜索配置失败", e)
            }
        }

        // 监听模式切换消息并显示 Toast
        viewModelScope.launch {
            simpleModeManager.modeSwitchMessage.collect { message ->
                showSnackbar(message)
            }
        }

        // 优化：分阶段初始化，优先加载关键配置
        // 调整：启用"上次打开会话"的恢复，保证多轮上下文在重启后延续（含图像模式）
        persistenceManager.loadInitialData(
            loadLastChat = true,
            onLoadWarning = ::showSnackbar,
        ) {
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

                // 提前完成唯一 MathJax WebView 的冷启动，避免首次打开公式会话时阻塞加载动画。
                mathJaxSvgRenderer.prewarm()

            }

            // 修复：始终加载分组信息，不依赖历史数据是否存在
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val groups = persistenceManager.loadConversationGroups()
                    withContext(Dispatchers.Main) {
                        stateHolder.conversationGroups.value = groups
                    }
                    Log.d("AppViewModel", "分组信息已加载 - 共 ${groups.size} 个分组")

                    // 加载分组展开状态
                    val expandedKeys = persistenceManager.loadExpandedGroupKeys()
                    withContext(Dispatchers.Main) {
                        stateHolder.expandedGroups.value = expandedKeys
                    }
                    Log.d("AppViewModel", "分组展开状态已加载 - 共 ${expandedKeys.size} 个展开的分组")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AppViewModel", "加载分组信息失败", e)
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AppViewModel", "加载置顶集合失败", e)
                }
            }

            // 加载会话滚动位置
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val states = persistenceManager.loadConversationScrollStates()
                    withContext(Dispatchers.Main) {
                        stateHolder.conversationScrollStates.clear()
                        stateHolder.conversationScrollStates.putAll(states)
                        scrollStatesInitialized = true
                    }
                    Log.d("AppViewModel", "会话滚动位置已加载 - 共 ${states.size} 条")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AppViewModel", "加载会话滚动位置失败", e)
                }
            }

            // 在所有数据加载完成后，检查是否需要开启一个新聊天
            if (stateHolder.messages.isEmpty() && stateHolder.imageGenerationMessages.isEmpty()) {
                startNewChat()
            }
        }

        // 同步初始化处理器，确保在后续调用前可用
        apiHandler
        configManager
        messageSender
        stateHolder.setApiHandler(apiHandler)

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

    /**
     * Alias for getStreamingContent for backward compatibility
     */

    // 模式状态检测方法 - 供设置界面等外部组件使用

    // 气泡状态与 ChatListItem 构建已委托到 MessageItemsController

    // 更新当前会话的生成参数

    // 供外部调用的保存历史记录方法（用于语音模式等）

    // 获取当前会话的生成参数

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

    /**
     * 切换“暂停/继续”流式显示。
     * 暂停：仍然接收并解析后端数据，但不更新UI；
     * 继续：一次性将暂停期间累积的文本刷新到UI。
     */

    // 图片查看器
    internal val _showImageViewer = MutableStateFlow(false)
    val showImageViewer: StateFlow<Boolean> = _showImageViewer.asStateFlow()

    internal val _imageViewerUrl = MutableStateFlow<String?>(null)
    val imageViewerUrl: StateFlow<String?> = _imageViewerUrl.asStateFlow()

    internal val _imageViewerUrls = MutableStateFlow<List<String>>(emptyList())
    val imageViewerUrls: StateFlow<List<String>> = _imageViewerUrls.asStateFlow()

    internal val _imageViewerIndex = MutableStateFlow(0)
    val imageViewerIndex: StateFlow<Int> = _imageViewerIndex.asStateFlow()

    /**
     * 更新当前选中的图像生成配置的推理步数（numInferenceSteps）。
     * 仅在存在选中图像配置时生效，并会同步更新配置列表与持久化存储。
     */

    /**
     * 更新当前选中的图像生成配置的推理步数和引导系数。
     * 用于 Qwen-Image-Edit 等需要调节这两个参数的模型。
     */

    /**
     * 更新当前选中的图像生成配置的 Gemini 图像尺寸（1K/2K/4K）。
     */

    // 应用暂停或停止时保存当前对话状态

    // 新增：用于通知UI显示添加模型对话框的 Flow
    internal val _showManualModelInputRequest = MutableSharedFlow<ManualModelInputRequest>(replay = 0)
    val showManualModelInputRequest: SharedFlow<ManualModelInputRequest> = _showManualModelInputRequest.asSharedFlow()

    data class ManualModelInputRequest(
        val isImageGen: Boolean,
    )

    internal var scrollStatesInitialized = false

    /**
     * 将URI编码为Base64字符串
     */

    /**
     * 处理加载的消息列表，确保完整性
     */
    // 历史消息的完整性修复已移至 HistoryController

    /**
     * 清理所有缓存
     */

    // ===== 配置添加流程：交互逻辑 =====

    /**
     * 清除指定消息的ChatListItem缓存
     * 用于消息编辑后强制UI更新
     */

    override fun onCleared() {
        GeminiDirectClient.clearMcpToolExecutor(mcpToolExecutorOwner)
        OpenAIDirectClient.clearMcpToolExecutor(mcpToolExecutorOwner)
        OpenAIResponsesClient.clearMcpToolExecutor(mcpToolExecutorOwner)
        // 清理消息内容控制器（若未来扩展内部资源）
        messageContentController.cleanup()
        // 统一的生命周期清理
        lifecycleCoordinator.onCleared()
        // 关闭 MCP 管理器
        mcpManager.close()
    }

    // ===== MCP 服务器管理方法 =====

    /**
     * 低内存回调 - 清理非必要缓存
     * 在MainActivity的onTrimMemory中调用
     */

    // ========= 置顶功能 API =========

    /**
     * 解析会话的稳定ID（用于置顶标识）
     * 优先使用首条User消息ID，其次非占位System消息ID，最后使用首条消息ID
     */

    /**
     * 判断指定索引的会话是否已置顶
     */

    /**
     * 切换指定索引会话的置顶状态
     */

    /**
     * 清理置顶集合中已不存在的会话ID
     * 在删除会话后调用
     */

    // ========= 分组功能 API =========

    // ========= 分组展开/折叠状态管理 =========

    // ========= 会话分享功能 =========

    /**
     * 分享指定索引的会话
     * @param index 会话在历史列表中的索引
     * @param isImageGeneration 是否为图像生成模式
     */
}
