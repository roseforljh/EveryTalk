package com.android.everytalk.data.database.entities

import androidx.room.Entity
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

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String, // Stable conversation ID
    val creationTimestamp: Long,
    val lastModifiedTimestamp: Long,
    val isImageGeneration: Boolean,
    val title: String? = null // Optional title caching
)

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
    indices = [Index(value = ["sessionId"])]
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
    )
}

fun Message.toEntity(sessionId: String): MessageEntity {
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
    )
}
