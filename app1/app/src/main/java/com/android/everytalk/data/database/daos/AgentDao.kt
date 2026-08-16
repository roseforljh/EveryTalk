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
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity

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

    @Query("SELECT * FROM agent_runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE visibleAssistantMessageId = :messageId LIMIT 1")
    suspend fun getRunByVisibleMessage(messageId: String): AgentRunEntity?

    /**
     * 用户点击停止时按可见消息原子封存 Run。
     * 条件更新避免 AgentLoop 恰好完成时又被旧的停止请求覆盖成 CANCELLED。
     */
    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt
        WHERE visibleAssistantMessageId = :messageId
          AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        """
    )
    suspend fun cancelActiveRunByVisibleMessage(
        messageId: String,
        reason: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM agent_runs WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getRunsForSession(sessionId: String): List<AgentRunEntity>

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

    @Query("SELECT * FROM agent_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')")
    suspend fun getActiveRuns(): List<AgentRunEntity>

    /** 多条历史脏记录并存时先展示当前最新申请，禁止旧卡片抢走用户点击。 */
    @Query("SELECT * FROM agent_runs WHERE status = 'WAITING_APPROVAL' ORDER BY updatedAt DESC")
    suspend fun getWaitingApprovalRuns(): List<AgentRunEntity>

    @Query(
        """
        UPDATE agent_runs
        SET status = 'CANCELLED', terminalReason = :reason, updatedAt = :updatedAt
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

    @Query(
        """
        UPDATE agent_runs
        SET status = 'INTERRUPTED', terminalReason = :reason, updatedAt = :updatedAt
        WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'WAITING_APPROVAL', 'WAITING_REMOTE_EXECUTION', 'MODEL_CONTINUATION_PENDING')
        """
    )
    suspend fun markActiveRunsInterrupted(reason: String, updatedAt: Long)

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

    @Query("SELECT * FROM agent_requests WHERE runId = :runId ORDER BY ordinal ASC")
    suspend fun getRequests(runId: String): List<AgentRequestEntity>

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


    /** App 进程已经消失，旧 HTTP 流无法继续，统一封存为中断状态。 */
    @Transaction
    suspend fun recoverInterruptedAgentRuns(
        reason: String = "APP_PROCESS_RESTARTED",
        timestamp: Long = System.currentTimeMillis(),
    ) {
        markActiveRequestsInterrupted(reason, timestamp)
        markActiveRunsInterrupted(reason, timestamp)
    }
}
