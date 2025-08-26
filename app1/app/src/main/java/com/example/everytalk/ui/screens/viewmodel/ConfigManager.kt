package com.example.everytalk.ui.screens.viewmodel

import android.util.Log
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.statecontroller.ApiHandler
import com.example.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ConfigManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val apiHandler: ApiHandler,
    private val viewModelScope: CoroutineScope
) {
    private val TAG_CM = "ConfigManager"

    fun addConfig(configToAdd: ApiConfig) {
        val isDuplicate = stateHolder._apiConfigs.value.any {
            it.key == configToAdd.key &&
            it.model == configToAdd.model &&
            it.address == configToAdd.address &&
            it.provider == configToAdd.provider
        }

        if (isDuplicate) {
            Log.d(TAG_CM, "Skipping duplicate config: '${configToAdd.model}'")
            return
        }

        val finalConfig = if (stateHolder._apiConfigs.value.any { it.id == configToAdd.id })
            configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd

        stateHolder._apiConfigs.update { it + finalConfig }
        Log.d(TAG_CM, "Added new config '${finalConfig.model}' to in-memory list.")

        // 立即保存配置到持久化存储
        viewModelScope.launch {
            persistenceManager.saveApiConfigs(stateHolder._apiConfigs.value)
            Log.d(TAG_CM, "Saved API configs to persistence after adding '${finalConfig.model}'")
            
            if (stateHolder._selectedApiConfig.value == null || stateHolder._apiConfigs.value.size == 1) {
                stateHolder._selectedApiConfig.value = finalConfig
                persistenceManager.saveSelectedConfigIdentifier(finalConfig.id)
                Log.d(
                    TAG_CM,
                    "Added and selected new config: ${finalConfig.model}. Selection saved."
                )
            }
        }
        if (stateHolder._selectedApiConfig.value?.id == finalConfig.id) {
            Log.d(TAG_CM, "UI feedback: Added and selected new config: ${finalConfig.model}")
        } else {
            Log.d(
                TAG_CM,
                "UI feedback: Added new config: ${finalConfig.model}, selection unchanged."
            )
        }
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        var listActuallyUpdated = false
        val oldSelectedIdInMemory = stateHolder._selectedApiConfig.value?.id

        stateHolder._apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToUpdate.id }
            if (index != -1) {
                if (currentConfigs[index] != configToUpdate) {
                    val mutableConfigs = currentConfigs.toMutableList()
                    mutableConfigs[index] = configToUpdate
                    listActuallyUpdated = true
                    Log.d(TAG_CM, "Config '${configToUpdate.model}' updated in memory.")
                    mutableConfigs
                } else {
                    Log.d(
                        TAG_CM,
                        "Config '${configToUpdate.model}' content identical, no in-memory update."
                    )
                    currentConfigs
                }
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("更新失败：未找到配置 ID ${configToUpdate.id}") }
                Log.w(TAG_CM, "Update failed: Config not found with ID ${configToUpdate.id}")
                currentConfigs
            }
        }

        if (listActuallyUpdated) {
            viewModelScope.launch {
                persistenceManager.saveApiConfigs(stateHolder._apiConfigs.value)
                Log.d(TAG_CM, "Config list updated, saved API configs list to persistence.")

                if (stateHolder._selectedApiConfig.value?.id == configToUpdate.id) {
                    stateHolder._selectedApiConfig.value = configToUpdate
                    if (oldSelectedIdInMemory != configToUpdate.id) {
                        persistenceManager.saveSelectedConfigIdentifier(configToUpdate.id)
                        Log.d(
                            TAG_CM,
                            "Updated selected config's ID also changed and was saved: ${configToUpdate.id}"
                        )
                    }
                    Log.d(TAG_CM, "Updated config was the selected one: ${configToUpdate.model}")
                }
            }
        } else {
            Log.d(
                TAG_CM,
                "Update called for '${configToUpdate.model}', but no actual changes to save to persistence."
            )
        }
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        val currentConfigs = stateHolder._apiConfigs.value
        val indexToDelete = currentConfigs.indexOfFirst { it.id == configToDelete.id }

        if (indexToDelete == -1) {
            Log.w(TAG_CM, "Attempted to delete a config not found in the list: ID=${configToDelete.id}")
            viewModelScope.launch {
                // Silent failure as per user request
            }
            return
        }

        val wasCurrentlySelected = stateHolder._selectedApiConfig.value?.id == configToDelete.id
        
        val updatedConfigs = currentConfigs.toMutableList().apply {
            removeAt(indexToDelete)
        }.toList()

        stateHolder._apiConfigs.value = updatedConfigs
        Log.d(TAG_CM, "Config with ID ${configToDelete.id} ('${configToDelete.model}') removed from memory list.")

        if (wasCurrentlySelected) {
            apiHandler.cancelCurrentApiJob("Selected config '${configToDelete.model}' was deleted")
            
            val newSelectedConfig = updatedConfigs.firstOrNull()
            stateHolder._selectedApiConfig.value = newSelectedConfig
            Log.d(TAG_CM, "Deleted config was selected. New in-memory selection: ${newSelectedConfig?.model ?: "None"}")

            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs)
                persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id)
                Log.d(TAG_CM, "Updated configs and new selection (${newSelectedConfig?.id ?: "null"}) saved to persistence.")
                
                // Silent deletion as per user request
            }
        } else {
            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs)
                Log.d(TAG_CM, "Updated API configs list (after deletion) saved to persistence.")
                // Silent deletion as per user request
            }
        }
    }

    fun clearAllConfigs() {
        if (stateHolder._apiConfigs.value.isNotEmpty() || stateHolder._selectedApiConfig.value != null) {
            apiHandler.cancelCurrentApiJob("Clearing all configs")
            stateHolder._apiConfigs.value = emptyList()
            stateHolder._selectedApiConfig.value = null
            Log.d(TAG_CM, "In-memory configs and selection cleared.")

            viewModelScope.launch {
                persistenceManager.clearAllApiConfigData()
                Log.d(TAG_CM, "Persistence layer notified to clear all config data.")
                stateHolder._snackbarMessage.emit("所有配置已清除")
                delay(250)
                stateHolder._snackbarMessage.emit("请添加一个 API 配置")
            }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("没有配置可清除") }
            Log.d(TAG_CM, "No configs to clear.")
        }
    }

    fun selectConfig(config: ApiConfig) {
        if (stateHolder._selectedApiConfig.value?.id != config.id) {
            apiHandler.cancelCurrentApiJob("Switching selected config to '${config.model}'")
            stateHolder._selectedApiConfig.value = config
            Log.d(TAG_CM, "Selected config in memory: ${config.model} (${config.provider}).")

            viewModelScope.launch {
                persistenceManager.saveSelectedConfigIdentifier(config.id)
                Log.d(TAG_CM, "Selected config ID (${config.id}) saved to persistence.")
            }
            stateHolder._showSettingsDialog.value = false
        } else {
            stateHolder._showSettingsDialog.value = false
            Log.d(TAG_CM, "Config '${config.model}' was already selected.")
        }
    }
}