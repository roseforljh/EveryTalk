package com.example.everytalk.statecontroller.controller

import android.util.Log
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.statecontroller.ViewModelStateHolder
import com.example.everytalk.statecontroller.viewmodel.ExportManager
import com.example.everytalk.statecontroller.viewmodel.ProviderManager
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SettingsController
 * 负责设置导出/导入（文本与图像两类配置）。
 */
class SettingsController(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val providerManager: ProviderManager,
    private val dataSource: com.example.everytalk.data.local.SharedPreferencesDataSource,
    private val exportManager: ExportManager,
    private val json: Json,
    private val showSnackbar: (String) -> Unit,
    private val scope: CoroutineScope
) {

    @Serializable
    private data class ExportedSettings(
        val apiConfigs: List<ApiConfig>,
        val customProviders: Set<String> = emptySet()
    )

    fun exportSettings(isImageGen: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val settingsToExport = if (isImageGen) {
                ExportedSettings(apiConfigs = stateHolder._imageGenApiConfigs.value)
            } else {
                ExportedSettings(apiConfigs = stateHolder._apiConfigs.value)
            }
            val finalJson = json.encodeToString(settingsToExport)
            val fileName = if (isImageGen) "eztalk_image_settings" else "eztalk_settings"
            exportManager.requestSettingsExport(fileName, finalJson)
        }
    }

    fun importSettings(jsonContent: String, isImageGen: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                // 先尝试新格式
                try {
                    val parsedNew = json.decodeFromString<ExportedSettings>(jsonContent)
                    if (parsedNew.apiConfigs.none { it.id.isBlank() || it.provider.isBlank() }) {
                        if (isImageGen) {
                            stateHolder._imageGenApiConfigs.value = parsedNew.apiConfigs
                            val firstConfig = parsedNew.apiConfigs.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedNew.apiConfigs, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            stateHolder._apiConfigs.value = parsedNew.apiConfigs
                            providerManager.setCustomProviders(parsedNew.customProviders)
                            val firstConfig = parsedNew.apiConfigs.firstOrNull()
                            stateHolder._selectedApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedNew.apiConfigs)
                            dataSource.saveCustomProviders(parsedNew.customProviders)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)
                        }
                        withContext(Dispatchers.Main) { showSnackbar("配置已成功导入") }
                        return@launch
                    }
                } catch (_: Exception) {
                    // 继续尝试旧格式
                }

                // 旧格式：仅 List<ApiConfig>
                try {
                    val parsedOld = json.decodeFromString<List<ApiConfig>>(jsonContent)
                    if (parsedOld.none { it.id.isBlank() || it.provider.isBlank() }) {
                        if (isImageGen) {
                            stateHolder._imageGenApiConfigs.value = parsedOld
                            val firstConfig = parsedOld.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedOld, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            stateHolder._apiConfigs.value = parsedOld
                            providerManager.setCustomProviders(emptySet())
                            val firstConfig = parsedOld.firstOrNull()
                            stateHolder._selectedApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedOld)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)
                        }
                        dataSource.saveCustomProviders(emptySet())
                        val firstConfig = parsedOld.firstOrNull()
                        persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id)
                        withContext(Dispatchers.Main) { showSnackbar("旧版配置已成功导入") }
                        return@launch
                    }
                } catch (_: Exception) {
                    // 落到最终错误
                }

                throw IllegalStateException("JSON content does not match any known valid format.")
            } catch (e: Exception) {
                Log.e("SettingsController", "Settings import failed", e)
                withContext(Dispatchers.Main) { showSnackbar("导入失败: 文件内容或格式无效") }
            }
        }
    }
}