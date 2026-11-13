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
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.safety.AiContentReportRepository
import com.android.everytalk.data.auth.AccountSwitchProtection
import com.android.everytalk.data.auth.DataStoreSyncAccountBindingStore
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import android.webkit.MimeTypeMap
import com.android.everytalk.R
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.statecontroller.viewmodel.ConversationSearchManager
import com.android.everytalk.statecontroller.viewmodel.DrawerManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.SettingsExportRequest
import com.android.everytalk.statecontroller.facade.MessageItemsController
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCategory
import com.android.everytalk.statecontroller.mcp.dispatch.toToolDefinition
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
import com.android.everytalk.statecontroller.viewmodel.ComputerManager
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpStatus
import com.android.everytalk.data.agent.AgentToolExecutorRegistry
import com.android.everytalk.data.agent.AgentTerminalReasons
import com.android.everytalk.data.agent.AgentRunControlState
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.computer.hostConfirmationRequest
import com.android.everytalk.data.computer.CloudflareSettingsOAuthStore
import com.android.everytalk.data.computer.publicPreviewRequest
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.AppToolExecutionResult
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import com.android.everytalk.data.network.ExternalWebSearchService
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.WebFetchToolExecutor
import com.android.everytalk.data.auth.AuthRepository
import com.android.everytalk.data.auth.DataStoreAuthModeStore
import com.android.everytalk.data.auth.FeatureGate
import com.android.everytalk.data.auth.FeatureGateCoordinator
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
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    /** 设置、Computer 详情和聊天页共用一份 OAuth 草稿，避免 PKCE verifier 丢失。 */
    val cloudflareOAuthStore: CloudflareSettingsOAuthStore = CloudflareSettingsOAuthStore()

    /** EveryTalk 会员身份独立于模型配置、MCP 授权和 Computer 账号。 */
    private val authRuntime = com.android.everytalk.data.auth.AuthRuntime.get(application)
    internal val accountManager = authRuntime.account
    /** 认证状态和本地模式持久化的唯一入口。 */
    internal val authRepository = authRuntime.auth
    /** 所有商业功能共用同一个门禁判断。 */
    internal val featureGate = FeatureGate(authRepository.state)
    /** 登录后恢复被拦截的一次性操作。 */
    internal val featureGateCoordinator = FeatureGateCoordinator(
        gate = featureGate,
        authState = authRepository.state,
        scope = viewModelScope,
    )
    /** 上次明确选择同步到哪个账号；没有绑定记录时普通登录不触发换账号保护。 */
    internal val syncAccountBindingStore = authRuntime.binding
    internal val syncController: com.android.everytalk.data.sync.SyncController = com.android.everytalk.data.sync.SyncController(
        application, authRepository.state, syncAccountBindingStore, viewModelScope,
        beforeClearLocal = {
            apiHandler.stopAllTextRuns()
            stateHolder.clearTextConversationCache()
            stateHolder._currentConversationId.value = ""
            stateHolder.isTextConversationDirty.value = false
        },
    )
    internal val accountSwitchProtection: AccountSwitchProtection = AccountSwitchProtection(
        authState = authRepository.state,
        bindingStore = syncAccountBindingStore,
        scope = viewModelScope,
        clearLocalData = ::clearSyncableLocalData,
        onChoice = syncController::handleAccountSwitch,
    )

    /** 清理同步范围内的本地文字对话，保留 API Key 与其他本地设置。 */
    internal fun clearSyncableLocalData() {
        clearAllConversations()
    }

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

    // --- 强调色（色值见 ui/theme/AccentPalette.kt） ---
    internal val _accentColor = MutableStateFlow(com.android.everytalk.ui.theme.AccentColor.Default)
    val accentColor: StateFlow<com.android.everytalk.ui.theme.AccentColor>
        get() = _accentColor.asStateFlow()

    fun setAccentColor(color: com.android.everytalk.ui.theme.AccentColor) {
        _accentColor.value = color
        viewModelScope.launch { persistenceManager.saveAccentColorName(color.name) }
    }

    internal val fileManager: FileManager by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get()
    }
    internal val mathJaxSvgRenderer: MathJaxSvgRenderer by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get()
    }
    internal val aiContentReportRepository: AiContentReportRepository by lazy {
        AiContentReportRepository(
            context = application.applicationContext,
            httpClient = org.koin.java.KoinJavaComponent.getKoin().get(),
        )
    }

    internal val messagesMutex = Mutex()
    internal val historyMutex = Mutex()
    internal val stateHolder = ViewModelStateHolder()
    private fun localizedDefaultConversationName(index: Int, isImageGeneration: Boolean): String =
        getApplication<Application>().getString(
            if (isImageGeneration) {
                R.string.conversation_default_image_name
            } else {
                R.string.conversation_default_name
            },
            index + 1,
        )

    internal val conversationPreviewController = ConversationPreviewController(
        stateHolder = stateHolder,
        defaultNameFactory = ::localizedDefaultConversationName,
    )

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

    /** Computer 的 SSH、Workspace 与凭据全部由 Android 本地对象持有。 */
    internal val computerManager = ComputerManager(
        context = application.applicationContext,
        scope = viewModelScope,
        additionalComputerIds = { id -> stateHolder.conversationFunctionToggleStates.value[id]?.additionalComputerIds.orEmpty() },
        attachmentsForConversation = { conversationId ->
            // 工具始终读取所属会话。用户切换页面后，跨机中转仍能读取已落库的源文件，
            // 当前页面中的新下载附件则优先从主线程快照获取，避免等待异步历史保存。
            val visibleAttachments = withContext(Dispatchers.Main.immediate) {
                if (stateHolder._currentConversationId.value == conversationId) {
                    stateHolder.messages.flatMap(Message::attachments)
                } else null
            }
            visibleAttachments ?: com.android.everytalk.data.database.AppDatabase.getDatabase(application)
                .chatDao().getMessagesForSession(conversationId).flatMap { it.attachments }
        },
        onDownloaded = { conversationId, runId, attachment ->
            // 工具结果属于固定 AgentRun；当前流式 ID 可能已经切到另一条消息或另一会话。
            val chatDao = com.android.everytalk.data.database.AppDatabase.getDatabase(application).chatDao()
            val run = com.android.everytalk.data.database.AppDatabase.getDatabase(application)
                .agentDao().getRun(runId)
                ?: error("下载所属的 Agent 任务不存在")
            require(run.sessionId == conversationId) { "下载会话与 Agent 任务不一致" }
            val attachedToVisibleMessage = withContext(Dispatchers.Main.immediate) {
                if (stateHolder._currentConversationId.value != conversationId) return@withContext false
                val index = stateHolder.messages.indexOfFirst { it.id == run.visibleAssistantMessageId }
                if (index < 0) return@withContext false
                val message = stateHolder.messages[index]
                if (message.attachments.none { it.id == attachment.id }) {
                    stateHolder.messages[index] = message.copy(attachments = message.attachments + attachment)
                    stateHolder.isTextConversationDirty.value = true
                }
                true
            }
            if (attachedToVisibleMessage) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
            } else if (!chatDao.appendDownloadedAttachment(run.sessionId, run.visibleAssistantMessageId, attachment)) {
                error("下载所属的聊天消息不存在，附件未交付")
            }
        },
    )

    internal val historyManager: HistoryManager =
            HistoryManager(
                    stateHolder,
                    persistenceManager,
                    ::areMessageListsEffectivelyEqual,
                    onHistoryModified = {
                        conversationPreviewController.clearAllCaches()
                    },
                     scope = viewModelScope,
                     onConversationIdMigrated = { oldId, newId ->
                         computerManager.migrateConversationId(oldId, newId) { sourceId, targetId ->
                             // 选择可能在历史设置迁移之后才落库；与主目标共用迁移锁，接走这段间隙的附加目标。
                             stateHolder.conversationFunctionToggleStates.update { current ->
                                 val pending = current[sourceId]
                                 if (pending == null) current else (current - sourceId) + (targetId to pending)
                             }
                         }
                         persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
                         pendingMessageController.migrateConversationId(oldId, newId)
                         if (stateHolder._forkConversationIds.value.values.any { it == oldId || it == newId }) {
                             forkPendingMessageController.migrateConversationId(oldId, newId)
                         }
                     },
                     deleteConversationWorkspaces = { conversationId ->
                         computerManager.deleteWorkspacesForConversation(conversationId)
                     },
             )

    val simpleModeManager = SimpleModeManager(
        stateHolder = stateHolder,
        historyManager = historyManager,
        scope = viewModelScope,
        onTextHistoryLoaded = { apiHandler.restoreVisibleAgentState() },
        loadHistorySession = persistenceManager::loadHistorySession,
    )

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
                getApplication<Application>(),
                stateHolder,
                viewModelScope,
                historyManager,
                onAiMessageFullTextChanged = ::onAiMessageFullTextChanged,
                triggerScrollToBottom = ::triggerScrollToBottom,
                computerSessionStateProvider = computerManager::computerSessionState,
                prepareAgentResumeRequest = ::prepareAgentRequestedChatRequest,
                prepareMcpResumeRequest = ::prepareMcpRequestedChatRequest,
                persistImageGenerationResult = ::persistImageGenerationResult,
        )
    }

    /**
     * 把图像任务结果写回指定会话。
     * 任务可能在用户已经切换页面后完成，因此这里始终通过会话 ID 读取和保存 Room，
     * 只有目标会话当前可见时才同步更新 Compose 列表。
     */
    private suspend fun persistImageGenerationResult(
        conversationId: String,
        messageId: String,
        response: ImageGenerationResponse?,
        error: Throwable?,
        isImageGeneration: Boolean,
    ) {
        val stored = persistenceManager.loadHistorySession(conversationId).orEmpty()
        val baseMessages = if (!isImageGeneration && stateHolder._currentConversationId.value == conversationId) {
            // 取当前完整快照，保留尚未写入 Room 的占位身份、模型名和期间新增的消息。
            stateHolder.messages.toList()
        } else if (stored.isNotEmpty()) {
            stored
        } else if (stateHolder._currentImageGenerationConversationId.value == conversationId) {
            stateHolder.imageGenerationMessages.toList()
        } else {
            emptyList()
        }
        if (baseMessages.isEmpty()) {
            Log.w("AppViewModel", "图像任务完成但目标会话不存在: $conversationId")
            return
        }

        val storedTitle = ConversationNameHelper.getStoredConversationTitle(baseMessages)
        val messages = ConversationNameHelper.withoutStoredConversationTitle(baseMessages).toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == messageId }
        val existing = messages.getOrNull(existingIndex)
        val now = System.currentTimeMillis()
        val updated = if (response != null && response.images.isNotEmpty()) {
            val localUrls = response.images.mapIndexedNotNull { index, image ->
                when (val result = historyManager.persistGeneratedImageSource(image.url, messageId, index)) {
                    is com.android.everytalk.util.image.ImagePersistenceResult.Success -> result.filePath
                    is com.android.everytalk.util.image.ImagePersistenceResult.Failure -> null
                }
            }
            (existing ?: Message(
                id = messageId,
                text = "",
                sender = Sender.AI,
                conversationId = conversationId,
            )).copy(
                text = response.text.orEmpty(),
                sender = Sender.AI,
                imageUrls = localUrls,
                contentStarted = true,
                isError = localUrls.isEmpty(),
                executionFinishedAt = now,
                conversationId = conversationId,
            )
        } else {
            (existing ?: Message(
                id = messageId,
                text = "",
                sender = Sender.AI,
                conversationId = conversationId,
            )).copy(
                text = "⚠️ 图像生成失败：${error?.message ?: response?.text ?: "未返回图片"}",
                contentStarted = true,
                isError = true,
                executionFinishedAt = now,
                conversationId = conversationId,
            )
        }
        if (existingIndex >= 0) messages[existingIndex] = updated
        else addAiMessageAfterUserMessage(messages, updated, messages.lastOrNull { it.sender == Sender.User }?.id)

        val toSave = if (storedTitle == null) messages else {
            listOfNotNull(baseMessages.firstOrNull { it.sender == Sender.System && it.isPlaceholderName }) + messages
        }
        persistenceManager.saveHistorySession(conversationId, toSave, isImageGeneration = isImageGeneration)

        withContext(Dispatchers.Main.immediate) {
            if (!isImageGeneration && stateHolder._currentConversationId.value == conversationId) {
                stateHolder.messages.clear()
                stateHolder.messages.addAll(messages)
                stateHolder.rememberSeenTextMessages(messages)
                stateHolder.isTextConversationDirty.value = false
            } else if (isImageGeneration && stateHolder._currentImageGenerationConversationId.value == conversationId) {
                stateHolder.imageGenerationMessages.clear()
                stateHolder.imageGenerationMessages.addAll(messages)
                stateHolder.isImageConversationDirty.value = false
            }
        }
    }

    /** 仅在持久化批准后启用原会话，切换页面不会把权限误授给另一个会话。 */
    private suspend fun prepareMcpRequestedChatRequest(conversationId: String, request: ChatRequest): ChatRequest {
        // 这里表示用户已经明确批准开启 MCP。即使设置页开关之前是关闭的，也要先连接
        // 已保存的服务器，再把当前可用工具注入原 Run，否则恢复请求会拿到空工具列表。
        val tools = mcpManager.enableConfiguredServersForCurrentRequest().map { it.toToolDefinition() }
        check(tools.isNotEmpty()) { "没有可用的 MCP 工具，请检查已配置服务器的连接状态" }
        val requestMcpName = com.android.everytalk.data.agent.AgentControlToolNames.REQUEST_MCP
        val resumed = request.copy(
            messages = request.messages.map { message ->
                if (message is SimpleTextApiMessage && message.role.equals("system", ignoreCase = true)) {
                    message.copy(content = removeMcpPendingApprovalPrompt(message.content))
                } else {
                    message
                }
            },
            tools = request.tools.orEmpty()
                .filterNot { tool ->
                    val function = tool["function"] as? Map<*, *>
                    function?.get("name")?.toString()?.equals(requestMcpName, ignoreCase = true) == true
                }
                .plus(tools),
        )
        withContext(Dispatchers.Main.immediate) {
            stateHolder.conversationFunctionToggleStates.update { states ->
                val current = states[conversationId] ?: ConversationFunctionToggleState()
                states + (conversationId to current.copy(mcpEnabled = true))
            }
            if (stateHolder._currentConversationId.value == conversationId) {
                stateHolder._isMcpEnabledForNextRequest.value = true
            }
        }
        persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
        return resumed
    }

    /** request_agent 获批后，为原 Run 补齐服务器快照、环境说明和 Agent 工具。 */
    private suspend fun prepareAgentRequestedChatRequest(
        conversationId: String,
        request: ChatRequest,
        requiredSkillIds: List<String>,
    ): ChatRequest {
        // 冷启动恢复也必须补齐原会话开关；保存后再连接服务器，标签不依赖网络准备成功。
        withContext(Dispatchers.Main.immediate) {
            stateHolder.conversationFunctionToggleStates.update { states ->
                val current = states[conversationId] ?: ConversationFunctionToggleState()
                states + (conversationId to current.copy(agentEnabled = true))
            }
            if (stateHolder._currentConversationId.value == conversationId) {
                stateHolder._isAgentEnabled.value = true
            }
        }
        persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
        // 本地 Workspace 不代表已有服务器；批准 request_agent 后仍要绑定用户选择的目标。
        if (request.localComputerRequestContext?.let { it.computerId != "local" } == true) return request
        val prepared = computerManager.prepareRequest(conversationId, agentEnabled = true)
            ?: error("Agent 服务器准备失败")
        val syncedSkills = computerManager.syncSkills(
            context = prepared.context,
            snapshot = request.localSkillSnapshot,
            requiredSkillIds = requiredSkillIds,
        )
        val messages = request.messages.toMutableList()
        val systemIndex = messages.indexOfFirst { it.role.equals("system", ignoreCase = true) }
        if (systemIndex >= 0 && messages[systemIndex] is SimpleTextApiMessage) {
            val current = messages[systemIndex] as SimpleTextApiMessage
            val skillPaths = syncedSkills.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n\n已同步的 Skill 文件：\n") {
                "- ${it.skillId}: ${it.workspacePath}"
            }.orEmpty()
            messages[systemIndex] = current.copy(content = "${current.content}\n\n${prepared.environmentPrompt}$skillPaths")
        } else {
            messages.add(0, SimpleTextApiMessage(role = "system", content = prepared.environmentPrompt))
        }
        val requestToolName = com.android.everytalk.data.agent.AgentControlToolNames.REQUEST_AGENT
        val toolsWithoutRequest = request.tools.orEmpty().filterNot { tool ->
            val function = tool["function"] as? Map<*, *>
            val name = function?.get("name")?.toString()
            name?.equals(requestToolName, ignoreCase = true) == true ||
                name == "local_bash" || name == com.android.everytalk.data.computer.ComputerToolNames.LOCAL_FILE_SAVE
        }
        val toolsWithLocalWorkspace = appendLocalBashTool(
            toolsWithoutRequest,
            enabled = (prepared.localOnly || prepared.provider == com.android.everytalk.data.computer.ComputerProvider.CLOUDFLARE) &&
                computerManager.localBashEnabled,
        )
        return request.copy(
            messages = messages,
            tools = appendComputerTools(
                toolsWithLocalWorkspace,
                enabled = !prepared.localOnly,
                permissionMode = prepared.permissionMode,
                requestContext = prepared.context,
                cloudflare = prepared.provider == com.android.everytalk.data.computer.ComputerProvider.CLOUDFLARE,
                cloudflareWorkerWriteEnabled = computerManager.cloudflareWorkerWriteEnabled,
                cloudflareResourceToolsEnabled = computerManager.cloudflareResourceToolsEnabled,
            ),
            localComputerRequestContext = prepared.context,
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
                uriToBase64Encoder = { uri -> com.android.everytalk.util.image.encodeImageForModel(getApplication(), uri) },
                getMcpDispatchCandidates = { mcpManager.getDispatchCandidates() },
                getSelectedExternalWebSearchProvider = { selectedExternalWebSearchProvider },
                getSelectedExternalWebSearchProviderApiKey = { selectedExternalWebSearchProviderApiKey },
                prepareComputerRequest = computerManager::prepareRequest,
                cloudflareWorkerWriteEnabled = { computerManager.cloudflareWorkerWriteEnabled },
                cloudflareResourceToolsEnabled = { computerManager.cloudflareResourceToolsEnabled },
                localBashEnabled = { computerManager.localBashEnabled },
                generateConversationTitle = ::generateConversationTitle,
        )
    }

    /**
     * 异步生成首条问题的会话标题；失败时列表继续显示首条问题。
     * 发送流程在调用前已把首条消息入库，这里按（可能迁移后的）稳定 ID 找到历史会话。
     * 列表只观察内存历史，所以写进内存历史让列表立即刷新（之后每次保存都会带进 Room），再直接更新数据库。
     */
    private fun generateConversationTitle(
        requestConversationId: String,
        prompt: String,
        config: ApiConfig,
        isImageGeneration: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val generated = try {
                com.android.everytalk.statecontroller.controller.conversation.ConversationTitleGenerator
                    .generate(prompt, config)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("AppViewModel", "会话标题生成失败，将使用首条问题: ${error.message}")
                null
            } ?: return@launch

            val conversationId = withContext(Dispatchers.Main.immediate) {
                com.android.everytalk.statecontroller.controller.conversation.applyGeneratedConversationTitle(
                    if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations,
                    stateHolder.resolveConversationId(requestConversationId),
                    generated,
                )?.also { conversationPreviewController.setCachedTitle(it, generated, isImageGeneration) }
            } ?: return@launch
            historyManager.renameHistorySession(conversationId, generated)
        }
    }

    /** 当前可见 AgentRun 的真实安全暂停状态。UI 只消费 Coordinator 投影。 */
    internal val currentAgentRunControlState: StateFlow<AgentRunControlState?> =
        kotlinx.coroutines.flow.combine(
            stateHolder._currentTextStreamingAiMessageId,
            apiHandler.agentRunControlSnapshots,
        ) { messageId, snapshots ->
            messageId?.let(snapshots::get)?.state
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Pending 复用现有 Room、Composer 状态和 MessageSender，不建立第二套发送链路。 */
    internal val pendingMessageController: PendingMessageController by lazy { createPendingMessageController(false) }

    /** Fork 也复用持久化队列，仅将会话、草稿和运行状态投影到分支。 */
    internal val forkPendingMessageController: PendingMessageController by lazy { createPendingMessageController(true) }

    private fun createPendingMessageController(fork: Boolean): PendingMessageController {
        val conversation = if (fork) kotlinx.coroutines.flow.combine(
            stateHolder._currentConversationId, stateHolder._forkConversationIds,
        ) { parent, forks -> forks[parent].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "") else stateHolder._currentConversationId
        val activeMessage = if (fork) kotlinx.coroutines.flow.combine(
            conversation, stateHolder.textApiRunVersion,
        ) { id, _ -> stateHolder.textApiMessageIds[id] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null) else stateHolder._currentTextStreamingAiMessageId
        val running = if (fork) activeMessage.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false) else stateHolder._isTextApiCalling
        val control = if (fork) kotlinx.coroutines.flow.combine(
            activeMessage, apiHandler.agentRunControlSnapshots,
        ) { id, snapshots -> id?.let(snapshots::get)?.state }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null) else currentAgentRunControlState
        return PendingMessageController(
            chatDao = AppDatabase.getDatabase(getApplication()).chatDao(),
            scope = viewModelScope,
            currentConversationId = conversation,
            isApiCalling = running,
            isAbortInProgress = if (fork) MutableStateFlow(false) else stateHolder._isRemoteCancellationPending,
            agentRunControlState = control,
            recoverDispatches = !fork,
            isConversationRunning = { id ->
                stateHolder.textApiMessageIds.containsKey(id) || id in stateHolder.preparingTextConversationIds.value
            },
            conversationRunVersion = kotlinx.coroutines.flow.combine(
                stateHolder.textApiRunVersion, stateHolder.preparingTextConversationIds,
            ) { version, preparing -> version * 31 + preparing.hashCode() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0L),
            hasComposerContent = {
                if (fork) stateHolder._forkComposerText.value.isNotBlank() ||
                    stateHolder.forkSelectedMediaItems.isNotEmpty() || stateHolder.forkQuoteReferences.isNotEmpty()
                else stateHolder._text.value.isNotBlank() || stateHolder.selectedMediaItems.isNotEmpty() ||
                    stateHolder.composerQuoteReferences.isNotEmpty()
            },
            replaceComposer = { text, attachments, parts ->
                val media = if (fork) stateHolder.forkSelectedMediaItems else stateHolder.selectedMediaItems
                val quotes = if (fork) stateHolder.forkQuoteReferences else stateHolder.composerQuoteReferences
                (if (fork) stateHolder._forkComposerText else stateHolder._text).value = text
                media.clear()
                media.addAll(attachments)
                quotes.clear()
                quotes.addAll(parts.filterIsInstance<MessageContentPart.QuoteReference>().map { it.reference })
            },
            persistAttachments = { attachments, content ->
                messageSender.processAttachments(
                    attachments = attachments,
                    shouldUsePartsApiMessage = false,
                    textToActuallySend = content,
                ).takeIf { it.success }?.processedAttachmentsForUi
            },
            resumeStreaming = {
                if (fork) activeMessage.value?.let(apiHandler::resumePausedAgent)
                else streamingControls.resume()
            },
            steerCurrentRun = { pending ->
                apiHandler.steerCurrentAgent(
                    conversationId = pending.conversationId,
                    steeringId = pending.id,
                    content = pending.content,
                    contentParts = pending.contentParts,
                    attachments = pending.attachments,
                )
            },
            recordSteeredMessage = { pending ->
                messageSender.recordSteeredUserMessage(
                    messageId = pending.id,
                    text = pending.content,
                    contentParts = pending.contentParts,
                    attachments = pending.attachments,
                    targetConversationId = pending.conversationId.takeIf { fork },
                )
            },
            showMessage = ::showSnackbar,
            dispatch = { pending, onAccepted, onRejected, onFinished ->
                if (fork) {
                    sendForkMessage(
                        pending.content, pending.attachments, null, pending.contentParts,
                        onUserMessageDisplayed = {}, onSendRejected = onRejected,
                        onAccepted = onAccepted, onFinished = onFinished, manualMessageId = pending.id,
                    )
                } else {
                    val engaged = stateHolder.systemPromptEngagedState[pending.conversationId] ?: false
                    messageSender.sendMessage(
                        messageText = pending.content,
                        attachments = pending.attachments,
                        systemPrompt = systemPrompt.value.takeIf { engaged },
                        manualMessageId = pending.id,
                        contentParts = pending.contentParts,
                        onUserMessageAccepted = onAccepted,
                        onSendRejected = onRejected,
                        onTurnFinished = onFinished,
                    )
                }
            },
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
    val isLoadingImageHistory: StateFlow<Boolean>
        get() = stateHolder._isLoadingImageHistory.asStateFlow()
    val historyLoadGeneration: StateFlow<Long>
        get() = stateHolder._historyLoadGeneration.asStateFlow()
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
    val isRemoteCancellationPending: StateFlow<Boolean>
        get() = stateHolder._isRemoteCancellationPending.asStateFlow()
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
    val isCompactingContext: StateFlow<Boolean>
        get() = stateHolder._isCompactingContext.asStateFlow()
    val selectedMediaItems: SnapshotStateList<SelectedMediaItem>
        get() = stateHolder.selectedMediaItems
    val composerQuoteReferences: SnapshotStateList<com.android.everytalk.data.DataClass.MessageQuoteReference>
        get() = stateHolder.composerQuoteReferences
    val forkQuoteReferences: SnapshotStateList<com.android.everytalk.data.DataClass.MessageQuoteReference>
        get() = stateHolder.forkQuoteReferences
    val forkSelectedMediaItems: SnapshotStateList<SelectedMediaItem>
        get() = stateHolder.forkSelectedMediaItems
    val forkComposerText: StateFlow<String>
        get() = stateHolder._forkComposerText.asStateFlow()
    val forkDrawerOpenRequestVersion: StateFlow<Int>
        get() = stateHolder._forkDrawerOpenRequestVersion.asStateFlow()
    val forkConversationIds: StateFlow<Map<String, String>>
        get() = stateHolder._forkConversationIds.asStateFlow()
    val textApiRunVersion: StateFlow<Long>
        get() = stateHolder.textApiRunVersion.asStateFlow()
    val quoteJump: StateFlow<ViewModelStateHolder.QuoteJump?>
        get() = stateHolder._quoteJump.asStateFlow()

    val systemPromptExpandedState: SnapshotStateMap<String, Boolean>
        get() = stateHolder.systemPromptExpandedState

    // 重构：使用管理器类来组织代码
    internal val exportManager = ExportManager()
    val exportRequest: Flow<Pair<String, String>> = exportManager.exportRequest
    val settingsExportRequest: Flow<SettingsExportRequest> = exportManager.settingsExportRequest
    internal var pendingSettingsExport: SettingsExportRequest? = null

    internal val dialogManager = DialogManager()
    internal val editMessageController = EditMessageController(
        stateHolder = stateHolder,
        historyManager = historyManager,
        scope = viewModelScope,
        messagesMutex = messagesMutex,
        clearMessageCache = ::clearMessageCache,
        showSnackbar = ::showSnackbar,
        canEdit = {
            pendingMessageController.composerMode.value == com.android.everytalk.statecontroller.ComposerMode.Normal &&
                pendingMessageController.pendingMessages.value.isEmpty() &&
                pendingMessageController.runState.value == com.android.everytalk.statecontroller.ChatRunState.Idle
        },
    )
    val restoredMessageDraft = editMessageController.restoredDraft
    val isRestoringMessage = editMessageController.restoring
    val messageEditSession = editMessageController.editSession
    val showSystemPromptDialog: StateFlow<Boolean> = dialogManager.showSystemPromptDialog
    val showAboutDialog: StateFlow<Boolean> = dialogManager.showAboutDialog
    val showClearImageHistoryDialog: StateFlow<Boolean> = dialogManager.showClearImageHistoryDialog

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
    val expandedDrawerItemIndex: StateFlow<Int?> = drawerManager.expandedDrawerItemIndex
    internal val conversationSearchManager = ConversationSearchManager()
    val isConversationSearchActive: StateFlow<Boolean> = conversationSearchManager.isActive
    val conversationSearchQuery: StateFlow<String> = conversationSearchManager.query

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
    val isAgentEnabled: StateFlow<Boolean>
        get() = stateHolder._isAgentEnabled.asStateFlow()
    val isAgentPreparing: StateFlow<Boolean>
        get() = stateHolder._isAgentPreparing.asStateFlow()
    val computers get() = computerManager.computers
    val computerSelections get() = computerManager.selections
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentTaskRun = currentConversationId.flatMapLatest { id ->
        val dao = com.android.everytalk.data.database.AppDatabase.getDatabase(getApplication()).agentDao()
        dao.observeTaskRuns(id).map { runs ->
            withContext(Dispatchers.IO) {
                val store = com.android.everytalk.data.agent.AgentRunStore(dao)
                runs.firstOrNull { run -> store.taskTargets(run).size > 1 }
            }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)
    /** 抽屉观察所有会话的运行事实，不依赖当前页面或列表索引。 */
    val runningConversationIds: StateFlow<Set<String>> = kotlinx.coroutines.flow.combine(
        AppDatabase.getDatabase(getApplication()).agentDao().observeServiceRuns(),
        stateHolder.preparingTextConversationIds,
        apiHandler.agentRunControlSnapshots,
    ) { runs, preparing, controls ->
        runs.filter { run ->
            run.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "WAITING_APPROVAL") &&
                controls[run.visibleAssistantMessageId]?.state != AgentRunControlState.PAUSED
        }.mapTo(mutableSetOf()) { it.sessionId }.apply { addAll(preparing) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pendingComputerAgentApprovals = apiHandler.pendingAgentApprovals
    val pendingInterventions = apiHandler.pendingInterventions

    fun resolveIntervention(suspensionId: String, expectedVersion: Long, resolutionNonce: String) =
        apiHandler.resolveIntervention(suspensionId, expectedVersion, resolutionNonce)

    suspend fun resolveEphemeralIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secret: CharArray,
    ) = apiHandler.resolveEphemeralIntervention(suspensionId, expectedVersion, resolutionNonce, secret)

    /** 处理回答附件后交给 AgentRun；原始 Uri 不进入 Room 或 Agent 快照。 */
    suspend fun answerUserQuestion(
        intervention: com.android.everytalk.data.agent.PendingIntervention,
        content: String,
        attachments: List<com.android.everytalk.models.SelectedMediaItem>,
    ): Boolean {
        val stableAttachments = messageSender.processAttachments(
            attachments = attachments,
            shouldUsePartsApiMessage = false,
            textToActuallySend = content,
        ).takeIf { it.success }?.processedAttachmentsForUi ?: return false
        val nonce = intervention.resolutionNonce ?: return false
        return apiHandler.answerUserQuestion(
            suspensionId = intervention.suspensionId,
            expectedVersion = intervention.rowVersion,
            resolutionNonce = nonce,
            content = content,
            attachments = stableAttachments,
        )
    }

    suspend fun respondToInterventionWithText(
        intervention: com.android.everytalk.data.agent.PendingIntervention,
        content: String,
    ): Boolean {
        val nonce = intervention.resolutionNonce ?: return false
        return apiHandler.respondToInterventionWithText(
            suspensionId = intervention.suspensionId,
            expectedVersion = intervention.rowVersion,
            resolutionNonce = nonce,
            content = content,
        )
    }

    fun resolveDurableIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secureReference: String,
    ) = apiHandler.resolveDurableIntervention(
        suspensionId,
        expectedVersion,
        resolutionNonce,
        secureReference,
    )

    suspend fun rejectIntervention(suspensionId: String, expectedVersion: Long): Boolean =
        apiHandler.rejectIntervention(suspensionId, expectedVersion)

    suspend fun createAndResolveAuthorizationIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secret: CharArray,
    ) = apiHandler.createAndResolveAuthorizationIntervention(
        suspensionId,
        expectedVersion,
        resolutionNonce,
        secret,
    )

    fun confirmUnknownInterventionDelivered(suspensionId: String, expectedVersion: Long) =
        apiHandler.confirmUnknownInterventionDelivered(suspensionId, expectedVersion)

    fun continueAfterUnknownIntervention(suspensionId: String, expectedVersion: Long) =
        apiHandler.continueAfterUnknownIntervention(suspensionId, expectedVersion)
    val pendingAgentEnableApproval = kotlinx.coroutines.flow.combine(
        apiHandler.pendingAgentEnableApprovals,
        stateHolder._currentConversationId,
    ) { approvals, conversationId -> approvals.firstOrNull { it.conversationId == conversationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingMcpEnableApproval = kotlinx.coroutines.flow.combine(
        apiHandler.pendingMcpEnableApprovals,
        stateHolder._currentConversationId,
    ) { approvals, conversationId -> approvals.firstOrNull { it.conversationId == conversationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun respondToMcpEnableApproval(runId: String, approvalRequestId: String, approved: Boolean) {
        apiHandler.respondToAgentApproval(
            runId, approvalRequestId,
            if (approved) com.android.everytalk.data.agent.AgentApprovalDecision.APPROVED
            else com.android.everytalk.data.agent.AgentApprovalDecision.REJECTED,
        )
    }

    val pendingSkillSecretApproval = kotlinx.coroutines.flow.combine(
        apiHandler.pendingSkillSecretApprovals,
        stateHolder._currentConversationId,
    ) { approvals, conversationId -> approvals.firstOrNull { it.conversationId == conversationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingComputerAgentApproval = kotlinx.coroutines.flow.combine(
        apiHandler.pendingAgentApprovals,
        stateHolder._currentConversationId,
    ) { approvals, conversationId -> approvals.firstOrNull { it.request.context.conversationId == conversationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingComputerPublicPreview = kotlinx.coroutines.flow.combine(
        computerManager.pendingPublicPreview,
        pendingComputerAgentApproval,
    ) { legacy, agent -> legacy ?: agent?.publicPreviewRequest() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingComputerHostCommand = kotlinx.coroutines.flow.combine(
        computerManager.pendingHostCommand,
        pendingComputerAgentApproval,
    ) { legacy, agent ->
        legacy ?: agent?.hostConfirmationRequest()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val showSourcesDialog: StateFlow<Boolean>
        get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>>
        get() = stateHolder._sourcesForDialog.asStateFlow()
    val isStreamingPaused: StateFlow<Boolean>
        get() = stateHolder._isStreamingPaused.asStateFlow()
    val pendingMessages get() = pendingMessageController.visiblePendingMessages
    val composerMode get() = pendingMessageController.composerMode
    val chatRunState get() = pendingMessageController.runState

    val systemPrompt: StateFlow<String> = stateHolder._currentConversationId.flatMapLatest { id ->
        snapshotFlow { stateHolder.systemPrompts[id] ?: "" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    // 当前会话是否“接入系统提示”（开始/暂停）
    val isSystemPromptEngaged: StateFlow<Boolean> = stateHolder._currentConversationId.flatMapLatest { id ->
        snapshotFlow { stateHolder.systemPromptEngagedState[id] ?: false }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    // SystemPrompt moved to SystemPromptController

   // 控制器：系统提示
   internal val systemPromptController = SystemPromptController(stateHolder, dialogManager, historyManager, persistenceManager, viewModelScope)
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
       simpleModeSwitcher = simpleModeBridge,
       defaultNameFactory = ::localizedDefaultConversationName,
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
       sendMessage = { messageText, isFromRegeneration, attachments, isImageGeneration, manualMessageId, contentParts ->
           messageSender.sendMessage(
               messageText = messageText,
               isFromRegeneration = isFromRegeneration,
               attachments = attachments,
               isImageGeneration = isImageGeneration,
               manualMessageId = manualMessageId,
               contentParts = contentParts,
           )
       }
   )

   // 控制器：统一流式暂停/恢复/flush
   internal val streamingControls = StreamingControls(
       stateHolder = stateHolder,
       apiHandler = apiHandler,
       scope = viewModelScope,
       agentRunControlState = currentAgentRunControlState,
       requestPause = {
           stateHolder._currentTextStreamingAiMessageId.value
               ?.let(apiHandler::requestPauseCurrentAgent)
               ?: false
       },
       resumeAgent = {
           stateHolder._currentTextStreamingAiMessageId.value
               ?.let(apiHandler::resumePausedAgent)
               ?: false
       },
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
  private val computerFeatureFlags by lazy {
      com.android.everytalk.data.computer.ComputerFeatureFlags(getApplication())
  }
  private val localBashRuntime by lazy {
      com.android.everytalk.data.computer.SandboxJustBashRuntime(getApplication())
  }
  private val localBashToolExecutor by lazy {
      com.android.everytalk.data.computer.LocalBashToolExecutor(
          runtime = localBashRuntime,
          workspaceRoot = { workspaceId ->
              // 只规范化系统给出的应用目录；Workspace 及其子路径的符号链接仍由 Bridge 拒绝。
              java.io.File(getApplication<Application>().filesDir.canonicalFile, "agent-workspaces/$workspaceId")
          },
          enabled = { computerFeatureFlags.localBashEnabled },
      )
  }
  private val localFileSaveToolExecutor by lazy {
      com.android.everytalk.data.computer.LocalFileSaveToolExecutor(
          workspaceRoot = { workspaceId -> java.io.File(getApplication<Application>().filesDir.canonicalFile, "agent-workspaces/$workspaceId") },
          enabled = { computerFeatureFlags.localBashEnabled },
      )
  }

  init {
         // Fork 分支与父会话的对应关系存本机；会话 ID 迁移时 stateHolder 已同步改写。
         run {
             val prefs = application.getSharedPreferences("fork_conversations", android.content.Context.MODE_PRIVATE)
             stateHolder._forkConversationIds.value = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
             viewModelScope.launch {
                 stateHolder._forkConversationIds.collect { links ->
                     prefs.edit().clear().apply { links.forEach { (parent, fork) -> putString(parent, fork) } }.apply()
                 }
             }
         }
         // 恢复上次选中的强调色；找不到或名字失效时保持默认。
         viewModelScope.launch {
             val saved = persistenceManager.loadAccentColorName()
             if (saved != null) {
                 com.android.everytalk.ui.theme.AccentColor.entries
                     .firstOrNull { it.name == saved }
                     ?.let { _accentColor.value = it }
             }
         }
         val appToolExecutor: AppToolExecutor = {
             toolName,
             arguments,
             toolCallId,
             computerRequestContext,
             updateStatus,
         ->
            // 可信对象只由本地保存分支填充；MCP/云端 JSON 无法创建可打开的本机附件。
            var savedAttachment: SelectedMediaItem.GenericFile? = null
            var mcpResult: AppToolExecutionResult? = null
            val rawResult = executeSharedToolCall(
                    toolName = toolName,
                    arguments = arguments,
                    toolCallId = toolCallId,
                    computerRequestContext = computerRequestContext,
                    updateStatus = updateStatus,
                    mcpWebFetchFallback = buildMcpWebFetchFallback { mcpResult = it },
                    localWebSearchExecutor = buildLocalWebSearchExecutor(),
                    localAttachmentExecutor = buildLocalAttachmentExecutor(),
                    localBashExecutor = localBashToolExecutor::execute,
                    localFileSaveExecutor = { arguments, context ->
                        val saved = localFileSaveToolExecutor.execute(
                            arguments,
                            toolCallId,
                            context,
                        )
                        val attachment = com.android.everytalk.models.SelectedMediaItem.GenericFile(
                            uri = androidx.core.content.FileProvider.getUriForFile(
                                getApplication(), "${getApplication<Application>().packageName}.provider", saved.file,
                            ),
                            id = "local-file-${saved.sha256.take(16)}-${saved.path.hashCode().toUInt().toString(16)}",
                            displayName = "Workspace/${saved.path}",
                            mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(saved.path.substringAfterLast('.', "").lowercase())
                                ?: "text/plain",
                            filePath = saved.file.absolutePath,
                        )
                        savedAttachment = attachment
                        kotlinx.serialization.json.buildJsonObject {
                            put("attachment_id", kotlinx.serialization.json.JsonPrimitive(attachment.id))
                            put("file_link", kotlinx.serialization.json.JsonPrimitive("everytalk-file:${attachment.id}"))
                            put("ok", kotlinx.serialization.json.JsonPrimitive(true)); put("persisted", kotlinx.serialization.json.JsonPrimitive(true)); put("path", kotlinx.serialization.json.JsonPrimitive("Workspace/${saved.path}"))
                            put("bytes", kotlinx.serialization.json.JsonPrimitive(saved.bytes)); put("sha256", kotlinx.serialization.json.JsonPrimitive(saved.sha256))
                        }
                    },
                    localComputerExecutor = { localToolName, localArguments, localToolCallId, requestContext, updateComputerStatus ->
                        computerManager.execute(
                            localToolName,
                            localArguments,
                            localToolCallId,
                            requestContext,
                            updateComputerStatus,
                        )
                    },
                    fallbackExecutor = { fallbackToolName, fallbackArguments ->
                        mcpManager.callToolResult(fallbackToolName, fallbackArguments).also { mcpResult = it }.content
                    },
                )
            AppToolExecutionResult(
                content = rawResult,
                attachments = listOfNotNull(savedAttachment),
                scriptContent = mcpResult?.scriptContent,
                isError = mcpResult?.isError,
            )
         }
         AgentToolExecutorRegistry.register(
             owner = mcpToolExecutorOwner,
             executor = appToolExecutor,
             approvalProvider = computerManager::approvalRequest,
         )

          viewModelScope.launch(Dispatchers.IO) {
              computerManager.awaitLocalRecovery()
              val agentDao = AppDatabase.getDatabase(getApplication()).agentDao()
              // 进程内没有真实任务时，先封存消息已经结束的旧 Run，禁止冷启动把它恢复成运行中。
              if (!com.android.everytalk.service.ComputerConnectionServiceController.hasActiveTokens() &&
                  com.android.everytalk.service.ComputerConnectionServiceController.activeAgentRunCount() == 0
              ) {
                  agentDao.cancelStaleVisibleMessageRuns(
                      AgentTerminalReasons.VISIBLE_MESSAGE_TERMINAL,
                      System.currentTimeMillis(),
                  )
              }
              apiHandler.restorePendingAgentApproval()
              apiHandler.restoreVisibleAgentState()
              // 空闲冷启动不能先弹前台服务通知；真实任务仍由统一服务继续恢复和监听。
              com.android.everytalk.service.ComputerConnectionServiceController.resumeActiveTasksIfNeeded(getApplication())
          }

        viewModelScope.launch(Dispatchers.IO) {
            aiContentReportRepository.retryPendingReports()
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

        // 同账号其他设备运行中的回复：换成本机会话和消息 ID 后交给消息列表叠加显示。
        viewModelScope.launch {
            val chatDao = AppDatabase.getDatabase(getApplication()).chatDao()
            kotlinx.coroutines.flow.combine(stateHolder._currentConversationId, com.android.everytalk.data.sync.LiveSync.remote) { id, remote -> id to remote }
                .collectLatest { (currentId, remote) ->
                    stateHolder._remoteLiveRun.value = if (remote.isEmpty()) null else withContext(Dispatchers.IO) {
                        val sessionUuid = chatDao.getStoredSession(currentId)?.uuid ?: return@withContext null
                        val run = remote[sessionUuid] ?: return@withContext null
                        val localId = chatDao.getMessageByUuid(run.messageUuid)?.id ?: "cloud:${run.messageUuid}"
                        com.android.everytalk.statecontroller.RemoteLiveRun(sessionUuid, run.message.copy(id = localId, conversationId = currentId,
                            executionStatus = run.message.executionStatus.takeIf { run.running }), run.running)
                    }
                }
        }
        // 另一台设备点了停止：当前页面的任务走停止按钮同一流程，后台会话直接结束。
        viewModelScope.launch {
            com.android.everytalk.data.sync.LiveSync.cancelRequests.collect { sessionUuid ->
                val sessionId = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(getApplication()).chatDao().getSessionByUuid(sessionUuid)?.id
                } ?: return@collect
                if (stateHolder._currentConversationId.value == sessionId && stateHolder._isTextApiCalling.value) {
                    apiHandler.cancelCurrentApiJob("另一台设备请求停止", isImageGeneration = false, showFeedback = true)
                } else {
                    apiHandler.stopConversationRuns(sessionId)
                }
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
            // 初始恢复完毕后才监听同步；重读预览不会收尾当前进程的流式草稿。
            viewModelScope.launch {
                com.android.everytalk.data.sync.SyncLocalChanges.revision.debounce(300).collect { revision ->
                    if (revision == 0L) return@collect
                    try {
                        persistenceManager.refreshSyncedConfigurations(simpleModeManager::isInImageMode)
                        _externalWebSearchConfigs.value = persistenceManager.loadExternalWebSearchConfigs().associateBy { it.providerId }
                        persistenceManager.loadSelectedExternalWebSearchProviderId()?.let { _selectedExternalWebSearchProviderId.value = it }
                        val history = withContext(Dispatchers.IO) { persistenceManager.roomDataSource.loadChatHistoryPreviewResult() }
                        stateHolder._historicalConversations.value = history.sessions.map { it.messages }
                        stateHolder.conversationGroups.value = persistenceManager.loadConversationGroups()
                        stateHolder.pinnedTextConversationIds.value = persistenceManager.loadPinnedIds(false)
                        val currentId = stateHolder._currentConversationId.value
                        if (currentId.isNotBlank()) {
                            val stored = AppDatabase.getDatabase(getApplication()).chatDao().getStoredSession(currentId)
                            if (stored?.deleted == true) {
                                startNewChat()
                            } else if (stored != null && !stateHolder.isTextConversationDirty.value && stateHolder.textApiJob?.isActive != true &&
                                // 回答提问后由协调器续写，textApiJob 已结束；Agent 仍在输出时不能用 Room 快照覆盖实时消息。
                                !stateHolder._isTextApiCalling.value
                            ) {
                                val messages = persistenceManager.loadHistorySession(currentId)
                                if (stateHolder._currentConversationId.value == currentId && !stateHolder.isTextConversationDirty.value) {
                                    stateHolder.messages.clear()
                                    stateHolder.messages.addAll(messages.orEmpty())
                                    stateHolder.rememberSeenTextMessages(stateHolder.messages)
                                    // 与手动打开历史页走同一账本恢复，避免同步后把步骤与耗时退回旧快照。
                                    apiHandler.restoreVisibleAgentState()
                                }
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    // 同步静默进行：刷新失败只记日志，下次同步通知会再刷新。
                    catch (error: Exception) { Log.w("AppViewModel", "同步后刷新界面失败", error) }
                }
            }
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
                    val isGroupSectionExpanded = persistenceManager.loadIsGroupSectionExpanded()
                    withContext(Dispatchers.Main) {
                        stateHolder.expandedGroups.value = expandedKeys
                        stateHolder.isGroupSectionExpanded.value = isGroupSectionExpanded
                    }
                    Log.d("AppViewModel", "分组展开状态已加载 - 共 ${expandedKeys.size} 个展开的分组, 分组总区域展开=$isGroupSectionExpanded")
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
       // AccountManager 是应用级实例，WorkManager 仍可能使用，不能随 ViewModel 一起关闭。
       cloudflareOAuthStore.clear()
       (localBashRuntime as? AutoCloseable)?.close()
        AgentToolExecutorRegistry.clear(mcpToolExecutorOwner)
        // 清理消息内容控制器（若未来扩展内部资源）
        messageContentController.cleanup()
        // 统一的生命周期清理
        lifecycleCoordinator.onCleared()
        // 关闭 MCP 管理器
        mcpManager.close()
        computerManager.close()
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
