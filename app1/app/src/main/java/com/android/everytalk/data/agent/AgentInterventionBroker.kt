package com.android.everytalk.data.agent

import com.android.everytalk.data.database.entities.AgentCapabilityGrantEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentResourceLeaseEntity
import com.android.everytalk.data.database.entities.AgentSuspensionEntity
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 统一人类接力 Broker。
 *
 * Secret 只以 [ProtectedResolution.Ephemeral] 短暂存在 Broker 内存并直接交给可信 Adapter。
 * Room 只保存安全存储引用。模型、Tool Call、日志和通知都不会得到材料或 Grant ID。
 */
class AgentInterventionBroker(
    private val store: AgentInterventionStore,
    private val registry: AgentInterventionPolicyRegistry = AgentInterventionPolicyRegistry(),
    private val adapters: AgentInterventionAdapterRegistry = AgentInterventionAdapterRegistry(),
    private val grants: AgentCapabilityGrantStore? = null,
    private val resourceLeases: AgentResourceLeaseStore? = null,
    private val onSuspended: (AgentInterventionStore.SuspensionTicket) -> Unit = {},
) {
    private val ephemeralMaterials = ConcurrentHashMap<String, ProtectedResolution.Ephemeral>()

    /** 接收可信 UI 的一次提交并立即履行。CAS 失败时会清零本次材料。 */
    suspend fun resolve(
        suspensionId: String,
        expectedVersion: Long,
        nonce: String,
        material: ProtectedResolution = ProtectedResolution.None,
    ): Boolean {
        val suspension = store.get(suspensionId) ?: return clearAndFalse(material)
        val state = runCatching { SuspensionState.valueOf(suspension.status) }.getOrNull()
            ?: return clearAndFalse(material)
        if (state !in setOf(SuspensionState.WAITING_USER, SuspensionState.WAITING_USER_REENTRY)) {
            return clearAndFalse(material)
        }
        val expectedKind = runCatching { ResolutionMaterialKind.valueOf(suspension.resolutionMaterialKind) }.getOrNull()
            ?: return clearAndFalse(material)
        if (material.kind != expectedKind) return clearAndFalse(material)

        val ephemeral = material as? ProtectedResolution.Ephemeral
        if (ephemeral != null && ephemeralMaterials.putIfAbsent(suspensionId, ephemeral) != null) {
            return clearAndFalse(material)
        }
        val reference = (material as? ProtectedResolution.DurableReference)?.reference
        val resolved = store.resolve(suspensionId, state, expectedVersion, nonce, reference)
        if (!resolved) {
            if (ephemeral != null) ephemeralMaterials.remove(suspensionId, ephemeral)
            return clearAndFalse(material)
        }
        fulfill(suspensionId)
        return true
    }

    /** RESOLUTION_RECEIVED 的唯一履行入口；CAS 保证并发下只有一个 Adapter 调用者。 */
    suspend fun fulfill(suspensionId: String): AdapterDeliveryFact? {
        val suspension = store.get(suspensionId) ?: return null
        if (suspension.status != SuspensionState.RESOLUTION_RECEIVED.name) return null
        val policy = compatiblePolicy(suspension)
            ?: return failBeforeDeliveryAndClear(suspension, "POLICY_OR_ADAPTER_STALE")
        val adapter = adapters.get(policy.audience)
            ?: return failBeforeDeliveryAndClear(suspension, "ADAPTER_UNAVAILABLE")
        // 先 claim 再读取材料。否则并发恢复线程可能拿到同一 CharArray，失败线程会把
        // 正在被成功履行者使用的 Secret 提前清零。
        if (!store.claimFulfillment(suspension.id, suspension.rowVersion, suspension.runGeneration, UUID.randomUUID().toString())) {
            return null
        }
        val claimed = store.get(suspension.id) ?: return null
        val request = claimed.toTrustedRequest()
        if (!claimResourceLease(claimed, policy)) {
            ephemeralMaterials.remove(claimed.id)?.clear()
            store.recordDeliveryFact(
                claimed.id,
                SuspensionState.FULFILLING,
                SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                claimed.rowVersion,
                "RESOURCE_LEASE_UNAVAILABLE",
            )
            return AdapterDeliveryFact.NOT_DELIVERED
        }
        val material = materialFor(claimed)
        if (material == null) {
            if (claimed.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL.name) {
                store.enterUserReentry(
                    claimed.id,
                    SuspensionState.FULFILLING,
                    claimed.rowVersion,
                    UUID.randomUUID().toString(),
                )
            } else {
                store.recordDeliveryFact(
                    claimed.id,
                    SuspensionState.FULFILLING,
                    SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                    claimed.rowVersion,
                    "RESOLUTION_REFERENCE_MISSING",
                )
            }
            releaseResourceLease(claimed, policy)
            return AdapterDeliveryFact.NOT_DELIVERED
        }

        val valid = try {
            adapter.validate(request)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                clearMaterial(suspensionId, material)
                recordFulfillmentResult(claimed, policy, AdapterDeliveryFact.NOT_DELIVERED)
                releaseResourceLease(claimed, policy)
            }
            throw error
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            clearMaterial(suspensionId, material)
            recordFulfillmentResult(claimed, policy, AdapterDeliveryFact.NOT_DELIVERED)
            releaseResourceLease(claimed, policy)
            return AdapterDeliveryFact.NOT_DELIVERED
        }
        var cleanupDone = false
        val result = try {
            adapter.fulfill(request, material)
        } catch (error: CancellationException) {
            // 取消可能发生在外部写入之后。NonCancellable 只负责记 UNKNOWN 和清理，绝不恢复 Run。
            withContext(NonCancellable) {
                clearMaterial(suspensionId, material)
                runCatching { adapter.cleanup(request) }
                cleanupDone = true
                recordFulfillmentResult(claimed, policy, AdapterDeliveryFact.UNKNOWN)
            }
            throw error
        } catch (_: Exception) {
            // Adapter 已经开始执行，异常不能证明外部动作没有发生。
            AdapterFulfillmentResult(AdapterDeliveryFact.UNKNOWN)
        } finally {
            if (!cleanupDone) {
                withContext(NonCancellable) {
                    clearMaterial(suspensionId, material)
                    runCatching { adapter.cleanup(request) }
                }
            }
        }
        recordFulfillmentResult(claimed, policy, result.fact, result.failureCode)
        if (result.fact != AdapterDeliveryFact.UNKNOWN) releaseResourceLease(claimed, policy)
        return result.fact
    }

    /** FULFILLING / DELIVERY_UNKNOWN 的持久对账入口。UNKNOWN 永远不会自动重放。 */
    suspend fun reconcile(suspensionId: String): AdapterDeliveryFact? {
        var suspension = store.get(suspensionId) ?: return null
        val state = runCatching { SuspensionState.valueOf(suspension.status) }.getOrNull() ?: return null
        if (state !in setOf(
                SuspensionState.FULFILLING,
                SuspensionState.DELIVERY_UNKNOWN,
                SuspensionState.RECONCILIATION_REQUIRED,
                SuspensionState.RECONCILING,
            )
        ) return null
        val policy = compatiblePolicy(suspension) ?: return null
        val adapter = adapters.get(policy.audience) ?: return null
        // reconcile 只读取外部事实，不继续投递。Run cancel 可以撤销 Lease，但仍必须完成对账。
        if (state !in setOf(SuspensionState.FULFILLING, SuspensionState.RECONCILING)) {
            if (!store.recordDeliveryFact(
                    suspension.id,
                    state,
                    SuspensionState.RECONCILING,
                    suspension.rowVersion,
                )
            ) return null
            suspension = store.get(suspension.id) ?: return null
        }
        val fact = try {
            adapter.reconcile(suspension.toTrustedRequest())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AdapterDeliveryFact.UNKNOWN
        }
        recordReconciliationResult(suspension, policy, fact)
        if (fact != AdapterDeliveryFact.UNKNOWN) releaseResourceLease(suspension, policy)
        return fact
    }

    /** DELIVERED 已经持久化但 READY_TO_RESUME 尚未提交时的启动恢复。 */
    suspend fun recoverDelivered(suspensionId: String): Boolean {
        val suspension = store.get(suspensionId) ?: return false
        if (suspension.status != SuspensionState.DELIVERED.name) return false
        val policy = compatiblePolicy(suspension) ?: return false
        makeReadyToResume(suspensionId, policy)
        return store.get(suspensionId)?.status == SuspensionState.READY_TO_RESUME.name
    }

    suspend fun reject(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_REJECTED")

    suspend fun cancel(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_CANCELLED")

    suspend fun expire(suspensionId: String, expectedVersion: Long): Boolean =
        finishWaitingIntervention(suspensionId, expectedVersion, "INTERVENTION_EXPIRED")

    suspend fun reconcileRequired(suspensionId: String, expectedVersion: Long): Boolean =
        store.outcome(suspensionId, SuspensionState.DELIVERY_UNKNOWN, SuspensionState.RECONCILIATION_REQUIRED, expectedVersion)

    /** 用户只能确认外部事实已完成；此入口不会再次使用旧 resolution material。 */
    suspend fun confirmUnknownDelivered(suspensionId: String, expectedVersion: Long): Boolean {
        val suspension = store.get(suspensionId) ?: return false
        if (suspension.status != SuspensionState.USER_DECISION_REQUIRED.name) return false
        val policy = compatiblePolicy(suspension) ?: return false
        if (!store.recordDeliveryFact(
                suspension.id,
                SuspensionState.USER_DECISION_REQUIRED,
                SuspensionState.DELIVERED,
                expectedVersion,
                "USER_CONFIRMED_DELIVERED",
            )
        ) return false
        makeReadyToResume(suspension.id, policy)
        return store.get(suspension.id)?.status == SuspensionState.READY_TO_RESUME.name
    }

    /** 保留 UNKNOWN 事实并恢复失败结果，Agent 只能验证或重规划，不能自动重放原动作。 */
    suspend fun continueAfterUnknown(suspensionId: String, expectedVersion: Long): Boolean =
        store.outcome(
            suspensionId,
            SuspensionState.USER_DECISION_REQUIRED,
            SuspensionState.READY_TO_RESUME_WITH_FAILURE,
            expectedVersion,
            "DELIVERY_UNKNOWN_DO_NOT_REPLAY",
        )

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
                reasonSafe = capabilityRequest.reasonSafe,
                userVisibleContext = capabilityRequest.userVisibleContext,
                parameters = capabilityRequest.parameters,
                targetBindingRef = targetBindingRef,
                requestSource = requestSource,
                policyVersion = policy.policyVersion,
                adapterContractVersion = policy.adapterContractVersion,
                bindingGeneration = bindingGeneration,
                executionGeneration = executionGeneration,
                resourceEpoch = System.currentTimeMillis(),
                continuation = policy.continuation,
                resolutionMaterialKind = policy.materialKind,
                resolutionReference = null,
                activeSuspensionIdempotencyKey = key,
            ),
            resolutionNonce = UUID.randomUUID().toString(),
        ).also(onSuspended)
    } ?: throw IllegalArgumentException("未经 Registry 注册的 capability: ${capabilityRequest.requestedCapability}")

    private fun compatiblePolicy(suspension: AgentSuspensionEntity): AgentInterventionPolicyRegistry.Policy? {
        if (registry.compatibility(
                suspension.capabilityId,
                suspension.policyVersion,
                suspension.adapterContractVersion,
            ) != AgentInterventionPolicyRegistry.Compatibility.COMPATIBLE
        ) return null
        return registry.resolve(suspension.capabilityId)
    }

    private fun materialFor(suspension: AgentSuspensionEntity): ProtectedResolution? = when (
        ResolutionMaterialKind.valueOf(suspension.resolutionMaterialKind)
    ) {
        ResolutionMaterialKind.NONE -> ProtectedResolution.None
        ResolutionMaterialKind.DURABLE_REFERENCE -> suspension.resolutionReference
            ?.let(ProtectedResolution::DurableReference)
        ResolutionMaterialKind.EPHEMERAL -> ephemeralMaterials[suspension.id]
    }

    private suspend fun failBeforeDelivery(suspension: AgentSuspensionEntity, code: String): AdapterDeliveryFact? {
        store.outcome(
            suspension.id,
            SuspensionState.RESOLUTION_RECEIVED,
            SuspensionState.READY_TO_RESUME_WITH_FAILURE,
            suspension.rowVersion,
            code,
        )
        return AdapterDeliveryFact.NOT_DELIVERED
    }

    private suspend fun failBeforeDeliveryAndClear(
        suspension: AgentSuspensionEntity,
        code: String,
    ): AdapterDeliveryFact? {
        ephemeralMaterials.remove(suspension.id)?.clear()
        return failBeforeDelivery(suspension, code)
    }

    private suspend fun recordFulfillmentResult(
        claimed: AgentSuspensionEntity,
        policy: AgentInterventionPolicyRegistry.Policy,
        fact: AdapterDeliveryFact,
        failureCode: String? = null,
    ) {
        val runActive = isRunActive(claimed)
        when (fact) {
            AdapterDeliveryFact.DELIVERED -> {
                if (!store.recordDeliveryFact(
                        claimed.id,
                        SuspensionState.FULFILLING,
                        SuspensionState.DELIVERED,
                        claimed.rowVersion,
                    )
                ) return
                if (runActive) makeReadyToResume(claimed.id, policy)
            }
            AdapterDeliveryFact.NOT_DELIVERED -> {
                if (!runActive) {
                    store.recordDeliveryFact(
                        claimed.id,
                        SuspensionState.FULFILLING,
                        SuspensionState.CANCELLED,
                        claimed.rowVersion,
                        AgentRunTerminalResult.RUN_TERMINATED_NOT_DELIVERED,
                    )
                } else if (claimed.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL.name && failureCode == null) {
                    store.enterUserReentry(
                        claimed.id,
                        SuspensionState.FULFILLING,
                        claimed.rowVersion,
                        UUID.randomUUID().toString(),
                    )
                } else {
                    store.recordDeliveryFact(
                        claimed.id,
                        SuspensionState.FULFILLING,
                        SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                        claimed.rowVersion,
                        failureCode ?: "NOT_DELIVERED",
                    )
                }
            }
            AdapterDeliveryFact.UNKNOWN -> store.recordDeliveryFact(
                claimed.id,
                SuspensionState.FULFILLING,
                if (runActive) SuspensionState.USER_DECISION_REQUIRED else SuspensionState.DELIVERY_UNKNOWN,
                claimed.rowVersion,
                if (runActive) "DELIVERY_FACT_UNKNOWN" else AgentRunTerminalResult.RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN,
            )
        }
    }

    private suspend fun recordReconciliationResult(
        reconciling: AgentSuspensionEntity,
        policy: AgentInterventionPolicyRegistry.Policy,
        fact: AdapterDeliveryFact,
    ) {
        val expectedState = SuspensionState.valueOf(reconciling.status)
        val runActive = isRunActive(reconciling)
        when (fact) {
            AdapterDeliveryFact.DELIVERED -> {
                if (store.recordDeliveryFact(
                        reconciling.id,
                        expectedState,
                        SuspensionState.DELIVERED,
                        reconciling.rowVersion,
                    ) && runActive
                ) makeReadyToResume(reconciling.id, policy)
            }
            AdapterDeliveryFact.NOT_DELIVERED -> {
                if (runActive && reconciling.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL.name) {
                    store.enterUserReentry(
                        reconciling.id,
                        expectedState,
                        reconciling.rowVersion,
                        UUID.randomUUID().toString(),
                    )
                } else {
                    store.recordDeliveryFact(
                        reconciling.id,
                        expectedState,
                        if (runActive) SuspensionState.READY_TO_RESUME_WITH_FAILURE else SuspensionState.CANCELLED,
                        reconciling.rowVersion,
                        if (runActive) "NOT_DELIVERED" else AgentRunTerminalResult.RUN_TERMINATED_NOT_DELIVERED,
                    )
                }
            }
            AdapterDeliveryFact.UNKNOWN -> store.recordDeliveryFact(
                reconciling.id,
                expectedState,
                if (runActive) SuspensionState.USER_DECISION_REQUIRED else SuspensionState.DELIVERY_UNKNOWN,
                reconciling.rowVersion,
                if (runActive) "DELIVERY_FACT_UNKNOWN" else AgentRunTerminalResult.RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN,
            )
        }
    }

    private suspend fun makeReadyToResume(
        suspensionId: String,
        policy: AgentInterventionPolicyRegistry.Policy,
    ) {
        val delivered = store.get(suspensionId) ?: return
        if (delivered.status != SuspensionState.DELIVERED.name || !isRunActive(delivered)) return
        val now = System.currentTimeMillis()
        val grantStore = grants
        if (grantStore != null && !grantStore.createIfAbsent(
                AgentCapabilityGrantEntity(
                    grantId = UUID.nameUUIDFromBytes("grant|${delivered.id}|${delivered.executionGeneration}".toByteArray()).toString(),
                    capability = delivered.capabilityId,
                    runId = delivered.runId,
                    runGeneration = delivered.runGeneration,
                    toolCallId = delivered.toolCallId,
                    executionSlot = delivered.executionSlot,
                    operation = delivered.capabilityId,
                    targetBinding = delivered.targetBindingRef,
                    audience = policy.audience,
                    scope = "ONCE",
                    issuedAt = now,
                    expiresAt = now + GRANT_TTL_MILLIS,
                    maxUses = 1,
                    generation = delivered.executionGeneration,
                ),
            )
        ) return
        store.transition(
            delivered.id,
            SuspensionState.DELIVERED,
            SuspensionState.READY_TO_RESUME,
            delivered.rowVersion,
        )
    }

    private suspend fun claimResourceLease(
        suspension: AgentSuspensionEntity,
        policy: AgentInterventionPolicyRegistry.Policy,
    ): Boolean {
        val leaseKind = policy.leaseKind ?: return true
        val leaseStore = resourceLeases ?: return false
        val now = System.currentTimeMillis()
        return leaseStore.claim(
            AgentResourceLeaseEntity(
                resourceRef = suspension.targetBindingRef,
                leaseOwner = suspension.id,
                leaseKind = leaseKind,
                leaseGeneration = suspension.resourceEpoch,
                runId = suspension.runId,
                runGeneration = suspension.runGeneration,
                issuedAt = now,
                expiresAt = now + RESOURCE_LEASE_TTL_MILLIS,
            ),
        )
    }

    private suspend fun releaseResourceLease(
        suspension: AgentSuspensionEntity,
        policy: AgentInterventionPolicyRegistry.Policy,
    ) {
        val leaseKind = policy.leaseKind ?: return
        resourceLeases?.revoke(suspension.targetBindingRef, leaseKind, suspension.id)
    }

    private suspend fun isRunActive(suspension: AgentSuspensionEntity): Boolean =
        store.getRun(suspension.runId)?.let { run ->
            run.runGeneration == suspension.runGeneration && run.status !in TERMINAL_RUN_STATUSES
        } == true

    private fun AgentSuspensionEntity.toTrustedRequest(): TrustedInterventionRequest = TrustedInterventionRequest(
        suspensionId = id,
        runId = runId,
        runGeneration = runGeneration,
        turnId = turnId,
        requestId = requestId,
        toolCallId = toolCallId,
        executionSlot = executionSlot,
        requestHash = requestHash,
        capabilityId = capabilityId,
        reasonSafe = reasonSafe,
        userVisibleContext = userVisibleContext,
        parameters = runCatching { Json.decodeFromString<Map<String, String>>(parametersJson) }.getOrDefault(emptyMap()),
        targetBindingRef = targetBindingRef,
        requestSource = requestSource,
        policyVersion = policyVersion,
        adapterContractVersion = adapterContractVersion,
        bindingGeneration = bindingGeneration,
        executionGeneration = executionGeneration,
        resourceEpoch = resourceEpoch,
        continuation = AgentContinuationKind.valueOf(continuationKind),
        resolutionMaterialKind = ResolutionMaterialKind.valueOf(resolutionMaterialKind),
        resolutionReference = resolutionReference,
        activeSuspensionIdempotencyKey = activeSuspensionIdempotencyKey,
    )

    private fun clearMaterial(suspensionId: String, material: ProtectedResolution) {
        if (material is ProtectedResolution.Ephemeral) ephemeralMaterials.remove(suspensionId, material)
        material.clear()
    }

    private fun clearAndFalse(material: ProtectedResolution): Boolean {
        material.clear()
        return false
    }

    private fun stableKey(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val GRANT_TTL_MILLIS = 5 * 60 * 1000L
        const val RESOURCE_LEASE_TTL_MILLIS = 2 * 60 * 1000L
        val TERMINAL_RUN_STATUSES = setOf(
            AgentRunStatus.COMPLETED.name,
            AgentRunStatus.FAILED.name,
            AgentRunStatus.CANCELLED.name,
        )
    }
}
