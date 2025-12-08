package com.android.everytalk.ui.screens.viewmodel

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
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
            it.provider == configToAdd.provider &&
            it.channel == configToAdd.channel
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
                
                // 🔧 修复：将配置ID绑定到当前会话，确保切换会话时能恢复正确的模型
                if (!isImageGen) {
                    // 文本模式：绑定到当前文本会话ID
                    val currentConversationId = stateHolder._currentConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.value.toMutableMap()
                    currentMapping[currentConversationId] = config.id
                    stateHolder.conversationApiConfigIds.value = currentMapping
                    persistenceManager.saveConversationApiConfigIds(currentMapping)
                    Log.d(TAG_CM, "Bound config ${config.id} to text conversation $currentConversationId")
                } else {
                    // 图像模式：绑定到当前图像会话ID
                    val currentImageConversationId = stateHolder._currentImageGenerationConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.value.toMutableMap()
                    currentMapping[currentImageConversationId] = config.id
                    stateHolder.conversationApiConfigIds.value = currentMapping
                    persistenceManager.saveConversationApiConfigIds(currentMapping)
                    Log.d(TAG_CM, "Bound config ${config.id} to image conversation $currentImageConversationId")
                }
            }
        }
        if (isImageGen) {
            // Potentially close a different dialog if needed
        } else {
            stateHolder._showSettingsDialog.value = false
        }
    }

    /**
     * 成组删除：按 key/provider/address/channel 删除同组配置
     */
    fun deleteConfigGroup(representativeConfig: ApiConfig, isImageGen: Boolean = false) {
        viewModelScope.launch {
            val originalConfigs = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value
            val configsToKeep = originalConfigs.filterNot {
                it.key == representativeConfig.key &&
                it.provider == representativeConfig.provider &&
                it.address == representativeConfig.address &&
                it.channel == representativeConfig.channel
            }

            if (configsToKeep.size != originalConfigs.size) {
                if (isImageGen) {
                    stateHolder._imageGenApiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep, isImageGen = true)
                    val sel = stateHolder._selectedImageGenApiConfig.value
                    if (sel != null &&
                        sel.key == representativeConfig.key &&
                        sel.provider == representativeConfig.provider &&
                        sel.address == representativeConfig.address &&
                        sel.channel == representativeConfig.channel) {
                        val newSel = configsToKeep.firstOrNull()
                        stateHolder._selectedImageGenApiConfig.value = newSel
                        persistenceManager.saveSelectedConfigIdentifier(newSel?.id, isImageGen = true)
                    }
                } else {
                    stateHolder._apiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep)
                    val sel = stateHolder._selectedApiConfig.value
                    if (sel != null &&
                        sel.key == representativeConfig.key &&
                        sel.provider == representativeConfig.provider &&
                        sel.address == representativeConfig.address &&
                        sel.channel == representativeConfig.channel) {
                        apiHandler.cancelCurrentApiJob("Selected config group removed")
                        val newSel = configsToKeep.firstOrNull()
                        stateHolder._selectedApiConfig.value = newSel
                        persistenceManager.saveSelectedConfigIdentifier(newSel?.id)
                    }
                }
            }
        }
    }

    /**
     * 成组更新：按 key/provider/address/channel 匹配后，批量更新 address/key/channel
     * isImageGen 若为 null，则根据 representativeConfig.modalityType 自动判断
     */
    fun updateConfigGroup(
        representativeConfig: ApiConfig,
        newProvider: String,
        newAddress: String,
        newKey: String,
        newChannel: String,
        isImageGen: Boolean? = null,
        newEnableCodeExecution: Boolean? = null,
        newToolsJson: String? = null
    ) {
        viewModelScope.launch {
            val trimmedProvider = newProvider.trim()
            val trimmedAddress = newAddress.trim()
            val trimmedKey = newKey.trim()
            val trimmedChannel = newChannel.trim()
            val trimmedToolsJson = newToolsJson?.trim()

            val useImageGen = isImageGen
                ?: (representativeConfig.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE)

            if (useImageGen) {
                val currentConfigs = stateHolder._imageGenApiConfigs.value
                val newConfigs = currentConfigs.map { cfg ->
                    if (cfg.key == representativeConfig.key &&
                        cfg.provider == representativeConfig.provider &&
                        cfg.address == representativeConfig.address &&
                        cfg.channel == representativeConfig.channel) {
                        // 图像模式暂不支持 tools
                        cfg.copy(provider = trimmedProvider, address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                    } else cfg
                }
                if (newConfigs != currentConfigs) {
                    stateHolder._imageGenApiConfigs.value = newConfigs
                    persistenceManager.saveApiConfigs(newConfigs, isImageGen = true)

                    val selected = stateHolder._selectedImageGenApiConfig.value
                    if (selected != null &&
                        selected.key == representativeConfig.key &&
                        selected.provider == representativeConfig.provider &&
                        selected.address == representativeConfig.address &&
                        selected.channel == representativeConfig.channel) {
                        stateHolder._selectedImageGenApiConfig.value =
                            selected.copy(provider = trimmedProvider, address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                    }
                }
            } else {
                val currentConfigs = stateHolder._apiConfigs.value
                val newConfigs = currentConfigs.map { cfg ->
                    if (cfg.key == representativeConfig.key &&
                        cfg.provider == representativeConfig.provider &&
                        cfg.address == representativeConfig.address &&
                        cfg.channel == representativeConfig.channel) {
                        
                        // 仅当参数不为 null 时才更新（支持部分更新）
                        var updatedCfg = cfg.copy(provider = trimmedProvider, address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                        if (newEnableCodeExecution != null) {
                            updatedCfg = updatedCfg.copy(enableCodeExecution = newEnableCodeExecution)
                        }
                        if (trimmedToolsJson != null) {
                            updatedCfg = updatedCfg.copy(toolsJson = trimmedToolsJson.ifBlank { null })
                        }
                        updatedCfg
                    } else cfg
                }
                if (newConfigs != currentConfigs) {
                    stateHolder._apiConfigs.value = newConfigs
                    persistenceManager.saveApiConfigs(newConfigs)

                    val selected = stateHolder._selectedApiConfig.value
                    if (selected != null &&
                        selected.key == representativeConfig.key &&
                        selected.provider == representativeConfig.provider &&
                        selected.address == representativeConfig.address &&
                        selected.channel == representativeConfig.channel) {
                        
                        var updatedSel = selected.copy(provider = trimmedProvider, address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                        if (newEnableCodeExecution != null) {
                            updatedSel = updatedSel.copy(enableCodeExecution = newEnableCodeExecution)
                        }
                        if (trimmedToolsJson != null) {
                            updatedSel = updatedSel.copy(toolsJson = trimmedToolsJson.ifBlank { null })
                        }
                        stateHolder._selectedApiConfig.value = updatedSel
                    }
                }
            }
        }
    }

    /**
     * 清空当前选中的配置
     */
    fun clearSelectedConfig(isImageGen: Boolean = false) {
        if (isImageGen) {
            stateHolder._selectedImageGenApiConfig.value = null
            viewModelScope.launch { persistenceManager.saveSelectedConfigIdentifier(null, isImageGen = true) }
        } else {
            stateHolder._selectedApiConfig.value = null
            viewModelScope.launch { persistenceManager.saveSelectedConfigIdentifier(null) }
        }
    }

    /**
     * 将当前内存中的文本配置落库
     */
    fun saveApiConfigs() {
        viewModelScope.launch {
            persistenceManager.saveApiConfigs(stateHolder._apiConfigs.value)
        }
    }
}