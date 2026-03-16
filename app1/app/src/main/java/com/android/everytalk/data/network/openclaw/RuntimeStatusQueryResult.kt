package com.android.everytalk.data.network.openclaw

data class ModelsCatalogQueryResult(
    val ok: Boolean,
    val supported: Boolean = true,
    val providerGroups: List<OpenClawProviderModelsGroup> = emptyList(),
    val rawPayloadSummary: String? = null,
    val errorMessage: String? = null
)

data class OpenClawProviderModelsGroup(
    val provider: String,
    val models: List<String> = emptyList()
)
