package com.android.everytalk.ui.screens.viewmodel
import com.android.everytalk.statecontroller.*

import android.util.Log
import com.android.everytalk.data.DataClass.isBuiltinModel
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.safeApiConfigSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class ConfigManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val apiHandler: ApiHandler,
    private val viewModelScope: CoroutineScope
) {
    private val TAG_CM = "ConfigManager"

    private fun belongsToGroup(config: ApiConfig, representative: ApiConfig): Boolean =
        config.key == representative.key &&
            config.provider == representative.provider &&
            config.address == representative.address

    private fun remapConversationConfigIds(
        removedIds: Set<String>,
        fallbackId: String?,
    ): Map<String, String> {
        if (removedIds.isEmpty()) return stateHolder.conversationApiConfigIds.value
        val current = stateHolder.conversationApiConfigIds.value
        val updated = if (fallbackId == null) {
            current.filterValues { it !in removedIds }
        } else {
            current.mapValues { (_, configId) ->
                if (configId in removedIds) fallbackId else configId
            }
        }
        return if (updated != current) {
            stateHolder.conversationApiConfigIds.updateAndGet { currentMapping ->
                if (fallbackId == null) currentMapping.filterValues { it !in removedIds }
                else currentMapping.mapValues { (_, configId) ->
                    if (configId in removedIds) fallbackId else configId
                }
            }
        } else current
    }

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
            Log.d(TAG_CM, "Skipping duplicate config: ${safeApiConfigSummary(configToAdd)}")
            return
        }

        val finalConfig = if (configs.any { it.id == configToAdd.id })
            configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd

        if (isImageGen) {
            stateHolder._imageGenApiConfigs.update { it + finalConfig }
        } else {
            stateHolder._apiConfigs.update { it + finalConfig }
        }
        Log.d(TAG_CM, "Added new config to in-memory list: ${safeApiConfigSummary(finalConfig)}")

        viewModelScope.launch {
            persistenceManager.saveApiConfigs(if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value, isImageGen)
            Log.d(TAG_CM, "Saved API configs to persistence after adding config ID ${finalConfig.id}")

            val selectedConfig = if (isImageGen) stateHolder._selectedImageGenApiConfig.value else stateHolder._selectedApiConfig.value
            val configList = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value

            if (selectedConfig == null || configList.size == 1) {
                if (isImageGen) {
                    stateHolder._selectedImageGenApiConfig.value = finalConfig
                } else {
                    stateHolder._selectedApiConfig.value = finalConfig
                }
                persistenceManager.saveSelectedConfigIdentifier(finalConfig.id, isImageGen)

                // 绑定到当前会话
                if (isImageGen) {
                    val currentImageConversationId = stateHolder._currentImageGenerationConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.updateAndGet { mapping ->
                        mapping + (currentImageConversationId to finalConfig.id)
                    }
                    persistenceManager.saveConversationApiConfigIds(currentMapping)
                } else {
                    val currentConversationId = stateHolder._currentConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.updateAndGet { mapping ->
                        mapping + (currentConversationId to finalConfig.id)
                    }
                    persistenceManager.saveConversationApiConfigIds(currentMapping)
                }

                Log.d(
                    TAG_CM,
                    "Added and selected new config: ${safeApiConfigSummary(finalConfig)}. Selection saved and bound to session."
                )
            }
        }
    }

    fun updateConfig(configToUpdate: ApiConfig, isImageGen: Boolean = false) {
        if (stateHolder._imageGenApiConfigs.value.any { it.id == configToUpdate.id && it.isBuiltinModel() }) return
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
                    Log.d(TAG_CM, "Config updated in memory: ${safeApiConfigSummary(configToUpdate)}")
                    mutableConfigs
                } else {
                    Log.d(
                        TAG_CM,
                        "Config content identical, no in-memory update: ${safeApiConfigSummary(configToUpdate)}"
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
                    Log.d(TAG_CM, "Updated config was the selected one: ${safeApiConfigSummary(configToUpdate)}")
                }
                // 图片配置仍分开存储，但主聊天也可能正在使用它；更新后同步主选择的内容。
                if (isImageGen && stateHolder._selectedApiConfig.value?.id == configToUpdate.id) {
                    stateHolder._selectedApiConfig.value = configToUpdate
                }
            }
        }
    }

    fun deleteConfig(configToDelete: ApiConfig, isImageGen: Boolean = false) {
        if (configToDelete.isBuiltinModel()) return
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
        val currentConversationMapping = stateHolder.conversationApiConfigIds.value
        val fallbackConfig = updatedConfigs.firstOrNull { belongsToGroup(it, configToDelete) }
            ?: selectedConfigFlow.value
                ?.takeIf { selected -> updatedConfigs.any { it.id == selected.id } }
                ?.let { selected -> updatedConfigs.first { it.id == selected.id } }
            ?: updatedConfigs.firstOrNull()
        val updatedConversationMapping = remapConversationConfigIds(
            removedIds = setOf(configToDelete.id),
            fallbackId = fallbackConfig?.id,
        )
        val conversationMappingChanged = updatedConversationMapping != currentConversationMapping

        configsFlow.value = updatedConfigs
        // 统一聊天的主选择不能继续指向已删除的图片模型。
        if (isImageGen && stateHolder._selectedApiConfig.value?.id == configToDelete.id) {
            apiHandler.cancelCurrentApiJob("Selected image config was deleted")
            stateHolder._selectedApiConfig.value = fallbackConfig
            viewModelScope.launch { persistenceManager.saveSelectedConfigIdentifier(fallbackConfig?.id) }
        }
        Log.d(TAG_CM, "Config removed from memory list: ${safeApiConfigSummary(configToDelete)}")

        if (wasCurrentlySelected) {
            if (!isImageGen) {
                apiHandler.cancelCurrentApiJob("Selected config ID ${configToDelete.id} was deleted")
            }
            
            val newSelectedConfig = fallbackConfig
            selectedConfigFlow.value = newSelectedConfig
            Log.d(TAG_CM, "Deleted config was selected. New in-memory selection: ${safeApiConfigSummary(newSelectedConfig)}")

            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs, isImageGen)
                persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id, isImageGen)
                if (conversationMappingChanged) {
                    persistenceManager.saveConversationApiConfigIds(updatedConversationMapping)
                }
                Log.d(TAG_CM, "Updated configs and new selection (${newSelectedConfig?.id ?: "null"}) saved to persistence.")
            }
        } else {
            viewModelScope.launch {
                persistenceManager.saveApiConfigs(updatedConfigs, isImageGen)
                if (conversationMappingChanged) {
                    persistenceManager.saveConversationApiConfigIds(updatedConversationMapping)
                }
                Log.d(TAG_CM, "Updated API configs list (after deletion) saved to persistence.")
            }
        }
    }

    fun clearAllConfigs(isImageGen: Boolean = false) {
        deleteMatchingConfigs(isImageGen, "清空自定义配置") { !it.isBuiltinModel() }
    }

    fun selectConfig(config: ApiConfig, isImageGen: Boolean = false) {
        if (isImageGen) {
            stateHolder._selectedImageGenApiConfig.value = config
            selectConfig(config, isImageGen = false)
            return
        }
        val selectedConfigFlow = if (isImageGen) stateHolder._selectedImageGenApiConfig else stateHolder._selectedApiConfig

        if (selectedConfigFlow.value?.id != config.id) {
            if (!isImageGen) {
                apiHandler.cancelCurrentApiJob("Switching selected config to ID ${config.id}")
            }
            selectedConfigFlow.value = config
            
            if (isImageGen) {
                Log.d(TAG_CM, "=== IMAGE GEN CONFIG SELECTED ===")
                Log.d(TAG_CM, "ConfigSummary: ${safeApiConfigSummary(config)}")
                Log.d(TAG_CM, "ModalityType: ${config.modalityType}")
            } else {
                Log.d(TAG_CM, "Selected config in memory: ${safeApiConfigSummary(config)}")
            }

            viewModelScope.launch {
                persistenceManager.saveSelectedConfigIdentifier(config.id, isImageGen)
                Log.d(TAG_CM, "Selected config ID (${config.id}) saved to persistence.")
                
                // 修复：将配置ID绑定到当前会话，确保切换会话时能恢复正确的模型
                if (!isImageGen) {
                    // 文本模式：绑定到当前文本会话ID
                    val currentConversationId = stateHolder._currentConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.updateAndGet { mapping ->
                        mapping + (currentConversationId to config.id)
                    }
                    persistenceManager.saveConversationApiConfigIds(currentMapping)
                    Log.d(TAG_CM, "Bound config ${config.id} to text conversation $currentConversationId")
                } else {
                    // 图像模式：绑定到当前图像会话ID
                    val currentImageConversationId = stateHolder._currentImageGenerationConversationId.value
                    val currentMapping = stateHolder.conversationApiConfigIds.updateAndGet { mapping ->
                        mapping + (currentImageConversationId to config.id)
                    }
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

    /** 成组删除：同一平台、地址和密钥下的模型属于同一配置组。 */
    fun deleteConfigGroup(representativeConfig: ApiConfig, isImageGen: Boolean = false) {
        deleteMatchingConfigs(
            isImageGen = isImageGen,
            cancelReason = "Selected config group removed",
        ) { belongsToGroup(it, representativeConfig) }
    }

    /** 删除配置组中用户明确勾选的已下架模型。 */
    fun deleteModelsFromConfigGroup(
        representativeConfig: ApiConfig,
        modelNames: Collection<String>,
        isImageGen: Boolean = false,
    ) {
        val normalizedModels = modelNames
            .mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
        if (normalizedModels.isEmpty()) return
        deleteMatchingConfigs(
            isImageGen = isImageGen,
            cancelReason = "Selected unavailable models removed",
        ) {
            belongsToGroup(it, representativeConfig) &&
                it.model.trim().lowercase(Locale.ROOT) in normalizedModels
        }
    }

    /** 单次更新内存和持久化，避免批量删除时多个保存任务互相覆盖。 */
    private fun deleteMatchingConfigs(
        isImageGen: Boolean,
        cancelReason: String,
        matches: (ApiConfig) -> Boolean,
    ) {
        viewModelScope.launch {
            val originalConfigs = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value
            val configsToKeep = originalConfigs.filter { it.isBuiltinModel() || !matches(it) }

            if (configsToKeep.size != originalConfigs.size) {
                val removedIds = originalConfigs
                    .filter { config -> configsToKeep.none { it.id == config.id } }
                    .mapTo(mutableSetOf()) { it.id }
                val selectedConfigFlow = if (isImageGen) {
                    stateHolder._selectedImageGenApiConfig
                } else {
                    stateHolder._selectedApiConfig
                }
                val newSelectedConfig = selectedConfigFlow.value
                    ?.takeIf { selected -> selected.id !in removedIds }
                    ?: configsToKeep.firstOrNull()
                val updatedConversationMapping = remapConversationConfigIds(
                    removedIds = removedIds,
                    fallbackId = newSelectedConfig?.id,
                )

                if (isImageGen) {
                    stateHolder._imageGenApiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep, isImageGen = true)
                    if (stateHolder._selectedImageGenApiConfig.value?.id != newSelectedConfig?.id) {
                        stateHolder._selectedImageGenApiConfig.value = newSelectedConfig
                        persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id, isImageGen = true)
                    }
                    // 删除图片配置时同步主聊天；删除文本配置则保留当前有效的图片选择。
                    if (stateHolder._selectedApiConfig.value?.id in removedIds) {
                        apiHandler.cancelCurrentApiJob(cancelReason)
                        stateHolder._selectedApiConfig.value = newSelectedConfig
                        persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id)
                    }
                } else {
                    stateHolder._apiConfigs.value = configsToKeep
                    persistenceManager.saveApiConfigs(configsToKeep)
                    if (stateHolder._selectedApiConfig.value?.id != newSelectedConfig?.id) {
                        apiHandler.cancelCurrentApiJob(cancelReason)
                        stateHolder._selectedApiConfig.value = newSelectedConfig
                        persistenceManager.saveSelectedConfigIdentifier(newSelectedConfig?.id)
                    }
                }
                persistenceManager.saveConversationApiConfigIds(updatedConversationMapping)
            }
        }
    }

    /**
     * 成组更新配置。模型自己的协议覆盖保存在 ModelParameters，不影响这里的分组。
     * isImageGen 若为 null，则根据 representativeConfig.modalityType 自动判断。
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
        if (representativeConfig.isBuiltinModel()) return
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
                        cfg.address == representativeConfig.address) {
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
                        selected.address == representativeConfig.address) {
                        stateHolder._selectedImageGenApiConfig.value =
                            selected.copy(provider = trimmedProvider, address = trimmedAddress, key = trimmedKey, channel = trimmedChannel)
                    }
                    stateHolder._selectedApiConfig.value?.let { mainSelected ->
                        newConfigs.firstOrNull { it.id == mainSelected.id }?.let {
                            stateHolder._selectedApiConfig.value = it
                        }
                    }
                }
            } else {
                val currentConfigs = stateHolder._apiConfigs.value
                val newConfigs = currentConfigs.map { cfg ->
                    if (cfg.key == representativeConfig.key &&
                        cfg.provider == representativeConfig.provider &&
                        cfg.address == representativeConfig.address) {
                        
                        // 仅当参数不为 null 时才更新（支持部分更新）
                        var updatedCfg = cfg.copy(
                            provider = trimmedProvider,
                            address = trimmedAddress,
                            key = trimmedKey,
                            channel = trimmedChannel,
                            modelParameters = if (cfg.channel.equals(trimmedChannel, ignoreCase = true)) {
                                cfg.modelParameters
                            } else {
                                ModelParameters(
                                    // 组协议变化时保留当前模型原本使用的协议。
                                    apiProtocolOverride = (
                                        cfg.modelParameters.apiProtocolOverride
                                            ?: modelParameterProtocol(cfg.channel)
                                        ).takeUnless { it == modelParameterProtocol(trimmedChannel) },
                                )
                            },
                        )
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
                        selected.address == representativeConfig.address) {
                        
                        stateHolder._selectedApiConfig.value = newConfigs.first { it.id == selected.id }
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
