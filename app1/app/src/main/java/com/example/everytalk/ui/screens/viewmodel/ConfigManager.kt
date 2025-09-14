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

    fun addConfig(configToAdd: ApiConfig, isImageGen: Boolean = false) {
        val configs = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value

        val isDuplicate = configs.any {
            it.key == configToAdd.key &&
            it.model == configToAdd.model &&
            it.address == configToAdd.address &&
            it.provider == configToAdd.provider
        }

        if (isDuplicate) {
            Log.d(TAG_CM, "Skipping duplicate config: '${configToAdd.model}'")
            return
        }

        val finalConfig = if (configs.any { it.id == configToAdd.id })
            configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd

        if (isImageGen) {
            stateHolder._imageGenApiConfigs.update { it + finalConfig }
        } else {
            stateHolder._apiConfigs.update { it + finalConfig }
        }
        Log.d(TAG_CM, "Added new config '${finalConfig.model}' to in-memory list.")

        viewModelScope.launch {
            persistenceManager.saveApiConfigs(if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value, isImageGen)
            Log.d(TAG_CM, "Saved API configs to persistence after adding '${finalConfig.model}'")

            val selectedConfig = if (isImageGen) stateHolder._selectedImageGenApiConfig.value else stateHolder._selectedApiConfig.value
            val configList = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value

            if (selectedConfig == null || configList.size == 1) {
                if (isImageGen) {
                    stateHolder._selectedImageGenApiConfig.value = finalConfig
                } else {
                    stateHolder._selectedApiConfig.value = finalConfig
                }
                persistenceManager.saveSelectedConfigIdentifier(finalConfig.id, isImageGen)
                Log.d(
                    TAG_CM,
                    "Added and selected new config: ${finalConfig.model}. Selection saved."
                )
            }
        }
    }

    fun updateConfig(configToUpdate: ApiConfig, isImageGen: Boolean = false) {
        var listActuallyUpdated = false
        val selectedConfigFlow = if (isImageGen) stateHolder._selectedImageGenApiConfig else stateHolder._selectedApiConfig
        val configsFlow = if (isImageGen) stateHolder._imageGenApiConfigs else stateHolder._apiConfigs

        val oldSelectedIdInMemory = selectedConfigFlow.value?.id

        configsFlow.update { currentConfigs ->
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
                persistenceManager.saveApiConfigs(configsFlow.value, isImageGen)
                Log.d(TAG_CM, "Config list updated, saved API configs list to persistence.")

                if (selectedConfigFlow.value?.id == configToUpdate.id) {
                    selectedConfigFlow.value = configToUpdate
                    if (oldSelectedIdInMemory != configToUpdate.id) {
                        persistenceManager.saveSelectedConfigIdentifier(configToUpdate.id, isImageGen)
                        Log.d(
                            TAG_CM,
                            "Updated selected config's ID also changed and was saved: ${configToUpdate.id}"
                        )
                    }
                    Log.d(TAG_CM, "Updated config was the selected one: ${configToUpdate.model}")
                }
            }
        }
    }

    fun deleteConfig(configToDelete: ApiConfig, isImageGen: Boolean = false) {
        val configsFlow = if (isImageGen) stateHolder._imageGenApiConfigs else stateHolder._apiConfigs
        val selectedConfigFlow = if (isImageGen) stateHolder._selectedImageGenApiConfig else stateHolder._selectedApiConfig

        val currentConfigs = configsFlow.value
        val indexToDelete = currentConfigs.indexOfFirst { it.id == configToDelete.id }

        if (indexToDelete == -1) {
            Log.w(TAG_CM, "Attempted to delete a config not found in the list: ID=${configToDelete.id}")
            return
        }

        val wasCurrentlySelected = selectedConfigFlow.value?.id == configToDelete.id
        
        val updatedConfigs = currentConfigs.toMutableList().apply {
            removeAt(indexToDelete)
        }.toList()

        configsFlow.value = updatedConfigs
        Log.d(TAG_CM, "Config with ID ${configToDelete.id} ('${configToDelete.model}') removed from memory list.")

        if (wasCurrentlySelected) {
            if (!isImageGen) {
                apiHandler.cancelCurrentApiJob("Selected config '${configToDelete.model}' was deleted")
            }
            
            val newSelectedConfig = updatedConfigs.firstOrNull()
            selectedConfigFlow.value = newSelectedConfig
            Log.d(TAG_CM, "Deleted config was selected. New in-memory selection: ${newSelectedConfig?.model ?: "None"}")

            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs, isImageGen)
                persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id, isImageGen)
                Log.d(TAG_CM, "Updated configs and new selection (${newSelectedConfig?.id ?: "null"}) saved to persistence.")
            }
        } else {
            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs, isImageGen)
                Log.d(TAG_CM, "Updated API configs list (after deletion) saved to persistence.")
            }
        }
    }

    fun clearAllConfigs(isImageGen: Boolean = false) {
        if (isImageGen) {
            if (stateHolder._imageGenApiConfigs.value.isNotEmpty() || stateHolder._selectedImageGenApiConfig.value != null) {
                stateHolder._imageGenApiConfigs.value = emptyList()
                stateHolder._selectedImageGenApiConfig.value = null
                viewModelScope.launch {
                    persistenceManager.saveApiConfigs(emptyList(), true)
                    persistenceManager.saveSelectedConfigIdentifier(null, true)
                    stateHolder._snackbarMessage.emit("所有图像生成配置已清除")
                }
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("没有图像生成配置可清除") }
            }
        } else {
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
    }

    fun selectConfig(config: ApiConfig, isImageGen: Boolean = false) {
        val selectedConfigFlow = if (isImageGen) stateHolder._selectedImageGenApiConfig else stateHolder._selectedApiConfig

        if (selectedConfigFlow.value?.id != config.id) {
            if (!isImageGen) {
                apiHandler.cancelCurrentApiJob("Switching selected config to '${config.model}'")
            }
            selectedConfigFlow.value = config
            
            if (isImageGen) {
                Log.d(TAG_CM, "=== IMAGE GEN CONFIG SELECTED ===")
                Log.d(TAG_CM, "Config ID: ${config.id}")
                Log.d(TAG_CM, "Model: ${config.model}")
                Log.d(TAG_CM, "Provider: ${config.provider}")
                Log.d(TAG_CM, "Channel: ${config.channel}")
                Log.d(TAG_CM, "Address: ${config.address}")
                Log.d(TAG_CM, "ModalityType: ${config.modalityType}")
            } else {
                Log.d(TAG_CM, "Selected config in memory: ${config.model} (${config.provider}).")
            }

            viewModelScope.launch {
                persistenceManager.saveSelectedConfigIdentifier(config.id, isImageGen)
                Log.d(TAG_CM, "Selected config ID (${config.id}) saved to persistence.")
            }
        }
        if (isImageGen) {
            // Potentially close a different dialog if needed
        } else {
            stateHolder._showSettingsDialog.value = false
        }
    }
}