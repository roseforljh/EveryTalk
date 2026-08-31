package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentStoredAuthorizationEntity

/** 只管理长期授权的非敏感元数据和 Secure Store 引用。 */
class AgentStoredAuthorizationStore(private val dao: AgentDao) {
    suspend fun save(authorization: StoredAuthorization) {
        require(authorization.userConsentScope in setOf("WORKSPACE", "COMPUTER")) { "长期授权范围无效" }
        require(authorization.provider.isNotBlank() && authorization.credentialReference.isNotBlank()) {
            "长期授权 provider 和安全引用不能为空"
        }
        when (authorization.userConsentScope) {
            "WORKSPACE" -> require(!authorization.workspaceId.isNullOrBlank()) { "WORKSPACE 授权必须绑定 workspaceId" }
            "COMPUTER" -> require(!authorization.computerId.isNullOrBlank()) { "COMPUTER 授权必须绑定 computerId" }
        }
        check(dao.insertStoredAuthorizationIfAbsent(
            AgentStoredAuthorizationEntity(
                authorization.authorizationId,
                authorization.provider,
                authorization.credentialReference,
                authorization.userConsentScope,
                authorization.workspaceId,
                authorization.computerId,
                authorization.issuedAt,
                authorization.expiresAt,
                authorization.revoked,
                authorization.generation,
            ),
        ) != -1L) { "StoredAuthorization 已存在，禁止覆盖或重新打开旧授权" }
    }

    suspend fun getReference(
        id: String,
        provider: String,
        generation: Long,
        workspaceId: String? = null,
        computerId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val authorization = dao.getStoredAuthorization(id) ?: return null
        if (authorization.revoked || authorization.expiresAt?.let { it <= now } == true) return null
        if (authorization.provider != provider || authorization.generation != generation) return null
        when (authorization.userConsentScope) {
            "WORKSPACE" -> if (workspaceId == null || authorization.workspaceId != workspaceId) return null
            "COMPUTER" -> if (computerId == null || authorization.computerId != computerId) return null
            else -> return null
        }
        return authorization.credentialReference
    }

    suspend fun revoke(id: String): Boolean = dao.revokeStoredAuthorization(id) == 1
}
