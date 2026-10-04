package com.android.everytalk.statecontroller.controller.config

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS
import com.android.everytalk.data.DataClass.DEFAULT_MAX_OUTPUT_TOKENS
import com.android.everytalk.data.DataClass.modelParameterProtocol
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
            ApiClient.getFreshModelCapabilities(
                apiUrl = config.address,
                apiKey = config.key,
                channel = config.effectiveModelChannel(),
                modelId = config.model,
                providerHint = config.provider,
            )
        }
        // 全部实时来源都不可用时明确报错，不把旧草稿或保守默认值伪装成获取结果。
        if (candidates.isEmpty()) {
            throw IllegalStateException("未获取到实时模型参数，请检查网络和模型接口")
        }
        // 用户主动获取是为了用新数据恢复参数；旧的手动 Token 值不能再次参与优先级合并。
        // 其余设置仍保留在草稿中，只有用户保存对话框后才写入配置。
        val freshConfig = config.copy(
            maxTokens = null,
            modelParameters = config.modelParameters.copy(
                maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS,
                resolvedCapability = null,
            ),
        )
        val loaded = freshConfig.withModelCapabilityDefaults(candidates)
        val outputSource = loaded.modelParameters.resolvedCapability?.maxOutputSource
        if (outputSource == ModelCapabilitySource.FAMILY_FALLBACK ||
            outputSource == ModelCapabilitySource.CONSERVATIVE_DEFAULT) {
            throw IllegalStateException("实时来源未提供最大输出限制，无法还原这个参数")
        }
        Result.success(loaded)
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
        val catalogSnapshot = modelNames.associateWith(modelFetchManager::capabilitiesFor)
        scope.launch {
            // 复用获取阶段的全部参数；只有未经过获取流程的手动添加才补拉。
            val capabilitiesByModel = modelNames.associateWith { modelName ->
                loadCapabilitiesForModel(
                    apiUrl = address,
                    apiKey = key,
                    channel = channel,
                    provider = provider,
                    modelName = modelName,
                    cachedCandidates = catalogSnapshot[modelName],
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
        val catalogCandidates = modelFetchManager.capabilitiesFor(trimmedModelName)
        val config = representativeConfig.copy(
            id = UUID.randomUUID().toString(),
            model = trimmedModelName,
            name = trimmedModelName,
            modelParameters = ModelParameters(),
        ).withModelCapabilityDefaults(
            catalogCandidates.orEmpty()
        )
        configManager.addConfig(config, isImageGen)

        // 手动追加没有可用目录参数时先保存兜底配置，再异步用端点详情覆盖能力字段。
        if (catalogCandidates == null) {
            scope.launch {
                val candidates = loadCapabilitiesForModel(
                    apiUrl = representativeConfig.address,
                    apiKey = representativeConfig.key,
                    channel = config.effectiveModelChannel(),
                    provider = representativeConfig.provider,
                    modelName = trimmedModelName,
                    cachedCandidates = catalogCandidates,
                )
                // 网络返回后使用最新配置，避免覆盖期间的编辑，也不复活已删除的模型。
                val currentConfigs = if (isImageGen) stateHolder._imageGenApiConfigs.value else stateHolder._apiConfigs.value
                val current = currentConfigs.firstOrNull { it.id == config.id } ?: return@launch
                val enriched = current.withModelCapabilityDefaults(candidates)
                if (enriched != current) configManager.updateConfig(enriched, isImageGen)
            }
        }
    }

    fun refreshModelsForConfig(config: ApiConfig) {
        val refreshId = modelConfigGroupId(config)
        val isImageGen = config.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
        val parameterChannels = (stateHolder._apiConfigs.value + stateHolder._imageGenApiConfigs.value)
            .filter { modelConfigGroupId(it) == refreshId }
            .map { it.effectiveModelChannel() }
            .distinctBy(::modelParameterProtocol)
            .filter { modelParameterProtocol(it) != modelParameterProtocol(config.channel) }
        stateHolder._pendingConfigParams.value = null
        stateHolder._showAutoFetchConfirmDialog.value = false
        launchLatestModelRequest(
            apiUrl = config.address,
            apiKey = config.key,
            channel = config.channel,
            refreshId = refreshId,
            parameterChannels = parameterChannels,
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
        parameterChannels: List<String> = emptyList(),
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
                        val catalog = ApiClient.getModelCatalog(apiUrl, apiKey, channel)
                        val listedModels = catalog.mapTo(mutableSetOf()) { it.modelId.lowercase() }
                        // 单模型可能覆盖平台协议。按协议各获取一次，参数仍匹配自身协议，
                        // 额外目录只补能力，不改变平台列表和已下架判断。
                        val overrides = parameterChannels.flatMap { parameterChannel ->
                            try {
                                ApiClient.getModelCatalog(apiUrl, apiKey, parameterChannel)
                                    .filter { it.modelId.lowercase() in listedModels }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        catalog + overrides
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
                    if (refreshId != null) updateFetchedModelParameters(refreshId)
                    modelFetchManager.setRefreshingModel(null)
                    modelRequestJob = null
                    onSuccess(modelFetchManager.fetchedModels.value)
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
        val catalogSnapshot = requestedModels.associateWith(modelFetchManager::capabilitiesFor)
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
            // 获取阶段已经补拉参数，新增时直接采用快照，关闭弹窗也不会丢失结果。
            val capabilitiesByModel = modelsToAdd.associateWith { modelName ->
                loadCapabilitiesForModel(
                    apiUrl = params.address,
                    apiKey = params.key,
                    channel = params.channel,
                    provider = params.provider,
                    modelName = modelName,
                    cachedCandidates = catalogSnapshot[modelName],
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

    /** 刷新立即更新已保存模型，不需要再打开参数弹窗或重新勾选旧模型。 */
    private fun updateFetchedModelParameters(groupId: String) {
        listOf(false, true).forEach { isImageGen ->
            val configsFlow = if (isImageGen) stateHolder._imageGenApiConfigs else stateHolder._apiConfigs
            val selectedFlow = if (isImageGen) stateHolder._selectedImageGenApiConfig else stateHolder._selectedApiConfig
            val current = configsFlow.value
            val updated = current.map { config ->
                if (modelConfigGroupId(config) != groupId) return@map config
                val protocol = modelParameterProtocol(config.effectiveModelChannel())
                val candidates = modelFetchManager.capabilitiesFor(config.model)
                    ?.filter { it.protocol == protocol }
                    ?.takeIf { it.isNotEmpty() } ?: return@map config
                val previous = config.modelParameters.resolvedCapability
                // 网络暂时缺字段时保留上次结果，但不再把废弃内置表的值转换成本地缓存。
                // 来源明确的手动值由公共解析器优先保留。
                val cached = previous?.let {
                    ModelCapabilityCandidate(
                        modelId = config.model, protocol = protocol, endpointIdentity = it.endpointIdentity,
                        contextWindowTokens = it.contextWindowTokens.takeIf { _ ->
                            candidates.none { it.contextWindowTokens != null } &&
                                it.contextWindowSource !in setOf(ModelCapabilitySource.CONSERVATIVE_DEFAULT, ModelCapabilitySource.FAMILY_FALLBACK, ModelCapabilitySource.OFFICIAL_CATALOG)
                        },
                        maxOutputTokens = it.maxOutputTokens.takeIf { _ ->
                            candidates.none { it.maxOutputTokens != null } &&
                                it.maxOutputSource !in setOf(ModelCapabilitySource.CONSERVATIVE_DEFAULT, ModelCapabilitySource.FAMILY_FALLBACK, ModelCapabilitySource.OFFICIAL_CATALOG)
                        },
                        maxInputTokens = it.maxInputTokens.takeIf { _ -> it.maxInputSource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.maxInputTokens != null } },
                        family = it.family.takeIf { _ -> it.familySource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.family != null } },
                        inputModalities = it.inputModalities.takeIf { _ -> it.modalitiesSource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.inputModalities.isNotEmpty() || it.outputModalities.isNotEmpty() } }.orEmpty(),
                        outputModalities = it.outputModalities.takeIf { _ -> it.modalitiesSource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.inputModalities.isNotEmpty() || it.outputModalities.isNotEmpty() } }.orEmpty(),
                        supportsReasoning = it.supportsReasoning.takeIf { _ -> it.reasoningSource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.supportsReasoning != null || it.reasoningEfforts.isNotEmpty() } },
                        reasoningEfforts = it.reasoningEfforts.takeIf { _ -> it.reasoningSource != ModelCapabilitySource.OFFICIAL_CATALOG && candidates.none { it.supportsReasoning != null || it.reasoningEfforts.isNotEmpty() } }.orEmpty(),
                        source = ModelCapabilitySource.LOCAL_CACHE,
                    )
                }
                // 旧版本没有记录参数来源，只保护其中偏离默认值的限制，避免刷新抹掉手动设置。
                val legacyOverride = if (previous == null) ModelCapabilityCandidate(
                    modelId = config.model, protocol = protocol,
                    contextWindowTokens = config.modelParameters.maxContextTokens.takeIf { it != DEFAULT_MAX_CONTEXT_TOKENS },
                    maxOutputTokens = config.maxTokens?.takeIf { it != DEFAULT_MAX_OUTPUT_TOKENS },
                    source = ModelCapabilitySource.USER_OVERRIDE,
                ) else null
                config.withModelCapabilityDefaults(candidates + listOfNotNull(cached, legacyOverride))
            }
            if (updated != current) {
                configsFlow.value = updated
                selectedFlow.value?.id?.let { id ->
                    updated.firstOrNull { it.id == id }?.let { selectedFlow.value = it }
                }
                // 一组只保存一次，并在执行时读取最新列表，避免排队保存过时快照。
                scope.launch { persistenceManager.saveApiConfigs(configsFlow.value, isImageGen) }
            }
        }
    }

    /** 获取过的模型直接复用所有来源；手动输入的新模型才调用单模型获取接口。 */
    private suspend fun loadCapabilitiesForModel(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        provider: String,
        modelName: String,
        cachedCandidates: List<ModelCapabilityCandidate>? = modelFetchManager.capabilitiesFor(modelName),
    ): List<ModelCapabilityCandidate> {
        if (cachedCandidates != null) return cachedCandidates

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
        return live
    }
}
