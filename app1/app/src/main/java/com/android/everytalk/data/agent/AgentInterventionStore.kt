package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentSuspensionEntity
import com.android.everytalk.data.database.entities.AgentExecutionSlotEntity
import java.security.MessageDigest
import java.util.UUID

/** AgentIntervention 的持久化入口，所有并发控制下沉到 Room DAO。 */
class AgentInterventionStore(private val dao: AgentDao) {
    data class SuspensionTicket(
        val suspension: AgentSuspensionEntity,
        val resolutionNonce: String?,
    )

    suspend fun suspend(
        run: com.android.everytalk.data.database.entities.AgentRunEntity,
        request: TrustedInterventionRequest,
        resolutionNonce: String,
        now: Long = System.currentTimeMillis(),
    ): SuspensionTicket {
        val nonceHash = sha256(resolutionNonce)
        val suspension = AgentSuspensionEntity(
            id = request.suspensionId,
            runId = request.runId,
            runGeneration = request.runGeneration,
            turnId = request.turnId,
            requestId = request.requestId,
            toolCallId = request.toolCallId,
            executionSlot = request.executionSlot,
            requestHash = request.requestHash,
            capabilityId = request.capabilityId,
            targetBindingRef = request.targetBindingRef,
            requestSource = request.requestSource,
            policyVersion = request.policyVersion,
            adapterContractVersion = request.adapterContractVersion,
            bindingGeneration = request.bindingGeneration,
            executionGeneration = request.executionGeneration,
            resourceEpoch = request.resourceEpoch,
            activeSuspensionIdempotencyKey = request.activeSuspensionIdempotencyKey,
            resolutionMaterialKind = request.resolutionMaterialKind.name,
            status = SuspensionState.WAITING_USER.name,
            continuationKind = request.continuation.name,
            resolutionNonceHash = nonceHash,
            createdAt = now,
            updatedAt = now,
            expiresAt = null,
        )
        val event = AgentEntryEntity(
            id = UUID.randomUUID().toString(),
            runId = run.id,
            sequence = dao.nextEntrySequence(run.id),
            kind = AgentEntryKind.STATUS.name,
            requestId = request.requestId,
            toolCallId = request.toolCallId,
            payloadJson = "{\"type\":\"SUSPENSION_CREATED\",\"suspensionId\":\"${request.suspensionId}\"}",
            status = AgentEntryStatus.FINAL.name,
            createdAt = now,
            finalizedAt = now,
        )
        val persisted = dao.persistSuspensionAndPauseRun(
            suspension = suspension,
            waitingRun = run.copy(
                status = AgentRunStatus.WAITING_APPROVAL.name,
                loopState = AgentLoopState.WAITING_TOOL_BATCH.name,
                updatedAt = now,
            ),
            slot = AgentExecutionSlotEntity(
                runId = run.id,
                executionSlot = request.executionSlot,
                toolCallId = request.toolCallId,
                executionGeneration = request.executionGeneration,
                state = ExecutionSlotState.SUSPENDED.name,
                suspensionId = request.suspensionId,
                updatedAt = now,
            ),
            event = event,
        )
        val finalSuspension = if (
            persisted.requestSource == "MODEL_HINT" && request.requestSource in setOf("EXECUTOR_PROVEN", "SYSTEM_CHALLENGE")
        ) {
            if (dao.upgradeSuspensionEvidence(
                    id = persisted.id,
                    expectedVersion = persisted.rowVersion,
                    requestSource = request.requestSource,
                    bindingGeneration = request.bindingGeneration,
                    updatedAt = now,
                ) == 1
            ) dao.getSuspension(persisted.id) ?: persisted else persisted
        } else persisted
        return SuspensionTicket(
            suspension = finalSuspension,
            resolutionNonce = resolutionNonce.takeIf { finalSuspension.resolutionNonceHash == nonceHash },
        )
    }

    suspend fun get(id: String): AgentSuspensionEntity? = dao.getSuspension(id)

    suspend fun claimFulfillment(id: String, expectedVersion: Long, runGeneration: Long, attemptId: String): Boolean =
        dao.claimSuspensionFulfillment(id, expectedVersion, runGeneration, attemptId, System.currentTimeMillis()) == 1

    suspend fun claimResume(id: String, expectedVersion: Long, runGeneration: Long, attemptId: String): Boolean =
        dao.claimSuspensionResume(id, expectedVersion, runGeneration, attemptId, System.currentTimeMillis()) == 1

    suspend fun transition(id: String, expectedState: SuspensionState, nextState: SuspensionState, expectedVersion: Long): Boolean =
        dao.compareAndSetSuspension(id, expectedState.name, nextState.name, expectedVersion, System.currentTimeMillis()) == 1

    suspend fun enterUserReentry(id: String, expectedStatus: SuspensionState, expectedVersion: Long, newNonce: String): Boolean =
        dao.compareAndSetSuspension(id, expectedStatus.name, SuspensionState.WAITING_USER_REENTRY.name, expectedVersion, System.currentTimeMillis(), sha256(newNonce)) == 1

    /** nonce 明文只在内存中；冷启动投影等待卡片时必须轮换并只持久化摘要。 */
    suspend fun rotateResolutionNonce(
        id: String,
        state: SuspensionState,
        expectedVersion: Long,
        newNonce: String,
    ): Boolean = dao.compareAndSetSuspension(
        id,
        state.name,
        state.name,
        expectedVersion,
        System.currentTimeMillis(),
        sha256(newNonce),
    ) == 1

    suspend fun resolve(id: String, expectedState: SuspensionState, expectedVersion: Long, nonce: String): Boolean =
        dao.resolveSuspension(id, expectedState.name, expectedVersion, sha256(nonce), System.currentTimeMillis()) == 1

    suspend fun outcome(
        id: String,
        expectedState: SuspensionState,
        nextState: SuspensionState,
        expectedVersion: Long,
        failureCode: String? = null,
    ): Boolean = dao.transitionSuspensionOutcome(
        id,
        expectedState.name,
        nextState.name,
        expectedVersion,
        failureCode,
        System.currentTimeMillis(),
    ) == 1

    suspend fun startupCandidates(): List<AgentSuspensionEntity> = dao.getSuspensionsByStatuses(
        listOf(
            SuspensionState.WAITING_USER.name,
            SuspensionState.WAITING_USER_REENTRY.name,
            SuspensionState.RESOLUTION_RECEIVED.name,
            SuspensionState.FULFILLING.name,
            SuspensionState.DELIVERY_UNKNOWN.name,
            SuspensionState.RECONCILIATION_REQUIRED.name,
            SuspensionState.RECONCILING.name,
            SuspensionState.DELIVERED.name,
            SuspensionState.READY_TO_RESUME.name,
            SuspensionState.READY_TO_RESUME_WITH_FAILURE.name,
            SuspensionState.RESUMING.name,
            SuspensionState.USER_DECISION_REQUIRED.name,
        ),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
