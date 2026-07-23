package com.android.everytalk.statecontroller.controller.config

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.rethrowIfCancellation
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.UUID

internal fun modelConfigGroupId(config: ApiConfig): String {
    return listOf(
        config.provider,
        config.address,
        config.channel,
        config.key,
        config.modalityType.name,
    ).joinToString("\u0000")
}

/**
 * 负责模型拉取与配置批量管理的业务逻辑。
 *
 * 通过传入的 showSnackbar 回调向 UI 报告提示。
 */
class ModelAndConfigController(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val modelFetchManager: com.android.everytalk.statecontroller.viewmodel.ModelFetchManager,
    private val configManager: ConfigManager,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
) {
    private val modelRequestLock = Any()
    private var modelRequestGeneration = 0L
    private var modelRequestJob: Job? = null

    fun fetchModels(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        onResult: (Result<List<String>>) -> Unit,
    ) {
        launchLatestModelRequest(
            apiUrl = apiUrl,
            apiKey = apiKey,
            channel = channel,
            onSuccess = { models -> onResult(Result.success(models)) },
            onFailure = { error ->
                Log.e("ModelAndConfig", "Failed to fetch models", error)
                onResult(Result.failure(error))
            },
        )
    }

    fun clearFetchedModels() {
        synchronized(modelRequestLock) {
            modelRequestGeneration++
            modelRequestJob?.cancel()
            modelRequestJob = null
            modelFetchManager.setFetchedModels(emptyList())
            modelFetchManager.setRefreshingModel(null)
            stateHolder._showModelSelectionDialog.value = false
        }
    }

    fun createMultipleConfigs(
        provider: String,
        address: String,
        key: String,
        modelNames: List<String>,
        channel: String = "OpenAI兼容",
        isImageGen: Boolean = false,
        enableCodeExecution: Boolean? = null,
        toolsJson: String? = null,
        imageSize: String? = null,
        numInferenceSteps: Int? = null,
        guidanceScale: Float? = null,
    ) {
        if (modelNames.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }
        scope.launch {
            val successfulConfigs = mutableListOf<String>()
            val failedConfigs = mutableListOf<String>()

            modelNames.forEach { modelName ->
                try {
                    val config = ApiConfig(
                        address = address.trim(),
                        key = key.trim(),
                        model = modelName,
                        provider = provider,
                        name = modelName,
                        id = UUID.randomUUID().toString(),
                        isValid = true,
                        modalityType = if (isImageGen) {
                            com.android.everytalk.data.DataClass.ModalityType.IMAGE
                        } else {
                            com.android.everytalk.data.DataClass.ModalityType.TEXT
                        },
                        channel = channel,
                        enableCodeExecution = enableCodeExecution,
                        toolsJson = toolsJson,
                        imageSize = imageSize,
                        numInferenceSteps = numInferenceSteps,
                        guidanceScale = guidanceScale,
                    )
                    configManager.addConfig(config, isImageGen)
                    successfulConfigs.add(modelName)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    Log.e("ModelAndConfig", "Failed to create config for model: $modelName", e)
                    failedConfigs.add(modelName)
                }
            }

            if (successfulConfigs.isNotEmpty()) {
                showSnackbar("成功创建 ${successfulConfigs.size} 个配置")
            }
            if (failedConfigs.isNotEmpty()) {
                showSnackbar("${failedConfigs.size} 个配置创建失败")
            }
        }
    }

    fun addModelToConfigGroup(representativeConfig: ApiConfig, modelName: String) {
        val trimmedModelName = modelName.trim()
        if (trimmedModelName.isEmpty()) return

        configManager.addConfig(
            representativeConfig.copy(
                id = UUID.randomUUID().toString(),
                model = trimmedModelName,
                name = trimmedModelName,
            ),
            representativeConfig.modalityType == ModalityType.IMAGE,
        )
    }

    fun refreshModelsForConfig(config: ApiConfig) {
        val refreshId = modelConfigGroupId(config)
        val isImageGen = config.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
        stateHolder._pendingConfigParams.value = null
        stateHolder._showAutoFetchConfirmDialog.value = false
        launchLatestModelRequest(
            apiUrl = config.address,
            apiKey = config.key,
            channel = config.channel,
            refreshId = refreshId,
            onSuccess = { models ->
                if (models.isEmpty()) {
                    showSnackbar("未获取到任何模型")
                } else {
                    stateHolder._pendingConfigParams.value = com.android.everytalk.statecontroller.PendingConfigParams(
                        provider = config.provider,
                        address = config.address,
                        key = config.key,
                        channel = config.channel,
                        isImageGen = isImageGen,
                        enableCodeExecution = config.enableCodeExecution,
                        toolsJson = config.toolsJson,
                        imageSize = config.imageSize,
                        numInferenceSteps = config.numInferenceSteps,
                        guidanceScale = config.guidanceScale,
                        isRefresh = true,
                    )
                    stateHolder._showModelSelectionDialog.value = true
                }
            },
            onFailure = { error ->
                Log.e("ModelAndConfig", "刷新模型失败", error)
                showSnackbar("刷新模型失败: ${error.message}")
            },
        )
    }

    private fun launchLatestModelRequest(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        refreshId: String? = null,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        lateinit var requestJob: Job
        synchronized(modelRequestLock) {
            val generation = ++modelRequestGeneration
            modelRequestJob?.cancel()
            modelFetchManager.setFetchedModels(emptyList())
            modelFetchManager.setRefreshingModel(refreshId)
            stateHolder._showModelSelectionDialog.value = false

            requestJob = scope.launch(start = CoroutineStart.LAZY) {
                val models = try {
                    withContext(Dispatchers.IO) {
                        ApiClient.getModels(apiUrl, apiKey, channel)
                    }
                } catch (e: CancellationException) {
                    synchronized(modelRequestLock) {
                        if (modelRequestGeneration == generation) {
                            modelFetchManager.setRefreshingModel(null)
                            modelRequestJob = null
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    synchronized(modelRequestLock) {
                        if (modelRequestGeneration != generation) return@launch
                        modelFetchManager.setRefreshingModel(null)
                        modelRequestJob = null
                        onFailure(e)
                    }
                    return@launch
                }

                synchronized(modelRequestLock) {
                    if (modelRequestGeneration != generation) return@launch
                    modelFetchManager.setFetchedModels(models)
                    modelFetchManager.setRefreshingModel(null)
                    modelRequestJob = null
                    onSuccess(models)
                }
            }
            modelRequestJob = requestJob
        }
        requestJob.start()
    }

    fun replaceModelsForConfigGroup(params: com.android.everytalk.statecontroller.PendingConfigParams, modelNames: List<String>) {
        val requestedModels = modelNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requestedModels.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }

        scope.launch {
            val currentConfigs = if (params.isImageGen) {
                stateHolder._imageGenApiConfigs.value
            } else {
                stateHolder._apiConfigs.value
            }

            val belongsToGroup: (ApiConfig) -> Boolean = {
                it.key == params.key &&
                    it.provider == params.provider &&
                    it.address == params.address &&
                    it.channel == params.channel
            }
            val oldGroup = currentConfigs.filter(belongsToGroup)
            if (oldGroup.isEmpty()) {
                showSnackbar("配置组已不存在")
                return@launch
            }

            val oldByModel = oldGroup.associateBy { it.model }
            val template = oldGroup.first()
            val refreshedGroup = requestedModels.map { modelName ->
                oldByModel[modelName] ?: template.copy(
                    id = UUID.randomUUID().toString(),
                    model = modelName,
                    name = modelName,
                    modalityType = if (params.isImageGen) ModalityType.IMAGE else ModalityType.TEXT,
                )
            }
            val refreshedByModel = refreshedGroup.associateBy { it.model }
            val oldIds = oldGroup.mapTo(mutableSetOf()) { it.id }
            val currentSelectedConfig = if (params.isImageGen) {
                stateHolder._selectedImageGenApiConfig.value
            } else {
                stateHolder._selectedApiConfig.value
            }
            val fallback = currentSelectedConfig
                ?.takeIf { it.id in oldIds }
                ?.let { refreshedByModel[it.model] }
                ?: refreshedGroup.first()
            val oldIdToNewId = oldGroup.associate { oldConfig ->
                oldConfig.id to (refreshedByModel[oldConfig.model] ?: fallback).id
            }
            val finalConfigs = currentConfigs.filterNot(belongsToGroup) + refreshedGroup
            val currentConversationMapping = stateHolder.conversationApiConfigIds.value
            val updatedConversationMapping = currentConversationMapping.mapValues { (_, configId) ->
                oldIdToNewId[configId] ?: configId
            }

            if (params.isImageGen) {
                stateHolder._imageGenApiConfigs.value = finalConfigs
            } else {
                stateHolder._apiConfigs.value = finalConfigs
            }
            if (updatedConversationMapping != currentConversationMapping) {
                stateHolder.conversationApiConfigIds.update { currentMapping ->
                    currentMapping.mapValues { (_, configId) -> oldIdToNewId[configId] ?: configId }
                }
            }

            val newSelection = currentSelectedConfig
                ?.takeIf { it.id in oldIds }
                ?.let { selected ->
                    val targetId = oldIdToNewId.getValue(selected.id)
                    refreshedGroup.first { it.id == targetId }
                }
            if (newSelection != null && newSelection != currentSelectedConfig) {
                if (params.isImageGen) {
                    stateHolder._selectedImageGenApiConfig.value = newSelection
                } else {
                    stateHolder._selectedApiConfig.value = newSelection
                }
                persistenceManager.saveSelectedConfigIdentifier(newSelection.id, params.isImageGen)
            }

            persistenceManager.saveApiConfigs(finalConfigs, params.isImageGen)
            if (updatedConversationMapping != currentConversationMapping) {
                persistenceManager.saveConversationApiConfigIds(updatedConversationMapping)
            }

            showSnackbar("刷新成功，已更新 ${requestedModels.size} 个模型")
        }
    }
}
