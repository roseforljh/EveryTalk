package com.android.everytalk.data.agent

data class ResolvedBinding(
    val entityId: String,
    val generation: Long,
    val digest: String?,
    val ownerScope: String,
)

/** BindingRef 只是逻辑引用，使用前必须重新解析并校验全部边界。 */
class AgentBindingResolver(
    private val resolveEntity: suspend (BindingRef) -> ResolvedBinding?,
) {
    suspend fun resolve(reference: BindingRef): ResolvedBinding? {
        val resolved = resolveEntity(reference) ?: return null
        if (resolved.entityId != reference.bindingEntityId) return null
        if (resolved.generation != reference.bindingGeneration) return null
        if (resolved.ownerScope != reference.ownerScope) return null
        if (reference.bindingDigest != null && resolved.digest != reference.bindingDigest) return null
        return resolved
    }
}
