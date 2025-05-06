package com.example.app1.ui.screens.viewmodel

import com.example.app1.data.models.ApiConfig
import com.example.app1.ui.screens.USER_CANCEL_MESSAGE // 假设常量已正确导入
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages API Configuration operations: add, update, delete, clear, select.
 * Interacts with StateHolder and DataPersistenceManager.
 */
class ConfigManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val apiHandler: ApiHandler,
    private val viewModelScope: CoroutineScope
) {

    fun addConfig(configToAdd: ApiConfig) {
        val contentExists = stateHolder._apiConfigs.value.any { /* ... content check ... */
                existingConfig ->
            existingConfig.address.trim().equals(configToAdd.address.trim(), ignoreCase = true) &&
                    existingConfig.key.trim() == configToAdd.key.trim() &&
                    existingConfig.model.trim().equals(configToAdd.model.trim(), ignoreCase = true) &&
                    existingConfig.provider.trim().equals(configToAdd.provider.trim(), ignoreCase = true)
        }

        if (!contentExists) {
            val finalConfig = if (stateHolder._apiConfigs.value.any { it.id == configToAdd.id })
                configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd

            stateHolder._apiConfigs.update { it + finalConfig }

            var wasSelected = false
            if (stateHolder._selectedApiConfig.value == null) {
                stateHolder._selectedApiConfig.value = finalConfig
                // --- **修改点 1: 保存 ID 而不是 name** ---
                persistenceManager.saveSelectedConfigIdentifier(finalConfig.id) // 保存 ID
                wasSelected = true
            }
            // --- End 修改点 1 ---

            persistenceManager.saveApiConfigs()
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Config '${finalConfig.model}' saved" + if(wasSelected) " and selected." else ".") }
            println("ConfigManager: Added config '${finalConfig.model}'. Auto-selected: $wasSelected")
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Configuration with same content already exists") }
            println("ConfigManager: Config add attempt failed - duplicate content.")
        }
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        var updated = false
        stateHolder._apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToUpdate.id }
            if (index != -1) {
                if (currentConfigs[index] != configToUpdate) {
                    val mutableConfigs = currentConfigs.toMutableList()
                    mutableConfigs[index] = configToUpdate
                    if (stateHolder._selectedApiConfig.value?.id == configToUpdate.id) {
                        stateHolder._selectedApiConfig.value = configToUpdate
                        // 保存 ID (通常不变，但安全起见)
                        persistenceManager.saveSelectedConfigIdentifier(configToUpdate.id)
                    }
                    updated = true
                    mutableConfigs
                } else { currentConfigs }
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("Update failed: Config not found") }
                currentConfigs
            }
        }
        if (updated) {
            persistenceManager.saveApiConfigs()
        }
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        val wasSelected = stateHolder._selectedApiConfig.value?.id == configToDelete.id
        var deletedName: String? = null
        var newSelectedConfig: ApiConfig? = null

        stateHolder._apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToDelete.id }
            if (index != -1) {
                deletedName = currentConfigs[index].model
                val mutableConfigs = currentConfigs.toMutableList()
                mutableConfigs.removeAt(index)

                if (wasSelected) {
                    apiHandler.cancelCurrentApiJob("Selected config deleted")
                    newSelectedConfig = mutableConfigs.firstOrNull()
                    stateHolder._selectedApiConfig.value = newSelectedConfig
                    // --- **修改点 2: 保存新选中的 ID (或 null)** ---
                    persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id) // 保存 ID 或 null
                    println("ConfigManager: Deleted selected config. New selection ID: ${newSelectedConfig?.id}")
                    // --- End 修改点 2 ---
                }
                mutableConfigs
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("Delete failed: Config not found") }
                currentConfigs
            }
        }

        if (deletedName != null) {
            persistenceManager.saveApiConfigs()
            viewModelScope.launch { /* ... snackbar ... */
                if (wasSelected) {
                    stateHolder._snackbarMessage.emit("Selected config '$deletedName' deleted")
                    delay(250)
                    if (newSelectedConfig == null) {
                        stateHolder._snackbarMessage.emit("Please add or select a new API config")
                    } else {
                        stateHolder._snackbarMessage.emit("Auto-selected: ${newSelectedConfig.model}")
                    }
                } else {
                    stateHolder._snackbarMessage.emit("Config '$deletedName' deleted")
                }
            }
        }
    }

    fun clearAllConfigs() {
        if (stateHolder._apiConfigs.value.isNotEmpty()) {
            apiHandler.cancelCurrentApiJob("Clearing all configs")
            stateHolder._apiConfigs.value = emptyList()
            stateHolder._selectedApiConfig.value = null
            // 调用统一清除方法 (它内部应该清除 ID)
            persistenceManager.clearAllApiConfigData()
            println("ConfigManager: Cleared all configs and selection.")
            viewModelScope.launch { /* ... snackbar ... */
                stateHolder._snackbarMessage.emit("All configurations cleared")
                delay(250)
                stateHolder._snackbarMessage.emit("Please add an API configuration")
            }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("No configurations to clear") }
        }
    }

    fun selectConfig(config: ApiConfig) {
        if (stateHolder._selectedApiConfig.value?.id != config.id) {
            apiHandler.cancelCurrentApiJob("Switching selected config")
            stateHolder._selectedApiConfig.value = config
            // --- **修改点 3: 保存选中的 ID** ---
            persistenceManager.saveSelectedConfigIdentifier(config.id) // 保存 ID
            // --- End 修改点 3 ---
            stateHolder._showSettingsDialog.value = false
            println("ConfigManager: Selected config: ${config.model} (${config.provider}). ID saved.")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Selected: ${config.model}") }
        } else {
            stateHolder._showSettingsDialog.value = false
            println("ConfigManager: Config already selected.")
        }
    }
}