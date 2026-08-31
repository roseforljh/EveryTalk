package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentResourceLeaseEntity

/** 资源级持久互斥。主键冲突即 claim 失败，不能退回内存 Mutex。 */
class AgentResourceLeaseStore(private val dao: AgentDao) {
    suspend fun claim(lease: AgentResourceLeaseEntity): Boolean {
        require(lease.resourceRef.isNotBlank() && lease.leaseOwner.isNotBlank() && lease.leaseKind.isNotBlank()) {
            "ResourceLease 绑定不能为空"
        }
        require(lease.leaseGeneration >= 0 && lease.expiresAt > lease.issuedAt) { "ResourceLease generation 或 TTL 无效" }
        return dao.claimResourceLease(
            resourceRef = lease.resourceRef,
            leaseOwner = lease.leaseOwner,
            leaseKind = lease.leaseKind,
            leaseGeneration = lease.leaseGeneration,
            runId = lease.runId,
            runGeneration = lease.runGeneration,
            issuedAt = lease.issuedAt,
            expiresAt = lease.expiresAt,
        )
    }

    suspend fun revoke(resourceRef: String, leaseKind: String, owner: String): Boolean =
        dao.revokeResourceLease(resourceRef, leaseKind, owner) == 1
}
