package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.agent.AgentRunControlState
import com.android.everytalk.data.database.daos.ChatDao
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.models.SelectedMediaItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val PENDING_MESSAGE_STATUS_PENDING = "PENDING"
internal const val PENDING_MESSAGE_STATUS_EDITING = "EDITING"
internal const val PENDING_MESSAGE_STATUS_DISPATCHING = "DISPATCHING"

/** Composer 展示的 Agent 运行状态，暂停状态来自应用级 AgentRun gate。 */
sealed interface ChatRunState {
    data object Idle : ChatRunState
    data object Streaming : ChatRunState
    data object PauseRequested : ChatRunState
    data object Paused : ChatRunState
}

/** Composer 的业务模式，编辑 Pending 时保留原 ID、位置和完整附件引用。 */
sealed interface ComposerMode {
    data object Normal : ComposerMode

    data class EditingPending(
        val pendingId: String,
        val conversationId: String,
        val originalPosition: Long,
        val originalContent: String,
        val originalComposerText: String,
        val originalContentParts: List<MessageContentPart>,
        val originalAttachments: List<SelectedMediaItem>,
    ) : ComposerMode
}

/**
 * 统一管理 Pending 的持久化、编辑和安全边界派发。
 *
 * Compose 只提交用户动作。真正的状态竞争由这里和 Room 的条件更新共同处理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PendingMessageController(
    private val chatDao: ChatDao,
    private val scope: CoroutineScope,
    private val currentConversationId: StateFlow<String>,
    private val isApiCalling: StateFlow<Boolean>,
    private val isAbortInProgress: StateFlow<Boolean>,
    private val agentRunControlState: StateFlow<AgentRunControlState>,
    private val hasComposerContent: () -> Boolean,
    private val replaceComposer: (String, List<SelectedMediaItem>) -> Unit,
    private val persistAttachments: suspend (List<SelectedMediaItem>, String) -> List<SelectedMediaItem>?,
    private val resumeStreaming: () -> Unit,
    private val steerCurrentRun: suspend (PendingMessageEntity) -> Boolean,
    private val showMessage: (String) -> Unit,
    private val dispatch: (
        message: PendingMessageEntity,
        onAccepted: () -> Unit,
        onRejected: () -> Unit,
        onFinished: () -> Unit,
    ) -> Unit,
) {
    private val operationMutex = Mutex()
    private val submissionInProgress = AtomicBoolean(false)
    private val _composerMode = MutableStateFlow<ComposerMode>(ComposerMode.Normal)
    private val _isDispatchingPending = MutableStateFlow(false)
    private var activeDispatchId: String? = null
    private var blockedPendingId: String? = null

    val composerMode: StateFlow<ComposerMode> = _composerMode.asStateFlow()

    val pendingMessages: StateFlow<List<PendingMessageEntity>> = currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId.isBlank()) flowOf(emptyList())
            else chatDao.observePendingMessages(conversationId)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val visiblePendingMessages: StateFlow<List<PendingMessageEntity>> = combine(
        pendingMessages,
        composerMode,
    ) { messages, mode ->
        val editingId = (mode as? ComposerMode.EditingPending)?.pendingId
        messages.filter { it.status == PENDING_MESSAGE_STATUS_PENDING && it.id != editingId }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val runState: StateFlow<ChatRunState> = combine(
        isApiCalling,
        agentRunControlState,
        _isDispatchingPending,
    ) { running, controlState, dispatching ->
        when {
            controlState == AgentRunControlState.PAUSE_REQUESTED -> ChatRunState.PauseRequested
            controlState == AgentRunControlState.PAUSED -> ChatRunState.Paused
            running || dispatching -> ChatRunState.Streaming
            else -> ChatRunState.Idle
        }
    }.stateIn(scope, SharingStarted.Eagerly, ChatRunState.Idle)

    init {
        scope.launch(Dispatchers.IO) {
            chatDao.recoverPendingDispatches()
        }
        scope.launch {
            pendingMessages.collect { rows ->
                val persistedEditing = rows.firstOrNull { it.status == PENDING_MESSAGE_STATUS_EDITING }
                if (
                    persistedEditing != null &&
                    persistedEditing.conversationId == currentConversationId.value &&
                    _composerMode.value == ComposerMode.Normal &&
                    !hasComposerContent()
                ) {
                    _composerMode.value = persistedEditing.toEditingMode()
                    replaceComposer(persistedEditing.composerText, persistedEditing.attachments)
                }
            }
        }
        scope.launch {
            combine(pendingMessages, isApiCalling, isAbortInProgress, agentRunControlState, composerMode) { _, _, _, _, _ -> Unit }
                .collect { dispatchNextIfIdle() }
        }
        scope.launch {
            currentConversationId.drop(1).collect { conversationId ->
                val editing = _composerMode.value as? ComposerMode.EditingPending
                if (editing != null && editing.conversationId != conversationId) {
                    withContext(Dispatchers.IO) { chatDao.cancelPendingMessageEdit(editing.pendingId) }
                    _composerMode.value = ComposerMode.Normal
                    replaceComposer("", emptyList())
                }
            }
        }
    }

    /** 把当前草稿持久化到队尾。成功后才允许 UI 清空输入，避免落库失败丢草稿。 */
    fun enqueue(
        content: String,
        composerText: String,
        contentParts: List<MessageContentPart>,
        attachments: List<SelectedMediaItem>,
        onStored: () -> Unit,
    ) {
        if ((content.isBlank() && attachments.isEmpty()) || !submissionInProgress.compareAndSet(false, true)) return
        val conversationId = currentConversationId.value
        val now = System.currentTimeMillis()
        scope.launch {
            val stored = runCatching {
                val stableAttachments = persistAttachments(attachments, content)
                    ?: error("附件持久化失败")
                withContext(Dispatchers.IO) {
                    chatDao.enqueuePendingMessage(
                        PendingMessageEntity(
                            id = "pending_${UUID.randomUUID()}",
                            conversationId = conversationId,
                            content = content,
                            composerText = composerText,
                            contentParts = contentParts,
                            attachments = stableAttachments,
                            createdAt = now,
                            updatedAt = now,
                            status = PENDING_MESSAGE_STATUS_PENDING,
                            queuePosition = 0,
                        ),
                    )
                }
            }.isSuccess
            submissionInProgress.set(false)
            if (stored) onStored() else showMessage("暂存消息失败，请重试")
        }
    }

    /** 先从可发送队列摘出，再把原内容交给 Composer。 */
    fun beginEdit(id: String) {
        if (hasComposerContent()) {
            showMessage("请先处理当前输入内容")
            return
        }
        val pending = pendingMessages.value.firstOrNull {
            it.id == id && it.status == PENDING_MESSAGE_STATUS_PENDING
        } ?: return
        _composerMode.value = ComposerMode.EditingPending(
            pendingId = pending.id,
            conversationId = pending.conversationId,
            originalPosition = pending.queuePosition,
            originalContent = pending.content,
            originalComposerText = pending.composerText,
            originalContentParts = pending.contentParts,
            originalAttachments = pending.attachments,
        )
        replaceComposer(pending.composerText, pending.attachments)
        scope.launch {
            operationMutex.withLock {
                val detached = withContext(Dispatchers.IO) { chatDao.detachPendingMessageForEdit(id) }
                if (detached == 0 && (_composerMode.value as? ComposerMode.EditingPending)?.pendingId == id) {
                    _composerMode.value = ComposerMode.Normal
                    replaceComposer("", emptyList())
                    showMessage("这条消息已开始发送，无法编辑")
                }
            }
        }
    }

    /** 原位更新数据库行，不改变 ID、createdAt 和 queuePosition。 */
    fun commitEdit(
        content: String,
        composerText: String,
        contentParts: List<MessageContentPart>,
        attachments: List<SelectedMediaItem>,
        onStored: () -> Unit,
    ) {
        val editing = _composerMode.value as? ComposerMode.EditingPending ?: return
        if ((content.isBlank() && attachments.isEmpty()) || !submissionInProgress.compareAndSet(false, true)) return
        scope.launch {
            operationMutex.withLock {
                val stableAttachments = persistAttachments(attachments, content)
                if (stableAttachments == null) {
                    submissionInProgress.set(false)
                    return@withLock
                }
                val updated = withContext(Dispatchers.IO) {
                    chatDao.updatePendingMessage(
                        id = editing.pendingId,
                        content = content,
                        composerText = composerText,
                        contentParts = contentParts,
                        attachments = stableAttachments,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                submissionInProgress.set(false)
                if (updated == 1) {
                    blockedPendingId = blockedPendingId.takeUnless { it == editing.pendingId }
                    _composerMode.value = ComposerMode.Normal
                    onStored()
                } else {
                    showMessage("这条消息已开始发送，无法继续编辑")
                }
            }
        }
    }

    /** 取消编辑只恢复状态，原内容、原位置和原 ID 从未改变。 */
    fun cancelEdit() {
        val editing = _composerMode.value as? ComposerMode.EditingPending ?: return
        scope.launch {
            operationMutex.withLock {
                val restored = withContext(Dispatchers.IO) { chatDao.cancelPendingMessageEdit(editing.pendingId) }
                if (restored == 1 && (_composerMode.value as? ComposerMode.EditingPending)?.pendingId == editing.pendingId) {
                    _composerMode.value = ComposerMode.Normal
                    replaceComposer("", emptyList())
                }
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            operationMutex.withLock {
                val deleted = withContext(Dispatchers.IO) { chatDao.deletePendingMessage(id) }
                if (deleted == 0) {
                    showMessage("这条消息已开始发送，无法删除")
                    return@withLock
                }
                if ((_composerMode.value as? ComposerMode.EditingPending)?.pendingId == id) {
                    _composerMode.value = ComposerMode.Normal
                    replaceComposer("", emptyList())
                }
            }
        }
    }

    /** 原子抢占 Pending，优先 steering；没有可 steer 的 AgentRun 时直接复用普通发送链路。 */
    fun sendNow(id: String) {
        scope.launch {
            operationMutex.withLock {
                val pending = pendingMessages.value.firstOrNull {
                    it.id == id && it.status == PENDING_MESSAGE_STATUS_PENDING
                } ?: return@withLock
                val claimed = withContext(Dispatchers.IO) { chatDao.claimPendingMessage(id) }
                if (claimed != 1) return@withLock
                blockedPendingId = blockedPendingId.takeUnless { it == id }
                activeDispatchId = id
                _isDispatchingPending.value = true
                val steered = runCatching { steerCurrentRun(pending) }.getOrDefault(false)
                if (steered) {
                    withContext(Dispatchers.IO) { chatDao.finishPendingDispatch(id) }
                    activeDispatchId = null
                    _isDispatchingPending.value = false
                } else {
                    // 普通聊天流或空闲会话没有 AgentRun 可接收 steering，
                    // 直接走同一条 MessageSender 链路。该链路会在新请求开始前保存并结束旧流。
                    dispatchClaimed(pending)
                }
            }
        }
    }

    suspend fun migrateConversationId(oldId: String, newId: String) {
        chatDao.migratePendingConversationId(oldId, newId)
        val editing = _composerMode.value as? ComposerMode.EditingPending
        if (editing?.conversationId == oldId) {
            _composerMode.value = editing.copy(conversationId = newId)
        }
    }

    /** Room 条件更新先抢占状态，同一 Pending 因而只能被一个派发流程取得。 */
    private fun dispatchNextIfIdle() {
        scope.launch {
            operationMutex.withLock {
                if (
                    agentRunControlState.value != AgentRunControlState.RUNNING ||
                    isAbortInProgress.value ||
                    isApiCalling.value ||
                    activeDispatchId != null
                ) return@withLock
                val editingPosition = (_composerMode.value as? ComposerMode.EditingPending)?.originalPosition
                val next = nextDispatchablePending(
                    // 必须保留 EDITING / DISPATCHING 行。它们是尚未完成的逻辑槽位，
                    // 过滤掉以后会让后面的 Pending 越过队首。
                    messages = pendingMessages.value,
                    editingPosition = editingPosition,
                ) ?: return@withLock
                if (next.id == blockedPendingId) return@withLock
                val claimed = withContext(Dispatchers.IO) { chatDao.claimPendingMessage(next.id) }
                if (claimed != 1) return@withLock

                activeDispatchId = next.id
                _isDispatchingPending.value = true
                if (agentRunControlState.value == AgentRunControlState.PAUSED) resumeStreaming()
                dispatchClaimed(next)
            }
        }
    }

    /** 回调只允许清理自己登记的派发，防止“调整方案”抢占后旧请求误清新请求。 */
    private fun dispatchClaimed(message: PendingMessageEntity) {
        dispatch(
            message,
            {
                scope.launch(Dispatchers.IO) { chatDao.finishPendingDispatch(message.id) }
            },
            {
                scope.launch {
                    withContext(Dispatchers.IO) { chatDao.restorePendingDispatch(message.id) }
                    blockedPendingId = message.id
                    if (activeDispatchId == message.id) {
                        activeDispatchId = null
                        _isDispatchingPending.value = false
                        dispatchNextIfIdle()
                    }
                }
            },
            {
                scope.launch {
                    if (activeDispatchId == message.id) {
                        activeDispatchId = null
                        _isDispatchingPending.value = false
                        dispatchNextIfIdle()
                    }
                }
            },
        )
    }
}

/** EDITING 保留逻辑位置并形成屏障，后面的 Pending 不能越过它。 */
internal fun nextDispatchablePending(
    messages: List<PendingMessageEntity>,
    editingPosition: Long? = null,
): PendingMessageEntity? {
    val head = messages
        .asSequence()
        .filter {
            it.status == PENDING_MESSAGE_STATUS_PENDING ||
                it.status == PENDING_MESSAGE_STATUS_EDITING ||
                it.status == PENDING_MESSAGE_STATUS_DISPATCHING
        }
        .minWithOrNull(compareBy<PendingMessageEntity> { it.queuePosition }.thenBy { it.id })
        ?: return null

    // Composer 尚未恢复到数据库行时，用原位置保留同一个编辑屏障。
    if (editingPosition != null && head.queuePosition >= editingPosition) return null

    // 最早槽位只有 PENDING 才能派发。EDITING 和 DISPATCHING 都必须等它完成。
    return head.takeIf { it.status == PENDING_MESSAGE_STATUS_PENDING }
}

private fun PendingMessageEntity.toEditingMode() = ComposerMode.EditingPending(
    pendingId = id,
    conversationId = conversationId,
    originalPosition = queuePosition,
    originalContent = content,
    originalComposerText = composerText,
    originalContentParts = contentParts,
    originalAttachments = attachments,
)
