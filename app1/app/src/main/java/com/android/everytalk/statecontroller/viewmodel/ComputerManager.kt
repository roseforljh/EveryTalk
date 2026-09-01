package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.computer.AddComputerRequest
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAttachmentBridge
import com.android.everytalk.data.computer.ComputerAuditEvent
import com.android.everytalk.data.computer.ComputerDeleteResult
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerErrorCodes
import com.android.everytalk.data.computer.ComputerExecTarget
import com.android.everytalk.data.computer.ComputerHostCommandConfirmationRequest
import com.android.everytalk.data.computer.ComputerPreview
import com.android.everytalk.data.computer.ComputerPreviewManager
import com.android.everytalk.data.computer.ComputerPreviewOpenResult
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerPublicPreviewRequest
import com.android.everytalk.data.computer.ComputerToolApprovalPhase
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.database.entities.toModel
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerToolExecutor
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.ComputerWorkspace
import com.android.everytalk.data.computer.ComputerWorkspaceManager
import com.android.everytalk.data.computer.ComputerWorkspaceSecret
import com.android.everytalk.data.computer.ComputerWorkspaceSecretManager
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.data.computer.PreparedComputerRequest
import com.android.everytalk.data.computer.UpdateComputerRequest
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.util.AppLogger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 同一 Workspace 同时只运行一个预热任务，任务结束时按实例安全移除。 */
internal fun launchComputerPrewarm(
    scope: CoroutineScope,
    jobs: java.util.concurrent.ConcurrentHashMap<String, Job>,
    workspaceId: String,
    dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    block: suspend () -> Unit,
) {
    var created: Job? = null
    jobs.compute(workspaceId) { _, current ->
        current?.takeUnless { it.isCompleted } ?: scope.launch(
            context = dispatcher,
            start = CoroutineStart.LAZY,
        ) {
            block()
        }.also { created = it }
    }
    created?.let { job ->
        job.invokeOnCompletion { jobs.remove(workspaceId, job) }
        job.start()
    }
}

/** 同一 Workspace 只创建一份远端准备任务，开启 Agent、模型思考和工具调用共同复用。 */
internal fun <T> sharedComputerPreparation(
    scope: CoroutineScope,
    preparations: java.util.concurrent.ConcurrentHashMap<String, Deferred<T>>,
    key: String,
    dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    block: suspend () -> T,
): Deferred<T> {
    var created: Deferred<T>? = null
    val preparation = preparations.compute(key) { _, current ->
        // 失败任务会在 invokeOnCompletion 中按实例移除；这里无需读取实验性的完成异常 API。
        current?.takeUnless { it.isCancelled }
            ?: scope.async(context = dispatcher, start = CoroutineStart.LAZY) { block() }.also { created = it }
    } ?: error("无法创建 Agent 准备任务")
    created?.let { deferred ->
        deferred.invokeOnCompletion { error ->
            if (error != null) preparations.remove(key, deferred)
        }
        deferred.start()
    }
    return preparation
}

/**
 * 串行化“创建会话映射”和“临时会话 ID 迁移”。
 * 迁移先完成时会记住新 ID；创建先完成时，后续数据库迁移会接管已经创建的映射。
 */
internal class ComputerConversationIdCoordinator {
    private val mutex = Mutex()
    private val migratedIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 读取当前有效 ID。允许连续迁移，并防止异常数据形成循环。 */
    fun resolve(conversationId: String): String {
        var current = conversationId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            current = migratedIds[current] ?: return current
        }
        return conversationId
    }

    suspend fun <T> withCurrentId(
        conversationId: String,
        block: suspend (String) -> T,
    ): T = mutex.withLock { block(resolve(conversationId)) }

    suspend fun migrate(
        sourceConversationId: String,
        targetConversationId: String,
        migrateStoredMappings: suspend (sourceId: String, targetId: String) -> Unit,
    ) = mutex.withLock {
        val sourceId = resolve(sourceConversationId)
        val targetId = resolve(targetConversationId)
        if (sourceId != targetId) migrateStoredMappings(sourceId, targetId)
        if (sourceConversationId != targetId) migratedIds[sourceConversationId] = targetId
        if (sourceId != targetId) migratedIds[sourceId] = targetId
    }
}

/** Agent 按钮的本地校验结果，便于在无网络测试中锁定即时反馈路径。 */
internal fun requireSelectedReadyComputer(
    conversationId: String,
    selections: Map<String, String>,
    computers: List<Computer>,
): Computer {
    val computerId = selections[conversationId] ?: throw ComputerException(
        ComputerErrorCodes.SERVER_NOT_SELECTED,
        "当前会话还没有选择服务器",
        action = "SELECT_COMPUTER",
    )
    val computer = computers.firstOrNull { it.id == computerId } ?: throw ComputerException(
        ComputerErrorCodes.COMPUTER_NOT_READY,
        "当前服务器不可用，请长按 Agent 改选或修复服务器",
        action = "SELECT_COMPUTER",
    )
    if (computer.status != ComputerStatus.READY) {
        throw ComputerException(
            ComputerErrorCodes.COMPUTER_NOT_READY,
            "当前服务器不可用，请长按 Agent 改选或修复服务器",
            action = "SELECT_COMPUTER",
        )
    }
    return computer
}

/**
 * Android 端 Computer 功能协调器。
 * 它只组合本地 Room、Keystore 与 SSH 组件，不创建网络中转服务，也不持有模型参数。
 */
class ComputerManager(
    context: Context,
    private val scope: CoroutineScope,
    attachmentsForConversation: (String) -> List<SelectedMediaItem>,
    onDownloaded: suspend (String, SelectedMediaItem.GenericFile) -> Unit,
) : AutoCloseable {
    private val repository = ComputerRepository(context.applicationContext)
    private val workspaceManager = ComputerWorkspaceManager(repository)
    private val previewManager = ComputerPreviewManager(repository)
    private val secretManager = ComputerWorkspaceSecretManager(repository)
    private val skillRepository = com.android.everytalk.data.skill.SkillRepository(context.applicationContext)
    private val skillServerSync = com.android.everytalk.data.skill.SkillServerSync(skillRepository, repository)
    private val attachmentBridge = ComputerAttachmentBridge(
        context = context.applicationContext,
        attachmentsForConversation = attachmentsForConversation,
        onDownloaded = onDownloaded,
    )
    private val previewConfirmationMutex = Mutex()
    private val hostCommandConfirmationMutex = Mutex()
    private val _pendingPublicPreview = MutableStateFlow<ComputerPublicPreviewRequest?>(null)
    private val _pendingHostCommand = MutableStateFlow<ComputerHostCommandConfirmationRequest?>(null)

    @Volatile
    private var publicPreviewDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var hostCommandDecision: CompletableDeferred<Boolean>? = null

    private val toolExecutor = ComputerToolExecutor(
        context = context.applicationContext,
        repository = repository,
        workspaceManager = workspaceManager,
        previewManager = previewManager,
        secretManager = secretManager,
        attachmentBridge = attachmentBridge,
        publicPreviewConfirmer = ::awaitPublicPreviewConfirmation,
        hostCommandConfirmer = ::awaitHostCommandConfirmation,
    )
    private val closed = AtomicBoolean(false)
    private val prewarmJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val requestPreparations = java.util.concurrent.ConcurrentHashMap<String, Deferred<PreparedComputerRequest>>()
    private val localRecovery = CompletableDeferred<Unit>()
    private val conversationIds = ComputerConversationIdCoordinator()
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val activeNetwork = AtomicReference<Network?>(null)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = activeNetwork.getAndSet(network)
            if (previous != null && previous != network) {
                AppLogger.warn(
                    "ComputerNetwork",
                    "默认网络发生变化 previous=$previous current=$network，主动关闭旧 SSH",
                )
                handleNetworkChanged()
            }
        }

        override fun onLost(network: Network) {
            if (activeNetwork.compareAndSet(network, null)) {
                AppLogger.warn("ComputerNetwork", "默认网络丢失 network=$network，主动关闭 SSH")
                handleNetworkChanged()
            }
        }

        private fun handleNetworkChanged() {
            clearRequestPreparations()
            previewManager.handleNetworkChanged()
            scope.launch(Dispatchers.IO) { repository.handleNetworkChanged() }
        }
    }
    private val connectionStopListener = ComputerConnectionServiceController.addStopListener {
        clearRequestPreparations()
        previewManager.handleNetworkChanged()
        toolExecutor.closeTransientConnections()
        scope.launch(Dispatchers.IO) { repository.handleNetworkChanged() }
    }

    val computers: StateFlow<List<Computer>> = repository.observeComputers().stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val selections: StateFlow<Map<String, String>> = repository.observeSelections().stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap(),
    )
    val pendingPublicPreview: StateFlow<ComputerPublicPreviewRequest?> = _pendingPublicPreview.asStateFlow()
    val pendingHostCommand: StateFlow<ComputerHostCommandConfirmationRequest?> = _pendingHostCommand.asStateFlow()

    init {
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        scope.launch(Dispatchers.IO) {
            try {
                repository.recoverLocalState()
                localRecovery.complete(Unit)
                previewManager.reconcileExpirations()
            } catch (error: Throwable) {
                if (!localRecovery.isCompleted) localRecovery.completeExceptionally(error)
                throw error
            }
        }
    }

    /** Agent 恢复前等待 ComputerExecution 从 RUNNING 收敛到 UNKNOWN。 */
    suspend fun awaitLocalRecovery() = localRecovery.await()

    /** 当前四类文本 Provider 都走应用内置 Tool Loop；图像、音频和视频配置不开放 Agent。 */
    fun supportsToolCalls(config: ApiConfig?): Boolean = config != null &&
        config.model.isNotBlank() &&
        config.modalityType in setOf(ModalityType.TEXT, ModalityType.MULTIMODAL)

    /**
     * Agent 按钮只读取已经加载到内存的服务器与选择状态，禁止在点击反馈前连接 VPS。
     * 发送消息时的 prepareRequest 仍会执行完整 Workspace 和远端校验。
     */
    fun requireSelectedReadyComputer(conversationId: String): Computer {
        val resolvedId = conversationIds.resolve(conversationId)
        val currentSelections = selections.value
        val lookupId = resolvedId.takeIf(currentSelections::containsKey) ?: conversationId
        return requireSelectedReadyComputer(lookupId, currentSelections, computers.value)
    }

    /**
     * 在模型请求启动前冻结 Computer 与 Workspace。
     * Agent 已关闭时返回 null；已开启却不可用时抛出明确错误，禁止静默移除工具。
     */
    suspend fun prepareRequest(conversationId: String, agentEnabled: Boolean): PreparedComputerRequest? {
        if (!agentEnabled) return null
        return conversationIds.withCurrentId(conversationId) { currentConversationId ->
            val computer = repository.getSelectedComputer(currentConversationId) ?: throw ComputerException(
                ComputerErrorCodes.SERVER_NOT_SELECTED,
                "当前会话还没有选择服务器",
                action = "SELECT_COMPUTER",
            )
            if (computer.status != ComputerStatus.READY) {
                throw ComputerException(
                    ComputerErrorCodes.COMPUTER_NOT_READY,
                    "当前服务器不可用，请长按 Agent 改选或修复服务器",
                    action = "SELECT_COMPUTER",
                )
            }
            val workspace = workspaceManager.getOrCreateLocal(computer.id, currentConversationId)
            val requestContext = ComputerRequestContext(
                conversationId = currentConversationId,
                computerId = computer.id,
                workspaceId = workspace.id,
                permissionMode = computer.permissionMode,
            )
            prepareComputer(computer, requestContext)
            PreparedComputerRequest(
                context = requestContext,
                environmentPrompt = buildEnvironmentPrompt(computer),
                permissionMode = computer.permissionMode,
            )
        }
    }

    private fun prepareComputer(
        computer: Computer,
        requestContext: ComputerRequestContext,
    ): Deferred<PreparedComputerRequest> {
        val key = preparationKey(computer.id, requestContext.workspaceId)
        return sharedComputerPreparation(scope, requestPreparations, key) {
            val workspace = workspaceManager.prepare(requestContext.workspaceId)
            val preparedContext = requestContext.copy(
                conversationId = workspace.conversationId,
                permissionMode = computer.permissionMode,
            )
            startPrewarm(preparedContext)
            PreparedComputerRequest(
                context = preparedContext,
                environmentPrompt = buildEnvironmentPrompt(computer),
                permissionMode = computer.permissionMode,
            )
        }
    }

    /**
     * 请求返回后模型才开始首轮响应，这里只负责异步预热 SSH 和 Wrapper。
     * 同一 Workspace 的重复发送复用正在执行的预热，避免取消后立刻再做一次冷启动。
     */
    private fun startPrewarm(requestContext: ComputerRequestContext) {
        launchComputerPrewarm(scope, prewarmJobs, requestContext.workspaceId) {
            try {
                toolExecutor.prewarm(requestContext)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLogger.warn(
                    "ComputerRuntime",
                    "Agent SSH 预热失败，将在工具调用时重试：${error.javaClass.simpleName}",
                )
            }
        }
    }

    /** 服务器选择只更新本地映射；Workspace 在后台预热或首次发送时创建。 */
    suspend fun selectComputer(conversationId: String, computerId: String, agentEnabled: Boolean) {
        val selectedConversationId = conversationIds.withCurrentId(conversationId) { currentConversationId ->
            repository.selectComputer(currentConversationId, computerId)
            currentConversationId
        }
        if (agentEnabled) prewarmWorkspace(computerId, selectedConversationId)
    }

    private fun prewarmWorkspace(computerId: String, conversationId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val computer = repository.getComputer(computerId) ?: return@launch
                val (currentConversationId, workspace) = conversationIds.withCurrentId(conversationId) { currentId ->
                    currentId to workspaceManager.getOrCreateLocal(computerId, currentId)
                }
                prepareComputer(
                    computer = computer,
                    requestContext = ComputerRequestContext(
                        conversationId = currentConversationId,
                        computerId = computerId,
                        workspaceId = workspace.id,
                        permissionMode = computer.permissionMode,
                    ),
                ).await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLogger.warn(
                    "ComputerRuntime",
                    "Agent Workspace 后台准备失败，将在发送时重试：${error.javaClass.simpleName}",
                )
            }
        }
    }

    suspend fun execute(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit = {},
    ): kotlinx.serialization.json.JsonElement {
        // 首轮模型响应已经与 SSH 准备并行；模型真正调用工具时再确认远端 Workspace 已就绪。
        val computer = repository.getComputer(requestContext.computerId)
            ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
        val prepared = prepareComputer(computer, requestContext).await()
        val currentContext = prepared.context.copy(
            conversationId = requestContext.conversationId,
            permissionMode = requestContext.permissionMode,
            approvedToolCallId = requestContext.approvedToolCallId,
            retryUnknownToolCallId = requestContext.retryUnknownToolCallId,
        )
        return toolExecutor.execute(
            toolName = toolName,
            arguments = arguments,
            toolCallId = toolCallId,
            requestContext = currentContext,
            updateStatus = updateStatus,
        )
    }

    /** 停止按钮取消当前 AgentRun 的全部前台和后台受管远端任务。 */
    fun cancelActiveExecutions(
        conversationId: String,
        runId: String? = null,
        onComplete: (Boolean) -> Unit = {},
    ): Job {
        if (conversationId.isBlank() && runId.isNullOrBlank()) return scope.launch { onComplete(true) }
        return scope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            var success = true
            runCatching { toolExecutor.cancelActiveExecutions(conversationId, runId) }
                .onSuccess { remoteCancelSucceeded ->
                    success = remoteCancelSucceeded
                }
                .onFailure { error ->
                    success = false
                    if (error !is kotlinx.coroutines.CancellationException) {
                        AppLogger.warn("ComputerRuntime", "停止会话远端任务失败：${error.message}")
                    }
                }
            onComplete(success)
        }
    }

    /** 应用恢复轮询使用统一对账入口，网络不可用时保留本地最后状态。 */
    suspend fun reconcileRemoteExecutions(
        conversationIds: Set<String> = emptySet(),
    ): List<com.android.everytalk.data.computer.ComputerExecutionReconciliation> =
        repository.reconcileRemoteExecutions(conversationIds)

    /** 每轮模型请求前注入精简的活动远端任务状态。 */
    suspend fun computerSessionState(requestContext: ComputerRequestContext?): String? {
        requestContext ?: return null
        return repository.getComputerSessionState(requestContext.workspaceId)?.toPrompt()
    }

    /** AgentLoop 的持久化审批入口；普通详情页操作继续使用原内存确认入口。 */
    suspend fun approvalRequest(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext?,
        phase: ComputerToolApprovalPhase,
    ): ComputerToolApprovalRequest? {
        val context = requestContext ?: return null
        return toolExecutor.approvalRequest(toolName, arguments, toolCallId, context, phase)
    }

    suspend fun migrateConversationId(sourceConversationId: String, targetConversationId: String) {
        if (sourceConversationId.isBlank() || targetConversationId.isBlank() || sourceConversationId == targetConversationId) {
            return
        }
        conversationIds.migrate(sourceConversationId, targetConversationId) { sourceId, targetId ->
            repository.migrateConversationId(sourceId, targetId)
        }
    }

    suspend fun probeHostKey(request: AddComputerRequest): HostKeyProbeResult = repository.probeHostKey(request)

    suspend fun addConfirmedComputer(
        request: AddComputerRequest,
        confirmedHostKey: HostKeyProbeResult,
        sudoPassword: CharArray?,
        onProgress: suspend (com.android.everytalk.data.computer.ComputerSetupStage) -> Unit = {},
    ): Computer = repository.addConfirmedComputer(request, confirmedHostKey, sudoPassword, onProgress)

    suspend fun probeUpdatedComputerHostKey(request: UpdateComputerRequest): HostKeyProbeResult =
        repository.probeUpdatedComputerHostKey(request)

    suspend fun updateComputer(
        request: UpdateComputerRequest,
        confirmedHostKey: HostKeyProbeResult,
        sudoPassword: CharArray?,
        replaceSudoPassword: Boolean,
    ): Computer {
        clearComputerPreparations(request.id)
        return repository.updateComputer(
            request,
            confirmedHostKey,
            sudoPassword,
            replaceSudoPassword,
        )
    }

    suspend fun refreshComputer(computerId: String): Computer {
        clearComputerPreparations(computerId)
        val refreshed = repository.refreshComputer(computerId)
        previewManager.reconcileExpirations()
        previewManager.reconcileComputer(computerId)
        return refreshed
    }

    suspend fun provisionContainer(
        computerId: String,
        onProgress: suspend (com.android.everytalk.data.computer.ComputerSetupStage) -> Unit = {},
    ): Computer {
        val provisioned = repository.provisionContainer(computerId, onProgress)
        previewManager.reconcileComputer(computerId)
        return provisioned
    }

    suspend fun cancelComputerOperation(computerId: String) = repository.cancelComputerOperation(computerId)

    fun observeWorkspaces(computerId: String): Flow<List<ComputerWorkspace>> =
        repository.observeWorkspaces(computerId)

    fun observeActiveTaskCount(computerId: String): Flow<Int> =
        repository.observeActiveTaskCount(computerId)

    suspend fun getWorkspaces(computerId: String): List<ComputerWorkspace> =
        repository.getWorkspaces(computerId)

    fun observePreviews(workspaceId: String): Flow<List<ComputerPreview>> =
        repository.observePreviews(workspaceId)

    fun observeWorkspaceSecrets(workspaceId: String): Flow<List<ComputerWorkspaceSecret>> =
        secretManager.observe(workspaceId)

    fun observeAuditEvents(computerId: String): Flow<List<ComputerAuditEvent>> =
        repository.observeAuditEvents(computerId)

    suspend fun saveWorkspaceSecret(workspaceId: String, name: String, value: CharArray) =
        secretManager.save(workspaceId, name, value)

    suspend fun deleteWorkspaceSecret(workspaceId: String, name: String) =
        secretManager.delete(workspaceId, name)

    suspend fun openPrivatePreview(
        workspace: ComputerWorkspace,
        port: Int,
        protocol: String,
    ): ComputerPreviewOpenResult = previewManager.openPrivate(workspace.requestContext(), port, protocol)

    suspend fun openPublicPreview(
        workspace: ComputerWorkspace,
        port: Int,
        protocol: String,
        expiresInSeconds: Long?,
    ): ComputerPreviewOpenResult = previewManager.confirmPublic(
        ComputerPublicPreviewRequest(
            context = workspace.requestContext(),
            port = port,
            protocol = protocol,
            expiresInSeconds = expiresInSeconds,
            target = ComputerExecTarget.CONTAINER,
        ),
    )

    suspend fun stopPreview(previewId: String) = previewManager.stop(previewId)

    suspend fun probeReplacementHostKey(computerId: String): HostKeyProbeResult =
        repository.probeReplacementHostKey(computerId)

    suspend fun confirmReplacementHostKey(
        computerId: String,
        replacement: HostKeyProbeResult,
    ): Computer = repository.confirmReplacementHostKey(computerId, replacement)

    suspend fun setPrivateNetworkAllowed(computerId: String, allowed: Boolean): Computer =
        repository.setPrivateNetworkAllowed(computerId, allowed)

    suspend fun setPermissionMode(computerId: String, permissionMode: ComputerPermissionMode): Computer {
        clearComputerPreparations(computerId)
        return repository.setPermissionMode(computerId, permissionMode)
    }

    /** 删除单个 Workspace；Host Path 只有用户二次确认后才从 VPS 删除。 */
    suspend fun deleteWorkspace(workspaceId: String, deleteRemoteFiles: Boolean): ComputerWorkspace {
        val workspace = repository.getWorkspace(workspaceId)
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
        toolExecutor.cancelActiveExecutions(workspace.conversationId)
        toolExecutor.closeWorkspace(workspaceId)
        previewManager.stopByWorkspace(workspaceId)
        workspaceManager.deleteRemote(workspaceId, deleteRemoteFiles)
        secretManager.deleteAll(workspaceId)
        workspaceManager.deleteMapping(workspaceId)
        repository.recordAudit(workspace.computerId, "WORKSPACE_DELETED", "SUCCESS", null)
        return workspace
    }

    /** 删除会话时，先取消该会话所有远端任务并清理对应 Workspace。 */
    suspend fun deleteWorkspacesForConversation(
        conversationId: String,
        deleteRemoteFiles: Boolean = false,
    ): Boolean {
        if (conversationId.isBlank()) return true
        if (!toolExecutor.cancelActiveExecutions(conversationId)) return false
        val workspaces = repository.dao().getWorkspacesForConversation(conversationId)
        return try {
            workspaces.forEach { entity ->
                val workspace = entity.toModel()
                toolExecutor.closeWorkspace(workspace.id)
                previewManager.stopByWorkspace(workspace.id)
                workspaceManager.deleteRemote(workspace.id, deleteRemoteFiles)
                secretManager.deleteAll(workspace.id)
                workspaceManager.deleteMapping(workspace.id)
            }
            true
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppLogger.warn("ComputerRuntime", "删除会话 Workspace 失败：${error.message}")
            false
        }
    }

    suspend fun syncSkills(
        context: ComputerRequestContext,
        snapshot: com.android.everytalk.data.skill.SkillRequestSnapshot?,
        requiredSkillIds: List<String>,
    ): List<com.android.everytalk.data.skill.SyncedSkill> =
        skillServerSync.sync(context, snapshot, requiredSkillIds)

    suspend fun disconnect(computerId: String) {
        clearComputerPreparations(computerId)
        repository.disconnect(computerId)
    }

    /**
     * 删除 Computer 时始终销毁本地 Secret 和凭据。
     * 远端清理失败不会阻止本地删除，返回值用于提示残留公钥或 Workspace。
     */
    suspend fun deleteComputer(
        computerId: String,
        cleanupContainers: Boolean,
        deleteRemoteFiles: Boolean,
    ): ComputerDeleteResult {
        clearComputerPreparations(computerId)
        val workspaces = repository.getWorkspaces(computerId)
        var remoteWorkspaceCleanupSucceeded = true
        workspaces.forEach { workspace ->
            toolExecutor.closeWorkspace(workspace.id)
            runCatching { previewManager.stopByWorkspace(workspace.id) }
                .onFailure { remoteWorkspaceCleanupSucceeded = false }
            if (deleteRemoteFiles || (cleanupContainers && workspace.runMode == com.android.everytalk.data.computer.ComputerRunMode.CONTAINER)) {
                runCatching { workspaceManager.deleteRemote(workspace.id, deleteRemoteFiles) }
                    .onFailure { remoteWorkspaceCleanupSucceeded = false }
            }
            secretManager.deleteAll(workspace.id)
        }
        val deleted = repository.deleteComputer(computerId)
        return deleted.copy(remoteWorkspaceCleanupSucceeded = remoteWorkspaceCleanupSucceeded)
    }

    fun respondToPublicPreview(approved: Boolean) {
        publicPreviewDecision?.complete(approved)
    }

    /** 只完成当前确认请求；重复点击和已经过期的 UI 回调不会影响下一条命令。 */
    fun respondToHostCommand(requestId: String, approved: Boolean) {
        if (_pendingHostCommand.value?.requestId == requestId) hostCommandDecision?.complete(approved)
    }

    private suspend fun awaitPublicPreviewConfirmation(request: ComputerPublicPreviewRequest): Boolean =
        previewConfirmationMutex.withLock {
            val decision = CompletableDeferred<Boolean>()
            publicPreviewDecision = decision
            _pendingPublicPreview.value = request
            try {
                decision.await()
            } finally {
                _pendingPublicPreview.value = null
                publicPreviewDecision = null
            }
        }

    private suspend fun awaitHostCommandConfirmation(request: ComputerHostCommandConfirmationRequest): Boolean =
        hostCommandConfirmationMutex.withLock {
            val decision = CompletableDeferred<Boolean>()
            hostCommandDecision = decision
            _pendingHostCommand.value = request
            try {
                decision.await()
            } finally {
                _pendingHostCommand.value = null
                hostCommandDecision = null
            }
        }

    private fun buildEnvironmentPrompt(computer: Computer): String {
        val architecture = computer.capabilities?.architecture?.takeIf(String::isNotBlank) ?: "unknown"
        val permissionInstruction = when (computer.permissionMode) {
            ComputerPermissionMode.MANUAL ->
                "The app decides locally which host commands and public previews require user approval."
            ComputerPermissionMode.SMART ->
                "For every exec and open_port call, set ask_user_approval=true only when you judge that the user should approve it; otherwise set false and execute directly."
            ComputerPermissionMode.FULL ->
                "All valid Agent operations execute directly without a user approval prompt."
        }
        return "Agent server tools are enabled for this request. The Android app connects to the VPS directly over SSH. " +
            "For exec, use target=container by default for code, scripts, builds, tests, dependency installation, and file-producing work. " +
            "Use target=host only when inspecting or managing the VPS operating system, services, processes, ports, logs, packages, or deployed applications. " +
            "$permissionInstruction " +
            "All file, terminal, upload, and download tools operate in the persistent /workspace Container. " +
            "Architecture: $architecture. Available tools: ${ComputerToolNames.all.sorted().joinToString(", ")}."
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        prewarmJobs.values.forEach(Job::cancel)
        prewarmJobs.clear()
        clearRequestPreparations()
        publicPreviewDecision?.complete(false)
        hostCommandDecision?.complete(false)
        toolExecutor.close()
        previewManager.close()
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        connectionStopListener.close()
        repository.close()
    }

    private fun ComputerWorkspace.requestContext(): ComputerRequestContext = ComputerRequestContext(
        conversationId = conversationId,
        computerId = computerId,
        workspaceId = id,
    )

    private fun clearComputerPreparations(computerId: String) {
        val prefix = "$computerId\u0000"
        requestPreparations.entries.removeIf { (key, preparation) ->
            if (!key.startsWith(prefix)) return@removeIf false
            preparation.cancel()
            true
        }
    }

    private fun clearRequestPreparations() {
        requestPreparations.values.forEach { it.cancel() }
        requestPreparations.clear()
    }

    private fun preparationKey(computerId: String, workspaceId: String): String =
        "$computerId\u0000$workspaceId"
}
