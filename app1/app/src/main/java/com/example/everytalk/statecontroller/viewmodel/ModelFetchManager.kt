package com.example.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理模型列表获取状态
 */
class ModelFetchManager {
    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()
    
    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()
    
    private val _isRefreshingModels = MutableStateFlow<Set<String>>(emptySet())
    val isRefreshingModels: StateFlow<Set<String>> = _isRefreshingModels.asStateFlow()
    
    fun setFetching(isFetching: Boolean) {
        _isFetchingModels.value = isFetching
    }
    
    fun setFetchedModels(models: List<String>) {
        _fetchedModels.value = models
    }
    
    fun addRefreshingModel(configId: String) {
        _isRefreshingModels.value = _isRefreshingModels.value + configId
    }
    
    fun removeRefreshingModel(configId: String) {
        _isRefreshingModels.value = _isRefreshingModels.value - configId
    }
}
