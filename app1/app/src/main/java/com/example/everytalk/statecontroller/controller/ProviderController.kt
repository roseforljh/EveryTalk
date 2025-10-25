package com.example.everytalk.statecontroller.controller

import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.statecontroller.ViewModelStateHolder
import com.example.everytalk.statecontroller.viewmodel.ProviderManager
import com.example.everytalk.ui.screens.viewmodel.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 负责自定义 Provider 的增删以及与配置的联动清理。
 * - 新增 Provider（去重并持久化）
 * - 删除 Provider（联动删除使用该 Provider 的配置，并持久化）
 */
class ProviderController(
    private val stateHolder: ViewModelStateHolder,
    private val providerManager: ProviderManager,
    private val configManager: ConfigManager,
    private val dataSource: SharedPreferencesDataSource,
    private val scope: CoroutineScope
) {

    fun addProvider(providerName: String) {
        val trimmed = providerName.trim()
        if (trimmed.isBlank()) return
        val current = providerManager.customProviders.value
        if (current.contains(trimmed)) return

        val updated = current + trimmed
        providerManager.setCustomProviders(updated)
        scope.launch(Dispatchers.IO) {
            dataSource.saveCustomProviders(updated)
        }
    }

    fun deleteProvider(providerName: String) {
        val current = providerManager.customProviders.value
        if (!current.contains(providerName)) return

        // 删除所有使用该 provider 的配置
        val toDelete: List<ApiConfig> = stateHolder._apiConfigs.value.filter { it.provider == providerName }
        toDelete.forEach { cfg -> configManager.deleteConfig(cfg) }

        // 从自定义 provider 集合移除并持久化
        val updated = current - providerName
        providerManager.setCustomProviders(updated)
        scope.launch(Dispatchers.IO) {
            dataSource.saveCustomProviders(updated)
        }
    }
}