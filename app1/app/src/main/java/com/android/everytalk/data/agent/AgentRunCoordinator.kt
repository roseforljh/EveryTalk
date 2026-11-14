package com.android.everytalk.data.agent

import com.android.everytalk.util.image.encodeImageForModel
import android.content.Context
import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.DataClass.resolvedModelTokenLimits
import com.android.everytalk.data.DataClass.toApiText
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.toApiConfig
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.targetContexts
import com.android.everytalk.data.computer.ComputerToolRequestHasher
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.data.network.buildDirectMultimodalRequest
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.util.AgentNotificationManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillRuntimeTools
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 后台仅累积不能从执行账本还原的可见元数据；正文和工具顺序始终以持久账本为准。 */
internal fun projectBackgroundLiveMetadata(
    message: com.android.everytalk.data.DataClass.Message,
    event: AppStreamEvent,
): com.android.everytalk.data.DataClass.Message = when (event) {
    is AppStreamEvent.Usage -> com.android.everytalk.statecontroller.applyMessageTokenUsage(message, event)
    is AppStreamEvent.AgentUsage -> com.android.everytalk.statecontroller.applyMessageTokenUsage(message, event)
    is AppStreamEvent.WebSearchResults -> message.copy(webSearchResults = event.results)
    is AppStreamEvent.ExecutionStatusUpdate -> message.copy(executionStatus = event.status)
    else -> message
}

/** 消费队列时才读取附件文件并生成多模态消息，Room 永远只保存稳定文件引用。 */
internal suspend fun materializeAndroidQueuedMessage(
    context: Context,
    instruction: AgentSteeringInstruction,
    request: com.android.everytalk.data.DataClass.ChatRequest,
): AbstractApiMessage {
    val baseMessage = SimpleTextApiMessage(
        id = "queued:${instruction.id}",
        role = "user",
        content = instruction.contentParts.toApiText(instruction.content),
    )
    return buildDirectMultimodalRequest(
        request = request.copy(messages = listOf(baseMessage)),
        attachments = instruction.attachments,
        context = context,
    ).messages.single()
}

/**
 * 手动「立即压缩」用的请求。
 * Run 一进终态，恢复快照就被回收，restoreChatRequest 拿不到历史。所以模型参数从 ApiConfig 重建，
 * 历史由会话消息表加 AgentEntry 投影拼出来，再交给调用方送进 compactNow。
 */
internal fun buildManualCompactionRequest(
    config: ApiConfig,
    limits: ModelTokenLimits,
    messages: List<AbstractApiMessage>,
): ChatRequest = ChatRequest(
    messages = messages,
    provider = config.provider,
    channel = config.effectiveModelChannel(),
    apiAddress = config.address,
    apiKey = config.key,
    model = config.model,
    generationConfig = GenerationConfig(maxOutputTokens = limits.maxOutputTokens),
    contextManagement = RequestContextManagement(
        configId = config.id,
        maxContextTokens = limits.maxContextTokens,
        reservedOutputTokens = limits.maxOutputTokens,
        // forceLocalCompaction 会跳过阈值判断，这里只为满足压缩的前置开关。
        compactThresholdTokens = limits.maxContextTokens.toLong(),
        autoCompressionEnabled = true,
    ),
)

/**
 * 独立的全局应用级/服务级 AgentRun 协调器。
 * 拥有独立于 Activity/ViewModel 生命周期的协程作用域，
 * 负责驱动 AgentLoop 运行与断线/续写恢复。
 */
class AgentRunCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
    private val pauseController: AgentRunPauseController = AgentRunPauseController(),
    private val databaseOverride: AppDatabase? = null,
    private val modelTransport: ModelTurnTransport = ModelTurnTransport { ApiClient.streamModelTurn(it.request) },
    private val interventionAdapters: AgentInterventionAdapterRegistry? = null,
) {
    companion object {
        @Volatile private var sharedInstance: AgentRunCoordinator? = null

        /** UI 与前台服务必须共用同一个协调器，避免同一 Run 被两套恢复循环重复驱动。 */
        fun shared(
            context: Context,
            computerSessionStateProvider: suspend (ComputerRequestContext?) -> String? = { null },
        ): AgentRunCoordinator = sharedInstance ?: synchronized(this) {
            sharedInstance ?: AgentRunCoordinator(
                context = context.applicationContext,
                computerSessionStateProvider = computerSessionStateProvider,
            ).also { sharedInstance = it }
        }
    }
    private val appContext = context.applicationContext
    private val database by lazy { databaseOverride ?: AppDatabase.getDatabase(appContext) }
    private val agentDao by lazy { database.agentDao() }
    private val computerDao by lazy { database.computerDao() }
    private val agentRunStore by lazy {
        AgentRunStore(
            dao = agentDao,
            queuedMessageMaterializer = { instruction, request ->
                materializeAndroidQueuedMessage(appContext, instruction, request)
            },
            skillReferenceValidator = { references ->
                skillRepository.createSnapshot(references).manualReferences
            },
        )
    }
    private val interventionStore by lazy { AgentInterventionStore(agentDao) }
    private val interventionPolicyRegistry by lazy { AgentInterventionPolicyRegistry() }
    private val capabilityGrantStore by lazy { AgentCapabilityGrantStore(agentDao) }
    private val interventionResourceLeaseStore by lazy { AgentResourceLeaseStore(agentDao) }
    private val interventionCredentialStore by lazy {
        com.android.everytalk.data.computer.ComputerCredentialStore(appContext)
    }
    private val interventionStoredAuthorizationStore by lazy { AgentStoredAuthorizationStore(agentDao) }
    private val interventionAuthorizationManager by lazy {
        AgentAuthorizationManager(
            interventionStoredAuthorizationStore,
            interventionCredentialStore,
        )
    }
    private val computerRepository by lazy {
        com.android.everytalk.data.computer.ComputerRepository(appContext)
    }
    private val interventionAdapterRegistry by lazy {
        interventionAdapters ?: AgentInterventionAdapterRegistry(
            mapOf(
                "git-adapter" to StoredAuthorizationCapabilityAdapter(
                    "github",
                    interventionStoredAuthorizationStore,
                    interventionCredentialStore,
                ),
                "skill-capability-proxy" to StoredAuthorizationCapabilityAdapter(
                    "openai",
                    interventionStoredAuthorizationStore,
                    interventionCredentialStore,
                ),
                "workspace-secret-adapter" to WorkspaceSecretCapabilityAdapter(
                    com.android.everytalk.data.computer.ComputerWorkspaceSecretManager(
                        computerRepository,
                    ),
                    computerRepository,
                ),
                "cloudflare-reauthorization-adapter" to CloudflareReauthorizationAdapter { request ->
                    val computerId = request.parameters["computer_id"]
                    val expectedGeneration = request.parameters["previous_generation"]?.toLongOrNull()
                    if (computerId.isNullOrBlank() || expectedGeneration == null) false else {
                        val config = computerDao.getCloudflareConfig(computerId)
                        val authorization = config?.let { computerDao.getCloudflareAuthorization(it.authorizationId) }
                        authorization != null &&
                            authorization.authorizationId == request.parameters["authorization_id"] &&
                            config.accountId == request.parameters["account_id"] &&
                            !authorization.revoked &&
                            authorization.generation > expectedGeneration &&
                            (authorization.expiresAt == null || authorization.expiresAt > System.currentTimeMillis())
                    }
                },
                "cloudflare-resource-selection-adapter" to CloudflareResourceSelectionAdapter { request ->
                    com.android.everytalk.data.computer.CloudflareResourceIndex(computerDao)
                        .selectionReady(request.suspensionId, request.parameters)
                },
            ),
        )
    }
    private val interventionRecovery by lazy {
        AgentInterventionRecovery(
            agentDao,
            interventionStore,
            registry = interventionPolicyRegistry,
            broker = interventionBroker,
        )
    }
    private val interventionBroker by lazy {
        AgentInterventionBroker(
            store = interventionStore,
            registry = interventionPolicyRegistry,
            adapters = interventionAdapterRegistry,
            grants = capabilityGrantStore,
            resourceLeases = interventionResourceLeaseStore,
            onSuspended = { ticket ->
                val suspension = ticket.suspension
                _pendingInterventions.update { current ->
                    val existing = current.firstOrNull { it.suspensionId == suspension.id }
                    // 一个 Run 在 AgentLoop 暂停后不会继续创建第二条接力；如果恢复/重试竞态留下旧投影，
                    // 旧记录不能挡住本次最新请求。按 runId 清理旧投影，真正的持久状态仍以 Room 为准。
                    current.filterNot { it.runId == suspension.runId } +
                        PendingIntervention(
                            suspensionId = suspension.id,
                            runId = suspension.runId,
                            sessionId = ticket.sessionId,
                            capabilityId = suspension.capabilityId,
                            reasonSafe = suspension.reasonSafe,
                            userVisibleContext = suspension.userVisibleContext,
                            parameters = runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(suspension.parametersJson) }.getOrDefault(emptyMap()),
                            materialKind = ResolutionMaterialKind.valueOf(suspension.resolutionMaterialKind),
                            fields = interventionPolicyRegistry.resolve(suspension.capabilityId)?.fields.orEmpty(),
                            requestSource = InterventionRequestSource.valueOf(suspension.requestSource),
                            rowVersion = suspension.rowVersion,
                            state = SuspensionState.valueOf(suspension.status),
                            resolutionNonce = ticket.resolutionNonce ?: existing?.resolutionNonce,
                        )
                }
            },
        )
    }
    private val agentToolResultStore by lazy { AgentToolResultStore(appContext) }
    private val skillRepository by lazy { SkillRepository(appContext, database.skillDao()) }
    private val skillRuntimeTools by lazy { SkillRuntimeTools(skillRepository, agentRunStore) }
    private val memoryCoordinator by lazy { com.android.everytalk.data.memory.MemoryCoordinator(database, scope) }
    private val recoveryToolRuntime by lazy {
        AgentToolRuntime(
            executorProvider = AgentToolExecutorRegistry::current,
            resultStore = agentToolResultStore,
            skillRuntimeTools = skillRuntimeTools,
        )
    }
    private val activeJobs = ConcurrentHashMap<String, Job>()
    /** 先于 Room Run 创建到达的停止请求，防止用户第一次点击落在登记空窗内。 */
    private val visibleRunCancellationReasons = ConcurrentHashMap<String, String>()
    private val recoveringRunIds = ConcurrentHashMap.newKeySet<String>()
    private val resumeRetryStates = ConcurrentHashMap<String, ResumeRetryState>()
    private val resumeMutex = Mutex()
    // 恢复扫描不能把仍在本进程履行的接力误当成崩溃遗留状态。
    private val interventionMutex = Mutex()

    private val _events = MutableSharedFlow<Pair<String, AppStreamEvent>>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    /** ApiHandler 收集事件时计数；为 0 表示界面已销毁、只有前台服务在跑，由协调器自己上报实时快照。 */
    val uiEventCollectors = java.util.concurrent.atomic.AtomicInteger(0)
    private val liveMessages = ConcurrentHashMap<String, com.android.everytalk.data.DataClass.Message>()
    private val liveJobs = ConcurrentHashMap<String, Job>()
    // 常驻收集器不放进 Run 的 scope，等待 Run 全部结束的地方不能被它们挡住。
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _cloudRuns = MutableStateFlow<Map<String, com.android.everytalk.data.sync.LiveRemoteRun>>(emptyMap())
    internal val cloudRuns = _cloudRuns.asStateFlow()
    @Volatile private var cloudAccountId: String? = null
    @Volatile private var cloudCoordinator: CloudAgentCoordinator? = null
    private val cloudReady = MutableStateFlow<Pair<String, CloudAgentCoordinator>?>(null)
    private val cloudViews = ConcurrentHashMap<String, CloudRunView>()

    /** 授权只属于当前账号；账号切换取消的是订阅，不是服务器上的任务。 */
    private suspend fun cloudPermitted(accountId: String): Boolean {
        val auth = com.android.everytalk.data.auth.AuthRuntime.get(appContext)
        val state = com.android.everytalk.data.sync.SyncStateStore(database)
        return (auth.auth.state.value as? com.android.everytalk.data.auth.AuthState.SignedIn)?.user?.id == accountId &&
            !auth.account.state.value.sessionExpired && auth.binding.readBoundAccountId() == accountId && state.isEnabled()
    }

    private suspend fun projectCloudSnapshot(accountId: String, view: CloudRunView, message: com.android.everytalk.data.DataClass.Message) {
        if (!cloudPermitted(accountId)) return
        cloudViews[view.id] = view
        cloudStatuses.update { it + (view.id to view.status) }
        message.conversationId?.let { projectCloudWaiting(view, it) }
        if (view.terminal || view.status == "PAUSED") cloudPauseRequested.update { it - view.id }
        _cloudRuns.update { current -> current + (view.sessionUuid to com.android.everytalk.data.sync.LiveRemoteRun(
            view.assistantMessageUuid, message, !view.answered, view.id)) }
    }

    /** 复用既有认证和账号绑定；发现与重连均在应用级作用域，页面生命周期不拥有执行权。 */
    private suspend fun observeCloudAccount() {
        val auth = com.android.everytalk.data.auth.AuthRuntime.get(appContext)
        auth.auth.startup.first { it.initialized }
        auth.auth.state.map { (it as? com.android.everytalk.data.auth.AuthState.SignedIn)?.user?.id }
            .distinctUntilChanged().collectLatest { accountId ->
                cloudAccountId = null
                cloudCoordinator = null
                cloudReady.value = null
                cloudViews.clear()
                _cloudRuns.value = emptyMap(); clearCloudWaiting()
                if (accountId == null || !com.android.everytalk.data.sync.syncServerConfigured(com.android.everytalk.BuildConfig.EVERYTALK_SYNC_BASE_URL)) return@collectLatest
                val state = com.android.everytalk.data.sync.SyncStateStore(database)
                if (!state.prepareAutomaticAccount(accountId, auth.binding) {
                    (auth.auth.state.value as? com.android.everytalk.data.auth.AuthState.SignedIn)?.user?.id == accountId
                }) return@collectLatest
                kotlinx.coroutines.coroutineScope {
                    val client = CloudAgentClient(com.android.everytalk.BuildConfig.EVERYTALK_SYNC_BASE_URL,
                        token = { auth.account.accessTokenForOwnedServer(it) }, permitted = { cloudPermitted(accountId) })
                    val coordinator = CloudAgentCoordinator(accountId, database, client, interventionCredentialStore, this,
                        permitted = { cloudPermitted(accountId) },
                        onSnapshot = { view, message -> projectCloudSnapshot(accountId, view, message) },
                        onConnection = { run, status ->
                            if (cloudPermitted(accountId)) _cloudRuns.update { current -> current.mapValues { (_, live) ->
                                if (live.runId == run.id && live.running) live.copy(message = live.message.copy(executionStatus = status)) else live
                            } }
                        })
                    cloudAccountId = accountId
                    cloudCoordinator = coordinator
                    cloudReady.value = accountId to coordinator
                    // 归档可能先于最后一帧 SSE 到达：同步写入后立刻对账，避免界面停在运行中。
                    launch { com.android.everytalk.data.sync.SyncLocalChanges.revision.collect { reconcileCloudOverlays() } }
                    try {
                        while (isActive) {
                            reconcileCloudOverlays()
                            if (cloudPermitted(accountId)) {
                                try { coordinator.discover() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* 服务未升级或临时断网：保留持久任务，后续再次发现。 */ }
                            } else {
                                cloudViews.clear()
                                _cloudRuns.value = emptyMap(); clearCloudWaiting()
                            }
                            delay(15_000)
                        }
                    } finally {
                        client.close()
                        if (cloudCoordinator === coordinator) {
                            cloudCoordinator = null
                            cloudReady.value = null
                            cloudAccountId = null
                            cloudViews.clear()
                            _cloudRuns.value = emptyMap(); clearCloudWaiting()
                        }
                    }
                }
            }
    }

    internal suspend fun cloudMessage(run: AgentRunEntity): com.android.everytalk.data.DataClass.Message? {
        val account = cloudAccountId?.takeIf { it == run.cloudAccountId && cloudPermitted(it) } ?: return null
        val store = CloudAgentStore(database)
        val view = store.read(run.id) ?: return null
        return store.project(account, view)
    }

    internal suspend fun commandCloudRun(runId: String, type: String, fields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), generation: Long? = null): Boolean {
        val coordinator = cloudCoordinator ?: return false
        coordinator.command(runId, type, fields, generation)
        return true
    }

    init {
        if (databaseOverride == null) liveScope.launch { observeCloudAccount() }
        liveScope.launch {
            events.collect { (messageId, event) -> if (uiEventCollectors.get() == 0) publishLiveFromLedger(messageId, event) }
        }
        // 界面不在时，另一台设备的停止请求由这里执行。
        liveScope.launch {
            com.android.everytalk.data.sync.LiveSync.cancelRequests.collect { sessionUuid ->
                if (uiEventCollectors.get() > 0) return@collect
                val session = database.chatDao().getSessionByUuid(sessionUuid) ?: return@collect
                agentRunStore.getRunsForSession(session.id)
                    .filter { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED") }
                    .forEach { cancelVisibleRun(it.visibleAssistantMessageId) }
            }
        }
    }

    /** 按 Room 账本重建可见回复并上报，500ms 节流；Finish 立即通知结束。 */
    private fun publishLiveFromLedger(messageId: String, event: AppStreamEvent) {
        val terminal = event is AppStreamEvent.Finish
        // 元数据先于节流判断吸收；否则 500ms 内到达的搜索/用量事件会永久丢失。
        liveMessages.compute(messageId) { _, previous ->
            projectBackgroundLiveMetadata(previous ?: com.android.everytalk.data.DataClass.Message(
                id = messageId, text = "", sender = com.android.everytalk.data.DataClass.Sender.AI), event)
        }
        if (!terminal && liveJobs[messageId]?.isActive == true) return
        if (terminal) liveJobs.remove(messageId)?.cancel()
        liveJobs[messageId] = liveScope.launch {
            if (!terminal) delay(500)
            val run = agentRunStore.getRunByVisibleMessage(messageId) ?: return@launch
            if (run.executionOwner == "CLOUD") return@launch
            val chat = database.chatDao()
            val session = chat.getStoredSession(run.sessionId)
                ?.takeUnless { it.deleted || it.isImageGeneration } ?: return@launch
            val stored = chat.getMessage(run.sessionId, messageId)
            val metadata = liveMessages[messageId]
            val trace = agentRunStore.executionTrace(run.id)
            val message = (stored?.toMessage() ?: com.android.everytalk.data.DataClass.Message(
                id = messageId, text = "", sender = com.android.everytalk.data.DataClass.Sender.AI,
                timestamp = run.createdAt,
            )).copy(
                conversationId = run.sessionId, contentStarted = true,
                executionFinishedAt = run.updatedAt.takeIf { terminal },
                text = trace.filterIsInstance<ExecutionTraceEvent.Content>().joinToString("") { it.text },
                reasoning = trace.filterIsInstance<ExecutionTraceEvent.Reasoning>().joinToString("") { it.text }.ifBlank { null },
                executionTrace = trace,
                executionSteps = trace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step },
                executionStatus = metadata?.executionStatus.takeUnless { terminal },
                webSearchResults = metadata?.webSearchResults ?: stored?.webSearchResults,
                tokenUsage = metadata?.tokenUsage ?: stored?.tokenUsage,
                contextUsageSnapshot = metadata?.contextUsageSnapshot ?: stored?.contextUsageSnapshot,
                isError = run.status == AgentRunStatus.FAILED.name,
                isIncomplete = terminal && run.status != AgentRunStatus.COMPLETED.name,
            )
            if (terminal) { liveMessages.remove(messageId); liveJobs.remove(messageId) }
            com.android.everytalk.data.sync.LiveSync.publish(appContext, session.uuid,
                stored?.uuid ?: chat.resolveMessageSyncUuid(messageId), message, terminal) { isRunActive(run) }
        }
    }
    private val _lifecycleEvents = MutableSharedFlow<AgentLifecycleEvent>(extraBufferCapacity = 128)
    val lifecycleEvents = _lifecycleEvents.asSharedFlow()
    private val _pendingInterventions = MutableStateFlow<List<PendingIntervention>>(emptyList())
    /** 云端任务停在“等你回答”时投影成同一种提问卡；按 runId 保存，每个任务同时只有一个等待点。 */
    private val cloudInterventions = MutableStateFlow<Map<String, PendingIntervention>>(emptyMap())
    val pendingInterventions: StateFlow<List<PendingIntervention>> = combine(_pendingInterventions, cloudInterventions) { local, cloud ->
        local + cloud.values
    }.stateIn(liveScope, SharingStarted.Eagerly, emptyList())
    /** 云端任务停在“等你确认保存文件”时投影成本机同一种文件写入确认卡。 */
    private val _cloudToolApprovals = MutableStateFlow<Map<String, com.android.everytalk.data.computer.PendingComputerToolApproval>>(emptyMap())
    internal val cloudToolApprovals: StateFlow<List<com.android.everytalk.data.computer.PendingComputerToolApproval>> =
        _cloudToolApprovals.map { it.values.toList() }.stateIn(liveScope, SharingStarted.Eagerly, emptyList())
    private val cloudDeviceHandled = ConcurrentHashMap.newKeySet<String>()
    private val cloudFileSave by lazy {
        com.android.everytalk.data.computer.LocalFileSaveToolExecutor(
            workspaceRoot = { workspaceId -> java.io.File(appContext.filesDir.canonicalFile, "agent-workspaces/$workspaceId") },
            enabled = { com.android.everytalk.data.computer.ComputerFeatureFlags(appContext).localBashEnabled },
        )
    }
    private val cloudRunContexts by lazy { appContext.getSharedPreferences("cloud_run_contexts", Context.MODE_PRIVATE) }

    private fun clearCloudWaiting() {
        cloudInterventions.value = emptyMap()
        _cloudToolApprovals.value = emptyMap()
        _cloudMcpEnables.value = emptyMap()
        _cloudAgentEnables.value = emptyMap()
        _cloudSkillSecrets.value = emptyMap()
    }

    /**
     * 只在提交任务的手机上记住本地 Workspace 与记忆快照；手机上的工具只能在这台手机执行，其他设备不接这类等待。
     * owner 标记与上下文同步落盘，进程被杀后重开也能继续执行等待中的手机工具。
     */
    private fun saveCloudRunContext(runId: String, context: ComputerRequestContext?, memory: com.android.everytalk.data.memory.MemoryRequestSnapshot?) {
        cloudRunContexts.edit().apply {
            putString(ownerKey(runId), "1")
            context?.let { putString(runId, cloudAgentJson.encodeToString(ComputerRequestContext.serializer(), it)) }
            memory?.let { putString(memoryKey(runId), cloudAgentJson.encodeToString(com.android.everytalk.data.memory.MemoryRequestSnapshot.serializer(), it)) }
        }.commit()
    }

    private fun ownsCloudRun(runId: String) = cloudRunContexts.contains(ownerKey(runId)) || cloudRunContexts.contains(runId)
    private fun ownerKey(runId: String) = "owner:$runId"
    private fun memoryKey(runId: String) = "memory:$runId"
    private fun approvedToolKey(runId: String, toolCallId: String) = "approved_tool:$runId:$toolCallId"

    /** 手机工具复用本机同一个执行入口（含 Computer 审批策略、结果截断与归档）。 */
    private val cloudToolRuntime by lazy {
        AgentToolRuntime(executorProvider = AgentToolExecutorRegistry::current, resultStore = agentToolResultStore, skillRuntimeTools = skillRuntimeTools)
    }

    private fun approvedContext(context: ComputerRequestContext?, runId: String, toolCallId: String,
        request: com.android.everytalk.data.computer.ComputerToolApprovalRequest?) = context?.copy(
        runId = runId,
        approvedToolCallId = toolCallId.takeIf { request != null },
        approvedCloudflareCronChange = request as? com.android.everytalk.data.computer.ComputerToolApprovalRequest.CloudflareCronChange,
        approvedCloudflareWrite = request as? com.android.everytalk.data.computer.ComputerToolApprovalRequest.CloudflareWrite,
        approvedLocalFileWrite = (request as? com.android.everytalk.data.computer.ComputerToolApprovalRequest.LocalFileWrite)
            ?.let { it.copy(context = it.context.copy(runId = runId)) },
    )

    /** 在本机执行云端交来的一个工具，返回给模型的文本与是否失败。 */
    private suspend fun executeCloudDeviceTool(runId: String, call: AgentContentBlock.ToolCall,
        approval: com.android.everytalk.data.computer.ComputerToolApprovalRequest?): Pair<String, Boolean> {
        val context = approvedContext(cloudRunContext(runId), runId, call.id, approval)
        if (call.name == com.android.everytalk.data.memory.MemoryTool.NAME) {
            val snapshot = cloudRunContexts.getString(memoryKey(runId), null)?.let {
                runCatching { cloudAgentJson.decodeFromString(com.android.everytalk.data.memory.MemoryRequestSnapshot.serializer(), it) }.getOrNull()
            }
            memoryCoordinator.bind(runId, snapshot)
            val content = memoryCoordinator.execute(runId, call.arguments)
            val ok = ((content as? kotlinx.serialization.json.JsonObject)?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content
            return content.toString() to (ok == "false")
        }
        val result = cloudToolRuntime.execute(call, context, runId = runId) { }
        val text = (result.content as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: result.content.toString()
        return text to result.isError
    }

    private fun sendCloudDeviceResult(runId: String, toolCallId: String, text: String, isError: Boolean,
        tools: kotlinx.serialization.json.JsonArray? = null, onFailure: () -> Unit = {}) =
        launchCloudCommand(runId, "device-result", "工具结果回传失败，请重试", kotlinx.serialization.json.buildJsonObject {
            put("toolCallId", kotlinx.serialization.json.JsonPrimitive(toolCallId))
            put("result", kotlinx.serialization.json.JsonPrimitive(text))
            put("isError", kotlinx.serialization.json.JsonPrimitive(isError))
            tools?.let { put("tools", it) }
        }, onFailure)

    private val _cloudMcpEnables = MutableStateFlow<Map<String, PendingMcpEnableApproval>>(emptyMap())
    private val _cloudAgentEnables = MutableStateFlow<Map<String, PendingAgentEnableApproval>>(emptyMap())
    val cloudMcpEnableApprovals: StateFlow<List<PendingMcpEnableApproval>> =
        _cloudMcpEnables.map { it.values.toList() }.stateIn(liveScope, SharingStarted.Eagerly, emptyList())
    val cloudAgentEnableApprovals: StateFlow<List<PendingAgentEnableApproval>> =
        _cloudAgentEnables.map { it.values.toList() }.stateIn(liveScope, SharingStarted.Eagerly, emptyList())

    /**
     * 云端任务里开启 MCP/Agent 的决定。批准后用本机同一套准备流程（连接 MCP、选服务器、同步 Skill），
     * 只把新增工具冻结成云端绑定交给任务；环境说明随工具结果交给模型。
     */
    internal fun respondCloudEnable(runId: String, approvalRequestId: String, approved: Boolean,
        prepare: suspend (String, Boolean, com.android.everytalk.data.DataClass.ChatRequest, List<String>) -> com.android.everytalk.data.DataClass.ChatRequest) {
        val toolCallId = approvalRequestId.removePrefix("cloud:enable:")
        val mcp = _cloudMcpEnables.value[runId]?.takeIf { it.approvalRequestId == approvalRequestId }
        val agent = _cloudAgentEnables.value[runId]?.takeIf { it.approvalRequestId == approvalRequestId }
        _cloudMcpEnables.update { it - runId }; _cloudAgentEnables.update { it - runId }
        val sessionId = mcp?.conversationId ?: agent?.conversationId ?: return
        scope.launch {
            if (!approved) { sendCloudDeviceResult(runId, toolCallId, "用户拒绝开启", true); return@launch }
            try {
                val base = com.android.everytalk.data.DataClass.ChatRequest(
                    messages = listOf(com.android.everytalk.data.DataClass.SimpleTextApiMessage(role = "system", content = "")),
                    provider = "", channel = "", apiAddress = "", apiKey = "", model = "",
                    localComputerRequestContext = cloudRunContext(runId))
                val next = prepare(sessionId, mcp != null, base, agent?.requiredSkillIds.orEmpty())
                val added = next.tools.orEmpty().filter { tool ->
                    McpToolDiscovery.name(tool).let { it.isNotEmpty() && it !in AgentControlToolNames.all && it !in com.android.everytalk.data.skill.SkillToolNames.all }
                }
                next.localComputerRequestContext?.let { saveCloudRunContext(runId, it, null) }
                val tools = cloudAgentTools(appContext, next.copy(tools = added), emptyList(), skillRepository)
                val prompt = (next.messages.firstOrNull { it.role == "system" } as? com.android.everytalk.data.DataClass.SimpleTextApiMessage)?.content?.trim().orEmpty()
                val text = if (mcp != null) "用户已允许开启 MCP，请使用本轮提供的 MCP 工具继续任务"
                    else "用户已允许开启当前会话 Agent，可以继续调用 Agent 服务器工具" + prompt.takeIf(String::isNotEmpty)?.let { "\n\n$it" }.orEmpty()
                sendCloudDeviceResult(runId, toolCallId, text, false, tools)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { sendCloudDeviceResult(runId, toolCallId, error.message ?: "开启失败", true) }
        }
    }

    /**
     * 停在“等设备执行”的申请：
     * - 补充 Skill 密钥：与本机同一张密钥卡，密钥只存手机，然后交给云端与本机相同的工具结果；
     * - 服务器受保护变量、其他能力：在这条任务的本地记录上建同一个接力（Broker），
     *   用户在接力卡里完成后由本机 Adapter 履行（写服务器 .env 等），结果交回云端而不是本机续跑。
     */
    private suspend fun requestCloudSecret(runId: String, call: AgentContentBlock.ToolCall, sessionId: String, key: String) {
        fun fail(message: String) = sendCloudDeviceResult(runId, call.id, message, true) { cloudDeviceHandled.remove(key) }
        try {
            val skillId = (call.arguments["skill_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val allowedSkills = setOfNotNull(skillId.takeIf { it.isNotEmpty() && skillRepository.get(it) != null })
            val request = agentPauseRequest(call, allowedSkills) ?: return fail("申请参数无效")
            if (request is AgentPauseRequest.SkillSecret) {
                _cloudSkillSecrets.update { it + (runId to PendingSkillSecretApproval(runId, "cloud:secret:${call.id}", sessionId,
                    request.skillId, skillRepository.get(request.skillId)?.name ?: request.skillId, request.name, request.reason)) }
                return
            }
            val context = cloudRunContext(runId)?.copy(runId = runId)
            val target = if (request is AgentPauseRequest.ProtectedSecret) {
                val targets = context?.targetContexts().orEmpty()
                targets.singleOrNull { request.targetId == null || request.targetId == it.computerId || request.targetId == it.workspaceId }
                    ?: return fail(if (targets.isEmpty()) "当前运行没有可用的服务器 Workspace 上下文，无法安全写入 Secret；请先选择服务器和 Workspace。"
                        else "Secret 目标不匹配。允许的 target_id：${targets.joinToString { "computer_id=${it.computerId} 或 workspace_id=${it.workspaceId}" }}；请明确指定其中一个。")
            } else context
            val capabilityRequest = when (request) {
                is AgentPauseRequest.Capability -> request.request
                is AgentPauseRequest.ProtectedSecret -> CapabilityRequest("server.env.secret", request.reason, "目标服务器的受保护环境变量",
                    mapOf("scope" to request.scope.name, "name" to request.name, "path" to request.path.orEmpty(), "target_id" to request.targetId.orEmpty()))
                else -> return fail("申请参数无效")
            }
            val run = agentDao.getRun(runId) ?: return
            interventionBroker.suspend(run, capabilityRequest, turnId = call.id, requestId = call.id, toolCallId = call.id,
                executionSlot = call.id, requestHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("${call.name}|${call.arguments}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
                requestSource = "MODEL_HINT", bindingGeneration = 0, executionGeneration = 0,
                targetBindingRef = target?.let { "computer:${it.computerId}:workspace:${it.workspaceId}" } ?: "current-run-resource")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { fail(error.message ?: "申请参数无效") }
    }

    private val _cloudSkillSecrets = MutableStateFlow<Map<String, PendingSkillSecretApproval>>(emptyMap())
    val cloudSkillSecretApprovals: StateFlow<List<PendingSkillSecretApproval>> =
        _cloudSkillSecrets.map { it.values.toList() }.stateIn(liveScope, SharingStarted.Eagerly, emptyList())
    private val cloudSkillSecretStore by lazy { com.android.everytalk.data.skill.SkillSecretStore(appContext) }

    /** 云端任务的 Skill 密钥卡：保存方式与本机一致，交回云端的只有“已授权”，不含密钥正文。 */
    internal fun respondCloudSecret(runId: String, approvalRequestId: String, value: CharArray?, remember: Boolean) {
        val current = _cloudSkillSecrets.value[runId]?.takeIf { it.approvalRequestId == approvalRequestId }
            ?: run { value?.fill('\u0000'); return }
        _cloudSkillSecrets.update { it - runId }
        val toolCallId = approvalRequestId.removePrefix("cloud:secret:")
        scope.launch {
            if (value == null || value.isEmpty()) {
                value?.fill('\u0000')
                return@launch saveAndSendCloudDeviceResult(runId, toolCallId, "用户拒绝了本次操作", true)
            }
            try {
                if (remember) cloudSkillSecretStore.save(current.skillId, current.name, value)
                com.android.everytalk.data.skill.SkillSecretSessionStore.put(runId, current.name, value)
            } finally { value.fill('\u0000') }
            saveAndSendCloudDeviceResult(runId, toolCallId, kotlinx.serialization.json.buildJsonObject {
                put("available", kotlinx.serialization.json.JsonPrimitive(true))
                put("name", kotlinx.serialization.json.JsonPrimitive(current.name))
                put("message", kotlinx.serialization.json.JsonPrimitive("用户已完成授权；正文不会返回，后续只能由已注册的语义 capability Adapter 使用"))
            }.toString(), false)
        }
    }

    /** 先落盘再发：送达失败或重启后，同一等待点直接重发这份结果，不重复要用户输入。 */
    private fun saveAndSendCloudDeviceResult(runId: String, toolCallId: String, text: String, isError: Boolean) {
        cloudRunContexts.edit().putString(deviceResultKey(runId, toolCallId), (if (isError) "1" else "0") + text).commit()
        sendCloudDeviceResult(runId, toolCallId, text, isError) { cloudDeviceHandled.removeIf { it.startsWith("$runId:$toolCallId:") } }
    }

    private fun deviceResultKey(runId: String, toolCallId: String) = "device_result:$runId:$toolCallId"

    /**
     * 云端停在“等设备执行”：本机按同一审批策略判断，需要确认就出确认卡，否则直接执行并回传。
     * 批准记录先落盘再执行，进程被杀后重开不会重复弹卡，也不会绕过已作出的决定。
     */
    private fun handleCloudDeviceTool(view: CloudRunView, waiting: CloudRunWaiting, args: kotlinx.serialization.json.JsonObject, sessionId: String) {
        val key = "${view.id}:${waiting.toolCallId}:${view.generation}"
        if (!cloudDeviceHandled.add(key)) return
        // 开启 MCP/Agent：与本机同一张确认卡，批准后在 respondCloudEnable 里准备新工具。
        if (waiting.toolName == AgentControlToolNames.REQUEST_MCP || waiting.toolName == AgentControlToolNames.REQUEST_AGENT) {
            val id = "cloud:enable:${waiting.toolCallId}"
            val reason = (args["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (waiting.toolName == AgentControlToolNames.REQUEST_MCP) _cloudMcpEnables.update { it + (view.id to PendingMcpEnableApproval(view.id, id, sessionId, reason)) }
            else _cloudAgentEnables.update { it + (view.id to PendingAgentEnableApproval(view.id, id, sessionId, reason,
                (args["required_skill_ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())) }
            return
        }
        // 已在本机完成但没送达云端的结果（密钥卡提交后断网、重启）：直接重发，不再弹卡。
        cloudRunContexts.getString(deviceResultKey(view.id, waiting.toolCallId), null)?.let { saved ->
            sendCloudDeviceResult(view.id, waiting.toolCallId, saved.drop(1), saved.startsWith("1")) { cloudDeviceHandled.remove(key) }
            return
        }
        if (waiting.toolName in setOf(AgentControlToolNames.REQUEST_SKILL_SECRET, AgentControlToolNames.REQUEST_PROTECTED_SECRET,
                AgentControlToolNames.REQUEST_CAPABILITY)) {
            scope.launch { requestCloudSecret(view.id, AgentContentBlock.ToolCall(waiting.toolCallId, waiting.toolName, args), sessionId, key) }
            return
        }
        scope.launch {
            val call = AgentContentBlock.ToolCall(waiting.toolCallId, waiting.toolName, args)
            val (text, isError) = try {
                val approved = cloudRunContexts.getString(approvedToolKey(view.id, call.id), null)?.let {
                    cloudAgentJson.decodeFromString(com.android.everytalk.data.computer.ComputerToolApprovalRequest.serializer(), it)
                }
                val approval = approved ?: if (call.name == com.android.everytalk.data.memory.MemoryTool.NAME) null
                    else cloudToolRuntime.approvalRequest(call, cloudRunContext(view.id)?.copy(runId = view.id))
                if (approval != null && approved == null) {
                    _cloudToolApprovals.update { it + (view.id to com.android.everytalk.data.computer.PendingComputerToolApproval(
                        view.id, "cloud:device:${call.id}", approval)) }
                    return@launch
                }
                executeCloudDeviceTool(view.id, call, approval)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { (error.message ?: "工具执行失败") to true }
            sendCloudDeviceResult(view.id, call.id, text, isError) { cloudDeviceHandled.remove(key) }
        }
    }

    /** 云端自带执行器的工具（服务器 exec）停在审批或结果未知：本机按同一策略判断，不需要确认就直接放行。 */
    private fun handleCloudToolApproval(view: CloudRunView, waiting: CloudRunWaiting, args: kotlinx.serialization.json.JsonObject) {
        val key = "${view.id}:${waiting.toolCallId}:${view.generation}:${waiting.kind}"
        if (!cloudDeviceHandled.add(key)) return
        scope.launch {
            val context = cloudRunContext(view.id)?.copy(runId = view.id)
            val call = AgentContentBlock.ToolCall(waiting.toolCallId, waiting.toolName, args)
            val request = try {
                if (waiting.kind == "unknown") com.android.everytalk.data.computer.ComputerToolApprovalRequest.UnknownExecution(
                    toolCallId = call.id, context = context ?: ComputerRequestContext("", "", ""),
                    computerName = (args["computer_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "云端任务",
                    toolName = call.name, detail = (args["command"] ?: args["path"] ?: args).toString().take(500),
                    isWriteOperation = call.name != com.android.everytalk.data.computer.ComputerToolNames.READ_FILE,
                ) else cloudToolRuntime.approvalRequest(call, context)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // 本机核对不了（服务器记录被删、参数解析失败）时不能当作免确认放行，改由用户确认。
                com.android.everytalk.data.computer.ComputerToolApprovalRequest.HostCommand(call.id,
                    com.android.everytalk.data.computer.ComputerHostCommandConfirmationRequest(
                        requestId = call.id, context = context ?: ComputerRequestContext("", "", ""), computerName = "云端任务",
                        command = (args["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: args.toString().take(500),
                        cwd = (args["cwd"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "~",
                        requestsPrivilege = false, reason = "本机无法核对这条命令的风险，请确认后再执行", risks = emptySet()))
            }
            if (request == null) {
                // 本机策略判定不需要确认。
                if (waiting.kind == "approval") launchCloudCommand(view.id, "approval", "确认结果未送达，请重试", kotlinx.serialization.json.buildJsonObject {
                    put("toolCallId", kotlinx.serialization.json.JsonPrimitive(call.id))
                    put("approved", kotlinx.serialization.json.JsonPrimitive(true))
                }) { cloudDeviceHandled.remove(key) }
                return@launch
            }
            _cloudToolApprovals.update { it + (view.id to com.android.everytalk.data.computer.PendingComputerToolApproval(
                view.id, "cloud:${if (waiting.kind == "unknown") "unknown:" else ""}${call.id}", request)) }
        }
    }

    private fun cloudRunContext(runId: String): ComputerRequestContext? = cloudRunContexts.getString(runId, null)?.let {
        runCatching { cloudAgentJson.decodeFromString(ComputerRequestContext.serializer(), it) }.getOrNull()
    }

    /**
     * 把云端的等待点接到现有卡片：提问 → 提问卡；保存文件 → 确认卡，确认后由本机写入并回传结果。
     * 每次快照都重算，等待点消失或任务结束时卡片随之撤掉。
     */
    private fun projectCloudWaiting(view: CloudRunView, sessionId: String) {
        val waiting = view.snapshot.waiting?.takeUnless { view.terminal }
        // 批准记录要留到任务结束：写入成功但结果未送达时重新执行仍需它（写入对相同内容幂等）。
        if (view.terminal) {
            val prefixes = listOf("approved:${view.id}:", "approved_tool:${view.id}:", "device_result:${view.id}:")
            cloudRunContexts.edit().apply { cloudRunContexts.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(::remove) }
                .remove(view.id).remove(ownerKey(view.id)).remove(memoryKey(view.id)).apply()
            memoryCoordinator.bind(view.id, null)
            // 指向这一轮的调整标记：对应排队消息已经出队的才清，仍在队列里的留给派发前核对。
            val steerKeys = cloudRunContexts.all.filter { (key, value) -> key.startsWith("steer_unknown:") && value == view.id }.keys
            if (steerKeys.isNotEmpty()) scope.launch {
                val done = steerKeys.filter { database.chatDao().countPendingMessage(it.removePrefix("steer_unknown:")) == 0 }
                if (done.isNotEmpty()) cloudRunContexts.edit().apply { done.forEach(::remove) }.apply()
            }
        }
        cloudInterventions.update { it - view.id }
        // 同一个等待点重复投影（重连、对账）时保留已弹出的确认卡；换了等待点或任务结束才撤掉。
        _cloudToolApprovals.update { current ->
            current[view.id]?.takeIf { waiting == null || !it.approvalRequestId.endsWith(":${waiting.toolCallId}") }?.let { current - view.id } ?: current
        }
        val enableId = waiting?.let { "cloud:enable:${it.toolCallId}" }
        _cloudMcpEnables.update { current -> if (current[view.id]?.approvalRequestId == enableId) current else current - view.id }
        _cloudAgentEnables.update { current -> if (current[view.id]?.approvalRequestId == enableId) current else current - view.id }
        _cloudSkillSecrets.update { current -> if (current[view.id]?.approvalRequestId == waiting?.let { "cloud:secret:${it.toolCallId}" }) current else current - view.id }
        // 云端任务的本地接力卡（受保护变量、能力申请）：云端不再等它时撤掉。
        if (waiting == null) _pendingInterventions.update { items -> items.filterNot { it.runId == view.id } }
        if (waiting == null) return
        val args = waiting.arguments as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
        fun text(name: String) = (args[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        when {
            waiting.kind == "question" -> cloudInterventions.update { it + (view.id to PendingIntervention(
                suspensionId = "cloud:${view.id}:${waiting.toolCallId}", runId = view.id, sessionId = sessionId,
                capabilityId = "user.question", reasonSafe = text("question").orEmpty().take(500),
                userVisibleContext = text("context")?.takeIf(String::isNotBlank)?.take(500),
                materialKind = ResolutionMaterialKind.NONE, fields = emptyList(),
                requestSource = InterventionRequestSource.MODEL_HINT, rowVersion = view.generation,
                state = SuspensionState.WAITING_USER, resolutionNonce = "cloud",
            )) }
            waiting.toolName == com.android.everytalk.data.computer.ComputerToolNames.LOCAL_FILE_SAVE -> {
                val context = cloudRunContext(view.id)?.copy(runId = view.id) ?: return
                if (waiting.kind == "approval") {
                    scope.launch {
                        val request = try { cloudFileSave.approval(args, waiting.toolCallId, context) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        // 参数本身无效（路径越界、内容过大）：直接拒绝，模型拿到错误另想办法。
                        if (request == null) {
                            launchCloudCommand(view.id, "approval", "保存请求无效，已拒绝", kotlinx.serialization.json.buildJsonObject {
                                put("toolCallId", kotlinx.serialization.json.JsonPrimitive(waiting.toolCallId))
                                put("approved", kotlinx.serialization.json.JsonPrimitive(false))
                            })
                            return@launch
                        }
                        _cloudToolApprovals.update { it + (view.id to com.android.everytalk.data.computer.PendingComputerToolApproval(
                            view.id, "cloud:${waiting.toolCallId}", request)) }
                    }
                } else if (waiting.kind == "device" && cloudDeviceHandled.add("${view.id}:${waiting.toolCallId}:${view.generation}")) {
                    scope.launch {
                        val (result, isError) = try {
                            // 只按本机批准时冻结的版本写入；没有本机批准记录（别的设备批准、数据被清）就报错，
                            // 绝不在收到等待设备时重新生成审批，避免绕过内容与原文件版本校验。
                            val approved = cloudApprovedWrite(view.id, waiting.toolCallId)
                                ?: error("这次保存没有在本机确认，请在发起任务的手机上确认后重试")
                            val saved = cloudFileSave.execute(args, waiting.toolCallId, approved.context.copy(
                                runId = view.id, approvedToolCallId = waiting.toolCallId, approvedLocalFileWrite = approved))
                            kotlinx.serialization.json.buildJsonObject {
                                put("ok", kotlinx.serialization.json.JsonPrimitive(true))
                                put("persisted", kotlinx.serialization.json.JsonPrimitive(true))
                                put("path", kotlinx.serialization.json.JsonPrimitive("Workspace/${saved.path}"))
                                put("bytes", kotlinx.serialization.json.JsonPrimitive(saved.bytes))
                                put("sha256", kotlinx.serialization.json.JsonPrimitive(saved.sha256))
                            } to false
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) {
                            kotlinx.serialization.json.buildJsonObject {
                                put("ok", kotlinx.serialization.json.JsonPrimitive(false))
                                put("error", kotlinx.serialization.json.JsonPrimitive(error.message ?: "保存失败"))
                            } to true
                        }
                        launchCloudCommand(view.id, "device-result", "保存结果回传失败，请重试", kotlinx.serialization.json.buildJsonObject {
                            put("toolCallId", kotlinx.serialization.json.JsonPrimitive(waiting.toolCallId))
                            put("result", result)
                            put("isError", kotlinx.serialization.json.JsonPrimitive(isError))
                        }) { cloudDeviceHandled.remove("${view.id}:${waiting.toolCallId}:${view.generation}") }
                    }
                }
            }
            !ownsCloudRun(view.id) -> Unit
            waiting.kind == "device" -> handleCloudDeviceTool(view, waiting, args, sessionId)
            waiting.kind == "approval" || waiting.kind == "unknown" -> handleCloudToolApproval(view, waiting, args)
        }
    }

    private fun approvedWriteKey(runId: String, toolCallId: String) = "approved:$runId:$toolCallId"
    private fun unknownSteerKey(steeringId: String) = "steer_unknown:$steeringId"

    private fun cloudApprovedWrite(runId: String, toolCallId: String): com.android.everytalk.data.computer.ComputerToolApprovalRequest.LocalFileWrite? =
        cloudRunContexts.getString(approvedWriteKey(runId, toolCallId), null)?.let {
            runCatching { cloudAgentJson.decodeFromString(com.android.everytalk.data.computer.ComputerToolApprovalRequest.LocalFileWrite.serializer(), it) }.getOrNull()
        }

    /** 文件写入确认卡对云端任务的决定；批准时先在本机冻结这份审批，设备执行只认它。 */
    internal fun respondCloudApproval(runId: String, approvalRequestId: String, approved: Boolean) {
        if (approvalRequestId.startsWith("cloud:device:")) {
            val toolCallId = approvalRequestId.removePrefix("cloud:device:")
            val pending = _cloudToolApprovals.value.values.firstOrNull { it.approvalRequestId == approvalRequestId }?.request
            _cloudToolApprovals.update { current -> current.filterValues { it.approvalRequestId != approvalRequestId } }
            scope.launch {
                val (text, isError) = if (!approved || pending == null) "用户拒绝执行本次工具" to true else try {
                    cloudRunContexts.edit().putString(approvedToolKey(runId, toolCallId), cloudAgentJson.encodeToString(
                        com.android.everytalk.data.computer.ComputerToolApprovalRequest.serializer(), pending)).commit()
                    val waiting = CloudAgentStore(database).read(runId)?.snapshot?.waiting?.takeIf { it.toolCallId == toolCallId }
                        ?: error("等待点已变化，请重试")
                    executeCloudDeviceTool(runId, AgentContentBlock.ToolCall(toolCallId, waiting.toolName,
                        waiting.arguments as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())), pending)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { (error.message ?: "工具执行失败") to true }
                sendCloudDeviceResult(runId, toolCallId, text, isError)
            }
            return
        }
        if (approvalRequestId.startsWith("cloud:unknown:")) {
            _cloudToolApprovals.update { current -> current.filterValues { it.approvalRequestId != approvalRequestId } }
            launchCloudCommand(runId, "approval", "确认结果未送达，请重试", kotlinx.serialization.json.buildJsonObject {
                put("toolCallId", kotlinx.serialization.json.JsonPrimitive(approvalRequestId.removePrefix("cloud:unknown:")))
                put("approved", kotlinx.serialization.json.JsonPrimitive(approved))
                if (approved) put("retryUnknown", kotlinx.serialization.json.JsonPrimitive(true))
            })
            return
        }
        val request = _cloudToolApprovals.value.values.firstOrNull { it.approvalRequestId == approvalRequestId }?.request
            as? com.android.everytalk.data.computer.ComputerToolApprovalRequest.LocalFileWrite
        if (approved && request != null) cloudRunContexts.edit().putString(approvedWriteKey(runId, request.toolCallId),
            cloudAgentJson.encodeToString(com.android.everytalk.data.computer.ComputerToolApprovalRequest.LocalFileWrite.serializer(), request)).commit()
        _cloudToolApprovals.update { current -> current.filterValues { it.approvalRequestId != approvalRequestId } }
        launchCloudCommand(runId, "approval", "确认结果未送达，请重试", kotlinx.serialization.json.buildJsonObject {
            put("toolCallId", kotlinx.serialization.json.JsonPrimitive(approvalRequestId.removePrefix("cloud:")))
            put("approved", kotlinx.serialization.json.JsonPrimitive(approved))
        })
    }

    private val agentLoop by lazy {
        AgentLoop(
            runStore = agentRunStore,
            toolRuntime = AgentToolRuntime(
                executorProvider = AgentToolExecutorRegistry::current,
                resultStore = agentToolResultStore,
                skillRuntimeTools = skillRuntimeTools,
            ),
            computerSessionStateProvider = computerSessionStateProvider,
            pauseController = pauseController,
            interventionBroker = interventionBroker,
            apiKeyProvider = { run, request ->
                run.configIdSnapshot
                    ?.let { configId -> database.apiConfigDao().getTextConfig(configId)?.key }
                    ?.takeIf(String::isNotBlank)
                    ?: request.apiKey
            },
            lifecycleSink = { event -> _lifecycleEvents.emit(event) },
            memoryCoordinator = memoryCoordinator,
            modelTransport = modelTransport,
            codemodeEngine = SandboxCodemodeEngine(context),
        )
    }

    /** UI 只读投影；真实状态由应用级 pauseController 持有。 */
    private val cloudStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    private val cloudPauseRequested = MutableStateFlow<Set<String>>(emptySet())

    /** 本机 AgentLoop 与云端任务共用一份运行控制状态，输入框和队列按同一规则显示暂停/继续。 */
    val runControlSnapshots: StateFlow<Map<String, AgentRunControlSnapshot>> = combine(
        pauseController.snapshots, _cloudRuns, cloudStatuses, cloudPauseRequested,
    ) { local, cloud, statuses, pausing ->
        cloud.values.filter { it.running }.associate { live ->
            live.message.id to AgentRunControlSnapshot(live.runId, live.message.id, when {
                statuses[live.runId] == "PAUSED" -> AgentRunControlState.PAUSED
                live.runId in pausing -> AgentRunControlState.PAUSE_REQUESTED
                else -> AgentRunControlState.RUNNING
            })
        } + local
    }.stateIn(liveScope, SharingStarted.Eagerly, emptyMap())

    /**
     * 本机 Run 已是终态（含归档交接改成的 SYNC_HISTORY_ONLY）但界面仍显示运行中：撤掉运行态和控制，
     * 页面改显示已恢复的正式消息。不能只依赖最后一帧 SSE，它可能晚于归档到达或被拒绝投影。
     */
    private suspend fun reconcileCloudOverlays() {
        val finished = _cloudRuns.value.values.filter { it.running }.mapNotNull { live ->
            val run = agentDao.getRun(live.runId)
            live.runId.takeIf { run == null || run.status in setOf(AgentRunStatus.COMPLETED.name, AgentRunStatus.FAILED.name, AgentRunStatus.CANCELLED.name) }
        }.toSet()
        if (finished.isEmpty()) return
        _cloudRuns.update { current -> current.filterValues { it.runId !in finished } }
        cloudPauseRequested.update { it - finished }
        cloudInterventions.update { it - finished }
        _cloudToolApprovals.update { it - finished }
        _cloudMcpEnables.update { it - finished }
        _cloudAgentEnables.update { it - finished }
        _cloudSkillSecrets.update { it - finished }
    }

    private fun cloudRunIdFor(visibleAssistantMessageId: String): String? =
        _cloudRuns.value.values.firstOrNull { it.running && it.message.id == visibleAssistantMessageId }?.runId

    /** 控制命令失败只更新提示，不改动后端任务事实。 */
    private fun launchCloudCommand(runId: String, type: String, failure: String,
        fields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), onFailure: () -> Unit = {}) {
        scope.launch {
            try { check(commandCloudRun(runId, type, fields)) { "云端尚未连接" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                onFailure()
                _cloudRuns.update { current -> current.mapValues { (_, value) ->
                    if (value.runId == runId && value.running) value.copy(message = value.message.copy(executionStatus = failure)) else value
                } }
            }
        }
    }

    fun requestPause(visibleAssistantMessageId: String): Boolean {
        val runId = cloudRunIdFor(visibleAssistantMessageId) ?: return pauseController.requestPause(visibleAssistantMessageId)
        cloudPauseRequested.update { it + runId }
        launchCloudCommand(runId, "pause", "暂停请求未确认，请重试") { cloudPauseRequested.update { it - runId } }
        return true
    }

    fun resumePausedRun(visibleAssistantMessageId: String): Boolean {
        val runId = cloudRunIdFor(visibleAssistantMessageId) ?: return pauseController.resume(visibleAssistantMessageId)
        cloudPauseRequested.update { it - runId }
        launchCloudCommand(runId, "resume", "继续请求未确认，请重试")
        return true
    }

    /**
     * 首次启动时 Run 还没创建，只能按可见消息 ID 登记 Job；恢复启动才按 Run ID 登记。
     * 两种登记都代表同一个 Run 正在执行，恢复器必须同时检查，避免重复驱动同一轮请求。
     */
    fun isRunActive(run: AgentRunEntity): Boolean = isAgentRunActive(activeJobs, run)

    /**
     * 手动「立即压缩」：不看阈值，直接给当前会话压出一条新的摘要检查点。
     * 压缩必须挂在一次真实 Run 上，而建 Run 会取消同会话里等待审批的其他 Run，
     * 所以有活跃 Run 时直接拒绝，等它停下来再压。
     */
    suspend fun compressContextNow(
        visibleAssistantMessageId: String,
        fallbackSessionId: String,
        fallbackConfigId: String?,
    ): ManualCompactionOutcome {
        // 别的设备同步来的、云端执行的、或旧版本留下的消息在本机没有 Run，
        // 压缩只需要会话和模型配置，不能因此拒绝。
        val run = agentRunStore.getRunByVisibleMessage(visibleAssistantMessageId)
        val sessionId = run?.sessionId ?: fallbackSessionId.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("当前消息没有可压缩的会话记录")
        if (agentRunStore.getRunsForSession(sessionId).any(::isRunActive)) {
            throw IllegalStateException("会话正在执行，结束后再压缩")
        }
        val configId = run?.configIdSnapshot ?: fallbackConfigId
            ?: throw IllegalStateException("会话缺少模型配置，无法压缩")
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig()
            ?: throw IllegalStateException("模型配置已不存在，无法压缩")
        val limits = resolvedModelTokenLimits(
            maxOutputTokens = config.maxTokens,
            maxContextTokens = config.modelParameters.maxContextTokens,
        )
        // Run 一进终态，恢复快照就被回收（AGENT_FINAL_RUN_STATUSES），restoreChatRequest 拿不到历史。
        // 这里从还活着的两张表重建：会话消息表给原始对话，AgentEntry 给工具轨迹。
        // 附件编码必须和发送管线一致（真实 Base64 + Context 解析 MIME），
        // 否则 PartsApiMessage.parts 内容对不上，压缩检查点的指纹校验会静默失败。
        val history = database.chatDao().getMessagesForSession(sessionId)
            .map { it.toMessage().toApiMessage { uri -> encodeImageForModel(appContext, uri) } }
        val request = buildManualCompactionRequest(
            config = config,
            limits = limits,
            messages = agentRunStore.expandTranscript(sessionId, history),
        )
        return agentLoop.compactNow(sessionId, request, limits)
    }

    /**
     * 只做同步确认和冻结提交；模型、工具及后续轮次全部由云端执行。
     * 写下云端 Run 之前的任何失败都抛 [CloudAgentIneligible]，原因报给用户，不改走本机；写下之后只认云端。
     */
    private suspend fun submitCloudRequest(input: AgentLoopRequest, accountId: String, status: suspend (String) -> Unit) {
        status("正在连接云端")
        val auth = com.android.everytalk.data.auth.AuthRuntime.get(appContext)
        val state = com.android.everytalk.data.sync.SyncStateStore(database)
        suspend fun permitted() = cloudPermitted(accountId) &&
            !visibleRunCancellationReasons.containsKey(input.visibleAssistantMessageId)
        val previous = agentDao.getRunByVisibleMessage(input.visibleAssistantMessageId)
        if (previous != null) {
            check(previous.executionOwner == "CLOUD" && previous.cloudAccountId == accountId) { "消息已有其他执行者" }
            val coordinator = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                cloudReady.first { it?.first == accountId }?.second
            } ?: error("云端连接尚未就绪，请稍后重试")
            coordinator.retry(previous.id)
            return
        }
        if (!com.android.everytalk.data.sync.syncServerConfigured(com.android.everytalk.BuildConfig.EVERYTALK_SYNC_BASE_URL))
            throw CloudAgentIneligible("这个安装包没有配置后端地址，无法执行云端任务")
        // 云端清空后暂停、换号待选数据时同步被挂起：要用户先处理提示，不能悄悄改在手机上跑。
        if (!state.isEnabled()) throw CloudAgentIneligible("账号数据同步已暂停，请先处理同步提示后重发")
        // 刚启动或刚重连时云端连接还没就绪：等它连上，不因为早点了一下发送就改在手机上跑。
        val coordinator = kotlinx.coroutines.withTimeoutOrNull(30_000) { cloudReady.first { it?.first == accountId }?.second }
            ?: throw CloudAgentIneligible("云端暂时连不上，请稍后重发")
        if (!coordinator.serverSupported) throw CloudAgentIneligible("后端不支持云端任务，请检查后端版本")
        if (!permitted()) throw CloudAgentIneligible("账号登录状态已变化，请重新登录后重发")
        try {
            check(state.prepareAutomaticAccount(accountId, auth.binding) {
                (auth.auth.state.value as? com.android.everytalk.data.auth.AuthState.SignedIn)?.user?.id == accountId
            }) { "账号数据选择未完成" }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { throw CloudAgentIneligible(error.message.orEmpty(), error) }
        val secrets = com.android.everytalk.data.sync.SyncSecretManager(appContext)
        val api = com.android.everytalk.data.sync.KtorSyncApi(com.android.everytalk.BuildConfig.EVERYTALK_SYNC_BASE_URL,
            auth.account, isLocalOnly = { auth.auth.state.value !is com.android.everytalk.data.auth.AuthState.SignedIn },
            canRequest = { permitted() }, readSecretsEpoch = { state.readSecretsEpoch().coerceAtLeast(0) })
        try {
            status("正在核对云端同步")
            val credentials = com.android.everytalk.data.sync.SyncCredentialSynchronizer(appContext, database, api, secrets, accountId)
            val epochs = try {
                synchronizeCloudAgentParents(database, api,
                    com.android.everytalk.data.sync.RoomSyncStore(database, secrets, accountId), state,
                    input.sessionId, input.userMessageId, ::permitted,
                    beforePull = { credentials.captureLocal() }, afterPull = { credentials.recover() },
                    onSecretsEpochChanged = { secrets.clearCachedDek() })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                // 会话设为仅本机：本来就不上云。其余（断网、服务重启）是暂时的，报错让用户重发。
                throw CloudAgentIneligible(error.message.orEmpty(), error)
            }
            status("正在准备上下文与工具")
            prepareCloudAgentSubmission(appContext, database, agentRunStore, skillRepository, memoryCoordinator,
                input, accountId, epochs, computerSessionStateProvider(input.request.localComputerRequestContext), ::permitted,
                compact = { sessionId, request, limits, plan ->
                    status("正在压缩上下文")
                    agentLoop.compactForSubmission(sessionId, request, limits, plan)
                },
                beforePersist = { run, frozen ->
                    val bytes = frozen.toString().toByteArray(Charsets.UTF_8)
                    try { interventionCredentialStore.saveCloudAgentInput(accountId, run.id, bytes) }
                    finally { bytes.fill(0) }
                }) { run, frozen, request ->
                check(permitted()) { "云端任务账号授权已变更" }
                // 提交后立刻交接到与其它设备相同的 overlay；不等待首个模型事件才能显示停止按钮。
                val chat = database.chatDao()
                val session = checkNotNull(chat.getSession(run.sessionId))
                val assistant = checkNotNull(chat.getMessage(run.sessionId, run.visibleAssistantMessageId))
                saveCloudRunContext(run.id, request.localComputerRequestContext, request.localMemorySnapshot)
                _cloudRuns.update { it + (session.uuid to com.android.everytalk.data.sync.LiveRemoteRun(
                    assistant.uuid, assistant.toMessage().copy(executionStatus = "正在提交云端任务"), true, run.id)) }
                coordinator.submit(run, frozen)
            }
        } finally { api.close() }
    }

    /**
     * 云任务只提交和订阅，本机任务才启动 AgentLoop。页面退订不取消任何云端执行。
     */
    fun run(request: AgentLoopRequest): Flow<AppStreamEvent> = callbackFlow {
        val persisted = request.existingRun ?: agentDao.getRunByVisibleMessage(request.visibleAssistantMessageId)
        val accountId = if (databaseOverride == null) {
            val auth = com.android.everytalk.data.auth.AuthRuntime.get(appContext)
            auth.auth.startup.first { it.initialized }
            (auth.auth.state.value as? com.android.everytalk.data.auth.AuthState.SignedIn)?.user?.id
        } else null
        // 旧 Run 维持原执行者；新请求登录后先尝试云端（含分支对话），提交前发现云端做不了就留在本机。
        val cloudOwned = persisted?.executionOwner?.let { it == "CLOUD" } ?: (accountId != null)
        val jobKey = request.existingRun?.id?.let { "run:$it" }
            ?: "message:${request.visibleAssistantMessageId}"
        val uiAttached = AtomicBoolean(true)
        // 页面审批和服务恢复共用登记锁；已接管的 Run 继续通过应用级事件流投影，不能再次启动。
        val job = resumeMutex.withLock {
            if (request.existingRun?.let(::isRunActive) == true) return@withLock null
            scope.launchTrackedAgentJob(activeJobs, jobKey, pauseController, request.visibleAssistantMessageId) {
            var foregroundActivity: java.io.Closeable? = null
            try {
                if (cloudOwned) {
                    val owner = checkNotNull(accountId) { "请登录原账号以恢复云端任务" }
                    // 登录即云端执行，提交不了就把原因报给用户，绝不悄悄改在手机上跑（否则看不出后端出了问题）。
                    submitCloudRequest(request, owner) { trySend(AppStreamEvent.ExecutionStatusUpdate(it)) }
                    return@launchTrackedAgentJob
                }
                request.existingRun?.let { snapshot ->
                    if (!canExecuteAgentSnapshot(snapshot, agentDao.getRun(snapshot.id))) return@launchTrackedAgentJob
                }
                foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
                AgentRecoveryDiagnostics.runtime("started", request.visibleAssistantMessageId)
                agentLoop.run(request).collect { event ->
                    if (!uiAttached.get()) {
                        _events.emit(request.visibleAssistantMessageId to event)
                    } else {
                        try {
                            // 同一次运行只走前台通道，并通过挂起发送保持严格顺序。
                            // 禁止在通道繁忙时把单个事件改送全局通道，否则 Finish 可能越过正文 delta。
                            send(event)
                        } catch (error: CancellationException) {
                            // 下游页面取消收集也会关闭 callbackFlow。Agent 自身仍活跃时转交应用级通道；
                            // 用户真正取消 Agent 时必须继续抛出，不能把已取消任务偷偷恢复。
                            if (!currentCoroutineContext().isActive) throw error
                            _events.emit(request.visibleAssistantMessageId to event)
                        } catch (_: ClosedSendChannelException) {
                            // 页面恰好在发送期间离开时，后续事件交给应用级收集器继续处理。
                            _events.emit(request.visibleAssistantMessageId to event)
                        }
                    }
                }
                val completedRun = agentDao.getRunByVisibleMessage(request.visibleAssistantMessageId)
                updateResumeRetryAfterRun(completedRun)
                notifyTerminalRun(completedRun)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 包括前台服务启动失败。错误必须传回 UI，不能从应用级 scope 逃逸成崩溃。
                android.util.Log.e("AgentRuntime", "run_failed type=${error.javaClass.simpleName}")
                if (uiAttached.get()) {
                    close(error)
                } else {
                    _events.emit(request.visibleAssistantMessageId to AppStreamEvent.Error("任务执行中断，请核对任务状态"))
                    _events.emit(request.visibleAssistantMessageId to AppStreamEvent.Finish("agent_failed"))
                }
            } finally {
                runCatching { foregroundActivity?.close() }.onFailure {
                    android.util.Log.e("AgentRuntime", "foreground_release_failed type=${it.javaClass.simpleName}")
                }
                visibleRunCancellationReasons[request.visibleAssistantMessageId]?.let { reason ->
                    withContext(NonCancellable) {
                        agentRunStore.cancelActiveRunByVisibleMessage(
                            request.visibleAssistantMessageId,
                            reason,
                        )
                        visibleRunCancellationReasons.remove(request.visibleAssistantMessageId, reason)
                    }
                }
                AgentRecoveryDiagnostics.runtime("ended", request.visibleAssistantMessageId)
                close()
            }
            }
        }
        if (job == null) {
            close()
            return@callbackFlow
        }
        // 即使 scope 已取消、Job 尚未进入函数体，也必须关闭 UI 通道，避免永久挂起。
        job.invokeOnCompletion { error -> close(error) }
        visibleRunCancellationReasons[request.visibleAssistantMessageId]?.let { reason ->
            job.cancel(CancellationException(reason))
        }
        job.start()
        // UI Collector 消失时不取消 job；前台服务和 Room 继续持有任务事实。
        awaitClose { uiAttached.set(false) }
    }

    suspend fun resume(runId: String): Boolean {
        val run = agentDao.getRun(runId) ?: return false
        return resumeRun(run)
    }

    suspend fun resumeRun(
        run: AgentRunEntity,
        approvalDecision: AgentApprovalRecord? = null,
    ): Boolean {
        if (!canAttemptResume(run.id) || !recoveringRunIds.add(run.id)) return false
        return try {
            resumeMutex.withLock { resumeRunLocked(run, approvalDecision) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            recordResumeFailure(run.id)
            AppLogger.warn("AgentRunCoordinator", "Resume failed for run ${run.id}: ${exception.message}")
            false
        } finally {
            recoveringRunIds.remove(run.id)
        }
    }

    /**
     * 正式 steering API。
     * 模型正在输出时切断当前模型请求并恢复同一个 Run；工具执行时不取消工具，
     * 由 AgentLoop 在工具结果落库后的安全边界消费方向。
     */
    suspend fun steer(
        sessionId: String,
        steeringId: String,
        content: String,
        contentParts: List<MessageContentPart> = emptyList(),
        attachments: List<SelectedMediaItem> = emptyList(),
    ): Boolean {
        if ((content.isBlank() && contentParts.isEmpty() && attachments.isEmpty()) || steeringId.isBlank()) return false
        val messageUuid = com.android.everytalk.data.database.entities.localSyncUuid("messages", steeringId)
        // 上次结果未知的调整：云端那一轮已经消费过就按已调整收尾，不能再当新消息发一遍。
        // 核对不了（断网、未连接、任务仍在运行）同样算结果未知，手动发送也不能降级成普通发送。
        val consumed = try { isCloudSteerConsumed(sessionId, steeringId, resendingSteer = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw CloudSteerNotAccepted(error.message ?: "方向调整尚未核对，暂不发送", unknown = true) }
        if (consumed) return true
        val run = agentDao.getLatestSteerableRun(sessionId) ?: return false
        if (run.executionOwner == "CLOUD") {
            // 云端方向调整只收文字；带附件或引用的调整留在队列，等这一轮结束后作为新消息发送。
            if (attachments.isNotEmpty() || contentParts.isNotEmpty() || content.isBlank()) {
                throw CloudSteerNotAccepted("云端任务的方向调整只支持文字，这条消息会在本轮结束后发送", unknown = false)
            }
            // 网络请求之前就持久绑定原 Run：请求已送达但响应未回时被杀，重启也先向服务器核对。
            // 标记只在核对出权威结论（已消费或任务已结束）时清除，不在命令成功时清。
            cloudRunContexts.edit().putString(unknownSteerKey(steeringId), run.id).commit()
            val accepted = try {
                commandCloudRun(run.id, "steer", kotlinx.serialization.json.buildJsonObject {
                    // 与随后 recordSteeredUserMessage 写入的用户消息同一个同步 UUID，各设备都能定位这条调整。
                    put("messageUuid", kotlinx.serialization.json.JsonPrimitive(messageUuid))
                    put("text", kotlinx.serialization.json.JsonPrimitive(content))
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { false }
            // 后端按 messageUuid 去重，重试同一条调整不会重复生效。结果未知时持久记下发给了哪个任务，
            // 之后无论自动还是手动派发都先向服务器核对，重启后也一样。
            if (!accepted) throw CloudSteerNotAccepted("方向调整未确认，已留在队列，请点立即发送重试", unknown = true)
            return true
        }
        val enqueued = agentRunStore.enqueueSteering(
            runId = run.id,
            instruction = AgentSteeringInstruction(
                id = steeringId,
                content = content,
                contentParts = contentParts,
                attachments = attachments,
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (!enqueued) return false

        // 只打断模型请求；EXECUTING_TOOL 等状态绝不取消，
        // 防止 edit/write/bash 被截断。恢复调用沿用原 Run，不创建新的用户任务。
        val latest = agentDao.getRun(run.id) ?: return true
        val modelIsActive = latest.status in setOf(
            AgentRunStatus.PREPARING_CONTEXT.name,
            AgentRunStatus.WAITING_MODEL.name,
            AgentRunStatus.STREAMING_MODEL.name,
        )
        if (!modelIsActive) return true

        // 首次运行在 Run 持久化前后可能仍使用 message key；恢复运行才使用 run key。
        // 两个 key 都指向同一个 AgentLoop，必须都能被 steering 找到。
        val job = activeJobs["run:${run.id}"]
            ?: activeJobs["message:${run.visibleAssistantMessageId}"]
        if (job != null && job.isActive) {
            job.cancel(AgentSteeringCancellation())
            job.join()
        }
        val interrupted = agentDao.getRun(run.id) ?: return true
        if (interrupted.status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name) {
            resumeRun(interrupted)
        } else {
            // 取消与自然终态竞争时，不重复启动已结束的 Run；steering 仍已持久化。
            true
        }
        return true
    }

    /**
     * 提交用户回答并恢复同一个 AgentRun。
     * 回答、用户历史和问题确认在同一事务接收，且只绑定问题所属的 Run。
     * 应用级协程拥有这次提交，卡片关闭取消 UI 协程时不能中断已经接收的回答。
     */
    suspend fun answerUserQuestion(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        content: String,
        contentParts: List<MessageContentPart> = emptyList(),
        attachments: List<SelectedMediaItem> = emptyList(),
    ): Boolean = scope.async {
        if (content.isBlank() && contentParts.isEmpty() && attachments.isEmpty()) return@async false
        // 云端提问：回答作为 answer 命令发给同一任务的同一等待点；云端回答只收文字。
        if (suspensionId.startsWith("cloud:")) {
            val (runId, toolCallId) = suspensionId.removePrefix("cloud:").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (attachments.isNotEmpty() || content.isBlank() || toolCallId.isEmpty()) return@async false
            return@async try {
                commandCloudRun(runId, "answer", kotlinx.serialization.json.buildJsonObject {
                    put("toolCallId", kotlinx.serialization.json.JsonPrimitive(toolCallId))
                    put("text", kotlinx.serialization.json.JsonPrimitive(content))
                }, generation = expectedVersion).also { accepted ->
                    if (accepted) cloudInterventions.update { it - runId }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { false }
        }
        interventionMutex.withLock {
            val instruction = AgentSteeringInstruction(
                id = "answer:$suspensionId:$expectedVersion",
                content = content,
                contentParts = contentParts,
                attachments = attachments,
                createdAt = System.currentTimeMillis(),
            )
            val run = database.withTransaction {
                val suspension = interventionStore.get(suspensionId) ?: return@withTransaction null
                val state = SuspensionState.valueOf(suspension.status)
                if (suspension.capabilityId != "user.question" ||
                    suspension.resolutionMaterialKind != ResolutionMaterialKind.NONE.name ||
                    state !in setOf(SuspensionState.WAITING_USER, SuspensionState.WAITING_USER_REENTRY) ||
                    suspension.rowVersion != expectedVersion ||
                    !interventionStore.matchesResolutionNonce(suspension, resolutionNonce)
                ) return@withTransaction null
                val run = agentDao.getRun(suspension.runId) ?: return@withTransaction null
                if (run.runGeneration != suspension.runGeneration ||
                    visibleRunCancellationReasons.containsKey(run.visibleAssistantMessageId)
                ) return@withTransaction null
                // 入队会检查 Run 终态；确认失败必须回滚整个事务，不能留下没有被接收的用户消息。
                if (!agentRunStore.enqueueSteering(run.id, instruction)) return@withTransaction null
                check(interventionStore.resolve(suspensionId, state, expectedVersion, resolutionNonce))
                run
            } ?: return@withLock false

            // 回答已经落库，立即沿用现有消息管线显示气泡，不等待下一轮模型消费队列。
            // Loop 后续消费同一 ID 时，显示层会幂等处理，不会追加第二个气泡。
            _events.emit(run.visibleAssistantMessageId to AppStreamEvent.AgentFollowUpAccepted(
                instruction.id, instruction.content, instruction.contentParts, instruction.attachments,
            ))
            interventionBroker.fulfill(suspensionId)
            scheduleInterventionContinuation(suspensionId)
            _pendingInterventions.update { items -> items.filterNot { it.suspensionId == suspensionId } }
            true
        }
    }.await()

    /**
     * 密钥或授权接力也允许用户发送普通说明。
     * “当前能力未提供”和“用户新消息入队”必须在同一笔事务中完成，避免拒绝后 Run
     * 先恢复结束，导致这条普通回复无法继续原任务。
     */
    suspend fun respondToInterventionWithText(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        content: String,
    ): Boolean = scope.async {
        val reply = content.trim()
        if (reply.isEmpty()) return@async false
        interventionMutex.withLock {
            val instruction = AgentSteeringInstruction(
                id = "intervention-text:$suspensionId:$expectedVersion",
                content = reply,
                createdAt = System.currentTimeMillis(),
            )
            val run = try {
                database.withTransaction {
                    val suspension = interventionStore.get(suspensionId)
                        ?: return@withTransaction null
                    val state = SuspensionState.valueOf(suspension.status)
                    if (suspension.resolutionMaterialKind !in setOf(
                            ResolutionMaterialKind.EPHEMERAL.name,
                            ResolutionMaterialKind.DURABLE_REFERENCE.name,
                        ) || state !in setOf(
                            SuspensionState.WAITING_USER,
                            SuspensionState.WAITING_USER_REENTRY,
                        ) || suspension.rowVersion != expectedVersion ||
                        !interventionStore.matchesResolutionNonce(suspension, resolutionNonce)
                    ) return@withTransaction null
                    val run = agentDao.getRun(suspension.runId)
                        ?: return@withTransaction null
                    if (run.runGeneration != suspension.runGeneration ||
                        visibleRunCancellationReasons.containsKey(run.visibleAssistantMessageId)
                    ) return@withTransaction null
                    check(agentRunStore.enqueueSteering(run.id, instruction))
                    // 普通文字不是密钥，当前工具能力必须明确以失败结果结束后再交给模型重规划。
                    check(interventionBroker.reject(suspensionId, expectedVersion))
                    run
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalStateException) {
                null
            } ?: return@withLock false

            // 文字已落库，立即显示用户气泡；AgentLoop 后续消费时会按 messageId 幂等。
            _events.emit(run.visibleAssistantMessageId to AppStreamEvent.AgentFollowUpAccepted(
                instruction.id,
                instruction.content,
                instruction.contentParts,
                instruction.attachments,
            ))
            scheduleInterventionContinuation(suspensionId)
            _pendingInterventions.update { items -> items.filterNot { it.suspensionId == suspensionId } }
            true
        }
    }.await()

    private suspend fun resumeRunLocked(
        run: AgentRunEntity,
        approvalDecision: AgentApprovalRecord?,
    ): Boolean {
        if (run.executionOwner != "LOCAL") return false
        val jobKey = "run:${run.id}"
        if (isRunActive(run)) return false

        val configId = run.configIdSnapshot ?: return resumeFailed(run.id)
        val config = database.apiConfigDao().getTextConfig(configId)?.toApiConfig() ?: return resumeFailed(run.id)
        val chatRequest = agentRunStore.restoreChatRequest(run, config.key) ?: return resumeFailed(run.id)
        AgentRecoveryDiagnostics.record(
            run = run,
            recoveryDecision = "RESUME_AGENT_LOOP",
            serviceStartReason = "COORDINATOR",
            requestId = agentRunStore.latestInterruptedAgentRequest(run.id)?.id,
            providerProtocol = config.channel,
        )

        // 读取 VPS 最终 stdout/stderr，沿用 ComputerToolExecutor 的 Secret 过滤和输出截断，
        // 成功写入 ToolResult 后才声明 Execution 已接回。
        val unconsumed = computerDao.getUnconsumedCompletedExecutionsForRun(run.id)
        for (exec in unconsumed) {
            if (!persistRecoveredToolResult(run, exec, chatRequest.localComputerRequestContext)) {
                return resumeFailed(run.id)
            }
        }

        val limits = com.android.everytalk.data.DataClass.ModelTokenLimits(
            maxOutputTokens = chatRequest.generationConfig?.maxOutputTokens ?: 4096,
            maxContextTokens = chatRequest.contextManagement?.maxContextTokens
                ?: com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS,
        )

        val loopRequest = AgentLoopRequest(
            request = chatRequest,
            sessionId = run.sessionId,
            userMessageId = run.userMessageId,
            visibleAssistantMessageId = run.visibleAssistantMessageId,
            tokenLimits = limits,
            existingRun = run,
            approvalDecision = approvalDecision,
        )

        val job = scope.launchTrackedAgentJob(activeJobs, jobKey, pauseController, run.visibleAssistantMessageId) {
            var foregroundActivity: java.io.Closeable? = null
            try {
                // 恢复准备包含多次挂起读取；期间可能已收到停止，启动前必须再次核对 generation。
                if (!canExecuteAgentSnapshot(run, agentDao.getRun(run.id))) return@launchTrackedAgentJob
                foregroundActivity = ComputerConnectionServiceController.acquireAgentRun(appContext)
                AgentRecoveryDiagnostics.runtime("resumed", run.visibleAssistantMessageId)
                agentLoop.run(loopRequest).collect { event ->
                    _events.emit(run.visibleAssistantMessageId to event)
                }
                val completedRun = agentDao.getRun(run.id)
                updateResumeRetryAfterRun(completedRun)
                notifyTerminalRun(completedRun)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordResumeFailure(run.id)
                AppLogger.error("AgentRunCoordinator", "Error executing resumed loop for run ${run.id}", e)
                agentRunStore.updateRunStatus(
                    run,
                    AgentRunStatus.MODEL_CONTINUATION_PENDING,
                    terminalReason = AgentTerminalReasons.MODEL_CONTINUATION_PENDING,
                )
            } finally {
                runCatching { foregroundActivity?.close() }.onFailure {
                    android.util.Log.e("AgentRuntime", "foreground_release_failed type=${it.javaClass.simpleName}")
                }
                visibleRunCancellationReasons[run.visibleAssistantMessageId]?.let { reason ->
                    withContext(NonCancellable) {
                        agentRunStore.cancelActiveRunByVisibleMessage(run.visibleAssistantMessageId, reason)
                        visibleRunCancellationReasons.remove(run.visibleAssistantMessageId, reason)
                    }
                }
                AgentRecoveryDiagnostics.runtime("ended", run.visibleAssistantMessageId)
            }
        }
        visibleRunCancellationReasons[run.visibleAssistantMessageId]?.let { reason ->
            job.cancel(CancellationException(reason))
        }
        job.start()
        return true
    }

    suspend fun resumePendingContinuationRuns(): Int {
        val pendingRuns = agentDao.getPendingModelContinuationRuns()
        var resumedCount = 0
        for (run in pendingRuns) {
            if (!canAttemptResume(run.id)) continue
            if (resumeRun(run)) {
                resumedCount++
            }
        }
        return resumedCount
    }

    /** App 启动时显式扫描全部非终态 Suspension，包括 RESOLUTION_RECEIVED 与 DELIVERED。 */
    suspend fun recoverInterventions(): List<AgentInterventionRecovery.RecoveryAction> =
        interventionMutex.withLock { recoverInterventionsLocked() }

    private suspend fun recoverInterventionsLocked(): List<AgentInterventionRecovery.RecoveryAction> {
        val nonceById = _pendingInterventions.value.associate { it.suspensionId to it.resolutionNonce }.toMutableMap()
        val actions = interventionRecovery.recover(
            activeNonces = nonceById.filterValues { it != null },
        )
        actions.forEach { action ->
            if (action.newResolutionNonce != null) nonceById[action.suspensionId] = action.newResolutionNonce
        }
        _pendingInterventions.value = interventionStore.startupCandidates()
            .filter {
                it.status in setOf(
                    SuspensionState.WAITING_USER.name,
                    SuspensionState.WAITING_USER_REENTRY.name,
                    SuspensionState.USER_DECISION_REQUIRED.name,
                )
            }
            // 只保留每个 Run 最近的一条可交互 Suspension，避免旧请求遮住重试后的新请求。
            .groupBy { it.runId }
            .values
            .mapNotNull { suspensions -> suspensions.maxByOrNull { it.updatedAt } }
            .sortedBy { it.updatedAt }
            .mapNotNull { suspension ->
                val pendingRun = interventionStore.getRun(suspension.runId) ?: return@mapNotNull null
                if (pendingRun.runGeneration != suspension.runGeneration || pendingRun.status in setOf(
                        AgentRunStatus.COMPLETED.name,
                        AgentRunStatus.FAILED.name,
                        AgentRunStatus.CANCELLED.name,
                    )
                ) return@mapNotNull null
                PendingIntervention(
                    suspensionId = suspension.id,
                    runId = suspension.runId,
                    sessionId = pendingRun.sessionId,
                    capabilityId = suspension.capabilityId,
                    reasonSafe = suspension.reasonSafe,
                    userVisibleContext = suspension.userVisibleContext,
                    parameters = runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(suspension.parametersJson) }.getOrDefault(emptyMap()),
                    materialKind = ResolutionMaterialKind.valueOf(suspension.resolutionMaterialKind),
                    fields = interventionPolicyRegistry.resolve(suspension.capabilityId)?.fields.orEmpty(),
                    requestSource = InterventionRequestSource.valueOf(suspension.requestSource),
                    rowVersion = suspension.rowVersion,
                    state = SuspensionState.valueOf(suspension.status),
                    resolutionNonce = nonceById[suspension.id],
                )
            }
        interventionStore.startupCandidates()
            .filter { suspension ->
                suspension.status in setOf(
                    SuspensionState.READY_TO_RESUME.name,
                    SuspensionState.READY_TO_RESUME_WITH_FAILURE.name,
                    SuspensionState.RESUMING.name,
                    SuspensionState.RESUMED.name,
                )
            }
            .forEach { suspension -> resumeSuspension(suspension.id) }
        return actions
    }

    /**
     * 只恢复当前 Suspension 对应的 control-tool slot。ToolResult 使用稳定 ID 幂等写入，
     * sibling slot 的已完成结果不会重放。RESUMED 仍可在冷启动时重新触发丢失的 wake-up。
     */
    private suspend fun resumeSuspension(suspensionId: String): Boolean {
        var suspension = interventionStore.get(suspensionId) ?: return false
        val initialState = SuspensionState.valueOf(suspension.status)
        if (initialState in setOf(
                SuspensionState.READY_TO_RESUME,
                SuspensionState.READY_TO_RESUME_WITH_FAILURE,
            )
        ) {
            if (!interventionStore.claimResume(
                    suspension.id,
                    initialState,
                    suspension.rowVersion,
                    suspension.runGeneration,
                    java.util.UUID.randomUUID().toString(),
                )
            ) return false
            suspension = interventionStore.get(suspension.id) ?: return false
        }
        val state = SuspensionState.valueOf(suspension.status)
        val run = agentDao.getRun(suspension.runId) ?: return false
        if (state == SuspensionState.RESUMING) {
            var failed = !suspension.failureCode.isNullOrBlank()
            val cloudflareReauthorization = suspension.capabilityId == "cloudflare.reauthorize"
            val resourceSelection = suspension.capabilityId == "cloudflare.resource.select"
            val selection = if (resourceSelection) com.android.everytalk.data.computer.CloudflareResourceIndex(computerDao)
                .selection(suspension.id) else null
            val parameters = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(suspension.parametersJson)
            }.getOrDefault(emptyMap())
            // 接力可能来自安全输入、普通提问或 capability 工具，结果必须对应原调用名称。
            // 原 Assistant 已持久化，不能用 request_capability 覆盖 request_protected_secret。
            val originalToolName = agentRunStore.findToolCall(run.id, suspension.toolCallId, suspension.requestId)?.name
                ?: parameters["tool_name"]
                ?: when (suspension.capabilityId) {
                    "user.question" -> AgentControlToolNames.REQUEST_USER_INPUT
                    "server.env.secret" -> AgentControlToolNames.REQUEST_PROTECTED_SECRET
                    else -> AgentControlToolNames.REQUEST_CAPABILITY
                }
            if (cloudflareReauthorization && !failed) {
                // 重新授权完成后把新的 generation 写回恢复快照；只要绑定校验失败，
                // 就不能把“授权已恢复”交给模型，更不能继续执行旧请求。
                val computerId = parameters["computer_id"]
                val config = computerId?.let { computerDao.getCloudflareConfig(it) }
                val authorization = config?.let { computerDao.getCloudflareAuthorization(it.authorizationId) }
                val previousGeneration = parameters["previous_generation"]?.toLongOrNull()
                val currentBinding = if (config != null && authorization != null && previousGeneration != null &&
                    config.authorizationId == parameters["authorization_id"] &&
                    config.accountId == parameters["account_id"] &&
                    authorization.generation > previousGeneration && !authorization.revoked &&
                    (authorization.expiresAt == null || authorization.expiresAt > System.currentTimeMillis())
                ) {
                    com.android.everytalk.data.computer.CloudflareRequestBinding(
                        config.accountId, config.authorizationId, authorization.generation,
                    )
                } else null
                if (currentBinding == null || !agentRunStore.refreshCloudflareRequestBinding(
                        run.id, suspension.id, suspension.rowVersion, currentBinding,
                    )) failed = true
            }
            val toolResult = AgentContentBlock.ToolResult(
                    toolCallId = suspension.toolCallId,
                    toolName = originalToolName,
                    content = if (cloudflareReauthorization) kotlinx.serialization.json.buildJsonObject {
                        put("ok", false)
                        put("error_code", if (failed) "REAUTHORIZATION_FAILED" else "AUTHORIZATION_RESTORED")
                        put("retryable", !failed)
                        put("message", if (failed) {
                            "Cloudflare 重新授权未完成：${suspension.failureCode}。请重新规划。"
                        } else {
                            "Cloudflare 授权已恢复，原工具调用尚未执行，请重新调用原工具。"
                        })
                    } else if (resourceSelection) kotlinx.serialization.json.buildJsonObject {
                        put("ok", false)
                        put("error_code", if (failed || selection == null) "RESOURCE_SELECTION_FAILED" else "RESOURCE_SELECTED")
                        put("retryable", !failed && selection != null)
                        put("message", "资源选择已结束，原工具尚未执行；重新调用时仍需通过写操作审批")
                        if (!failed && selection != null) {
                            put("resource_parameter", parameters["resource_parameter"].orEmpty())
                            put("resource_id", selection.resourceId)
                            put("account_id", selection.accountId)
                        }
                    } else if (suspension.capabilityId == "user.question" && !failed) kotlinx.serialization.json.JsonPrimitive(
                        "用户已回答，回答内容见接下来的用户消息。请继续当前任务。",
                    ) else kotlinx.serialization.json.JsonPrimitive(
                        if (failed) "能力接力未完成：${suspension.failureCode}。请根据当前条件重新规划。"
                        else "所需能力已由本地 Broker 准备完成。授权仅限当前 Run、Tool 和目标。",
                    ),
                    /*
                     * 原工具没有执行成功，因此必须保留 isError=true；模型得到的是
                     * “可重试的授权状态”，不能把重新授权误报成 Worker/D1 已完成。
                     */
                    isError = cloudflareReauthorization || resourceSelection || failed,
                )
            // 云端任务的接力：结果交回云端同一个等待点，由云端续跑；本机不写工具结果也不续跑。
            if (run.executionOwner == "CLOUD") {
                val text = (toolResult.content as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: toolResult.content.toString()
                saveAndSendCloudDeviceResult(run.id, suspension.toolCallId, text, toolResult.isError)
            } else agentRunStore.appendToolResult(runId = suspension.runId, requestId = suspension.requestId, result = toolResult)
            if (!interventionStore.finishResume(suspension.id, suspension.rowVersion)) return false
            suspension = interventionStore.get(suspension.id) ?: return false
        }
        if (run.executionOwner == "CLOUD") return false
        if (suspension.status != SuspensionState.RESUMED.name) return false
        if (run.runGeneration != suspension.runGeneration || run.status in setOf(
                AgentRunStatus.COMPLETED.name,
                AgentRunStatus.FAILED.name,
                AgentRunStatus.CANCELLED.name,
            )
        ) return false

        // Room 的完成事实也要通知当前页面。只标记这个工具结束，后续模型仍属于同一个 Run。
        _events.emit(run.visibleAssistantMessageId to AppStreamEvent.ExecutionStatusUpdate(
            status = null,
            toolCallId = suspension.toolCallId,
        ))
        val siblingBlocksRun = interventionStore.startupCandidates().any { sibling ->
            sibling.runId == run.id && sibling.id != suspension.id &&
                sibling.status !in setOf(SuspensionState.RESUMED.name)
        }
        if (siblingBlocksRun) return false
        return resumeRun(run)
    }

    /**
     * 应用级协程接管接收、履行与续写，关闭弹窗或切页只能停止等待结果，不能截断已接收的密钥写入。
     * 仍通过 nonce、Run generation 和 Broker 校验权限；应用退出时允许取消，绝不包裹不可取消的网络请求。
     */
    suspend fun resolveIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        material: ProtectedResolution = ProtectedResolution.None,
    ): Boolean {
        val submission = scope.async {
            interventionMutex.withLock {
                val resolved = interventionBroker.resolve(suspensionId, expectedVersion, resolutionNonce, material)
                if (resolved) scheduleInterventionContinuation(suspensionId)
                // CAS 拒绝也刷新版本和 nonce；不重发 Secret、不重放外部动作。
                recoverInterventionsLocked()
                resolved
            }
        }
        // 包括作用域已经结束、协程体没有机会运行的情况。UI 取消 await 时，不能提前擦除 Adapter 的副本。
        submission.invokeOnCompletion { material.clear() }
        return submission.await()
    }

    /** 调用方的可变缓冲区无论提交成功与否都在返回前清零。 */
    suspend fun resolveEphemeralIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secret: CharArray,
    ): Boolean = try {
        resolveIntervention(
            suspensionId,
            expectedVersion,
            resolutionNonce,
            ProtectedResolution.Ephemeral(secret),
        )
    } finally {
        secret.fill('\u0000')
    }

    /**
     * 迁移旧版本遗留的服务器 Secret 审批。
     *
     * 旧版本把 SERVER_ENV 当成 SkillSecret 保存，提交后不会进入服务器环境变量适配器。
     * 这里把旧审批转换成统一 Broker 接力，再沿用现有的安全写入和恢复流程。
     * Secret 只在内存中短暂存在，既不写入审批记录，也不进入模型消息。
     */
    suspend fun resolveLegacyProtectedSecret(
        runId: String,
        approvalRequestId: String,
        secret: CharArray,
    ): Boolean {
        val submission = scope.async {
            interventionMutex.withLock {
                val pending = agentRunStore.pendingApproval(runId)
                    ?.takeIf { it.approvalRequestId == approvalRequestId }
                    ?: return@withLock false
                val request = pending.agentRequest as? AgentPauseRequest.ProtectedSecret
                    ?: return@withLock false
                if (request.scope != SecretScope.SERVER_ENV) return@withLock false

                val run = agentDao.getRun(runId) ?: return@withLock false
                val workspace = when {
                    request.targetId == null -> computerDao
                        .getWorkspacesForConversation(run.sessionId)
                        .singleOrNull()
                    else -> computerDao.getWorkspaceById(request.targetId)
                        ?: computerDao.getWorkspace(request.targetId, run.sessionId)
                } ?: return@withLock false

                // 先把旧审批标记为已处理，再由 Broker 接管同一 Tool 槽位，
                // 避免旧审批和新 Suspension 并行恢复同一个 AgentRun。
                agentRunStore.decideApproval(
                    runId,
                    approvalRequestId,
                    AgentApprovalDecision.APPROVED,
                ) ?: return@withLock false
                val currentRun = agentDao.getRun(runId) ?: return@withLock false
                val capabilityRequest = CapabilityRequest(
                    requestedCapability = "server.env.secret",
                    reasonSafe = request.reason,
                    userVisibleContext = "目标服务器的受保护环境变量",
                    parameters = mapOf(
                        "scope" to request.scope.name,
                        "name" to request.name,
                        "path" to request.path.orEmpty(),
                        "target_id" to (request.targetId ?: workspace.id),
                    ),
                )
                val requestHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("${pending.toolCall.name}|${pending.toolCall.arguments}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val ticket = interventionBroker.suspend(
                    run = currentRun,
                    capabilityRequest = capabilityRequest,
                    turnId = pending.requestId,
                    requestId = pending.requestId,
                    toolCallId = pending.toolCall.id,
                    executionSlot = pending.toolCall.id,
                    requestHash = requestHash,
                    requestSource = "MODEL_HINT",
                    targetBindingRef = "computer:${workspace.computerId}:workspace:${workspace.id}",
                    bindingGeneration = 0,
                    executionGeneration = 0,
                )
                val nonce = ticket.resolutionNonce ?: return@withLock false
                val resolved = interventionBroker.resolve(
                    suspensionId = ticket.suspension.id,
                    expectedVersion = ticket.suspension.rowVersion,
                    nonce = nonce,
                    material = ProtectedResolution.Ephemeral(secret),
                )
                if (resolved) scheduleInterventionContinuation(ticket.suspension.id)
                recoverInterventionsLocked()
                resolved
            }
        }
        // UI 协程被关闭时，应用级提交仍需完成；提交结束后再清零调用方缓冲区。
        submission.invokeOnCompletion { secret.fill('\u0000') }
        return submission.await()
    }

    suspend fun resolveDurableIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secureReference: String,
    ): Boolean = resolveIntervention(
        suspensionId,
        expectedVersion,
        resolutionNonce,
        ProtectedResolution.DurableReference(secureReference),
    )

    /** 可信 UI 新建长期授权。Secret 进入 Keystore 后只用非敏感引用继续 Broker。 */
    suspend fun createAndResolveAuthorizationIntervention(
        suspensionId: String,
        expectedVersion: Long,
        resolutionNonce: String,
        secret: CharArray,
    ): Boolean {
        val suspension = interventionStore.get(suspensionId) ?: run {
            secret.fill('\u0000')
            return false
        }
        val provider = when (suspension.capabilityId) {
            "git.push" -> "github"
            "skill.openai_api_access" -> "openai"
            else -> {
                secret.fill('\u0000')
                return false
            }
        }
        val workspaceId = suspension.targetBindingRef
            .substringAfter(":workspace:", missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?: run {
                secret.fill('\u0000')
                return false
            }
        val reference = interventionAuthorizationManager.saveWorkspaceAuthorization(
            provider = provider,
            workspaceId = workspaceId,
            secret = secret,
        )
        val resolved = resolveDurableIntervention(suspensionId, expectedVersion, resolutionNonce, reference)
        if (!resolved) interventionAuthorizationManager.discard(reference)
        return resolved
    }

    suspend fun rejectIntervention(suspensionId: String, expectedVersion: Long): Boolean {
        // 拒绝只做本地 CAS，不能排在持有 interventionMutex 的远端履行/对账之后。
        // 决策一旦落库，关闭弹窗导致的 UI 协程取消也不能丢失这次唤醒。
        return withContext(NonCancellable) {
            val rejected = interventionBroker.reject(suspensionId, expectedVersion)
            if (!rejected) {
                scope.launch { recoverInterventions() }
                return@withContext false
            }
            scheduleInterventionContinuation(suspensionId)
            _pendingInterventions.update { items -> items.filterNot { it.suspensionId == suspensionId } }
            true
        }
    }

    /** 先登记应用级唤醒再关闭卡片；旧 Run 尚在收尾时必须等它退出，避免丢掉这次续写。 */
    private suspend fun scheduleInterventionContinuation(suspensionId: String) {
        val suspension = interventionStore.get(suspensionId) ?: return
        val run = agentDao.getRun(suspension.runId) ?: return
        scope.launchInterventionContinuation(activeJobs, run) {
            try {
                interventionMutex.withLock { resumeSuspension(suspensionId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 决策仍在 Room 中，保留给恢复机制重试；不记录外部异常正文。
                AppLogger.warn("AgentRunCoordinator", "Intervention continuation needs recovery: $suspensionId")
            }
        }
    }

    suspend fun confirmUnknownInterventionDelivered(suspensionId: String, expectedVersion: Long): Boolean {
        val confirmed = interventionBroker.confirmUnknownDelivered(suspensionId, expectedVersion)
        if (confirmed) recoverInterventions()
        return confirmed
    }

    suspend fun continueAfterUnknownIntervention(suspensionId: String, expectedVersion: Long): Boolean {
        val continued = interventionBroker.continueAfterUnknown(suspensionId, expectedVersion)
        if (continued) recoverInterventions()
        return continued
    }

    /**
     * 恢复工具结果落库窗口中的 Run。
     * AgentRunStore 会先检查最终 ToolResult 和原 ComputerExecution：结果已存在就直接续写，
     * 远端仍在运行就继续等待，执行事实缺失且可能有副作用时改为等待用户确认。
     */
    suspend fun resumeInterruptedToolRuns(): Int {
        var resumedCount = 0
        agentRunStore.resumableApprovalRuns(computerDao)
            // request_agent/request_mcp/request_secret 的恢复必须经过 ApiHandler：
            // 它负责把获批能力写回原请求快照（尤其是 MCP 工具和 Agent 服务器上下文）。
            // 服务层只自动接管没有本地能力申请的普通计算机工具审批，否则会绕过
            // 输入框开关和恢复准备，直接用旧请求继续，表现为批准后任务立即结束。
            .filter { (_, record) -> record.agentRequest == null }
            .filter { (run, _) -> run.status == AgentRunStatus.INTERRUPTED.name }
            .forEach { (run, record) ->
                if (resumeRun(run, record)) resumedCount++
            }
        return resumedCount
    }

    fun cancelRun(runId: String, reason: String = "USER_CANCELLED") {
        resumeRetryStates.remove(runId)
        scope.launch {
            val run = agentDao.getRun(runId) ?: return@launch
            if (run.executionOwner == "CLOUD") {
                stopCloudRun(run)
                return@launch
            }
            agentRunStore.updateRunStatus(
                run,
                AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
            _pendingInterventions.update { items -> items.filterNot { it.runId == runId } }
            val jobs = listOfNotNull(activeJobs["run:$runId"], activeJobs["message:${run.visibleAssistantMessageId}"]).distinct()
            jobs.forEach { it.cancel(CancellationException(reason)) }
            jobs.forEach { it.join() }
            // 收尾或恢复扫描不能把停止前读到的卡片重新投影回来。
            interventionMutex.withLock {
                _pendingInterventions.update { items -> items.filterNot { it.runId == runId } }
            }
        }
    }

    /**
     * 排队消息是否已作为方向调整被云端消费。运行中看快照；归档交接会删快照，
     * 所以还要查会话里正式回复的执行轨迹（边界可能已映射成本机 ID，也可能还是同步 UUID）。
     */
    internal suspend fun isCloudSteerConsumed(sessionId: String, steeringId: String, resendingSteer: Boolean = false): Boolean {
        val uuid = com.android.everytalk.data.database.entities.localSyncUuid("messages", steeringId)
        // 结果未知的调整只认服务器：本机快照和正式消息可能还没追平（刚重启、SSE 全丢）。
        cloudRunContexts.getString(unknownSteerKey(steeringId), null)?.let { runId ->
            val view = checkNotNull(cloudCoordinator) { "云端尚未连接，暂不发送" }.fetch(runId)
            val consumed = view.snapshot.trace.any { it.kind == "user" && it.messageUuid == uuid }
            if (!consumed && !view.terminal) {
                // 还在运行：重发同一条调整是安全的（后端按 messageUuid 去重）；当新消息另发则不行。
                check(resendingSteer) { "云端任务仍在运行，暂不发送" }
                return false
            }
            cloudRunContexts.edit().remove(unknownSteerKey(steeringId)).apply()
            return consumed
        }
        val runs = agentRunStore.getRunsForSession(sessionId).filter { it.executionOwner == "CLOUD" }
        if (runs.isEmpty()) return false
        if (runs.any { run -> CloudAgentStore(database).read(run.id)?.snapshot?.trace?.any { it.kind == "user" && it.messageUuid == uuid } == true }) return true
        return database.chatDao().getMessagesForSession(sessionId).any { entity ->
            entity.toMessage().executionTrace.any {
                it is com.android.everytalk.data.DataClass.ExecutionTraceEvent.UserMessageBoundary && it.messageId in setOf(steeringId, uuid)
            }
        }
    }

    /** 页面停止按钮与提交前停止共用同一入口：游标为 0 的提交走墓碑，不能只发普通 stop 撞 404。 */
    internal suspend fun stopCloudRunById(runId: String): Boolean =
        agentDao.getRun(runId)?.takeIf { it.executionOwner == "CLOUD" }?.let { stopCloudRun(it) } ?: false

    /** 只有后端确认的快照能结束云端任务；控制失败只更新连接提示，保留原游标和运行事实。 */
    private suspend fun stopCloudRun(run: AgentRunEntity): Boolean = try {
        val coordinator = checkNotNull(cloudCoordinator) { "云端尚未连接" }
        // 服务器还没确认创建的提交：按同一 run 发停止并留墓碑，不能等发现扫描把它补发出去后再停。
        if (run.cloudCursor == 0L) coordinator.abandonUnsubmitted(run) else coordinator.command(run.id, "stop")
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        _cloudRuns.update { current -> current.mapValues { (_, value) ->
            if (value.runId == run.id && value.running) value.copy(message = value.message.copy(executionStatus = "停止请求未确认，请重试")) else value
        } }
        false
    }

    /** 全局停止等待本机 Job 收尾；换账号只清理本机，不把注销误当成云端停止。 */
    suspend fun cancelAllActiveRuns(reason: String = AgentTerminalReasons.USER_STOP, includeCloud: Boolean = true) {
        val jobs = activeJobs.values.distinct()
        // 先让持久 generation 失效，再停止内存 Job；外部 callback/claim 不能穿过终止窗口。
        agentDao.getActiveRuns().forEach { run ->
            if (run.executionOwner == "CLOUD") {
                if (includeCloud) stopCloudRun(run)
                return@forEach
            }
            agentRunStore.cancelOpenRequests(run.id, reason)
            agentRunStore.updateRunStatus(
                run = run,
                status = AgentRunStatus.CANCELLED,
                terminalReason = reason,
            )
        }
        _pendingInterventions.value = emptyList()
        jobs.forEach { job -> job.cancel(CancellationException(reason)) }
        jobs.forEach { job -> job.join() }
        interventionMutex.withLock { _pendingInterventions.value = emptyList() }
        visibleRunCancellationReasons.clear()
        recoveringRunIds.clear()
        resumeRetryStates.clear()
    }

    /** 用户第一次点击就登记停止；即使 Run 尚未写入 Room，稍后登记的 Job 也会立即取消。 */
    fun cancelVisibleRun(messageId: String, reason: String = AgentTerminalReasons.USER_STOP) {
        visibleRunCancellationReasons[messageId] = reason
        val messageJob = activeJobs["message:$messageId"]
        messageJob?.cancel(CancellationException(reason))
        scope.launch {
            agentDao.getRunByVisibleMessage(messageId)?.takeIf { it.executionOwner == "CLOUD" }?.let { run ->
                try { stopCloudRun(run) }
                finally { visibleRunCancellationReasons.remove(messageId, reason) }
                return@launch
            }
            // 已存在的本机 Run 先终止持久状态，随后再等待执行协程清理。
            agentRunStore.cancelActiveRunByVisibleMessage(messageId, reason)?.let { run ->
                // 持久终态确认后立即关闭问题卡片，不等待执行协程和远端调用收尾。
                _pendingInterventions.update { items -> items.filterNot { it.runId == run.id } }
                resumeRetryStates.remove(run.id)
                activeJobs["run:${run.id}"]?.let { runJob ->
                    runJob.cancel(CancellationException(reason))
                    runJob.join()
                }
                messageJob?.join()
                visibleRunCancellationReasons.remove(messageId, reason)
                interventionMutex.withLock {
                    _pendingInterventions.update { items -> items.filterNot { it.runId == run.id } }
                }
            }
            messageJob?.join()
        }
    }

    private suspend fun canAttemptResume(runId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (resumeRetryStates[runId]?.nextAttemptAt?.let { now < it } == true) return false
        // 请求次数和完成时间已落 Room；App 重启也不能跳过 5/10/15 秒等待。
        val latest = agentDao.getRequests(runId).lastOrNull()
        return latest?.takeIf { it.status == AgentRequestStatus.INTERRUPTED.name }?.let {
            now >= (it.finishedAt ?: 0L) + agentResumeRetryDelayMillis(it.attempt)
        } ?: true
    }

    private fun resumeFailed(runId: String): Boolean {
        recordResumeFailure(runId)
        return false
    }

    /**
     * Provider 返回临时错误时 AgentLoop 已把 Run 持久化为待续写。
     * 这里保留失败次数并退避；正常完成、审批暂停或永久失败都会清掉旧退避状态。
     */
    private fun updateResumeRetryAfterRun(run: AgentRunEntity?) {
        val runId = run?.id ?: return
        if (shouldBackoffAgentResume(run.status)) {
            recordResumeFailure(runId)
        } else {
            resumeRetryStates.remove(runId)
        }
    }

    /** 恢复失败保留原 Run，按 5/10/15 秒退避，避免前台服务反复读取同一份大快照。 */
    private fun recordResumeFailure(runId: String) {
        val now = System.currentTimeMillis()
        resumeRetryStates.compute(runId) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            ResumeRetryState(
                failures = failures,
                nextAttemptAt = now + agentResumeRetryDelayMillis(failures),
            )
        }
    }

    /**
     * 通知粒度跟随整个 AgentRun。普通聊天没有 ComputerExecution，不发送系统通知。
     * 通知失败不能反向改坏已经落库的任务终态。
     */
    private suspend fun notifyTerminalRun(run: AgentRunEntity?) {
        if (run == null) return
        try {
            val status = AgentRunStatus.valueOf(run.status)
            val executionCount = computerDao.countExecutionsForAgentRun(run.id)
            if (!shouldNotifyAgentRunTerminal(status, executionCount)) return
            AgentNotificationManager.notifyAgentRunTerminal(
                context = appContext,
                conversationId = run.sessionId,
                runId = run.id,
                status = status,
            )
        } catch (error: Exception) {
            AppLogger.warn(
                "AgentRunCoordinator",
                "Unable to publish terminal notification for run ${run.id}: ${error.message}",
            )
        }
    }

    /**
     * 先把结构化 Tool Result 写入 AgentEntry，再声明 Execution 已消费。
     * 崩溃发生在两步之间时，hasFinalToolResult 会阻止重复写入。
     */
    private suspend fun persistRecoveredToolResult(
        run: AgentRunEntity,
        execution: ComputerExecutionEntity,
        computerContext: ComputerRequestContext?,
    ): Boolean {
        val context = computerContext?.targetContexts()?.singleOrNull {
            it.computerId == execution.computerId && it.workspaceId == execution.workspaceId
        } ?: return false
        val startedEntry = agentDao.getEntries(run.id).lastOrNull { entry ->
            entry.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name &&
                entry.toolCallId?.let { rawToolCallId ->
                    runCatching { ComputerToolRequestHasher.toolCallKey(rawToolCallId, context) }.getOrNull()
                } == execution.toolCallId
        } ?: return false
        val requestId = startedEntry.requestId ?: return false
        val rawToolCallId = startedEntry.toolCallId ?: return false
        if (agentRunStore.finalToolResult(run.id, requestId, rawToolCallId) == null) {
            val call = agentRunStore.findToolCall(run.id, rawToolCallId, requestId) ?: return false
            val recovered = recoveryToolRuntime.execute(
                call = call,
                computerContext = context,
                runId = run.id,
                emit = {},
            )
            agentRunStore.appendToolResult(
                runId = run.id,
                requestId = requestId,
                result = recovered,
            )
        }
        computerDao.markResultAttached(execution.id)
        return true
    }
}

private data class ResumeRetryState(
    val failures: Int,
    val nextAttemptAt: Long,
)

/** 初始请求之后最多三次模型重试，分别等待 5、10、15 秒；恢复读取失败也不高频重试。 */
internal fun agentResumeRetryDelayMillis(failures: Int): Long = failures.coerceIn(1, 3) * 5_000L

/** 只有可恢复的模型中断参与退避，审批等待和终态都不能继承旧失败次数。 */
internal fun shouldBackoffAgentResume(status: String?): Boolean =
    status == AgentRunStatus.MODEL_CONTINUATION_PENDING.name

/** 同时识别首次启动的 message 键和恢复启动的 run 键。 */
internal fun isAgentRunActive(
    activeJobs: Map<String, Job>,
    run: AgentRunEntity,
): Boolean = listOf("run:${run.id}", "message:${run.visibleAssistantMessageId}")
    .any { key -> activeJobs[key]?.let { !it.isCompleted && !it.isCancelled } == true }

/**
 * 接力决策使用应用级作用域交接：仅等待发布弹窗的原 Run 退出，再消费决策。
 * 不取消原任务，也不等待其他 Run。捕获旧 Job，避免把后来启动的续写也加入等待。
 */
internal fun CoroutineScope.launchInterventionContinuation(
    activeJobs: Map<String, Job>,
    run: AgentRunEntity,
    resume: suspend () -> Unit,
): Job {
    val previousJobs = listOfNotNull(
        activeJobs["run:${run.id}"],
        activeJobs["message:${run.visibleAssistantMessageId}"],
    ).distinct()
    return launch {
        previousJobs.forEach { it.join() }
        resume()
    }
}

/** 只有实际使用过 VPS 且整个 Run 已结束时，才允许发送最终通知。 */
internal fun shouldNotifyAgentRunTerminal(
    status: AgentRunStatus,
    computerExecutionCount: Int,
): Boolean = computerExecutionCount > 0 && status in setOf(
    AgentRunStatus.COMPLETED,
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
)

/**
 * 先登记再启动，极快完成和启动前取消都通过 completion 清理同一份运行记录。
 * 调用方先绑定关闭通道等回调，再调用 start；仍在收尾的旧任务禁止被另一轮覆盖。
 */
internal fun CoroutineScope.launchTrackedAgentJob(
    activeJobs: ConcurrentHashMap<String, Job>,
    jobKey: String,
    pauseController: AgentRunPauseController,
    messageId: String,
    block: suspend CoroutineScope.() -> Unit,
): Job = synchronized(activeJobs) {
    check(activeJobs[jobKey]?.isCompleted != false) { "Agent task is already registered" }
    val job = launch(start = CoroutineStart.LAZY, block = block)
    activeJobs[jobKey] = job
    pauseController.register(messageId)
    job.invokeOnCompletion {
        synchronized(activeJobs) {
            if (activeJobs.remove(jobKey, job)) pauseController.finish(messageId)
        }
    }
    job
}

/** 已停止或 generation 失效的恢复快照绝不能再次调用模型、执行工具。 */
internal fun canExecuteAgentSnapshot(snapshot: AgentRunEntity, persisted: AgentRunEntity?): Boolean =
    persisted != null && snapshot.executionOwner == "LOCAL" && persisted.executionOwner == "LOCAL" &&
        persisted.runGeneration == snapshot.runGeneration &&
        persisted.status !in setOf(AgentRunStatus.COMPLETED.name, AgentRunStatus.FAILED.name, AgentRunStatus.CANCELLED.name)
