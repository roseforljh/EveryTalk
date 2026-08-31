package com.android.everytalk.data.agent

import com.android.everytalk.data.database.entities.AgentRunEntity
import java.security.MessageDigest
import java.util.UUID

/**
 * 统一人类接力 Broker。当前先接通持久化骨架和 Grant 原子消费，旧 Approval 链路保持兼容。
 */
class AgentInterventionBroker(
    private val store: AgentInterventionStore,
    private val registry: AgentInterventionPolicyRegistry = AgentInterventionPolicyRegistry(),
    private val onSuspended: (AgentInterventionStore.SuspensionTicket) -> Unit = {},
) {
    suspend fun resolve(suspensionId: String, expectedVersion: Long, nonce: String): Boolean {
        val suspension = store.get(suspensionId) ?: return false
        if (suspension.status != SuspensionState.WAITING_USER.name && suspension.status != SuspensionState.WAITING_USER_REENTRY.name) return false
        return store.resolve(suspensionId, SuspensionState.valueOf(suspension.status), expectedVersion, nonce)
    }

    suspend fun reject(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_REJECTED")

    suspend fun cancel(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_CANCELLED")

    suspend fun expire(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_EXPIRED")

    suspend fun reconcileRequired(suspensionId: String, expectedVersion: Long): Boolean =
        store.outcome(suspensionId, SuspensionState.DELIVERY_UNKNOWN, SuspensionState.RECONCILIATION_REQUIRED, expectedVersion)

    private suspend fun finishWaitingIntervention(
        suspensionId: String,
        expectedVersion: Long,
        failureCode: String,
    ): Boolean {
        val suspension = store.get(suspensionId) ?: return false
        val state = SuspensionState.valueOf(suspension.status)
        if (state !in setOf(SuspensionState.WAITING_USER, SuspensionState.WAITING_USER_REENTRY)) return false
        return store.outcome(
            suspensionId,
            state,
            SuspensionState.READY_TO_RESUME_WITH_FAILURE,
            expectedVersion,
            failureCode,
        )
    }
    suspend fun suspend(
        run: AgentRunEntity,
        capabilityRequest: CapabilityRequest,
        turnId: String,
        requestId: String,
        toolCallId: String,
        executionSlot: String,
        requestHash: String,
        requestSource: String,
        bindingGeneration: Long,
        executionGeneration: Long,
        targetBindingRef: String = "current-run-resource",
    ) = registry.resolve(capabilityRequest.requestedCapability)?.let { policy ->
        val source = runCatching { InterventionRequestSource.valueOf(requestSource) }
            .getOrElse { throw IllegalArgumentException("不可信的 Intervention request source") }
        require(source.trustLevel >= policy.minimumSource.trustLevel) {
            "${policy.capability} 要求 ${policy.minimumSource} 或更高可信来源"
        }
        val key = stableKey(
            run.id,
            turnId,
            executionSlot,
            policy.capability,
            targetBindingRef,
            bindingGeneration.toString(),
            requestHash,
            executionGeneration.toString(),
        )
        store.suspend(
            run,
            TrustedInterventionRequest(
                suspensionId = UUID.nameUUIDFromBytes(key.toByteArray()).toString(),
                runId = run.id,
                runGeneration = run.runGeneration,
                turnId = turnId,
                requestId = requestId,
                toolCallId = toolCallId,
                executionSlot = executionSlot,
                requestHash = requestHash,
                capabilityId = policy.capability,
                targetBindingRef = targetBindingRef,
                requestSource = requestSource,
                policyVersion = policy.policyVersion,
                adapterContractVersion = policy.adapterContractVersion,
                bindingGeneration = bindingGeneration,
                executionGeneration = executionGeneration,
                continuation = policy.continuation,
                resolutionMaterialKind = policy.materialKind,
                activeSuspensionIdempotencyKey = key,
            ),
            resolutionNonce = UUID.randomUUID().toString(),
        ).also(onSuspended)
    } ?: throw IllegalArgumentException("未经 Registry 注册的 capability: ${capabilityRequest.requestedCapability}")

    private fun stableKey(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
