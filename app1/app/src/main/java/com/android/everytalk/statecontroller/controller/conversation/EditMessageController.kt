package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.ConversationNameHelper
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 回填请求只由对应页面消费一次，重复编辑同一条消息也会重新聚焦输入框。 */
data class MessageEditDraft(val message: Message, val isImageGeneration: Boolean)

/** 一次回退编辑的原始快照；原消息对象保留 ID、附件、工具记录及后续 AI 回答。 */
data class MessageEditSession(
    val conversationId: String,
    val isImageGeneration: Boolean,
    val draft: Message,
    val originalMessages: List<Message>,
    val originalHistory: List<Message>,
    val retainedMessageIds: List<String>,
    val sourceConversationId: String = conversationId,
) {
    /** 自动保存可能把临时 ID 迁移为历史 ID；两者都属于本次编辑，其他会话不能复原这份快照。 */
    fun belongsTo(currentConversationId: String, imageMode: Boolean): Boolean =
        isImageGeneration == imageMode &&
            (currentConversationId == sourceConversationId || currentConversationId == conversationId)

    /** 比较当前内容，不记录“是否曾改过”：改回原文后仍可复原，附件顺序也参与比较。 */
    fun matchesDraft(text: String, attachments: List<SelectedMediaItem>, contentParts: List<MessageContentPart>? = null): Boolean {
        val originalParts = draft.contentParts.ifEmpty {
            listOf(MessageContentPart.Text(draft.text)).filterNot { it.text.isEmpty() }
        }
        // 文本编辑器按 contentParts 回填，发送时 draft.text 却可能已 trim；比较回填原文，保留真实空白改动。
        val originalText = if (contentParts == null) draft.text else originalParts.joinToString("") {
            when (it) {
                is MessageContentPart.Text -> it.text
                is MessageContentPart.SkillReference -> "‹${it.reference.displayName}›"
            }
        }
        // Compose 的 SnapshotStateList 不提供普通 List 的结构相等语义，先取快照再比较内容及顺序。
        return text == originalText && attachments.toList() == draft.attachments.toList() &&
            (contentParts == null || contentParts == originalParts)
    }
}

/** 保留目标之前的消息；同一 Agent 回复跨越追加消息时，同时裁剪其可见执行记录。 */
internal fun messagesBeforeEdit(messages: List<Message>, index: Int): List<Message> {
    val removedIds = messages.drop(index).mapTo(hashSetOf()) { it.id }
    return messages.take(index).map { message ->
        val boundary = message.executionTrace.indexOfFirst {
            it is ExecutionTraceEvent.UserMessageBoundary && it.messageId in removedIds
        }
        if (boundary < 0) message else {
            val trace = message.executionTrace.take(boundary)
            message.copy(
                // 裁剪正文也属于内容变更，必须替换身份，不能改写原正式消息。
                id = UUID.randomUUID().toString(),
                conversationId = ConversationNameHelper.resolveStableId(messages),
                text = trace.filterIsInstance<ExecutionTraceEvent.Content>().joinToString("") { it.text },
                reasoning = trace.filterIsInstance<ExecutionTraceEvent.Reasoning>().joinToString("") { it.text },
                executionTrace = trace,
                executionSteps = trace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step },
                parts = emptyList(),
                contextCompressionState = null,
                contextUsageSnapshot = null,
            )
        }
    }
}

/**
 * 复原快照只给被撤回的消息分配新身份；未受影响的前缀仍使用原身份。
 * 本地工具边界与上下文引用同步映射，避免新气泡继续引用已删除消息。
 */
internal fun messagesForEditRestore(session: MessageEditSession): List<Message> {
    val retained = session.retainedMessageIds.toSet()
    val newIds = session.originalMessages.filter { it.id !in retained }.associate { it.id to UUID.randomUUID().toString() }
    return session.originalMessages.map { message ->
        val id = newIds[message.id] ?: message.id
        message.copy(
            id = id, conversationId = session.conversationId, isStreamingDraft = false,
            contextUsageSnapshot = message.contextUsageSnapshot?.copy(messageId = id),
            contextCompressionState = message.contextCompressionState?.copy(
                summarizedThroughMessageId = newIds[message.contextCompressionState.summarizedThroughMessageId] ?: message.contextCompressionState.summarizedThroughMessageId,
                openAiResponsesThroughMessageId = newIds[message.contextCompressionState.openAiResponsesThroughMessageId] ?: message.contextCompressionState.openAiResponsesThroughMessageId,
                anthropicThroughMessageId = newIds[message.contextCompressionState.anthropicThroughMessageId] ?: message.contextCompressionState.anthropicThroughMessageId,
            ),
            executionTrace = message.executionTrace.map { event ->
                if (event is ExecutionTraceEvent.UserMessageBoundary) event.copy(messageId = newIds[event.messageId] ?: event.messageId) else event
            },
        )
    }
}

/**
 * 用户消息编辑统一走“回退并回填输入框”。先保存截断后的历史，成功后才更新界面。
 * 附件沿用原始引用，不删除文件，也不在编辑动作中发起模型请求。
 */
class EditMessageController(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope,
    private val messagesMutex: Mutex,
    private val clearMessageCache: (String, Boolean) -> Unit,
    private val showSnackbar: (String) -> Unit,
    private val canEdit: () -> Boolean = { true },
) {
    private val _restoredDraft = MutableStateFlow<MessageEditDraft?>(null)
    val restoredDraft = _restoredDraft.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()
    val editSession = stateHolder.messageEditSession.asStateFlow()

    fun finishEditing(session: MessageEditSession) {
        stateHolder.messageEditSession.compareAndSet(session, null)
    }

    /** 复原成功才清空输入；写库失败保留快照和输入，用户可重试，不发送模型请求。 */
    fun restoreOriginalMessages(text: String, attachments: List<SelectedMediaItem>, contentParts: List<MessageContentPart>?, isImageGeneration: Boolean) {
        val session = editSession.value ?: return
        val conversation = if (isImageGeneration) stateHolder._currentImageGenerationConversationId else stateHolder._currentConversationId
        if (_restoring.value || !session.belongsTo(conversation.value, isImageGeneration) ||
            !session.matchesDraft(text, attachments, contentParts) || !canEdit() ||
            stateHolder._isTextApiCalling.value || stateHolder._isImageApiCalling.value || stateHolder._isRemoteCancellationPending.value
        ) return
        _restoring.value = true
        scope.launch {
            try {
                messagesMutex.withLock {
                    val messages = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    if (editSession.value != session || !session.belongsTo(conversation.value, isImageGeneration) ||
                        messages.map { it.id } != session.retainedMessageIds
                    ) return@withLock
                    val restored = messagesForEditRestore(session)
                    val restoredHistory = ConversationNameHelper.preserveStoredConversationTitle(session.originalHistory, restored)
                    historyManager.rewindConversation(session.conversationId, restoredHistory, isImageGeneration, session.sourceConversationId)
                    if (!session.belongsTo(conversation.value, isImageGeneration)) return@withLock
                    messages.forEach { clearMessageCache(it.id, isImageGeneration) }
                    messages.clear()
                    messages.addAll(restored)
                    stateHolder._text.value = ""
                    stateHolder.selectedMediaItems.clear()
                    _restoredDraft.value = null
                    finishEditing(session)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showSnackbar("复原保存失败，原对话快照已保留，请重试")
            } finally {
                _restoring.value = false
            }
        }
    }

    fun consumeRestoredDraft(draft: MessageEditDraft) {
        _restoredDraft.compareAndSet(draft, null)
    }

    fun requestEditMessage(message: Message, isImageGeneration: Boolean = false) {
        if (message.sender != Sender.User || _restoring.value) return
        if (stateHolder._isTextApiCalling.value || stateHolder._isImageApiCalling.value ||
            stateHolder._isRemoteCancellationPending.value || !canEdit()
        ) {
            showSnackbar("请先停止当前回复并处理待发送消息，再编辑历史消息")
            return
        }
        if (stateHolder._text.value.isNotEmpty() || stateHolder.selectedMediaItems.isNotEmpty()) {
            showSnackbar("请先处理输入框里的草稿，再编辑历史消息")
            return
        }
        val conversation = if (isImageGeneration) stateHolder._currentImageGenerationConversationId else stateHolder._currentConversationId
        val conversationId = conversation.value
        _restoring.value = true
        scope.launch {
            try {
                messagesMutex.withLock {
                    val messages = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                    val index = messages.indexOfFirst { it.id == message.id && it.sender == Sender.User }
                    if (index < 0 || conversation.value != conversationId) return@withLock
                    val original = messages[index]
                    val originalMessages = messages.toList()
                    val historyId = ConversationNameHelper.resolveStableId(originalMessages) ?: conversationId
                    val retained = messagesBeforeEdit(originalMessages, index)
                    val history = if (isImageGeneration) stateHolder._imageGenerationHistoricalConversations else stateHolder._historicalConversations
                    val storedHistory = history.value.firstOrNull { ConversationNameHelper.resolveStableId(it) == historyId }
                    val session = MessageEditSession(historyId, isImageGeneration, original, originalMessages,
                        ConversationNameHelper.preserveStoredConversationTitle(storedHistory.orEmpty(), originalMessages), retained.map { it.id }, conversationId)
                    // 在回退落库前保护整段对话的媒体，避免孤立附件清理误删仍可复原的文件。
                    stateHolder.messageEditSession.value = session
                    try {
                        historyManager.rewindConversation(historyId, retained, isImageGeneration, conversationId)
                    } catch (exception: Exception) {
                        finishEditing(session)
                        throw exception
                    }
                    // 保存期间切换会话时只完成原会话的持久化，不覆盖另一个会话的输入和消息。
                    if (conversation.value !in setOf(conversationId, historyId) || messages.none { it.id == original.id }) return@withLock
                    messages.forEach { clearMessageCache(it.id, isImageGeneration) }
                    messages.clear()
                    messages.addAll(retained)
                    stateHolder.selectedMediaItems.addAll(original.attachments)
                    stateHolder._text.value = original.text
                    _restoredDraft.value = MessageEditDraft(original, isImageGeneration)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showSnackbar("回退保存失败，原消息已保留，请重试")
            } finally {
                _restoring.value = false
            }
        }
    }
}
