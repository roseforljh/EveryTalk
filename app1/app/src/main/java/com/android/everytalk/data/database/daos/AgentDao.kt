package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRequestUsageEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentRunSnapshotChunkEntity
import com.android.everytalk.data.database.entities.AgentSteeringMessageEntity
import com.android.everytalk.data.database.entities.AgentSuspensionEntity
import com.android.everytalk.data.database.entities.AgentCapabilityGrantEntity
import com.android.everytalk.data.database.entities.AgentResourceLeaseEntity
import com.android.everytalk.data.database.entities.AgentExecutionSlotEntity
import com.android.everytalk.data.database.entities.AgentStoredAuthorizationEntity
import com.android.everytalk.data.database.entities.AgentOAuthStateEntity
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

data class AgentTokenTotalsRow(
    val requestCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
)

@Dao
interface AgentDao {
    @Upsert suspend fun upsertRun(run: AgentRunEntity)
    @Upsert suspend fun upsertRunSnapshotChunks(chunks: List<AgentRunSnapshotChunkEntity>)
    @Upsert suspend fun upsertEntry(entry: AgentEntryEntity)
    @Upsert suspend fun upsertRequest(request: AgentRequestEntity)
    @Upsert suspend fun upsertUsage(usage: AgentRequestUsageEntity)
    @Upsert suspend fun upsertContextSnapshot(snapshot: AgentContextSnapshotEntity)
    @Upsert suspend fun upsertCompaction(compaction: AgentCompactionEntryEntity)
    @Upsert suspend fun upsertContinuationState(state: ProviderContinuationStateEntity)
    @Upsert suspend fun upsertAgentUserMessage(message: MessageEntity)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertSuspensionIfAbsent(suspension: AgentSuspensionEntity): Long
    @Upsert suspend fun upsertSuspension(suspension: AgentSuspensionEntity)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertCapabilityGrantIfAbsent(grant: AgentCapabilityGrantEntity): Long
    @Upsert suspend fun upsertResourceLease(lease: AgentResourceLeaseEntity)
    @Upsert suspend fun upsertExecutionSlot(slot: AgentExecutionSlotEntity)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertStoredAuthorizationIfAbsent(authorization: AgentStoredAuthorizationEntity): Long
    /** OAuth state 只能为 generation 匹配的非终态 Run 创建。 */
    @Query(
        """
        INSERT OR IGNORE INTO agent_oauth_states(
            stateHash, runId, runGeneration, capability, targetBinding, clientId,
            redirectUri, verifierReference, verifierGeneration, issuedAt, expiresAt,
            consumed, callbackAttemptId, rowVersion
        )
        SELECT :stateHash, :runId, :runGeneration, :capability, :targetBinding, :clientId,
               :redirectUri, :verifierReference, :verifierGeneration, :issuedAt, :expiresAt,
               0, NULL, 0
        FROM agent_runs
        WHERE id = :runId AND runGeneration = :runGeneration
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """,
    )
    suspend fun insertOAuthStateIfRunActive(
        stateHash: String,
        runId: String,
        runGeneration: Long,
        capability: String,
        targetBinding: String,
        clientId: String,
        redirectUri: String,
        verifierReference: String,
        verifierGeneration: Long,
        issuedAt: Long,
        expiresAt: Long,
    ): Long

    @Query("SELECT * FROM agent_oauth_states WHERE stateHash = :stateHash LIMIT 1")
    suspend fun getOAuthState(stateHash: String): AgentOAuthStateEntity?

    @Query("SELECT * FROM agent_stored_authorizations WHERE authorizationId = :id LIMIT 1")
    suspend fun getStoredAuthorization(id: String): AgentStoredAuthorizationEntity?

    /** Grant 创建也必须和 Run generation 校验放在同一事务，禁止终止竞态穿透。 */
    @Transaction
    suspend fun insertCapabilityGrantForActiveRun(grant: AgentCapabilityGrantEntity): Boolean {
        val run = getRun(grant.runId) ?: return false
        if (run.runGeneration != grant.runGeneration) return false
        if (run.status in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) return false
        return insertCapabilityGrantIfAbsent(grant) != -1L
    }

    @Query("UPDATE agent_stored_authorizations SET revoked = 1, generation = generation + 1 WHERE authorizationId = :id AND revoked = 0")
    suspend fun revokeStoredAuthorization(id: String): Int

    @Query("""
        UPDATE agent_oauth_states SET consumed = 1, callbackAttemptId = :attemptId,
            rowVersion = rowVersion + 1
        WHERE stateHash = :stateHash AND consumed = 0 AND expiresAt > :now
          AND runGeneration = :runGeneration AND clientId = :clientId
          AND redirectUri = :redirectUri AND verifierGeneration = :verifierGeneration
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE id = agent_oauth_states.runId
                AND runGeneration = :runGeneration
                AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          )
    """)
    suspend fun claimOAuthCallback(
        stateHash: String,
        runGeneration: Long,
        clientId: String,
        redirectUri: String,
        verifierGeneration: Long,
        attemptId: String,
        now: Long,
    ): Int

    @Query("DELETE FROM agent_oauth_states WHERE stateHash = :stateHash AND consumed = 1")
    suspend fun deleteConsumedOAuthState(stateHash: String): Int

    @Query("SELECT * FROM agent_execution_slots WHERE runId = :runId AND executionSlot = :slot LIMIT 1")
    suspend fun getExecutionSlot(runId: String, slot: String): AgentExecutionSlotEntity?

    @Query("SELECT * FROM agent_suspensions WHERE activeSuspensionIdempotencyKey = :key LIMIT 1")
    suspend fun findSuspensionByIdempotencyKey(key: String): AgentSuspensionEntity?

    @Query("SELECT * FROM agent_suspensions WHERE id = :id LIMIT 1")
    suspend fun getSuspension(id: String): AgentSuspensionEntity?

    @Query("SELECT * FROM agent_suspensions WHERE status IN (:statuses) ORDER BY updatedAt ASC")
    suspend fun getSuspensionsByStatuses(statuses: List<String>): List<AgentSuspensionEntity>

    @Query("""
        UPDATE agent_suspensions SET status = :nextStatus, rowVersion = rowVersion + 1,
            updatedAt = :updatedAt, resolutionNonceHash = :resolutionNonceHash
        WHERE id = :id AND status = :expectedStatus AND rowVersion = :expectedVersion
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE agent_runs.id = agent_suspensions.runId
                AND agent_runs.runGeneration = agent_suspensions.runGeneration
                AND agent_runs.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
          )
    """)
    suspend fun compareAndSetSuspension(
        id: String,
        expectedStatus: String,
        nextStatus: String,
        expectedVersion: Long,
        updatedAt: Long,
        resolutionNonceHash: String? = null,
    ): Int

    @Query("""
        UPDATE agent_suspensions SET status = :nextStatus, failureCode = :failureCode,
            rowVersion = rowVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus AND rowVersion = :expectedVersion
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE agent_runs.id = agent_suspensions.runId
                AND agent_runs.runGeneration = agent_suspensions.runGeneration
                AND agent_runs.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
          )
    """)
    suspend fun transitionSuspensionOutcome(
        id: String,
        expectedStatus: String,
        nextStatus: String,
        expectedVersion: Long,
        failureCode: String?,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE agent_suspensions SET status = 'RESOLUTION_RECEIVED', rowVersion = rowVersion + 1,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus AND rowVersion = :expectedVersion
          AND resolutionNonceHash = :resolutionNonceHash
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE agent_runs.id = agent_suspensions.runId
                AND agent_runs.runGeneration = agent_suspensions.runGeneration
                AND agent_runs.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
          )
    """)
    suspend fun resolveSuspension(
        id: String,
        expectedStatus: String,
        expectedVersion: Long,
        resolutionNonceHash: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE agent_suspensions SET requestSource = :requestSource,
            bindingGeneration = :bindingGeneration, rowVersion = rowVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND status = 'WAITING_USER' AND requestSource = 'MODEL_HINT'
          AND rowVersion = :expectedVersion
    """)
    suspend fun upgradeSuspensionEvidence(
        id: String,
        expectedVersion: Long,
        requestSource: String,
        bindingGeneration: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE agent_suspensions SET status = 'FULFILLING', fulfillmentAttemptId = :attemptId,
            rowVersion = rowVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND status = 'RESOLUTION_RECEIVED' AND rowVersion = :expectedVersion
          AND runGeneration = :runGeneration
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE agent_runs.id = agent_suspensions.runId
                AND agent_runs.runGeneration = :runGeneration
                AND agent_runs.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          )
    """)
    suspend fun claimSuspensionFulfillment(
        id: String,
        expectedVersion: Long,
        runGeneration: Long,
        attemptId: String,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE agent_suspensions SET status = 'RESUMING', resumeAttemptId = :attemptId,
            rowVersion = rowVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND status = 'READY_TO_RESUME' AND rowVersion = :expectedVersion
          AND runGeneration = :runGeneration
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE agent_runs.id = agent_suspensions.runId
                AND agent_runs.runGeneration = :runGeneration
                AND agent_runs.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          )
    """)
    suspend fun claimSuspensionResume(
        id: String,
        expectedVersion: Long,
        runGeneration: Long,
        attemptId: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE agent_capability_grants SET revoked = 1, status = 'REVOKED', rowVersion = rowVersion + 1 WHERE runId = :runId AND revoked = 0")
    suspend fun revokeGrantsForRun(runId: String): Int

    @Query("UPDATE agent_resource_leases SET revoked = 1 WHERE runId = :runId AND revoked = 0")
    suspend fun revokeResourceLeasesForRun(runId: String): Int

    @Query("""
        UPDATE agent_capability_grants SET status = 'RESERVED', grantUseAttemptId = :attemptId,
            usageCount = usageCount + 1, rowVersion = rowVersion + 1
        WHERE grantId = :grantId AND status = 'AVAILABLE' AND revoked = 0
          AND runId = :runId AND runGeneration = :runGeneration
          AND toolCallId = :toolCallId AND executionSlot = :executionSlot
          AND operation = :operation AND targetBinding = :targetBinding
          AND audience = :audience AND generation = :generation AND expiresAt > :now
          AND EXISTS (
              SELECT 1 FROM agent_runs
              WHERE id = :runId AND runGeneration = :runGeneration
                AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          )
          AND usageCount < maxUses
    """)
    suspend fun claimGrantUse(
        grantId: String,
        runId: String,
        runGeneration: Long,
        toolCallId: String,
        executionSlot: String,
        operation: String,
        targetBinding: String,
        audience: String,
        generation: Long,
        now: Long,
        attemptId: String,
    ): Int

    @Query("UPDATE agent_capability_grants SET status = 'CONSUMED', rowVersion = rowVersion + 1 WHERE grantId = :grantId AND status = 'RESERVED' AND grantUseAttemptId = :attemptId")
    suspend fun consumeGrant(grantId: String, attemptId: String): Int

    @Query("UPDATE agent_capability_grants SET revoked = 1, status = 'REVOKED', rowVersion = rowVersion + 1 WHERE grantId = :grantId AND revoked = 0")
    suspend fun revokeGrant(grantId: String): Int

    @Query(
        """
        INSERT OR IGNORE INTO agent_resource_leases(
            resourceRef, leaseOwner, leaseKind, leaseGeneration, runId, runGeneration,
            issuedAt, expiresAt, revoked
        )
        SELECT :resourceRef, :leaseOwner, :leaseKind, :leaseGeneration, :runId, :runGeneration,
               :issuedAt, :expiresAt, 0
        FROM agent_runs
        WHERE id = :runId AND runGeneration = :runGeneration
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """,
    )
    suspend fun insertResourceLeaseIfAbsent(
        resourceRef: String,
        leaseOwner: String,
        leaseKind: String,
        leaseGeneration: Long,
        runId: String,
        runGeneration: Long,
        issuedAt: Long,
        expiresAt: Long,
    ): Long

    /** 只有已撤销或已过期的旧 Lease 才能被更高 generation 接管。 */
    @Query(
        """
        UPDATE agent_resource_leases
        SET leaseOwner = :leaseOwner, leaseGeneration = :leaseGeneration,
            runId = :runId, runGeneration = :runGeneration,
            issuedAt = :issuedAt, expiresAt = :expiresAt, revoked = 0
        WHERE resourceRef = :resourceRef AND leaseKind = :leaseKind
          AND leaseGeneration < :leaseGeneration
          AND (revoked = 1 OR expiresAt <= :issuedAt)
          AND EXISTS (
              SELECT 1 FROM agent_runs WHERE id = :runId
                AND runGeneration = :runGeneration
                AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          )
        """,
    )
    suspend fun replaceReclaimableResourceLease(
        resourceRef: String,
        leaseOwner: String,
        leaseKind: String,
        leaseGeneration: Long,
        runId: String,
        runGeneration: Long,
        issuedAt: Long,
        expiresAt: Long,
    ): Int

    /** ResourceLease 的首次占用和过期接管必须放在同一数据库事务中。 */
    @Transaction
    suspend fun claimResourceLease(
        resourceRef: String,
        leaseOwner: String,
        leaseKind: String,
        leaseGeneration: Long,
        runId: String,
        runGeneration: Long,
        issuedAt: Long,
        expiresAt: Long,
    ): Boolean {
        if (
            replaceReclaimableResourceLease(
                resourceRef,
                leaseOwner,
                leaseKind,
                leaseGeneration,
                runId,
                runGeneration,
                issuedAt,
                expiresAt,
            ) == 1
        ) return true
        return insertResourceLeaseIfAbsent(
            resourceRef,
            leaseOwner,
            leaseKind,
            leaseGeneration,
            runId,
            runGeneration,
            issuedAt,
            expiresAt,
        ) != -1L
    }

    @Query("UPDATE agent_resource_leases SET revoked = 1 WHERE resourceRef = :resourceRef AND leaseKind = :leaseKind AND leaseOwner = :owner")
    suspend fun revokeResourceLease(resourceRef: String, leaseKind: String, owner: String): Int

    /** Suspension、Run 等待状态和事件必须在同一事务提交。事件沿用 agent_entries 账本。 */
    @Transaction
    suspend fun persistSuspensionAndPauseRun(
        suspension: AgentSuspensionEntity,
        waitingRun: AgentRunEntity,
        slot: AgentExecutionSlotEntity,
        event: AgentEntryEntity,
    ): AgentSuspensionEntity {
        val currentRun = getRun(suspension.runId) ?: error("Suspension run does not exist")
        check(currentRun.runGeneration == suspension.runGeneration) { "Suspension run generation is stale" }
        check(currentRun.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) {
            "Terminal AgentRun cannot be suspended"
        }
        val inserted = insertSuspensionIfAbsent(suspension)
        val existing = if (inserted == -1L) {
            findSuspensionByIdempotencyKey(suspension.activeSuspensionIdempotencyKey)
        } else {
            suspension
        } ?: error("Suspension insert lost without existing record")
        if (inserted != -1L) {
            upsertRun(
                currentRun.copy(
                    status = waitingRun.status,
                    loopState = waitingRun.loopState,
                    updatedAt = waitingRun.updatedAt,
                ),
            )
            upsertExecutionSlot(slot)
            upsertEntry(event)
        }
        return existing
    }

    @Query("SELECT * FROM agent_runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE visibleAssistantMessageId = :messageId LIMIT 1")
    suspend fun getRunByVisibleMessage(messageId: String): AgentRunEntity?

    /** 旧执行协程不得用整行 Upsert 覆盖已经终止或 generation 已变化的 Run。 */
    @Query(
        """
        UPDATE agent_runs
        SET status = :status, currentRequestOrdinal = :requestOrdinal,
            terminalReason = :terminalReason, updatedAt = :updatedAt
        WHERE id = :runId AND runGeneration = :expectedGeneration
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
        """,
    )
    suspend fun updateRunStatusIfActive(
        runId: String,
        expectedGeneration: Long,
        status: String,
        requestOrdinal: Int,
        terminalReason: String?,
        updatedAt: Long,
    ): Int

    /**
     * 用户点击停止时按可见消息原子封存 Run。
     * 条件更新避免 AgentLoop 恰好完成时又被旧的停止请求覆盖成 CANCELLED。
     */
    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt,
            runGeneration = runGeneration + 1
        WHERE visibleAssistantMessageId = :messageId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """
    )
    suspend fun cancelActiveRunByVisibleMessage(
        messageId: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt,
            runGeneration = runGeneration + 1
        WHERE id = :runId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """,
    )
    suspend fun cancelActiveRunById(runId: String, reason: String, updatedAt: Long): Int

    @Query("SELECT * FROM agent_runs WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getRunsForSession(sessionId: String): List<AgentRunEntity>

    @Query(
        """
        SELECT * FROM agent_runs
        WHERE sessionId = :sessionId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestSteerableRun(sessionId: String): AgentRunEntity?

    @Query(
        """
        INSERT OR IGNORE INTO agent_steering_messages(id, runId, content, payloadJson, status, createdAt, consumedAt)
        SELECT :id, :runId, :content, :payloadJson, 'PENDING', :createdAt, NULL
        FROM agent_runs
        WHERE id = :runId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """,
    )
    suspend fun enqueueSteeringIfRunActive(
        id: String,
        runId: String,
        content: String,
        payloadJson: String? = null,
        createdAt: Long,
    ): Long

    /** steering 队列和用户聊天记录同时落库，后台运行时也不会丢历史气泡。 */
    @Transaction
    suspend fun enqueueSteeringWithUserMessage(
        id: String,
        runId: String,
        content: String,
        payloadJson: String,
        createdAt: Long,
        userMessage: MessageEntity,
    ): Boolean {
        if (enqueueSteeringIfRunActive(id, runId, content, payloadJson, createdAt) == -1L) return false
        upsertAgentUserMessage(userMessage)
        return true
    }

    @Query(
        "SELECT * FROM pending_messages WHERE conversationId = :sessionId " +
            "ORDER BY queuePosition ASC, id ASC LIMIT 1",
    )
    suspend fun getPendingFollowUpHead(sessionId: String): PendingMessageEntity?

    @Query("DELETE FROM pending_messages WHERE id = :id AND status = 'PENDING'")
    suspend fun deletePendingFollowUp(id: String): Int

    @Query(
        "SELECT * FROM agent_steering_messages WHERE runId = :runId AND status = 'PENDING' " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun getPendingSteering(runId: String): List<AgentSteeringMessageEntity>

    @Query(
        "UPDATE agent_steering_messages SET status = 'CONSUMED', consumedAt = :consumedAt " +
            "WHERE id = :id AND status = 'PENDING'",
    )
    suspend fun markSteeringConsumed(id: String, consumedAt: Long): Int

    /** steering 的 Transcript 事实和已消费状态必须一起提交，进程退出后不能丢失或重复。 */
    @Transaction
    suspend fun consumeSteering(
        entry: AgentEntryEntity,
        steeringId: String,
        consumedAt: Long,
        updatedRun: AgentRunEntity? = null,
        snapshotChunks: List<AgentRunSnapshotChunkEntity> = emptyList(),
    ): Boolean {
        if (markSteeringConsumed(steeringId, consumedAt) != 1) return false
        upsertEntry(entry)
        if (updatedRun != null) {
            upsertRun(updatedRun)
            deleteRunSnapshotChunks(updatedRun.id)
            if (snapshotChunks.isNotEmpty()) upsertRunSnapshotChunks(snapshotChunks)
        }
        return true
    }

    /** Follow-up 从 Pending 队列进入 Agent transcript 必须原子完成，崩溃后不能重复或丢失。 */
    @Transaction
    suspend fun consumePendingFollowUp(
        entry: AgentEntryEntity,
        pendingId: String,
        userMessage: MessageEntity,
        updatedRun: AgentRunEntity? = null,
        snapshotChunks: List<AgentRunSnapshotChunkEntity> = emptyList(),
    ): Boolean {
        if (deletePendingFollowUp(pendingId) != 1) return false
        upsertEntry(entry)
        upsertAgentUserMessage(userMessage)
        if (updatedRun != null) {
            upsertRun(updatedRun)
            deleteRunSnapshotChunks(updatedRun.id)
            if (snapshotChunks.isNotEmpty()) upsertRunSnapshotChunks(snapshotChunks)
        }
        return true
    }

    @Query(
        """
        UPDATE agent_runs
        SET status = 'COMPLETED', currentRequestOrdinal = :requestOrdinal,
            terminalReason = :terminalReason, updatedAt = :updatedAt
        WHERE id = :runId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
          AND NOT EXISTS (
              SELECT 1 FROM agent_steering_messages
              WHERE runId = :runId AND status = 'PENDING'
          )
        """,
    )
    suspend fun markRunCompletedIfNoPendingSteering(
        runId: String,
        requestOrdinal: Int,
        terminalReason: String?,
        updatedAt: Long,
    ): Int

    /**
     * Pending 队首为可发送消息时，它就是 Pi follow-up。检查与 Run 完成处于同一事务，
     * 保证并发入队和完成只有一个先发生。
     */
    @Transaction
    suspend fun completeRunIfNoQueuedUserMessage(
        runId: String,
        sessionId: String,
        requestOrdinal: Int,
        terminalReason: String?,
        updatedAt: Long,
    ): Int {
        if (getPendingFollowUpHead(sessionId)?.status == "PENDING") return 0
        return markRunCompletedIfNoPendingSteering(runId, requestOrdinal, terminalReason, updatedAt)
    }

    /**
     * 分页读取恢复快照。
     *
     * Room 会把一次查询的全部结果装进 CursorWindow。即使单块只有 64K，直接查询全部块仍会在
     * 总量超过约 2MB 时失败，因此调用方必须按 chunkIndex 分页读取。
     */
    @Query(
        """
        SELECT * FROM agent_run_snapshot_chunks
        WHERE runId = :runId AND chunkIndex > :afterChunkIndex
        ORDER BY chunkIndex ASC
        LIMIT :limit
        """
    )
    suspend fun getRunSnapshotChunkPage(
        runId: String,
        afterChunkIndex: Int,
        limit: Int,
    ): List<AgentRunSnapshotChunkEntity>

    @Query("DELETE FROM agent_run_snapshot_chunks WHERE runId = :runId")
    suspend fun deleteRunSnapshotChunks(runId: String)

    /**
     * 清理消息已经结束、但 Run 仍停在活动状态的历史脏记录。
     *
     * 可见消息被删除、已经报错或已经写入结束时间时，都不能再次恢复对应 Run。
     */
    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt,
            runGeneration = runGeneration + 1
        WHERE (
            status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
            OR (status = 'INTERRUPTED' AND terminalReason IN ('APP_PROCESS_RESTARTED', 'APPROVAL_DECIDED_PENDING_RESUME'))
        )
        AND (
            NOT EXISTS (
                SELECT 1 FROM messages
                WHERE messages.id = agent_runs.visibleAssistantMessageId
            )
            OR EXISTS (
                SELECT 1 FROM messages
                WHERE messages.id = agent_runs.visibleAssistantMessageId
                  AND (messages.isError = 1 OR messages.executionFinishedAt IS NOT NULL)
            )
        )
        """
    )
    suspend fun markStaleVisibleMessageRunsCancelled(
        reason: String,
        updatedAt: Long,
    ): Int

    /** 恢复快照只服务于未结束 Run，终态后继续保留会造成数据库无限增长。 */
    @Query(
        """
        DELETE FROM agent_run_snapshot_chunks
        WHERE runId IN (
            SELECT id FROM agent_runs
            WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
        )
        """
    )
    suspend fun deleteTerminalRunSnapshotChunks(): Int

    /** 标记脏 Run 与释放其恢复快照必须一起完成，避免下次启动继续占用磁盘。 */
    @Transaction
    suspend fun cancelStaleVisibleMessageRuns(
        reason: String,
        updatedAt: Long,
    ): Int {
        val changed = markStaleVisibleMessageRunsCancelled(reason, updatedAt)
        if (changed > 0) deleteTerminalRunSnapshotChunks()
        return changed
    }

    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
           OR (status = 'INTERRUPTED' AND terminalReason IN ('APP_PROCESS_RESTARTED', 'APPROVAL_DECIDED_PENDING_RESUME'))
        """
    )
    suspend fun getActiveRuns(): List<AgentRunEntity>

    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
           OR (status = 'INTERRUPTED' AND terminalReason IN ('APP_PROCESS_RESTARTED', 'APPROVAL_DECIDED_PENDING_RESUME'))
        ORDER BY updatedAt ASC
        """
    )
    fun observeServiceRuns(): Flow<List<AgentRunEntity>>

    /** 多条历史脏记录并存时先展示当前最新申请，禁止旧卡片抢走用户点击。 */
    @Query("SELECT * FROM agent_runs WHERE status = 'WAITING_APPROVAL' ORDER BY updatedAt DESC")
    suspend fun getWaitingApprovalRuns(): List<AgentRunEntity>

    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt,
            runGeneration = runGeneration + 1
        WHERE sessionId = :sessionId
          AND (
              status = 'WAITING_APPROVAL'
              OR (status = 'INTERRUPTED' AND terminalReason = 'APPROVAL_DECIDED_PENDING_RESUME')
          )
        """
    )
    suspend fun cancelSupersededApprovalRunsForSession(
        sessionId: String,
        reason: String,
        updatedAt: Long,
    )

    @Query("SELECT * FROM agent_runs WHERE status = 'WAITING_REMOTE_EXECUTION' ORDER BY updatedAt ASC")
    suspend fun getWaitingRemoteExecutionRuns(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE status = 'MODEL_CONTINUATION_PENDING' ORDER BY updatedAt ASC")
    suspend fun getPendingModelContinuationRuns(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE status = 'INTERRUPTED' ORDER BY updatedAt ASC")
    suspend fun getInterruptedRuns(): List<AgentRunEntity>

    /** 模型流随进程消失后可以从已保存快照续写，不能永久停在旧中间态。 */
    @Query(
        """
        UPDATE agent_runs
        SET status = 'MODEL_CONTINUATION_PENDING', terminalReason = 'MODEL_CONTINUATION_PENDING', updatedAt = :updatedAt
        WHERE status IN ('CREATED', 'PREPARING_CONTEXT', 'COMPACTING_CONTEXT', 'WAITING_MODEL', 'STREAMING_MODEL', 'RETRYING')
        """
    )
    suspend fun markModelRunsPendingContinuation(updatedAt: Long)

    /** 工具可能已经产生副作用，进程重启后先标成未知中断，交给工具恢复逻辑对账。 */
    @Query(
        """
        UPDATE agent_runs
        SET status = 'INTERRUPTED', terminalReason = :reason, updatedAt = :updatedAt
        WHERE status IN ('CHECKING_PERMISSION', 'EXECUTING_TOOL', 'PERSISTING_RESULT')
        """
    )
    suspend fun markToolRunsInterrupted(reason: String, updatedAt: Long)

    @Query(
        """
        UPDATE agent_requests
        SET status = 'INTERRUPTED', finishReason = :reason, finishedAt = :finishedAt
        WHERE status IN ('PREPARED', 'STREAMING')
        """
    )
    suspend fun markActiveRequestsInterrupted(reason: String, finishedAt: Long)

    @Query("SELECT * FROM agent_entries WHERE runId = :runId ORDER BY sequence ASC")
    suspend fun getEntries(runId: String): List<AgentEntryEntity>

    @Query(
        """
        SELECT * FROM agent_entries
        WHERE runId = :runId AND requestId = :requestId AND kind = 'ASSISTANT' AND status = 'PARTIAL'
        ORDER BY sequence DESC LIMIT 1
        """
    )
    suspend fun getPartialAssistantEntry(runId: String, requestId: String): AgentEntryEntity?

    @Query(
        """
        DELETE FROM agent_entries
        WHERE runId = :runId AND kind = 'ASSISTANT' AND status = 'PARTIAL'
        """
    )
    suspend fun deletePartialAssistantEntries(runId: String)

    @Query("SELECT * FROM agent_requests WHERE runId = :runId ORDER BY ordinal ASC")
    suspend fun getRequests(runId: String): List<AgentRequestEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM agent_entries
            WHERE requestId = :requestId AND kind = 'ASSISTANT' AND status = 'FINAL'
        )
        """
    )
    suspend fun hasFinalAssistantForRequest(requestId: String): Boolean

    @Query("SELECT * FROM agent_request_usage WHERE requestId = :requestId LIMIT 1")
    suspend fun getUsage(requestId: String): AgentRequestUsageEntity?

    @Query("SELECT * FROM agent_context_snapshots WHERE requestId = :requestId LIMIT 1")
    suspend fun getContextSnapshot(requestId: String): AgentContextSnapshotEntity?

    @Query("SELECT * FROM agent_compactions WHERE sessionId = :sessionId AND status = 'COMPLETED' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestCompaction(sessionId: String): AgentCompactionEntryEntity?

    @Query(
        """
        SELECT * FROM provider_continuation_states
        WHERE sessionId = :sessionId AND configId = :configId AND protocol = :protocol AND provider = :provider
          AND endpoint = :endpoint AND model = :model
        LIMIT 1
        """
    )
    suspend fun getContinuationState(
        sessionId: String,
        configId: String,
        protocol: String,
        provider: String,
        endpoint: String,
        model: String,
    ): ProviderContinuationStateEntity?

    @Query("DELETE FROM provider_continuation_states WHERE sessionId = :sessionId")
    suspend fun deleteContinuationStates(sessionId: String)

    @Query("DELETE FROM provider_continuation_states WHERE id = :stateId")
    suspend fun deleteContinuationState(stateId: String)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_entries WHERE runId = :runId")
    suspend fun nextEntrySequence(runId: String): Long

    @Query(
        """
        SELECT COUNT(*) AS requestCount,
               COALESCE(SUM(COALESCE(u.promptTokens, 0)), 0) AS inputTokens,
               COALESCE(SUM(COALESCE(u.outputTokens, 0)), 0) AS outputTokens,
               COALESCE(SUM(COALESCE(u.requestTotalTokens, COALESCE(u.promptTokens, 0) + COALESCE(u.outputTokens, 0))), 0) AS totalTokens
        FROM agent_requests r
        LEFT JOIN agent_request_usage u ON u.requestId = r.id
        WHERE r.runId = :runId
        """
    )
    suspend fun getRunTokenTotals(runId: String): AgentTokenTotalsRow

    @Query(
        """
        SELECT COUNT(*) AS requestCount,
               COALESCE(SUM(COALESCE(u.promptTokens, 0)), 0) AS inputTokens,
               COALESCE(SUM(COALESCE(u.outputTokens, 0)), 0) AS outputTokens,
               COALESCE(SUM(COALESCE(u.requestTotalTokens, COALESCE(u.promptTokens, 0) + COALESCE(u.outputTokens, 0))), 0) AS totalTokens
        FROM agent_requests r
        JOIN agent_runs run ON run.id = r.runId
        LEFT JOIN agent_request_usage u ON u.requestId = r.id
        WHERE run.sessionId = :sessionId
        """
    )
    suspend fun getSessionTokenTotals(sessionId: String): AgentTokenTotalsRow

    @Transaction
    suspend fun persistRequestFacts(
        request: AgentRequestEntity,
        snapshot: AgentContextSnapshotEntity,
    ) {
        upsertRequest(request)
        upsertContextSnapshot(snapshot)
    }

    /** 审批卡片及等待状态必须在同一事务出现，禁止留下界面无法发现的孤立请求。 */
    @Transaction
    suspend fun persistApprovalPause(
        entry: AgentEntryEntity,
        waitingRun: AgentRunEntity,
    ) {
        upsertEntry(entry)
        upsertRun(waitingRun)
    }

    /** 决定写入后立刻把 Run 标成可续接状态，进程退出后仍能识别未消费决定。 */
    @Transaction
    suspend fun persistApprovalDecision(
        entry: AgentEntryEntity,
        interruptedRun: AgentRunEntity,
    ) {
        upsertEntry(entry)
        upsertRun(interruptedRun)
    }

    /** 新用户消息会取代同一会话中尚未回答的旧审批，避免批准被旧 Run 接走。 */
    @Transaction
    suspend fun startRunSupersedingWaitingApprovals(
        run: AgentRunEntity,
        snapshotChunks: List<AgentRunSnapshotChunkEntity>,
        reason: String,
    ) {
        cancelSupersededApprovalRunsForSession(run.sessionId, reason, run.createdAt)
        deleteTerminalRunSnapshotChunks()
        upsertRun(run)
        deleteRunSnapshotChunks(run.id)
        if (snapshotChunks.isNotEmpty()) upsertRunSnapshotChunks(snapshotChunks)
    }

    /** 更新 Run 和恢复快照必须在同一事务完成，禁止状态指向半份快照。 */
    @Transaction
    suspend fun persistRunSnapshot(
        run: AgentRunEntity,
        snapshotChunks: List<AgentRunSnapshotChunkEntity>,
    ) {
        upsertRun(run)
        deleteRunSnapshotChunks(run.id)
        if (snapshotChunks.isNotEmpty()) upsertRunSnapshotChunks(snapshotChunks)
    }


    /** App 进程已经消失：模型流等待续写，可能产生副作用的工具流等待对账。 */
    @Transaction
    suspend fun recoverInterruptedAgentRuns(
        reason: String = "APP_PROCESS_RESTARTED",
        timestamp: Long = System.currentTimeMillis(),
    ) {
        markActiveRequestsInterrupted(reason, timestamp)
        markModelRunsPendingContinuation(timestamp)
        markToolRunsInterrupted(reason, timestamp)
    }
}
