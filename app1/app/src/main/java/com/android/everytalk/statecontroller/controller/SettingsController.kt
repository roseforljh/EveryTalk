package com.android.everytalk.statecontroller.controller

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val dataSource: com.android.everytalk.data.local.SharedPreferencesDataSource,
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
            // 过滤掉默认配置（provider 为"默认"或"default"的配置）
            val configsToExport = if (isImageGen) {
                stateHolder._imageGenApiConfigs.value.filter {
                    it.provider.trim().lowercase() !in listOf("默认", "default")
                }
            } else {
                stateHolder._apiConfigs.value.filter {
                    it.provider.trim().lowercase() !in listOf("默认", "default")
                }
            }
            val settingsToExport = ExportedSettings(apiConfigs = configsToExport)
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
                    // 过滤掉导入的默认配置
                    val filteredConfigs = parsedNew.apiConfigs.filter {
                        it.provider.trim().lowercase() !in listOf("默认", "default")
                    }
                    if (filteredConfigs.none { it.id.isBlank() || it.provider.isBlank() }) {
                        if (isImageGen) {
                            // 保留现有的默认配置，只添加导入的非默认配置
                            val existingDefaults = stateHolder._imageGenApiConfigs.value.filter {
                                it.provider.trim().lowercase() in listOf("默认", "default")
                            }
                            stateHolder._imageGenApiConfigs.value = existingDefaults + filteredConfigs
                            val firstConfig = parsedNew.apiConfigs.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedNew.apiConfigs, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            // 保留现有的默认配置，只添加导入的非默认配置
                            val existingDefaults = stateHolder._apiConfigs.value.filter {
                                it.provider.trim().lowercase() in listOf("默认", "default")
                            }
                            stateHolder._apiConfigs.value = existingDefaults + filteredConfigs
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
                    // 过滤掉导入的默认配置
                    val filteredOldConfigs = parsedOld.filter {
                        it.provider.trim().lowercase() !in listOf("默认", "default")
                    }
                    if (filteredOldConfigs.none { it.id.isBlank() || it.provider.isBlank() }) {
                        if (isImageGen) {
                            // 保留现有的默认配置，只添加导入的非默认配置
                            val existingDefaults = stateHolder._imageGenApiConfigs.value.filter {
                                it.provider.trim().lowercase() in listOf("默认", "default")
                            }
                            stateHolder._imageGenApiConfigs.value = existingDefaults + filteredOldConfigs
                            val firstConfig = parsedOld.firstOrNull()
                            stateHolder._selectedImageGenApiConfig.value = firstConfig
                            persistenceManager.saveApiConfigs(parsedOld, isImageGen = true)
                            persistenceManager.saveSelectedConfigIdentifier(firstConfig?.id, isImageGen = true)
                        } else {
                            // 保留现有的默认配置，只添加导入的非默认配置
                            val existingDefaults = stateHolder._apiConfigs.value.filter {
                                it.provider.trim().lowercase() in listOf("默认", "default")
                            }
                            stateHolder._apiConfigs.value = existingDefaults + filteredOldConfigs
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