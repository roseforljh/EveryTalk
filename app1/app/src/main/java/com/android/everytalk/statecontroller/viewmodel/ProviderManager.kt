package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 管理API提供商列表
 */
class ProviderManager(scope: CoroutineScope) {
    private val predefinedPlatformsList = listOf(
        "openai compatible",
        "google",
        "硅基流动",
        "阿里云百炼",
        "火山引擎",
        "深度求索",
        "OpenRouter"
    )
    
    private val _customProviders = MutableStateFlow<Set<String>>(emptySet())
    val customProviders: StateFlow<Set<String>> = _customProviders.asStateFlow()
    
    val allProviders: StateFlow<List<String>> = combine(_customProviders) { customProvidersArray ->
        predefinedPlatformsList + customProvidersArray[0].toList()
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = predefinedPlatformsList
    )
    
    fun setCustomProviders(providers: Set<String>) {
        _customProviders.value = providers
    }
}
