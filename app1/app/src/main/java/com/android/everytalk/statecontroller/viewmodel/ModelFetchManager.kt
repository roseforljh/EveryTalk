package com.android.everytalk.statecontroller.viewmodel

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理模型列表获取状态
 */
class ModelFetchManager {
    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()
    private var fetchedCatalogByModel: Map<String, ModelCapabilityCandidate> = emptyMap()
    
    private val _isRefreshingModels = MutableStateFlow<Set<String>>(emptySet())
    val isRefreshingModels: StateFlow<Set<String>> = _isRefreshingModels.asStateFlow()
    
    fun setFetchedModels(models: List<String>) {
        fetchedCatalogByModel = emptyMap()
        _fetchedModels.value = models
    }

    fun setFetchedCatalog(catalog: List<ModelCapabilityCandidate>) {
        fetchedCatalogByModel = catalog.associateBy { it.modelId.lowercase() }
        _fetchedModels.value = catalog.map(ModelCapabilityCandidate::modelId)
    }

    fun capabilityFor(modelId: String): ModelCapabilityCandidate? =
        fetchedCatalogByModel[modelId.trim().lowercase()]
    
    fun setRefreshingModel(configId: String?) {
        _isRefreshingModels.value = configId?.let(::setOf).orEmpty()
    }
}
