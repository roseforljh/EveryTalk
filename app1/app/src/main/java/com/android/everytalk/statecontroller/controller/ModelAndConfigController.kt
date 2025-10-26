package com.android.everytalk.statecontroller.controller

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 负责模型拉取与配置批量管理的业务逻辑。
 *
 * 通过传入的 showSnackbar 回调向 UI 报告提示；通过 emitManualModelInputRequest
 * 回调触发“手动输入模型”对话框（由 AppViewModel 暴露 SharedFlow）。
 */
class ModelAndConfigController(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val modelFetchManager: com.android.everytalk.statecontroller.viewmodel.ModelFetchManager,
    private val configManager: ConfigManager,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val emitManualModelInputRequest: (provider: String, address: String, key: String, channel: String, isImageGen: Boolean) -> Unit
) {

    val isFetchingModels: StateFlow<Boolean> get() = modelFetchManager.isFetchingModels
    val fetchedModels: StateFlow<List<String>> get() = modelFetchManager.fetchedModels
    val isRefreshingModels: StateFlow<Set<String>> get() = modelFetchManager.isRefreshingModels

    fun fetchModels(apiUrl: String, apiKey: String) {
        scope.launch {
            modelFetchManager.setFetching(true)
            modelFetchManager.setFetchedModels(emptyList())
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(apiUrl, apiKey)
                }
                modelFetchManager.setFetchedModels(models)
                withContext(Dispatchers.Main) { showSnackbar("获取到 ${models.size} 个模型") }
            } catch (e: Exception) {
                Log.e("ModelAndConfig", "Failed to fetch models", e)
                withContext(Dispatchers.Main) { showSnackbar("获取模型失败: ${e.message}") }
            } finally {
                modelFetchManager.setFetching(false)
            }
        }
    }

    fun clearFetchedModels() {
        scope.launch {
            modelFetchManager.setFetchedModels(emptyList())
            modelFetchManager.setFetching(false)
        }
    }

    fun createMultipleConfigs(provider: String, address: String, key: String, modelNames: List<String>) {
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
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.TEXT
                    )
                    configManager.addConfig(config)
                    successfulConfigs.add(modelName)
                } catch (e: Exception) {
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

    fun createConfigAndFetchModels(provider: String, address: String, key: String, channel: String, isImageGen: Boolean = false) {
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(address, key)
                }

                if (models.isNotEmpty()) {
                    val newConfigs = models.map { modelName ->
                        ApiConfig(
                            address = address.trim(),
                            key = key.trim(),
                            model = modelName,
                            provider = provider,
                            name = modelName,
                            id = UUID.randomUUID().toString(),
                            isValid = true,
                            modalityType = if (isImageGen) com.android.everytalk.data.DataClass.ModalityType.IMAGE else com.android.everytalk.data.DataClass.ModalityType.TEXT,
                            channel = channel
                        )
                    }
                    newConfigs.forEach { config ->
                        configManager.addConfig(config, isImageGen)
                    }
                    showSnackbar("成功添加 ${models.size} 个模型")
                } else {
                    // 无模型时直接触发手动输入
                    emitManualModelInputRequest(provider, address, key, channel, isImageGen)
                }
            } catch (e: Exception) {
                Log.e("ModelAndConfig", "获取模型失败", e)
                // 失败时直接触发手动输入
                emitManualModelInputRequest(provider, address, key, channel, isImageGen)
            }
        }
    }

    fun addModelToConfigGroup(apiKey: String, provider: String, address: String, modelName: String, channel: String, isImageGen: Boolean = false) {
        scope.launch {
            val newConfig = ApiConfig(
                id = UUID.randomUUID().toString(),
                name = modelName,
                provider = provider,
                address = address,
                key = apiKey,
                model = modelName,
                modalityType = if (isImageGen) com.android.everytalk.data.DataClass.ModalityType.IMAGE else com.android.everytalk.data.DataClass.ModalityType.TEXT,
                channel = channel
            )
            configManager.addConfig(newConfig, isImageGen)
        }
    }

    fun refreshModelsForConfig(config: ApiConfig) {
        val refreshId = "${config.key}-${config.modalityType}"
        scope.launch {
            modelFetchManager.addRefreshingModel(refreshId)
            try {
                val models = withContext(Dispatchers.IO) {
                    ApiClient.getModels(config.address, config.key)
                }

                // 1. 删除与此API密钥和模态类型匹配的所有现有配置
                val currentConfigs = stateHolder._apiConfigs.value
                val configsToKeep = currentConfigs.filterNot {
                    it.key == config.key &&
                    it.provider == config.provider &&
                    it.address == config.address &&
                    it.channel == config.channel
                }

                // 2. 新配置
                val newConfigs = models.map { modelName ->
                    ApiConfig(
                        address = config.address,
                        key = config.key,
                        model = modelName,
                        provider = config.provider,
                        name = modelName,
                        id = UUID.randomUUID().toString(),
                        isValid = true,
                        modalityType = config.modalityType,
                        channel = config.channel
                    )
                }
                val finalConfigs = configsToKeep + newConfigs

                // 3. 更新并保存
                stateHolder._apiConfigs.value = finalConfigs
                persistenceManager.saveApiConfigs(finalConfigs)

                // 4. 更新选中的配置（如有必要）
                val currentSelectedConfig = stateHolder._selectedApiConfig.value
                if (currentSelectedConfig != null &&
                    currentSelectedConfig.key == config.key &&
                    currentSelectedConfig.provider == config.provider &&
                    currentSelectedConfig.address == config.address &&
                    currentSelectedConfig.channel == config.channel &&
                    !finalConfigs.any { it.id == currentSelectedConfig.id }
                ) {
                    val newSelection = finalConfigs.firstOrNull {
                        it.key == config.key &&
                        it.provider == config.provider &&
                        it.address == config.address &&
                        it.channel == config.channel
                    }
                    stateHolder._selectedApiConfig.value = newSelection
                    persistenceManager.saveSelectedConfigIdentifier(newSelection?.id)
                }

                showSnackbar("刷新成功，获取到 ${models.size} 个模型")
            } catch (e: Exception) {
                Log.e("ModelAndConfig", "刷新模型失败", e)
                showSnackbar("刷新模型失败: ${e.message}")
            } finally {
                modelFetchManager.removeRefreshingModel(refreshId)
            }
        }
    }
}