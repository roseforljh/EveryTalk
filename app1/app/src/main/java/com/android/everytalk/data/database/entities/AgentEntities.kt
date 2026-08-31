package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["visibleAssistantMessageId"], unique = true),
        Index(value = ["status", "updatedAt"]),
    ],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val userMessageId: String,
    val visibleAssistantMessageId: String,
    val configIdSnapshot: String?,
    val requestSnapshotJson: String?,
    val status: String,
    val currentRequestOrdinal: Int,
    val terminalReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Agent 恢复快照的分块存储。
 *
 * Android CursorWindow 无法读取数 MB 的单行数据，因此每块必须保持足够小。
 * 主表只保存 Run 状态，列表查询不会再顺带读取完整上下文。
 */
@Entity(
    tableName = "agent_run_snapshot_chunks",
    primaryKeys = ["runId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class AgentRunSnapshotChunkEntity(
    val runId: String,
    val chunkIndex: Int,
    val payload: String,
)

@Entity(
    tableName = "agent_entries",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["runId", "sequence"], unique = true),
        Index("requestId"),
        Index("toolCallId"),
    ],
)
data class AgentEntryEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val sequence: Long,
    val kind: String,
    val requestId: String?,
    val toolCallId: String?,
    val payloadJson: String,
    val status: String,
    val createdAt: Long,
    val finalizedAt: Long?,
)

/**
 * 当前 AgentRun 的 steering 队列。
 *
 * UI 只负责原子登记；AgentLoop 在模型轮次或工具批次完成后的安全边界消费。
 */
@Entity(
    tableName = "agent_steering_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["runId", "status", "createdAt"])],
)
data class AgentSteeringMessageEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val content: String,
    val status: String,
    val createdAt: Long,
    val consumedAt: Long? = null,
)

@Entity(
    tableName = "agent_requests",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["runId", "ordinal"], unique = true),
        Index(value = ["runId", "status"]),
        Index("retryOfRequestId"),
    ],
)
data class AgentRequestEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val ordinal: Int,
    val purpose: String,
    val modelTurnOrdinal: Int?,
    val attempt: Int,
    val retryOfRequestId: String?,
    val provider: String,
    val endpoint: String?,
    val model: String,
    val payloadFingerprint: String,
    val status: String,
    val finishReason: String?,
    val startedAt: Long?,
    val firstEventAt: Long?,
    val finishedAt: Long?,
)

@Entity(
    tableName = "agent_request_usage",
    foreignKeys = [
        ForeignKey(
            entity = AgentRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["requestId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class AgentRequestUsageEntity(
    @PrimaryKey val requestId: String,
    val promptTokens: Long?,
    val freshInputTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val requestTotalTokens: Long?,
    val providerTotalTokens: Long?,
    val source: String,
    val quality: String,
    val rawUsageJson: String?,
)

@Entity(
    tableName = "agent_context_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = AgentRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["requestId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("compactionId")],
)
data class AgentContextSnapshotEntity(
    @PrimaryKey val requestId: String,
    val systemPromptTokens: Long,
    val conversationTextTokens: Long,
    val mediaTokens: Long,
    val toolSchemaTokens: Long,
    val protocolOverheadTokens: Long,
    val estimatedPromptTokens: Long,
    val reservedOutputTokens: Long,
    val contextWindowTokens: Long,
    val activeContextTokens: Long,
    val calibrationTokens: Long,
    val compactionId: String?,
    val transcriptFingerprint: String,
    val source: String,
)

@Entity(
    tableName = "agent_compactions",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["sessionId", "createdAt"])],
)
data class AgentCompactionEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val configIdSnapshot: String?,
    val summary: String,
    val summarizedThroughItemId: String,
    val prefixFingerprint: String,
    val retainedTailJson: String,
    val tokensBefore: Long,
    val estimatedTokensAfter: Long,
    val summaryRequestId: String?,
    val status: String,
    val createdAt: Long,
)

@Entity(
    tableName = "provider_continuation_states",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["sessionId", "configId", "protocol", "provider", "endpoint", "model"], unique = true),
    ],
)
data class ProviderContinuationStateEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val configId: String,
    val protocol: String,
    val provider: String,
    val endpoint: String,
    val model: String,
    val systemPromptFingerprint: String,
    val toolSchemaFingerprint: String,
    val summarizedThroughItemId: String?,
    val opaqueStateJson: String,
    val updatedAt: Long,
)
