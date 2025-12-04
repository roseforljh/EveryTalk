package com.android.everytalk.statecontroller.controller

import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SettingsController
 * 负责设置导出/导入（文本、图像、语音三类配置）。
 */
class SettingsController(
    private val context: android.content.Context,
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val providerManager: ProviderManager,
    private val dataSource: com.android.everytalk.data.local.SharedPreferencesDataSource,
    private val exportManager: ExportManager,
    private val json: Json,
    private val showSnackbar: (String) -> Unit,
    private val scope: CoroutineScope
) {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class ExportedSettings(
        val apiConfigs: List<ApiConfig>,
        @EncodeDefault
        val customProviders: Set<String> = emptySet(),
        // 会话生成参数（温度、maxTokens等）
        @EncodeDefault
        val conversationParameters: Map<String, com.android.everytalk.data.DataClass.GenerationConfig> = emptyMap(),
        // 新增：语音后端配置（STT/Chat/TTS）
        @EncodeDefault
        val voiceBackendConfigs: List<VoiceBackendConfig> = emptyList()
    )

    @OptIn(ExperimentalSerializationApi::class)
    fun exportSettings() {
        scope.launch(Dispatchers.IO) {
            // 1. 导出非默认的 API 配置 (文本/图像)
            val mainConfigsToExport = stateHolder._apiConfigs.value.filter {
                it.provider.trim().lowercase() !in listOf("默认", "default")
            }
            val imageConfigsToExport = stateHolder._imageGenApiConfigs.value.filter {
                it.provider.trim().lowercase() !in listOf("默认", "default")
            }
            val allConfigsToExport = mainConfigsToExport + imageConfigsToExport
            
            // 2. 导出会话生成参数 (conversationGenerationConfigs)
            val conversationParams = stateHolder.conversationGenerationConfigs.value
            
            // 3. 导出语音后端配置 (STT/Chat/TTS)
            // 同步当前 SharedPreferences 中的语音配置到列表，确保导出的是最新状态
            val currentLegacyConfig = createVoiceConfigFromLegacyPrefs()
            val currentVoiceConfigs = stateHolder._voiceBackendConfigs.value.toMutableList()
            
            // 检查是否已存在相同配置（简单判断：如果列表为空则添加，否则更新第一个或添加）
            // 这里简化处理：将当前 Legacy 配置作为最新的“当前配置”添加或更新到列表
            // 实际逻辑：找到当前选中的配置并更新它，或者如果没选中，就创建一个新的
            val selectedId = stateHolder._selectedVoiceConfig.value?.id
            if (selectedId != null) {
                val index = currentVoiceConfigs.indexOfFirst { it.id == selectedId }
                if (index != -1) {
                    currentVoiceConfigs[index] = currentLegacyConfig.copy(id = selectedId, name = currentVoiceConfigs[index].name)
                } else {
                    currentVoiceConfigs.add(currentLegacyConfig)
                }
            } else {
                // 如果没有选中的配置，且列表为空，则添加一个默认的
                if (currentVoiceConfigs.isEmpty()) {
                    currentVoiceConfigs.add(currentLegacyConfig)
                }
                // 如果有列表但没选中，通常不会发生，或者可以忽略
            }
            
            val voiceConfigsToExport = currentVoiceConfigs.toList()
            
            val settingsToExport = ExportedSettings(
                apiConfigs = allConfigsToExport,
                customProviders = providerManager.customProviders.value,
                conversationParameters = conversationParams,
                voiceBackendConfigs = voiceConfigsToExport
            )
            
            // 强制使用 encodeDefaults = true 的 Json 实例，确保空列表/Map 也能被序列化输出
            // 这样用户在导出的 JSON 中能明确看到这些字段的存在
            val exportJson = Json(json) {
                encodeDefaults = true
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }

            val finalJson = exportJson.encodeToString(settingsToExport)
            val fileName = "eztalk_settings"
            exportManager.requestSettingsExport(fileName, finalJson)
        }
    
    }

    fun importSettings(jsonContent: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. 解析配置 (优先尝试新格式)
                val importedSettings = try {
                    json.decodeFromString<ExportedSettings>(jsonContent)
                } catch (e: Exception) {
                    // 尝试旧格式兼容 (List<ApiConfig>)
                    try {
                        val oldList = json.decodeFromString<List<ApiConfig>>(jsonContent)
                        ExportedSettings(apiConfigs = oldList)
                    } catch (e2: Exception) {
                        throw IllegalStateException("JSON content does not match any known valid format.")
                    }
                }

                // 2. 过滤导入数据中的默认配置（防止污染）
                val validImportedConfigs = importedSettings.apiConfigs.filter {
                    it.provider.trim().lowercase() !in listOf("默认", "default") &&
                    it.id.isNotBlank() &&
                    it.provider.isNotBlank()
                }

                // 3. Clean & Replace 逻辑：
                //    - 保留现有的默认配置
                //    - 清除现有的非默认配置
                //    - 插入导入的非默认配置
                
                // --- 处理通用配置 (文本 + 音频/语音) ---
                // 音频配置目前与文本配置共用 _apiConfigs 列表
                val currentMainDefaults = stateHolder._apiConfigs.value.filter {
                    it.provider.trim().lowercase() in listOf("默认", "default")
                }
                val importedMainConfigs = validImportedConfigs.filter {
                    it.modalityType != com.android.everytalk.data.DataClass.ModalityType.IMAGE
                }
                val newMainConfigs = currentMainDefaults + importedMainConfigs
                
                // --- 处理图像配置 ---
                val currentImageDefaults = stateHolder._imageGenApiConfigs.value.filter {
                    it.provider.trim().lowercase() in listOf("默认", "default")
                }
                val importedImageConfigs = validImportedConfigs.filter {
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
                }
                val newImageConfigs = currentImageDefaults + importedImageConfigs

                // 4. 更新状态与持久化
                
                // 更新通用配置 (文本 + 音频)
                stateHolder._apiConfigs.value = newMainConfigs
                persistenceManager.saveApiConfigs(newMainConfigs, isImageGen = false)
                // 修正选中项（如果当前选中的被删除了，重置为第一个）
                val currentSelectedMain = stateHolder._selectedApiConfig.value
                if (currentSelectedMain != null && newMainConfigs.none { it.id == currentSelectedMain.id }) {
                    val newSel = newMainConfigs.firstOrNull()
                    stateHolder._selectedApiConfig.value = newSel
                    persistenceManager.saveSelectedConfigIdentifier(newSel?.id, isImageGen = false)
                }

                // 更新图像配置
                stateHolder._imageGenApiConfigs.value = newImageConfigs
                persistenceManager.saveApiConfigs(newImageConfigs, isImageGen = true)
                // 修正选中项
                val currentSelectedImage = stateHolder._selectedImageGenApiConfig.value
                if (currentSelectedImage != null && newImageConfigs.none { it.id == currentSelectedImage.id }) {
                    val newSel = newImageConfigs.firstOrNull()
                    stateHolder._selectedImageGenApiConfig.value = newSel
                    persistenceManager.saveSelectedConfigIdentifier(newSel?.id, isImageGen = true)
                }

                // 更新自定义提供商
                val newCustomProviders = importedSettings.customProviders
                providerManager.setCustomProviders(newCustomProviders)
                dataSource.saveCustomProviders(newCustomProviders)
                
                // 更新会话生成参数
                val newConversationParams = importedSettings.conversationParameters
                if (newConversationParams.isNotEmpty()) {
                    stateHolder.conversationGenerationConfigs.value = newConversationParams
                    persistenceManager.saveConversationParameters(newConversationParams)
                }
                
                 // 更新语音后端配置
                val importedVoiceConfigs = importedSettings.voiceBackendConfigs.filter {
                    it.id.isNotBlank()
                }
                if (importedVoiceConfigs.isNotEmpty()) {
                    // 语音配置采用“导入即覆盖”策略：完全以导入内容为准
                    val newVoiceConfigs = importedVoiceConfigs

                    // 尝试保留原来选中的配置ID，否则选中第一个
                    val previousSelectedId = stateHolder._selectedVoiceConfig.value?.id
                    val newSelected = newVoiceConfigs.find { it.id == previousSelectedId }
                        ?: newVoiceConfigs.firstOrNull()

                    stateHolder._voiceBackendConfigs.value = newVoiceConfigs
                    stateHolder._selectedVoiceConfig.value = newSelected
                    persistenceManager.saveVoiceBackendConfigs(newVoiceConfigs)
                    persistenceManager.saveSelectedVoiceConfigId(newSelected?.id)
                    
                    // 关键同步：将导入后选中的配置写入 Legacy SharedPreferences，以便 UI 立即生效
                    if (newSelected != null) {
                        syncVoiceConfigToLegacyPrefs(newSelected)
                    }
                }

                withContext(Dispatchers.Main) {
                    showSnackbar("配置已成功导入 (覆盖模式)")
                }

            } catch (e: Exception) {
                Log.e("SettingsController", "Settings import failed", e)
                withContext(Dispatchers.Main) { showSnackbar("导入失败: 文件内容或格式无效") }
            }
        }
    }
    /**
     * 从 Legacy SharedPreferences ("voice_settings") 读取当前配置并构建 VoiceBackendConfig 对象
     */
    private fun createVoiceConfigFromLegacyPrefs(): VoiceBackendConfig {
        val prefs = context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE)
        
        // STT
        val sttPlatform = prefs.getString("stt_platform", "Google") ?: "Google"
        val sttApiUrl = prefs.getString("stt_api_url_${sttPlatform}", null)
            ?: prefs.getString("stt_api_url", "")?.trim() ?: ""
        val sttModel = prefs.getString("stt_model_${sttPlatform}", null)
            ?: prefs.getString("stt_model", "")?.trim() ?: ""
        val sttApiKey = when (sttPlatform) {
            "OpenAI" -> prefs.getString("stt_key_OpenAI", "") ?: ""
            "SiliconFlow" -> prefs.getString("stt_key_SiliconFlow", "") ?: ""
            else -> prefs.getString("stt_key_Google", "") ?: ""
        }.trim()

        // Chat
        val chatPlatform = prefs.getString("chat_platform", "Google") ?: "Google"
        val chatApiUrl = prefs.getString("chat_api_url_${chatPlatform}", null)
            ?: prefs.getString("chat_api_url", "")?.trim() ?: ""
        val chatModel = prefs.getString("chat_model_${chatPlatform}", null)
            ?: prefs.getString("chat_model", "")?.trim() ?: ""
        val chatApiKey = when (chatPlatform) {
            "OpenAI" -> prefs.getString("chat_key_OpenAI", "") ?: ""
            else -> prefs.getString("chat_key_Google", "") ?: ""
        }.trim()

        // TTS
        val ttsPlatform = prefs.getString("voice_platform", "Gemini") ?: "Gemini"
        val ttsApiUrl = prefs.getString("voice_base_url_${ttsPlatform}", null)
            ?: prefs.getString("voice_base_url", "")?.trim() ?: ""
        val ttsModel = prefs.getString("voice_chat_model_${ttsPlatform}", null)
            ?: prefs.getString("voice_chat_model", "")?.trim() ?: ""
        val ttsApiKey = when (ttsPlatform) {
            "OpenAI" -> prefs.getString("voice_key_OpenAI", "") ?: ""
            "Minimax" -> prefs.getString("voice_key_Minimax", "") ?: ""
            "SiliconFlow" -> prefs.getString("voice_key_SiliconFlow", "") ?: ""
            else -> prefs.getString("voice_key_Gemini", "") ?: ""
        }.trim()
        val voiceName = prefs.getString("voice_name_${ttsPlatform}", null) ?: "Kore"

        return VoiceBackendConfig(
            sttPlatform = sttPlatform,
            sttApiKey = sttApiKey,
            sttApiUrl = sttApiUrl,
            sttModel = sttModel,
            chatPlatform = chatPlatform,
            chatApiKey = chatApiKey,
            chatApiUrl = chatApiUrl,
            chatModel = chatModel,
            ttsPlatform = ttsPlatform,
            ttsApiKey = ttsApiKey,
            ttsApiUrl = ttsApiUrl,
            ttsModel = ttsModel,
            voiceName = voiceName,
            name = "当前配置 (自动导出)"
        )
    }

    /**
     * 将 VoiceBackendConfig 同步写入 Legacy SharedPreferences ("voice_settings")
     */
    private fun syncVoiceConfigToLegacyPrefs(config: VoiceBackendConfig) {
        val prefs = context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // STT
        editor.putString("stt_platform", config.sttPlatform)
        editor.putString("stt_api_url_${config.sttPlatform}", config.sttApiUrl)
        editor.putString("stt_model_${config.sttPlatform}", config.sttModel)
        when (config.sttPlatform) {
            "OpenAI" -> editor.putString("stt_key_OpenAI", config.sttApiKey)
            "SiliconFlow" -> editor.putString("stt_key_SiliconFlow", config.sttApiKey)
            else -> editor.putString("stt_key_Google", config.sttApiKey)
        }

        // Chat
        editor.putString("chat_platform", config.chatPlatform)
        editor.putString("chat_api_url_${config.chatPlatform}", config.chatApiUrl)
        editor.putString("chat_model_${config.chatPlatform}", config.chatModel)
        when (config.chatPlatform) {
            "OpenAI" -> editor.putString("chat_key_OpenAI", config.chatApiKey)
            else -> editor.putString("chat_key_Google", config.chatApiKey)
        }

        // TTS
        editor.putString("voice_platform", config.ttsPlatform)
        editor.putString("voice_base_url_${config.ttsPlatform}", config.ttsApiUrl)
        editor.putString("voice_chat_model_${config.ttsPlatform}", config.ttsModel)
        when (config.ttsPlatform) {
            "OpenAI" -> editor.putString("voice_key_OpenAI", config.ttsApiKey)
            "Minimax" -> editor.putString("voice_key_Minimax", config.ttsApiKey)
            "SiliconFlow" -> editor.putString("voice_key_SiliconFlow", config.ttsApiKey)
            else -> editor.putString("voice_key_Gemini", config.ttsApiKey)
        }
        editor.putString("voice_name_${config.ttsPlatform}", config.voiceName)

        editor.apply()
    }
}