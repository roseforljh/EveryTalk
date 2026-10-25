package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.database.Converters
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.ui.components.MarkdownPart
import java.security.MessageDigest

const val HISTORY_PREVIEW_OUTPUT_TYPE = "history_preview"

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["isImageGeneration", "lastModifiedTimestamp"]), Index(value = ["uuid"], unique = true)],
)
data class ChatSessionEntity(
    @PrimaryKey
    val id: String, // Stable conversation ID
    val creationTimestamp: Long,
    val lastModifiedTimestamp: Long,
    val isImageGeneration: Boolean,
    val title: String? = null, // Optional title caching
    @ColumnInfo(defaultValue = "''") val uuid: String = localSyncUuid("chat_sessions", id),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = lastModifiedTimestamp,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "sync_status", defaultValue = "'LOCAL_ONLY'") val syncStatus: String =
        if (isSyncableTextSession(id, isImageGeneration)) SyncStatus.PENDING else SyncStatus.LOCAL_ONLY,
) {
    /** 同步合同中的毫秒时间取自同一修改字段，避免另存一份后发生分歧。 */
    @get:androidx.room.Ignore
    val clientModifiedAt: Long get() = updatedAt
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId", "timestamp"]), Index(value = ["uuid"], unique = true)]
)

data class MessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String, // Foreign Key to ChatSessionEntity
    val text: String,
    val contentParts: List<MessageContentPart> = emptyList(),
    val sender: Sender,
    val reasoning: String?,
    val contentStarted: Boolean,
    val isError: Boolean,
    val name: String?,
    val timestamp: Long,
    val isPlaceholderName: Boolean,
    val webSearchResults: List<WebSearchResult>?,
    val currentWebSearchStage: String?,
    val imageUrls: List<String>?,
    val attachments: List<SelectedMediaItem>,
    val outputType: String,
    val parts: List<MarkdownPart>,
    val executionStatus: String?,
    val executionSteps: List<ExecutionStep>,
    val executionTrace: List<ExecutionTraceEvent>,
    val executionFinishedAt: Long? = null,
    val enabledToolIds: List<String>,
    val computerIdSnapshot: String? = null,
    val workspaceIdSnapshot: String? = null,
    val modelName: String? = null,
    val providerName: String? = null,
    val tokenUsage: TokenUsage? = null,
    val contextUsageSnapshot: ContextUsageSnapshot? = null,
    val contextCompressionState: ContextCompressionState? = null,
    /**
     * 当前消息所有持久化字段的稳定摘要。
     * 保存会话时只查询这个小字段，避免为了判断消息是否变化而读取并解析整行 JSON。
     */
    val storageFingerprint: String,
    @ColumnInfo(defaultValue = "''") val uuid: String = localSyncUuid("messages", id),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = timestamp,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    // 由 DAO 在正式插入时标记 PENDING；流式草稿保持 LOCAL_ONLY，不能提前上传。
    @ColumnInfo(name = "sync_status", defaultValue = "'LOCAL_ONLY'") val syncStatus: String = SyncStatus.LOCAL_ONLY,
    /** 独立于当前状态：删除待同步时仍能知道服务器曾接收过此消息。 */
    @ColumnInfo(name = "ever_synced", defaultValue = "0") val everSynced: Boolean = false,
    @ColumnInfo(name = "is_incomplete", defaultValue = "0") val isIncomplete: Boolean = false,
)

/** 本地流式草稿沿用消息字段与转换器，避免维护第二份序列化格式；永不参与同步。 */
@Entity(
    tableName = "message_drafts", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "timestamp"])],
)
data class MessageDraftEntity(@Embedded val message: MessageEntity)

/**
 * AI 正在回答时暂存的用户消息。
 *
 * Pending 在真正派发前不会进入 messages 表。queuePosition 独立于创建时间，
 * 因此编辑能保留原位置，“立即发送”也只需安全地提升排序。
 */
@Entity(
    tableName = "pending_messages",
    indices = [Index(value = ["conversationId", "status", "queuePosition"])],
)
data class PendingMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val content: String,
    val composerText: String,
    val contentParts: List<MessageContentPart> = emptyList(),
    val attachments: List<SelectedMediaItem> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val queuePosition: Long,
)

/** 保存事务判断增量变化时使用的轻量投影。 */
data class MessageStorageState(
    val id: String,
    val storageFingerprint: String,
    val uuid: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "ever_synced") val everSynced: Boolean,
)

/**
 * 同步读取消息时使用的轻量投影。
 *
 * messages 表还保存搜索结果、附件、工具步骤和压缩状态等本地大字段。
 * 同步白名单不需要这些字段，直接 SELECT * 会把它们全部装进 CursorWindow，
 * 大字段增加读取内存和单行溢出风险；窗口填满日志本身不代表漏行。
 * 这里保留网络白名单和版本判断所需的列，明确把本地大字段隔离在同步查询之外。
 */
data class SyncMessageRow(
    val id: String,
    val sessionId: String,
    val text: String,
    val contentPartsJson: String,
    val sender: String,
    val reasoning: String?,
    val contentStarted: Boolean,
    val isError: Boolean,
    val name: String?,
    val timestamp: Long,
    val outputType: String,
    val executionTraceJson: String,
    val executionFinishedAt: Long?,
    val enabledToolIdsJson: String,
    val modelName: String?,
    val providerName: String?,
    val tokenUsageJson: String?,
    val contextUsageSnapshotJson: String?,
    val uuid: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "ever_synced") val everSynced: Boolean,
    @ColumnInfo(name = "is_incomplete") val isIncomplete: Boolean,
)

/** 把同步投影恢复成仅含同步字段的实体，禁止把本地大字段带入网络映射。 */
fun SyncMessageRow.toSyncEntity(converters: Converters = Converters()): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    text = text,
    contentParts = converters.toMessageContentParts(contentPartsJson),
    sender = converters.toSender(sender),
    reasoning = reasoning,
    contentStarted = contentStarted,
    isError = isError,
    name = name,
    timestamp = timestamp,
    isPlaceholderName = false,
    webSearchResults = null,
    currentWebSearchStage = null,
    imageUrls = null,
    attachments = emptyList(),
    outputType = outputType,
    parts = emptyList(),
    executionStatus = null,
    executionSteps = emptyList(),
    executionTrace = converters.toExecutionTrace(executionTraceJson),
    executionFinishedAt = executionFinishedAt,
    enabledToolIds = converters.toStringList(enabledToolIdsJson),
    modelName = modelName,
    providerName = providerName,
    tokenUsage = converters.toTokenUsage(tokenUsageJson),
    contextUsageSnapshot = converters.toContextUsageSnapshot(contextUsageSnapshotJson),
    contextCompressionState = null,
    storageFingerprint = "",
    uuid = uuid,
    updatedAt = updatedAt,
    deleted = deleted,
    syncStatus = syncStatus,
    everSynced = everSynced,
    isIncomplete = isIncomplete,
)

/** AI 历史检索只取正文片段及来源，不加载附件、思考内容、工具轨迹或整条消息。 */
data class ConversationHistoryRow(
    val conversationId: String,
    val title: String,
    val messageId: String,
    val sender: String,
    val timestamp: Long,
    val text: String,
    val snippetOffset: Int,
    val totalCharacters: Int,
)

/**
 * 启动阶段只读取会话列表和搜索真正需要的标量字段。
 * 大型 JSON 字段在用户打开会话时再由完整查询读取。
 */
data class LightweightMessageRow(
    val id: String,
    val sessionId: String,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val isPlaceholderName: Boolean,
)

fun LightweightMessageRow.toPreviewMessage(converters: Converters): Message = Message(
    id = id,
    text = text,
    sender = converters.toSender(sender),
    timestamp = timestamp,
    isPlaceholderName = isPlaceholderName,
    outputType = HISTORY_PREVIEW_OUTPUT_TYPE,
    conversationId = sessionId,
)

/**
 * 批量加载历史时使用原始 JSON 字段，避免单条坏数据让整批 Room 映射失败。
 */
data class RawMessageRow(
    val id: String,
    val sessionId: String,
    val text: String,
    val contentPartsJson: String,
    val sender: String,
    val reasoning: String?,
    val contentStarted: Boolean,
    val isError: Boolean,
    val name: String?,
    val timestamp: Long,
    val isPlaceholderName: Boolean,
    val webSearchResultsJson: String?,
    val currentWebSearchStage: String?,
    val imageUrlsJson: String?,
    val attachmentsJson: String,
    val outputType: String,
    val partsJson: String,
    val executionStatus: String?,
    val executionStepsJson: String,
    val executionTraceJson: String,
    val executionFinishedAt: Long?,
    val enabledToolIdsJson: String,
    val computerIdSnapshot: String?,
    val workspaceIdSnapshot: String?,
    val modelName: String?,
    val providerName: String?,
    val tokenUsageJson: String?,
    val contextUsageSnapshotJson: String?,
    val contextCompressionStateJson: String?,
    @ColumnInfo(name = "is_incomplete") val isIncomplete: Boolean,
)

fun RawMessageRow.toMessage(converters: Converters): Message = Message(
    id = id,
    text = text,
    contentParts = converters.toMessageContentParts(contentPartsJson),
    sender = converters.toSender(sender),
    reasoning = reasoning,
    contentStarted = contentStarted,
    isError = isError,
    name = name,
    timestamp = timestamp,
    isPlaceholderName = isPlaceholderName,
    webSearchResults = converters.toWebSearchResultList(webSearchResultsJson),
    currentWebSearchStage = currentWebSearchStage,
    imageUrls = converters.toStringList(imageUrlsJson),
    attachments = converters.toSelectedMediaItemList(attachmentsJson),
    outputType = outputType,
    parts = converters.toMarkdownPartList(partsJson),
    executionStatus = executionStatus,
    executionSteps = converters.toExecutionStepList(executionStepsJson),
    executionTrace = converters.toExecutionTrace(executionTraceJson),
    executionFinishedAt = executionFinishedAt,
    enabledToolIds = converters.toStringList(enabledToolIdsJson),
    computerIdSnapshot = computerIdSnapshot,
    workspaceIdSnapshot = workspaceIdSnapshot,
    modelName = modelName,
    providerName = providerName,
    tokenUsage = converters.toTokenUsage(tokenUsageJson),
    contextUsageSnapshot = converters.toContextUsageSnapshot(contextUsageSnapshotJson),
    contextCompressionState = converters.toContextCompressionState(contextCompressionStateJson),
    conversationId = sessionId,
    isIncomplete = isIncomplete,
)

fun MessageEntity.toMessage(): Message {
    return Message(
        id = id,
        text = text,
        contentParts = contentParts,
        sender = sender,
        reasoning = reasoning,
        contentStarted = contentStarted,
        isError = isError,
        name = name,
        timestamp = timestamp,
        isPlaceholderName = isPlaceholderName,
        webSearchResults = webSearchResults,
        currentWebSearchStage = currentWebSearchStage,
        imageUrls = imageUrls,
        attachments = attachments,
        outputType = outputType,
        parts = parts,
        executionStatus = executionStatus,
        executionSteps = executionSteps,
        executionTrace = executionTrace,
        executionFinishedAt = executionFinishedAt,
        enabledToolIds = enabledToolIds,
        computerIdSnapshot = computerIdSnapshot,
        workspaceIdSnapshot = workspaceIdSnapshot,
        modelName = modelName,
        providerName = providerName,
        tokenUsage = tokenUsage,
        contextUsageSnapshot = contextUsageSnapshot,
        contextCompressionState = contextCompressionState,
        conversationId = sessionId,
        isIncomplete = isIncomplete,
    )
}

fun Message.toEntity(sessionId: String, converters: Converters = Converters()): MessageEntity {
    return MessageEntity(
        id = id,
        sessionId = sessionId,
        text = text,
        contentParts = contentParts,
        sender = sender,
        reasoning = reasoning,
        contentStarted = contentStarted,
        isError = isError,
        name = name,
        timestamp = timestamp,
        isPlaceholderName = isPlaceholderName,
        webSearchResults = webSearchResults,
        currentWebSearchStage = currentWebSearchStage,
        imageUrls = imageUrls,
        attachments = attachments,
        outputType = outputType,
        parts = parts,
        executionStatus = executionStatus,
        executionSteps = executionSteps,
        executionTrace = executionTrace,
        executionFinishedAt = executionFinishedAt,
        enabledToolIds = enabledToolIds,
        computerIdSnapshot = computerIdSnapshot,
        workspaceIdSnapshot = workspaceIdSnapshot,
        modelName = modelName,
        providerName = providerName,
        tokenUsage = tokenUsage,
        contextUsageSnapshot = contextUsageSnapshot,
        contextCompressionState = contextCompressionState,
        storageFingerprint = storageFingerprint(converters),
        isIncomplete = isIncomplete,
    )
}

/**
 * 计算消息落库内容的稳定摘要。
 * 每个字段先写入长度，再分块写入 UTF-16 码元，避免拼出包含大段正文或附件的临时字符串。
 */
private fun Message.storageFingerprint(converters: Converters): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun update(value: String?) {
        if (value == null) {
            digest.update(byteArrayOf(-1, -1, -1, -1))
            return
        }
        val length = value.length
        digest.update((length ushr 24).toByte())
        digest.update((length ushr 16).toByte())
        digest.update((length ushr 8).toByte())
        digest.update(length.toByte())
        val buffer = ByteArray(8_192)
        var size = 0
        value.forEach { character ->
            val code = character.code
            buffer[size++] = (code ushr 8).toByte()
            buffer[size++] = code.toByte()
            if (size == buffer.size) {
                digest.update(buffer)
                size = 0
            }
        }
        if (size > 0) digest.update(buffer, 0, size)
    }

    update(text)
    update(converters.fromMessageContentParts(contentParts))
    update(sender.name)
    update(reasoning)
    update(contentStarted.toString())
    update(isError.toString())
    update(name)
    update(timestamp.toString())
    update(isPlaceholderName.toString())
    update(converters.fromWebSearchResultList(webSearchResults.orEmpty()))
    update(currentWebSearchStage)
    update(converters.fromStringList(imageUrls.orEmpty()))
    update(converters.fromSelectedMediaItemList(attachments))
    update(outputType)
    update(converters.fromMarkdownPartList(parts))
    update(executionStatus)
    update(converters.fromExecutionStepList(executionSteps))
    update(converters.fromExecutionTrace(executionTrace))
    update(executionFinishedAt?.toString())
    update(converters.fromStringList(enabledToolIds))
    update(computerIdSnapshot)
    update(workspaceIdSnapshot)
    update(modelName)
    update(providerName)
    update(converters.fromTokenUsage(tokenUsage))
    update(converters.fromContextUsageSnapshot(contextUsageSnapshot))
    update(converters.fromContextCompressionState(contextCompressionState))
    update(isIncomplete.toString())

    val bytes = digest.digest()
    val hex = "0123456789abcdef"
    return CharArray(bytes.size * 2).also { output ->
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = hex[value ushr 4]
            output[index * 2 + 1] = hex[value and 0x0f]
        }
    }.concatToString()
}
