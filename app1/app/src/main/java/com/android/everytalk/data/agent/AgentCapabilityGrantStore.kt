package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentCapabilityGrantEntity

/** CapabilityGrant 的持久原子消费入口。UNKNOWN 后保持 RESERVED，禁止自动恢复 AVAILABLE。 */
class AgentCapabilityGrantStore(private val dao: AgentDao) {
    suspend fun create(grant: AgentCapabilityGrantEntity) {
        require(grant.capability.isNotBlank()) { "CapabilityGrant capability 不能为空" }
        require(grant.operation.isNotBlank() && grant.targetBinding.isNotBlank() && grant.audience.isNotBlank()) {
            "CapabilityGrant 的 operation、target 和 audience 必须明确绑定"
        }
        require(grant.maxUses > 0 && grant.usageCount == 0) { "CapabilityGrant 初始使用次数无效" }
        require(grant.expiresAt > grant.issuedAt) { "CapabilityGrant TTL 无效" }
        check(dao.insertCapabilityGrantForActiveRun(grant)) {
            "CapabilityGrant 已存在，或 AgentRun 已终止、generation 已变化"
        }
    }

    suspend fun claimUse(
        grantId: String,
        runId: String,
        runGeneration: Long,
        toolCallId: String,
        executionSlot: String,
        operation: String,
        targetBinding: String,
        audience: String,
        generation: Long,
        attemptId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = dao.claimGrantUse(
        grantId,
        runId,
        runGeneration,
        toolCallId,
        executionSlot,
        operation,
        targetBinding,
        audience,
        generation,
        now,
        attemptId,
    ) == 1

    suspend fun consume(grantId: String, attemptId: String): Boolean =
        dao.consumeGrant(grantId, attemptId) == 1

    suspend fun revoke(grantId: String): Boolean = dao.revokeGrant(grantId) == 1
}
