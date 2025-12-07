package com.android.everytalk.statecontroller.controller

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
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
    // Removed dataSource: SharedPreferencesDataSource
    private val persistenceManager: DataPersistenceManager,
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
            persistenceManager.saveCustomProviders(updated)
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
            persistenceManager.saveCustomProviders(updated)
        }
    }
}