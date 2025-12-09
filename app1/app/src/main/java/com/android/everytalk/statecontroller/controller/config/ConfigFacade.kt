package com.android.everytalk.statecontroller.controller.config

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.ui.screens.viewmodel.ConfigManager

/**
 * 聚合配置相关的透传调用，瘦身 AppViewModel。
 * 不变更原有对外方法签名与行为，仅委托给 ConfigManager。
 */
class ConfigFacade(
    private val configManager: ConfigManager
) {
    fun addConfig(config: ApiConfig, isImageGen: Boolean = false) =
        configManager.addConfig(config, isImageGen)

    fun addMultipleConfigs(configs: List<ApiConfig>) {
        val distinctConfigs = configs.distinctBy { it.model }
        distinctConfigs.forEach { config ->
            configManager.addConfig(config)
        }
    }

    fun updateConfig(config: ApiConfig, isImageGen: Boolean = false) =
        configManager.updateConfig(config, isImageGen)

    fun deleteConfig(config: ApiConfig, isImageGen: Boolean = false) =
        configManager.deleteConfig(config, isImageGen)

    fun deleteConfigGroup(representativeConfig: ApiConfig, isImageGen: Boolean = false) =
        configManager.deleteConfigGroup(representativeConfig, isImageGen)

    fun clearAllConfigs(isImageGen: Boolean = false) =
        configManager.clearAllConfigs(isImageGen)

    fun selectConfig(config: ApiConfig, isImageGen: Boolean = false) =
        configManager.selectConfig(config, isImageGen)

    fun clearSelectedConfig(isImageGen: Boolean = false) =
        configManager.clearSelectedConfig(isImageGen)

    fun saveApiConfigs() =
        configManager.saveApiConfigs()

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
        configManager.updateConfigGroup(
            representativeConfig = representativeConfig,
            newProvider = newProvider,
            newAddress = newAddress,
            newKey = newKey,
            newChannel = newChannel,
            isImageGen = isImageGen,
            newEnableCodeExecution = newEnableCodeExecution,
            newToolsJson = newToolsJson
        )
    }
}