package com.android.everytalk.statecontroller.viewmodel

import android.content.Context
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.computer.AddComputerRequest
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAttachmentBridge
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerErrorCodes
import com.android.everytalk.data.computer.ComputerPublicPreviewRequest
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerToolExecutor
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.ComputerWorkspaceManager
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.data.computer.PreparedComputerRequest
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 端 Computer 功能协调器。
 * 它只组合本地 Room、Keystore 与 SSH 组件，不创建网络中转服务，也不持有模型参数。
 */
class ComputerManager(
    context: Context,
    private val scope: CoroutineScope,
    attachmentsForConversation: (String) -> List<SelectedMediaItem.GenericFile>,
    onDownloaded: suspend (String, SelectedMediaItem.GenericFile) -> Unit,
) : AutoCloseable {
    private val repository = ComputerRepository(context.applicationContext)
    private val workspaceManager = ComputerWorkspaceManager(repository)
    private val attachmentBridge = ComputerAttachmentBridge(
        context = context.applicationContext,
        attachmentsForConversation = attachmentsForConversation,
        onDownloaded = onDownloaded,
    )
    private val previewConfirmationMutex = Mutex()
    private val _pendingPublicPreview = MutableStateFlow<ComputerPublicPreviewRequest?>(null)

    @Volatile
    private var publicPreviewDecision: CompletableDeferred<Boolean>? = null

    private val toolExecutor = ComputerToolExecutor(
        context = context.applicationContext,
        repository = repository,
        workspaceManager = workspaceManager,
        attachmentBridge = attachmentBridge,
        publicPreviewConfirmer = ::awaitPublicPreviewConfirmation,
    )
    private val closed = AtomicBoolean(false)

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

    init {
        scope.launch(Dispatchers.IO) {
            repository.recoverLocalState()
        }
    }

    /** 当前四类文本 Provider 都走应用内置 Tool Loop；图像、音频和视频配置不开放 Agent。 */
    fun supportsToolCalls(config: ApiConfig?): Boolean = config != null &&
        config.model.isNotBlank() &&
        config.modalityType in setOf(ModalityType.TEXT, ModalityType.MULTIMODAL)

    /**
     * 在模型请求启动前冻结 Computer 与 Workspace。
     * Agent 已关闭时返回 null；已开启却不可用时抛出明确错误，禁止静默移除工具。
     */
    suspend fun prepareRequest(conversationId: String, agentEnabled: Boolean): PreparedComputerRequest? {
        if (!agentEnabled) return null
        val computer = repository.getSelectedComputer(conversationId) ?: throw ComputerException(
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
        val workspace = workspaceManager.getOrCreate(computer.id, conversationId)
        val requestContext = ComputerRequestContext(
            conversationId = conversationId,
            computerId = computer.id,
            workspaceId = workspace.id,
        )
        return PreparedComputerRequest(
            context = requestContext,
            environmentPrompt = buildEnvironmentPrompt(computer),
        )
    }

    /** Agent 开启时先准备目标 Workspace，成功后才覆盖当前选择。 */
    suspend fun selectComputer(conversationId: String, computerId: String, agentEnabled: Boolean) {
        if (agentEnabled) workspaceManager.getOrCreate(computerId, conversationId)
        repository.selectComputer(conversationId, computerId)
    }

    suspend fun execute(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        toolCallId: String,
        requestContext: ComputerRequestContext,
    ): kotlinx.serialization.json.JsonElement = toolExecutor.execute(
        toolName = toolName,
        arguments = arguments,
        toolCallId = toolCallId,
        requestContext = requestContext,
    )

    suspend fun migrateConversationId(sourceConversationId: String, targetConversationId: String) {
        repository.migrateConversationId(sourceConversationId, targetConversationId)
    }

    suspend fun probeHostKey(request: AddComputerRequest): HostKeyProbeResult = repository.probeHostKey(request)

    suspend fun addConfirmedComputer(
        request: AddComputerRequest,
        confirmedHostKey: HostKeyProbeResult,
    ): Computer = repository.addConfirmedComputer(request, confirmedHostKey)

    suspend fun refreshComputer(computerId: String): Computer = repository.refreshComputer(computerId)

    suspend fun provisionContainer(computerId: String, sudoPassword: CharArray?): Computer =
        repository.provisionContainer(computerId, sudoPassword)

    suspend fun disconnect(computerId: String) = repository.disconnect(computerId)

    suspend fun deleteComputer(computerId: String) = repository.deleteComputer(computerId)

    fun respondToPublicPreview(approved: Boolean) {
        publicPreviewDecision?.complete(approved)
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

    private fun buildEnvironmentPrompt(computer: Computer): String {
        val mode = computer.runMode.name.lowercase()
        val architecture = computer.capabilities?.architecture?.takeIf(String::isNotBlank) ?: "unknown"
        return "Agent server tools are enabled for this request. Work inside /workspace. " +
            "Runtime mode: $mode. Architecture: $architecture. Available tools: " +
            ComputerToolNames.all.sorted().joinToString(", ") + "."
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publicPreviewDecision?.complete(false)
        toolExecutor.close()
        repository.close()
    }
}
