package com.android.everytalk.statecontroller.controller.config

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.withModelCapabilityDefaults
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
import java.util.UUID
import java.util.Locale

internal fun modelConfigGroupId(config: ApiConfig): String {
    return listOf(
        config.provider,
        config.address,
        config.key,
        config.modalityType.name,
    ).joinToString("\u0000")
}

/** 返回待处理配置组里已经添加的模型名称，供刷新弹窗区分新模型和旧模型。 */
internal fun modelsForPendingConfigGroup(
    configs: List<ApiConfig>,
    params: com.android.everytalk.statecontroller.PendingConfigParams?,
): List<String> {
    if (params == null) return emptyList()
    return configs.asSequence()
        .filter {
            it.provider == params.provider &&
                it.address == params.address &&
                it.key == params.key
        }
        .map(ApiConfig::model)
        .distinctBy { it.trim().lowercase(Locale.ROOT) }
        .toList()
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

    suspend fun loadModelParameters(config: ApiConfig): Result<ApiConfig> = try {
        val candidates = withContext(Dispatchers.IO) {
            ApiClient.getModelCapabilities(
                apiUrl = config.address,
                apiKey = config.key,
                channel = config.effectiveModelChannel(),
                modelId = config.model,
                providerHint = config.provider,
            )
        }
        Result.success(config.withModelCapabilityDefaults(candidates))
    } catch (error: Exception) {
        error.rethrowIfCancellation()
        Log.e("ModelAndConfig", "自动获取模型参数失败", error)
        Result.failure(error)
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
        // 在启动协程前保存目录快照，避免界面关闭弹窗时清空目录导致参数丢失。
        val catalogSnapshot = modelNames.associateWith(modelFetchManager::capabilityFor)
        scope.launch {
            // 模型目录经常只有模型名。添加前补拉详情，尽量把端点返回的能力参数直接写入配置。
            val capabilitiesByModel = modelNames.associateWith { modelName ->
                loadCapabilitiesForModel(
                    apiUrl = address,
                    apiKey = key,
                    channel = channel,
                    provider = provider,
                    modelName = modelName,
                    cachedCandidate = catalogSnapshot[modelName],
                )
            }
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
                    ).withModelCapabilityDefaults(capabilitiesByModel[modelName].orEmpty())
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

        val isImageGen = representativeConfig.modalityType == ModalityType.IMAGE
        val catalogCandidate = modelFetchManager.capabilityFor(trimmedModelName)
        val config = representativeConfig.copy(
            id = UUID.randomUUID().toString(),
            model = trimmedModelName,
            name = trimmedModelName,
            modelParameters = ModelParameters(),
        ).withModelCapabilityDefaults(
            listOfNotNull(catalogCandidate)
        )
        configManager.addConfig(config, isImageGen)

        // 手动追加没有可用目录参数时先保存兜底配置，再异步用端点详情覆盖能力字段。
        if (needsCapabilityEnrichment(catalogCandidate)) {
            scope.launch {
                val enriched = config.copy(
                    modelParameters = ModelParameters(),
                ).withModelCapabilityDefaults(
                    loadCapabilitiesForModel(
                        apiUrl = representativeConfig.address,
                        apiKey = representativeConfig.key,
                        channel = representativeConfig.effectiveModelChannel(),
                        provider = representativeConfig.provider,
                        modelName = trimmedModelName,
                        cachedCandidate = catalogCandidate,
                    )
                )
                if (enriched != config) configManager.updateConfig(enriched, isImageGen)
            }
        }
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
                val catalog = try {
                    withContext(Dispatchers.IO) {
                        ApiClient.getModelCatalog(apiUrl, apiKey, channel)
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
                    modelFetchManager.setFetchedCatalog(catalog)
                    modelFetchManager.setRefreshingModel(null)
                    modelRequestJob = null
                    onSuccess(catalog.map { it.modelId })
                }
            }
            modelRequestJob = requestJob
        }
        requestJob.start()
    }

    fun appendModelsToConfigGroup(params: com.android.everytalk.statecontroller.PendingConfigParams, modelNames: List<String>) {
        val requestedModels = modelNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (requestedModels.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }
        // 在启动协程前保存目录快照，避免刷新弹窗关闭时清空目录导致参数丢失。
        val catalogSnapshot = requestedModels.associateWith(modelFetchManager::capabilityFor)
        scope.launch {
            val currentConfigs = if (params.isImageGen) {
                stateHolder._imageGenApiConfigs.value
            } else {
                stateHolder._apiConfigs.value
            }

            val belongsToGroup: (ApiConfig) -> Boolean = {
                it.key == params.key && it.provider == params.provider && it.address == params.address
            }
            val oldGroup = currentConfigs.filter(belongsToGroup)
            if (oldGroup.isEmpty()) {
                showSnackbar("配置组已不存在")
                return@launch
            }

            val existingModelIds = oldGroup.mapTo(mutableSetOf()) {
                it.model.trim().lowercase(Locale.ROOT)
            }
            val modelsToAdd = requestedModels.filter {
                it.lowercase(Locale.ROOT) !in existingModelIds
            }
            if (modelsToAdd.isEmpty()) {
                showSnackbar("没有可添加的新模型")
                return@launch
            }
            // 刷新得到的目录可能只有模型名。对真正要新增的模型补拉详情参数。
            val capabilitiesByModel = modelsToAdd.associateWith { modelName ->
                loadCapabilitiesForModel(
                    apiUrl = params.address,
                    apiKey = params.key,
                    channel = params.channel,
                    provider = params.provider,
                    modelName = modelName,
                    cachedCandidate = catalogSnapshot[modelName],
                )
            }

            // 详情请求期间可能同时执行了“删除已下架模型”。重新读取，避免用旧快照把刚删的配置写回来。
            val latestConfigs = if (params.isImageGen) {
                stateHolder._imageGenApiConfigs.value
            } else {
                stateHolder._apiConfigs.value
            }
            val latestGroup = latestConfigs.filter(belongsToGroup)
            if (latestGroup.isEmpty()) {
                showSnackbar("配置组已不存在")
                return@launch
            }
            val latestExistingModelIds = latestGroup.mapTo(mutableSetOf()) {
                it.model.trim().lowercase(Locale.ROOT)
            }
            val additions = modelsToAdd.filter {
                it.lowercase(Locale.ROOT) !in latestExistingModelIds
            }.map { modelName ->
                latestGroup.first().copy(
                    id = UUID.randomUUID().toString(),
                    model = modelName,
                    name = modelName,
                    modalityType = if (params.isImageGen) ModalityType.IMAGE else ModalityType.TEXT,
                    modelParameters = ModelParameters(),
                ).withModelCapabilityDefaults(capabilitiesByModel[modelName].orEmpty())
            }
            val finalConfigs = latestConfigs + additions

            if (params.isImageGen) {
                stateHolder._imageGenApiConfigs.value = finalConfigs
            } else {
                stateHolder._apiConfigs.value = finalConfigs
            }
            persistenceManager.saveApiConfigs(finalConfigs, params.isImageGen)
            showSnackbar("已添加 ${additions.size} 个新模型")
        }
    }

    /** 删除刷新结果中由用户明确勾选的已下架模型配置。 */
    fun removeModelsFromConfigGroup(
        params: com.android.everytalk.statecontroller.PendingConfigParams,
        modelNames: List<String>,
    ) {
        val normalizedModels = modelNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (normalizedModels.isEmpty()) return

        val currentConfigs = if (params.isImageGen) {
            stateHolder._imageGenApiConfigs.value
        } else {
            stateHolder._apiConfigs.value
        }
        val representative = currentConfigs.firstOrNull {
            it.key == params.key && it.provider == params.provider && it.address == params.address
        }
        if (representative == null) {
            showSnackbar("配置组已不存在")
            return
        }

        configManager.deleteModelsFromConfigGroup(
            representativeConfig = representative,
            modelNames = normalizedModels,
            isImageGen = params.isImageGen,
        )
        showSnackbar("已删除 ${normalizedModels.size} 个已下架模型")
    }

    /**
     * 返回模型的端点能力候选。目录已有完整参数时不重复请求，目录不完整时调用详情接口补齐。
     * 详情接口失败只影响能力增强，调用方仍会使用官方、家族和保守默认值。
     */
    private suspend fun loadCapabilitiesForModel(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        provider: String,
        modelName: String,
        cachedCandidate: ModelCapabilityCandidate? = modelFetchManager.capabilityFor(modelName),
    ): List<ModelCapabilityCandidate> {
        val cached = cachedCandidate
        if (!needsCapabilityEnrichment(cached)) return listOfNotNull(cached)

        val live = try {
            ApiClient.getModelCapabilities(
                apiUrl = apiUrl,
                apiKey = apiKey,
                channel = channel,
                modelId = modelName,
                providerHint = provider,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return buildList {
            cached?.let(::add)
            addAll(live)
        }
    }

    /** 核心 token 限制缺失时需要再请求详情，其他能力字段由解析器和兜底目录补全。 */
    private fun needsCapabilityEnrichment(candidate: ModelCapabilityCandidate?): Boolean {
        if (candidate == null) return true
        return candidate.contextWindowTokens == null || candidate.maxOutputTokens == null
    }
}
