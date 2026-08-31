package com.android.everytalk.data.agent

/** Adapter 履行结果。UNKNOWN 永远不会被解释成 NOT_DELIVERED。 */
enum class AdapterDeliveryFact { DELIVERED, NOT_DELIVERED, UNKNOWN }

data class AdapterFulfillmentResult(
    val fact: AdapterDeliveryFact,
    val safeSummary: String? = null,
)

interface AgentInterventionAdapter {
    suspend fun validate(request: TrustedInterventionRequest): Boolean
    suspend fun present(request: TrustedInterventionRequest): String
    suspend fun fulfill(request: TrustedInterventionRequest, protectedResolution: Any?): AdapterFulfillmentResult
    suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact
    suspend fun cleanup(request: TrustedInterventionRequest)
}

/** Adapter 注册表只由本地代码维护，模型不能选择 Adapter。 */
class AgentInterventionAdapterRegistry(
    private val adapters: Map<String, AgentInterventionAdapter> = emptyMap(),
) {
    fun get(audience: String): AgentInterventionAdapter? = adapters[audience]
}
