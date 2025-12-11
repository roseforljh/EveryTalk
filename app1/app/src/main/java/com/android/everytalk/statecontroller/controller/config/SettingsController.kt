package com.android.everytalk.statecontroller.controller.config

import android.util.Base64
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * SettingsController
 * 负责设置导出/导入（文本、图像、语音三类配置 + 聊天历史）。
 *
 * 增强功能：
 * 1. 支持聊天历史导入导出
 * 2. 增强数据验证逻辑
 * 3. 提供详细错误信息
 * 4. API密钥混淆保护
 * 5. 文件大小限制
 */
class SettingsController(
    private val context: android.content.Context,
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val providerManager: ProviderManager,
    private val exportManager: ExportManager,
    private val json: Json,
    private val showSnackbar: (String) -> Unit,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SettingsController"
        private const val EXPORT_VERSION = 3 // 升级版本以支持密钥混淆
        private const val MIN_API_KEY_LENGTH = 10
        private const val MAX_API_KEY_LENGTH = 500
        private const val MAX_IMPORT_FILE_SIZE = 50 * 1024 * 1024 // 50MB
        private const val OBFUSCATION_PREFIX = "EZT_OBF_V1:" // 混淆标记前缀
        
        // 密钥混淆警告消息
        const val EXPORT_SECURITY_WARNING = "⚠️ 导出文件包含敏感API密钥，请妥善保管，切勿分享给他人！"
    }

    // ==================== 密钥混淆工具方法 ====================
    
    /**
     * 混淆API密钥（简单的Base64编码 + XOR混淆）
     * 注意：这不是加密，仅用于防止密钥明文暴露
     */
    private fun obfuscateKey(key: String): String {
        if (key.isBlank()) return key
        return try {
            val xorKey = 0x5A.toByte()
            val xored = key.toByteArray(Charsets.UTF_8).map { (it.toInt() xor xorKey.toInt()).toByte() }.toByteArray()
            OBFUSCATION_PREFIX + Base64.encodeToString(xored, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obfuscate key", e)
            key
        }
    }
    
    /**
     * 还原混淆的API密钥
     */
    private fun deobfuscateKey(obfuscatedKey: String): String {
        if (obfuscatedKey.isBlank()) return obfuscatedKey
        if (!obfuscatedKey.startsWith(OBFUSCATION_PREFIX)) return obfuscatedKey // 兼容旧版本明文密钥
        return try {
            val encoded = obfuscatedKey.removePrefix(OBFUSCATION_PREFIX)
            val xorKey = 0x5A.toByte()
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            String(decoded.map { (it.toInt() xor xorKey.toInt()).toByte() }.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deobfuscate key, returning as-is", e)
            obfuscatedKey
        }
    }
    
    /**
     * 混淆ApiConfig中的密钥
     */
    private fun obfuscateApiConfig(config: ApiConfig): ApiConfig {
        return config.copy(key = obfuscateKey(config.key))
    }
    
    /**
     * 还原ApiConfig中的密钥
     */
    private fun deobfuscateApiConfig(config: ApiConfig): ApiConfig {
        return config.copy(key = deobfuscateKey(config.key))
    }
    
    /**
     * 混淆VoiceBackendConfig中的密钥
     */
    private fun obfuscateVoiceConfig(config: VoiceBackendConfig): VoiceBackendConfig {
        return config.copy(
            sttApiKey = obfuscateKey(config.sttApiKey),
            chatApiKey = obfuscateKey(config.chatApiKey),
            ttsApiKey = obfuscateKey(config.ttsApiKey)
        )
    }
    
    /**
     * 还原VoiceBackendConfig中的密钥
     */
    private fun deobfuscateVoiceConfig(config: VoiceBackendConfig): VoiceBackendConfig {
        return config.copy(
            sttApiKey = deobfuscateKey(config.sttApiKey),
            chatApiKey = deobfuscateKey(config.chatApiKey),
            ttsApiKey = deobfuscateKey(config.ttsApiKey)
        )
    }

    // ==================== 数据类定义 ====================

    /** 导出的消息数据类（简化版，不包含复杂对象） */
    @Serializable
    data class ExportedMessage(
        val id: String,
        val text: String,
        val sender: String,
        val reasoning: String? = null,
        val timestamp: Long,
        val isError: Boolean = false,
        val imageUrls: List<String>? = null
    )

    /** 导出的会话数据类 */
    @Serializable
    data class ExportedConversation(
        val id: String,
        val messages: List<ExportedMessage>,
        val createdAt: Long,
        val lastModifiedAt: Long
    )

    /** 验证结果类 */
    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Failure(val errors: List<String>) : ValidationResult()
    }

    /** 导入结果类 */
    data class ImportResult(
        var configsImported: Int = 0,
        var voiceConfigsImported: Int = 0,
        var chatHistoryImported: Int = 0,
        var imageHistoryImported: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
        val warnings: MutableList<String> = mutableListOf()
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class ExportedSettings(
        @EncodeDefault
        val version: Int = EXPORT_VERSION,
        @EncodeDefault
        val exportTimestamp: Long = System.currentTimeMillis(),
        val apiConfigs: List<ApiConfig>,
        @EncodeDefault
        val customProviders: Set<String> = emptySet(),
        @EncodeDefault
        val conversationParameters: Map<String, com.android.everytalk.data.DataClass.GenerationConfig> = emptyMap(),
        @EncodeDefault
        val voiceBackendConfigs: List<VoiceBackendConfig> = emptyList(),
        // 新增：聊天历史
        @EncodeDefault
        val chatHistory: List<ExportedConversation> = emptyList(),
        @EncodeDefault
        val imageGenerationHistory: List<ExportedConversation> = emptyList(),
        // 新增：置顶状态
        @EncodeDefault
        val pinnedTextIds: Set<String> = emptySet(),
        @EncodeDefault
        val pinnedImageIds: Set<String> = emptySet(),
        // 新增：分组信息
        @EncodeDefault
        val conversationGroups: Map<String, List<String>> = emptyMap()
    )

    @OptIn(ExperimentalSerializationApi::class)
    fun exportSettings(includeHistory: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. 导出所有 API 配置 (文本/图像) - 混淆密钥后导出
                val mainConfigsToExport = stateHolder._apiConfigs.value.map { obfuscateApiConfig(it) }
                val imageConfigsToExport = stateHolder._imageGenApiConfigs.value.map { obfuscateApiConfig(it) }
                val allConfigsToExport = mainConfigsToExport + imageConfigsToExport
                
                // 详细日志：记录每个配置的关键信息（不记录密钥）
                Log.i(TAG, "=== 导出配置开始 ===")
                Log.i(TAG, "文本配置数量: ${mainConfigsToExport.size}")
                mainConfigsToExport.forEachIndexed { index, config ->
                    Log.i(TAG, "  文本配置[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}, model=${config.model}")
                }
                Log.i(TAG, "图像配置数量: ${imageConfigsToExport.size}")
                imageConfigsToExport.forEachIndexed { index, config ->
                    Log.i(TAG, "  图像配置[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}, model=${config.model}")
                }
                
                // 2. 导出会话生成参数 (conversationGenerationConfigs)
                val conversationParams = stateHolder.conversationGenerationConfigs.value
                
                // 3. 导出语音后端配置 (STT/Chat/TTS) - 混淆密钥后导出
                val voiceConfigsToExport = stateHolder._voiceBackendConfigs.value.map { obfuscateVoiceConfig(it) }
                
                Log.i(TAG, "语音配置数量: ${voiceConfigsToExport.size}")
                voiceConfigsToExport.forEachIndexed { index, config ->
                    Log.i(TAG, "  语音配置[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}")
                    Log.i(TAG, "    STT: platform=${config.sttPlatform}, model=${config.sttModel}, url=${config.sttApiUrl}")
                    Log.i(TAG, "    Chat: platform=${config.chatPlatform}, model=${config.chatModel}, url=${config.chatApiUrl}")
                    Log.i(TAG, "    Chat API Key 非空: ${config.chatApiKey.isNotBlank()}, 长度: ${config.chatApiKey.length}")
                    Log.i(TAG, "    TTS: platform=${config.ttsPlatform}, model=${config.ttsModel}, url=${config.ttsApiUrl}, voice=${config.voiceName}")
                    Log.i(TAG, "    realtime=${config.useRealtimeStreaming}")
                }
                
                // 4. 导出聊天历史（可选）
                val chatHistoryToExport = if (includeHistory) {
                    stateHolder._historicalConversations.value.mapNotNull { conversation ->
                        exportConversation(conversation)
                    }
                } else emptyList()
                
                val imageHistoryToExport = if (includeHistory) {
                    stateHolder._imageGenerationHistoricalConversations.value.mapNotNull { conversation ->
                        exportConversation(conversation)
                    }
                } else emptyList()
                
                // 5. 导出置顶状态
                val pinnedTextIds = stateHolder.pinnedTextConversationIds.value
                val pinnedImageIds = stateHolder.pinnedImageConversationIds.value
                
                // 6. 导出分组信息
                val groups = stateHolder.conversationGroups.value
                
                val settingsToExport = ExportedSettings(
                    version = EXPORT_VERSION,
                    exportTimestamp = System.currentTimeMillis(),
                    apiConfigs = allConfigsToExport,
                    customProviders = providerManager.customProviders.value,
                    conversationParameters = conversationParams,
                    voiceBackendConfigs = voiceConfigsToExport,
                    chatHistory = chatHistoryToExport,
                    imageGenerationHistory = imageHistoryToExport,
                    pinnedTextIds = pinnedTextIds,
                    pinnedImageIds = pinnedImageIds,
                    conversationGroups = groups
                )
                
                // 强制使用 encodeDefaults = true 的 Json 实例，确保空列表/Map 也能被序列化输出
                val exportJson = Json(json) {
                    encodeDefaults = true
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }

                val finalJson = exportJson.encodeToString(settingsToExport)
                val fileName = if (includeHistory) "eztalk_full_backup" else "eztalk_settings"
                exportManager.requestSettingsExport(fileName, finalJson)
                
                Log.i(TAG, "=== 导出配置完成 ===")
                Log.i(TAG, "总配置数: ${allConfigsToExport.size}, 聊天历史: ${chatHistoryToExport.size}, 图像历史: ${imageHistoryToExport.size}")
                
                // 显示安全警告
                withContext(Dispatchers.Main) {
                    showSnackbar(EXPORT_SECURITY_WARNING)
                }
                    
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) {
                    showSnackbar("导出失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    
    }

    /**
     * 将消息列表转换为可导出的会话格式
     */
    private fun exportConversation(messages: List<Message>): ExportedConversation? {
        if (messages.isEmpty()) return null
        
        val firstMsg = messages.firstOrNull()
        val lastMsg = messages.lastOrNull()
        
        return ExportedConversation(
            id = firstMsg?.id ?: UUID.randomUUID().toString(),
            messages = messages.map { msg ->
                ExportedMessage(
                    id = msg.id,
                    text = msg.text,
                    sender = msg.sender.name,
                    reasoning = msg.reasoning,
                    timestamp = msg.timestamp,
                    isError = msg.isError,
                    // 过滤本地文件路径，只保留可访问的URL
                    imageUrls = msg.imageUrls?.filter { url ->
                        url.startsWith("http://") || url.startsWith("https://")
                    }?.takeIf { it.isNotEmpty() }
                )
            },
            createdAt = firstMsg?.timestamp ?: System.currentTimeMillis(),
            lastModifiedAt = lastMsg?.timestamp ?: System.currentTimeMillis()
        )
    }

    fun importSettings(jsonContent: String) {
        scope.launch(Dispatchers.IO) {
            val result = ImportResult()
            
            // 保存当前状态用于回滚
            val backupState = BackupState(
                apiConfigs = stateHolder._apiConfigs.value.toList(),
                imageGenApiConfigs = stateHolder._imageGenApiConfigs.value.toList(),
                selectedApiConfig = stateHolder._selectedApiConfig.value,
                selectedImageGenApiConfig = stateHolder._selectedImageGenApiConfig.value,
                voiceBackendConfigs = stateHolder._voiceBackendConfigs.value.toList(),
                selectedVoiceConfig = stateHolder._selectedVoiceConfig.value,
                historicalConversations = stateHolder._historicalConversations.value.toList(),
                imageGenerationHistoricalConversations = stateHolder._imageGenerationHistoricalConversations.value.toList(),
                pinnedTextConversationIds = stateHolder.pinnedTextConversationIds.value.toSet(),
                pinnedImageConversationIds = stateHolder.pinnedImageConversationIds.value.toSet(),
                conversationGroups = stateHolder.conversationGroups.value.toMap()
            )
            
            try {
                // 0. 文件大小检查
                if (jsonContent.length > MAX_IMPORT_FILE_SIZE) {
                    throw IllegalStateException("导入文件过大（最大支持50MB）")
                }
                
                // 1. 解析配置
                val importedSettings = parseImportedSettings(jsonContent, result)
                
                // 2. 验证并导入API配置（会自动还原混淆的密钥）
                importApiConfigs(importedSettings, result)
                
                // 3. 导入自定义提供商
                importCustomProviders(importedSettings, result)
                
                // 4. 导入会话生成参数
                importConversationParameters(importedSettings, result)
                
                // 5. 导入语音后端配置（会自动还原混淆的密钥）
                importVoiceConfigs(importedSettings, result)
                
                // 6. 导入聊天历史
                importChatHistory(importedSettings, result)
                
                // 7. 导入图像生成历史
                importImageHistory(importedSettings, result)
                
                // 8. 导入置顶状态（带验证）
                importPinnedIds(importedSettings, result)
                
                // 9. 导入分组信息（带验证）
                importConversationGroups(importedSettings, result)
                
                // 检查是否有严重错误需要回滚
                if (result.errors.size > result.configsImported + result.voiceConfigsImported + 
                    result.chatHistoryImported + result.imageHistoryImported) {
                    // 错误过多，回滚所有更改
                    rollbackState(backupState)
                    result.warnings.add("由于错误过多，已回滚所有更改")
                }
                
                // 显示详细结果
                withContext(Dispatchers.Main) {
                    val message = buildImportResultMessage(result)
                    showSnackbar(message)
                }
                
                Log.i(TAG, "Import completed: configs=${result.configsImported}, " +
                    "voiceConfigs=${result.voiceConfigsImported}, " +
                    "chatHistory=${result.chatHistoryImported}, " +
                    "imageHistory=${result.imageHistoryImported}, " +
                    "errors=${result.errors.size}, warnings=${result.warnings.size}")

            } catch (e: Exception) {
                // 发生异常时回滚状态
                Log.e(TAG, "Settings import failed, rolling back", e)
                rollbackState(backupState)
                
                val errorMessage = when (e) {
                    is SerializationException -> "JSON格式错误，请检查文件是否损坏"
                    is IllegalStateException -> e.message ?: "解析失败"
                    is OutOfMemoryError -> "文件过大，内存不足"
                    else -> "导入失败: ${e.message ?: "未知错误"}"
                }
                withContext(Dispatchers.Main) {
                    showSnackbar(errorMessage)
                }
            }
        }
    }
    
    /**
     * 备份状态数据类
     */
    private data class BackupState(
        val apiConfigs: List<ApiConfig>,
        val imageGenApiConfigs: List<ApiConfig>,
        val selectedApiConfig: ApiConfig?,
        val selectedImageGenApiConfig: ApiConfig?,
        val voiceBackendConfigs: List<VoiceBackendConfig>,
        val selectedVoiceConfig: VoiceBackendConfig?,
        val historicalConversations: List<List<Message>>,
        val imageGenerationHistoricalConversations: List<List<Message>>,
        val pinnedTextConversationIds: Set<String>,
        val pinnedImageConversationIds: Set<String>,
        val conversationGroups: Map<String, List<String>>
    )
    
    /**
     * 回滚状态到备份
     */
    private suspend fun rollbackState(backup: BackupState) {
        Log.i(TAG, "Rolling back to backup state")
        
        stateHolder._apiConfigs.value = backup.apiConfigs
        stateHolder._imageGenApiConfigs.value = backup.imageGenApiConfigs
        stateHolder._selectedApiConfig.value = backup.selectedApiConfig
        stateHolder._selectedImageGenApiConfig.value = backup.selectedImageGenApiConfig
        stateHolder._voiceBackendConfigs.value = backup.voiceBackendConfigs
        stateHolder._selectedVoiceConfig.value = backup.selectedVoiceConfig
        stateHolder._historicalConversations.value = backup.historicalConversations
        stateHolder._imageGenerationHistoricalConversations.value = backup.imageGenerationHistoricalConversations
        stateHolder.pinnedTextConversationIds.value = backup.pinnedTextConversationIds
        stateHolder.pinnedImageConversationIds.value = backup.pinnedImageConversationIds
        stateHolder.conversationGroups.value = backup.conversationGroups
        
        // 恢复持久化数据
        persistenceManager.saveApiConfigs(backup.apiConfigs, isImageGen = false)
        persistenceManager.saveApiConfigs(backup.imageGenApiConfigs, isImageGen = true)
        persistenceManager.saveSelectedConfigIdentifier(backup.selectedApiConfig?.id, isImageGen = false)
        persistenceManager.saveSelectedConfigIdentifier(backup.selectedImageGenApiConfig?.id, isImageGen = true)
        persistenceManager.saveVoiceBackendConfigs(backup.voiceBackendConfigs)
        persistenceManager.saveSelectedVoiceConfigId(backup.selectedVoiceConfig?.id)
        persistenceManager.saveChatHistory(backup.historicalConversations, isImageGeneration = false)
        persistenceManager.saveChatHistory(backup.imageGenerationHistoricalConversations, isImageGeneration = true)
        persistenceManager.savePinnedIds(backup.pinnedTextConversationIds, isImageGeneration = false)
        persistenceManager.savePinnedIds(backup.pinnedImageConversationIds, isImageGeneration = true)
        persistenceManager.saveConversationGroups(backup.conversationGroups)
    }

    /**
     * 解析导入的设置文件
     * 支持多版本兼容：v1(List<ApiConfig>), v2(ExportedSettings无密钥混淆), v3(ExportedSettings有密钥混淆)
     */
    private fun parseImportedSettings(
        jsonContent: String,
        result: ImportResult
    ): ExportedSettings {
        // 尝试新格式 (v2/v3)
        try {
            val settings = json.decodeFromString<ExportedSettings>(jsonContent)
            when {
                settings.version < 2 -> {
                    result.warnings.add("检测到v1格式，已自动兼容")
                }
                settings.version == 2 -> {
                    result.warnings.add("检测到v2格式（无密钥混淆），已自动兼容")
                }
                settings.version > EXPORT_VERSION -> {
                    result.warnings.add("检测到较新版本格式 (v${settings.version})，部分新功能可能不支持")
                }
            }
            // 迁移旧版本数据：为缺失字段设置默认值
            return migrateSettings(settings)
        } catch (e: SerializationException) {
            Log.d(TAG, "Failed to parse as ExportedSettings, trying legacy format", e)
        }
        
        // 尝试旧格式兼容 (v1: List<ApiConfig>)
        try {
            val oldList = json.decodeFromString<List<ApiConfig>>(jsonContent)
            result.warnings.add("检测到旧版本格式(v1)，已自动转换")
            return ExportedSettings(version = 1, apiConfigs = oldList)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse as legacy format", e)
        }
        
        throw IllegalStateException("无法解析导入文件。请确保文件是有效的JSON格式，且来自本应用的导出。")
    }
    
    /**
     * 迁移旧版本设置到当前版本
     */
    private fun migrateSettings(settings: ExportedSettings): ExportedSettings {
        // 目前v2和v3结构相同，只是密钥编码方式不同
        // 未来如果有字段变更，在这里处理迁移逻辑
        return settings.copy(
            // 确保apiConfigs中每个配置都有有效的默认值
            apiConfigs = settings.apiConfigs.map { config ->
                config.copy(
                    toolsJson = config.toolsJson,
                    enableCodeExecution = config.enableCodeExecution
                )
            }
        )
    }

    /**
     * 导入API配置（带验证）
     *
     * 目标：
     * - 保留本地已有的"默认配置卡片"等系统/设备级默认配置
     * - 同时完整合入导出文件中的所有配置（包括 Gemini、默认平台下的自定义模型等）
     * - 通过按 id 去重，避免重复配置
     * - 自动还原混淆的API密钥
     */
    private suspend fun importApiConfigs(settings: ExportedSettings, result: ImportResult) {
        Log.i(TAG, "=== 导入配置开始 ===")
        Log.i(TAG, "待导入配置总数: ${settings.apiConfigs.size}")
        
        // 详细记录每个待导入的配置（不记录密钥）
        settings.apiConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  待导入[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}, model=${config.model}, modalityType=${config.modalityType}")
        }
        
        // 1) 还原混淆的密钥并验证导入的配置
        val validConfigs = settings.apiConfigs.mapNotNull { config ->
            // 先还原混淆的密钥
            val deobfuscatedConfig = deobfuscateApiConfig(config)
            when (val validationResult = validateApiConfig(deobfuscatedConfig)) {
                is ValidationResult.Success -> deobfuscatedConfig
                is ValidationResult.Failure -> {
                    validationResult.errors.forEach { error ->
                        result.errors.add("配置 '${config.name}': $error")
                    }
                    Log.w(TAG, "配置验证失败: ${config.name}, 错误: ${validationResult.errors}")
                    null
                }
            }
        }
        
        Log.i(TAG, "验证通过的配置数: ${validConfigs.size}")

        if (validConfigs.isEmpty() && settings.apiConfigs.isNotEmpty()) {
            result.warnings.add("所有API配置验证失败，未导入任何配置")
            Log.w(TAG, "所有API配置验证失败")
            return
        }

        // 按文本/图像拆分导入集合
        val importedMainConfigs = validConfigs.filter {
            it.modalityType != com.android.everytalk.data.DataClass.ModalityType.IMAGE
        }
        val importedImageConfigs = validConfigs.filter {
            it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
        }
        
        Log.i(TAG, "文本配置数: ${importedMainConfigs.size}, 图像配置数: ${importedImageConfigs.size}")

        // 2) 文本配置：将"现有配置 + 导入配置"合并后按 id 去重
        // 改进：先按ID合并（导入的优先），再按内容去重（保留现有的）
        val existingMainConfigs = stateHolder._apiConfigs.value
        Log.i(TAG, "现有文本配置数: ${existingMainConfigs.size}")
        
        // 创建ID到配置的映射，导入的配置会覆盖现有同ID配置
        val idToConfigMap = mutableMapOf<String, ApiConfig>()
        existingMainConfigs.forEach { idToConfigMap[it.id] = it }
        importedMainConfigs.forEach { idToConfigMap[it.id] = it }
        
        // 按内容去重，保留第一个遇到的（即现有配置优先）
        val contentSeen = mutableSetOf<String>()
        val mergedMainConfigs = idToConfigMap.values.filter { config ->
            val contentKey = "${config.provider}|${config.address}|${config.key}|${config.model}|${config.channel}|${config.modalityType}"
            if (contentKey in contentSeen) {
                false
            } else {
                contentSeen.add(contentKey)
                true
            }
        }.toList()
        
        Log.i(TAG, "合并后文本配置数: ${mergedMainConfigs.size}")
        mergedMainConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  合并后文本[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}")
        }

        stateHolder._apiConfigs.value = mergedMainConfigs
        persistenceManager.saveApiConfigs(mergedMainConfigs, isImageGen = false)

        // 选中项：优先保持原来的 id，如不存在则选第一个，都不存在则为null
        val previousSelectedMainId = stateHolder._selectedApiConfig.value?.id
        val newSelectedMain = when {
            previousSelectedMainId != null -> mergedMainConfigs.find { it.id == previousSelectedMainId } ?: mergedMainConfigs.firstOrNull()
            else -> mergedMainConfigs.firstOrNull()
        }
        stateHolder._selectedApiConfig.value = newSelectedMain
        persistenceManager.saveSelectedConfigIdentifier(newSelectedMain?.id, isImageGen = false)

        // 3) 图像配置：同样"现有 + 导入"合并 + 去重
        val existingImageConfigs = stateHolder._imageGenApiConfigs.value
        Log.i(TAG, "现有图像配置数: ${existingImageConfigs.size}")
        
        val imageIdToConfigMap = mutableMapOf<String, ApiConfig>()
        existingImageConfigs.forEach { imageIdToConfigMap[it.id] = it }
        importedImageConfigs.forEach { imageIdToConfigMap[it.id] = it }
        
        val imageContentSeen = mutableSetOf<String>()
        val mergedImageConfigs = imageIdToConfigMap.values.filter { config ->
            val isDefaultProvider = config.provider.trim().lowercase() in listOf("默认", "default")
            val contentKey = if (isDefaultProvider) {
                "default|${config.model}|${config.modalityType}"
            } else {
                "${config.provider}|${config.address}|${config.key}|${config.model}|${config.channel}|${config.modalityType}"
            }
            if (contentKey in imageContentSeen) {
                false
            } else {
                imageContentSeen.add(contentKey)
                true
            }
        }.toList()

        Log.i(TAG, "合并后图像配置数: ${mergedImageConfigs.size}")
        mergedImageConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  合并后图像[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}")
        }

        stateHolder._imageGenApiConfigs.value = mergedImageConfigs
        persistenceManager.saveApiConfigs(mergedImageConfigs, isImageGen = true)

        val previousSelectedImageId = stateHolder._selectedImageGenApiConfig.value?.id
        val newSelectedImage = when {
            previousSelectedImageId != null -> mergedImageConfigs.find { it.id == previousSelectedImageId } ?: mergedImageConfigs.firstOrNull()
            else -> mergedImageConfigs.firstOrNull()
        }
        stateHolder._selectedImageGenApiConfig.value = newSelectedImage
        persistenceManager.saveSelectedConfigIdentifier(newSelectedImage?.id, isImageGen = true)

        // 4) 记录导入成功的配置条数（基于有效导入的集合）
        result.configsImported = validConfigs.size
        Log.i(TAG, "=== 导入配置完成 ===")
    }

    /**
     * 导入自定义提供商
     */
    private suspend fun importCustomProviders(settings: ExportedSettings, result: ImportResult) {
        if (settings.customProviders.isNotEmpty()) {
            providerManager.setCustomProviders(settings.customProviders)
            persistenceManager.saveCustomProviders(settings.customProviders)
        }
    }

    /**
     * 导入会话生成参数
     */
    private suspend fun importConversationParameters(settings: ExportedSettings, result: ImportResult) {
        if (settings.conversationParameters.isNotEmpty()) {
            stateHolder.conversationGenerationConfigs.value = settings.conversationParameters
            persistenceManager.saveConversationParameters(settings.conversationParameters)
        }
    }

    /**
     * 导入语音后端配置（带验证）
     * 自动还原混淆的API密钥
     */
    private suspend fun importVoiceConfigs(settings: ExportedSettings, result: ImportResult) {
        val validVoiceConfigs = settings.voiceBackendConfigs.mapNotNull { config ->
            // 先还原混淆的密钥
            val deobfuscatedConfig = deobfuscateVoiceConfig(config)
            when (val validationResult = validateVoiceConfig(deobfuscatedConfig)) {
                is ValidationResult.Success -> deobfuscatedConfig
                is ValidationResult.Failure -> {
                    validationResult.errors.forEach { error ->
                        result.errors.add("语音配置 '${config.name}': $error")
                    }
                    null
                }
            }
        }
        
        if (validVoiceConfigs.isNotEmpty()) {
            // 合并现有配置和导入配置，按ID去重
            val existingConfigs = stateHolder._voiceBackendConfigs.value
            val idToConfigMap = mutableMapOf<String, VoiceBackendConfig>()
            existingConfigs.forEach { idToConfigMap[it.id] = it }
            validVoiceConfigs.forEach { idToConfigMap[it.id] = it }
            val mergedConfigs = idToConfigMap.values.toList()
            
            // 关键修复：选择导入的配置作为选中配置
            // 优先选择有完整Chat配置（chatModel非空）的导入配置
            // 这样可以确保用户导入配置后能看到正确的LLM设置
            val importedConfigWithChat = validVoiceConfigs.firstOrNull { 
                it.chatModel.isNotBlank() || it.chatApiKey.isNotBlank() 
            }
            val newSelected = importedConfigWithChat 
                ?: validVoiceConfigs.firstOrNull()
                ?: stateHolder._selectedVoiceConfig.value
                ?: mergedConfigs.firstOrNull()

            stateHolder._voiceBackendConfigs.value = mergedConfigs
            stateHolder._selectedVoiceConfig.value = newSelected
            persistenceManager.saveVoiceBackendConfigs(mergedConfigs)
            persistenceManager.saveSelectedVoiceConfigId(newSelected?.id)
            
            Log.i(TAG, "语音配置导入完成: 导入 ${validVoiceConfigs.size} 个, 合并后总数 ${mergedConfigs.size}")
            Log.i(TAG, "  选中的配置: id=${newSelected?.id}, name=${newSelected?.name}")
            Log.i(TAG, "  选中配置Chat: platform=${newSelected?.chatPlatform}, model=${newSelected?.chatModel}, hasKey=${newSelected?.chatApiKey?.isNotBlank()}")
            
            result.voiceConfigsImported = validVoiceConfigs.size
        }
    }

    /**
     * 导入聊天历史
     */
    private suspend fun importChatHistory(settings: ExportedSettings, result: ImportResult) {
        if (settings.chatHistory.isEmpty()) return
        
        val importedConversations = settings.chatHistory.mapNotNull { conv ->
            importConversation(conv, result)
        }
        
        if (importedConversations.isNotEmpty()) {
            val currentHistory = stateHolder._historicalConversations.value.toMutableList()
            
            // 检查重复ID，避免重复导入
            val existingIds = currentHistory.flatMap { conv -> conv.map { it.id } }.toSet()
            val newConversations = importedConversations.filter { conv ->
                conv.none { msg -> msg.id in existingIds }
            }
            
            if (newConversations.size < importedConversations.size) {
                result.warnings.add("跳过了 ${importedConversations.size - newConversations.size} 个重复的会话")
            }
            
            currentHistory.addAll(newConversations)
            stateHolder._historicalConversations.value = currentHistory
            persistenceManager.saveChatHistory(currentHistory, isImageGeneration = false)
            
            result.chatHistoryImported = newConversations.size
        }
    }

    /**
     * 导入图像生成历史
     */
    private suspend fun importImageHistory(settings: ExportedSettings, result: ImportResult) {
        if (settings.imageGenerationHistory.isEmpty()) return
        
        val importedConversations = settings.imageGenerationHistory.mapNotNull { conv ->
            importConversation(conv, result)
        }
        
        if (importedConversations.isNotEmpty()) {
            val currentHistory = stateHolder._imageGenerationHistoricalConversations.value.toMutableList()
            
            // 检查重复ID
            val existingIds = currentHistory.flatMap { conv -> conv.map { it.id } }.toSet()
            val newConversations = importedConversations.filter { conv ->
                conv.none { msg -> msg.id in existingIds }
            }
            
            if (newConversations.size < importedConversations.size) {
                result.warnings.add("跳过了 ${importedConversations.size - newConversations.size} 个重复的图像会话")
            }
            
            currentHistory.addAll(newConversations)
            stateHolder._imageGenerationHistoricalConversations.value = currentHistory
            persistenceManager.saveChatHistory(currentHistory, isImageGeneration = true)
            
            result.imageHistoryImported = newConversations.size
        }
    }

    /**
     * 将导出的会话转换为消息列表
     */
    private fun importConversation(
        exported: ExportedConversation,
        result: ImportResult
    ): List<Message>? {
        return try {
            exported.messages.map { msg ->
                val sender = try {
                    Sender.valueOf(msg.sender)
                } catch (e: Exception) {
                    result.warnings.add("会话 ${exported.id}: 未知的发送者类型 '${msg.sender}'，默认为 User")
                    Sender.User
                }
                
                Message(
                    id = msg.id,
                    text = msg.text,
                    sender = sender,
                    reasoning = msg.reasoning,
                    timestamp = msg.timestamp,
                    isError = msg.isError,
                    imageUrls = msg.imageUrls
                )
            }
        } catch (e: Exception) {
            result.errors.add("会话 ${exported.id} 导入失败: ${e.message}")
            null
        }
    }

    /**
     * 导入置顶状态（验证会话ID是否存在）
     */
    private suspend fun importPinnedIds(settings: ExportedSettings, result: ImportResult) {
        // 获取所有存在的会话ID
        val existingTextConvIds = stateHolder._historicalConversations.value
            .flatMap { conv -> conv.map { it.id } }.toSet()
        val existingImageConvIds = stateHolder._imageGenerationHistoricalConversations.value
            .flatMap { conv -> conv.map { it.id } }.toSet()
        
        if (settings.pinnedTextIds.isNotEmpty()) {
            val currentPinned = stateHolder.pinnedTextConversationIds.value.toMutableSet()
            // 只添加存在的会话ID
            val validIds = settings.pinnedTextIds.filter { it in existingTextConvIds }
            val invalidCount = settings.pinnedTextIds.size - validIds.size
            if (invalidCount > 0) {
                result.warnings.add("跳过了 $invalidCount 个无效的文本置顶ID")
            }
            currentPinned.addAll(validIds)
            stateHolder.pinnedTextConversationIds.value = currentPinned
            persistenceManager.savePinnedIds(currentPinned, isImageGeneration = false)
        }
        
        if (settings.pinnedImageIds.isNotEmpty()) {
            val currentPinned = stateHolder.pinnedImageConversationIds.value.toMutableSet()
            // 只添加存在的会话ID
            val validIds = settings.pinnedImageIds.filter { it in existingImageConvIds }
            val invalidCount = settings.pinnedImageIds.size - validIds.size
            if (invalidCount > 0) {
                result.warnings.add("跳过了 $invalidCount 个无效的图像置顶ID")
            }
            currentPinned.addAll(validIds)
            stateHolder.pinnedImageConversationIds.value = currentPinned
            persistenceManager.savePinnedIds(currentPinned, isImageGeneration = true)
        }
    }

    /**
     * 导入分组信息（验证会话ID是否存在）
     */
    private suspend fun importConversationGroups(settings: ExportedSettings, result: ImportResult) {
        if (settings.conversationGroups.isNotEmpty()) {
            // 获取所有存在的会话ID
            val existingConvIds = (stateHolder._historicalConversations.value +
                stateHolder._imageGenerationHistoricalConversations.value)
                .flatMap { conv -> conv.map { it.id } }.toSet()
            
            val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
            var invalidIdCount = 0
            settings.conversationGroups.forEach { (groupName, ids) ->
                val existingIds = currentGroups[groupName]?.toMutableList() ?: mutableListOf()
                // 只添加存在的会话ID
                val validIds = ids.filter { it in existingConvIds && it !in existingIds }
                invalidIdCount += ids.count { it !in existingConvIds }
                existingIds.addAll(validIds)
                currentGroups[groupName] = existingIds
            }
            if (invalidIdCount > 0) {
                result.warnings.add("分组中跳过了 $invalidIdCount 个无效的会话ID")
            }
            stateHolder.conversationGroups.value = currentGroups
            persistenceManager.saveConversationGroups(currentGroups)
        }
    }

    // ==================== 数据验证 ====================

    /**
     * 验证API配置
     */
    private fun validateApiConfig(config: ApiConfig): ValidationResult {
        val errors = mutableListOf<String>()
        
        // 验证ID
        if (config.id.isBlank()) {
            errors.add("配置ID不能为空")
        }
        
        // 验证提供商
        if (config.provider.isBlank()) {
            errors.add("提供商名称不能为空")
        }
        
        // 验证提供商名称长度和字符
        if (config.provider.length > 100) {
            errors.add("提供商名称过长 (最多100个字符)")
        }
        
        // 验证URL格式
        if (config.address.isNotBlank() && !isValidUrl(config.address)) {
            errors.add("API地址格式无效")
        }
        
        // 验证API密钥长度 - 仅验证最大长度，不再验证最小长度
        if (config.key.isNotBlank() && config.key.length > MAX_API_KEY_LENGTH) {
            errors.add("API密钥长度过长 (最多${MAX_API_KEY_LENGTH}个字符)")
        }
        
        // 验证模型名称
        if (config.model.isBlank()) {
            errors.add("模型名称不能为空")
        }
        
        // 验证模型名称长度和格式
        if (config.model.length > 200) {
            errors.add("模型名称过长 (最多200个字符)")
        }
        
        // 验证温度范围 - 扩展范围以支持更多模型
        if (config.temperature < 0f || config.temperature > 3f) {
            errors.add("温度值超出范围 (0-3): ${config.temperature}")
        }
        
        // 验证topP范围
        config.topP?.let { topP ->
            if (topP < 0f || topP > 1f) {
                errors.add("topP值超出范围 (0-1): $topP")
            }
        }
        
        // 验证maxTokens
        config.maxTokens?.let { tokens ->
            if (tokens < 1 || tokens > 10000000) {
                errors.add("maxTokens超出范围 (1-10000000): $tokens")
            }
        }
        
        // 验证guidanceScale（图像生成）
        config.guidanceScale?.let { scale ->
            if (scale < 0f || scale > 50f) {
                errors.add("guidanceScale超出范围 (0-50): $scale")
            }
        }
        
        // 验证numInferenceSteps（图像生成）
        config.numInferenceSteps?.let { steps ->
            if (steps < 1 || steps > 1000) {
                errors.add("numInferenceSteps超出范围 (1-1000): $steps")
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * 验证语音配置
     */
    private fun validateVoiceConfig(config: VoiceBackendConfig): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (config.id.isBlank()) {
            errors.add("语音配置ID不能为空")
        }
        
        // 验证配置名称
        if (config.name.isBlank()) {
            errors.add("语音配置名称不能为空")
        }
        if (config.name.length > 100) {
            errors.add("语音配置名称过长 (最多100个字符)")
        }
        
        // 验证STT配置
        if (config.sttApiUrl.isNotBlank() && !isValidUrl(config.sttApiUrl)) {
            errors.add("STT API地址格式无效")
        }
        
        // 验证Chat配置
        if (config.chatApiUrl.isNotBlank() && !isValidUrl(config.chatApiUrl)) {
            errors.add("Chat API地址格式无效")
        }
        
        // 验证TTS配置
        if (config.ttsApiUrl.isNotBlank() && !isValidUrl(config.ttsApiUrl)) {
            errors.add("TTS API地址格式无效")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * 验证URL格式
     * 支持http/https协议，验证host和port有效性
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val trimmedUrl = url.trim().trimEnd('#')
            if (trimmedUrl.isBlank()) return false
            
            val uri = java.net.URI(trimmedUrl)
            val scheme = uri.scheme?.lowercase()
            
            // 验证协议
            if (scheme !in listOf("http", "https")) return false
            
            // 验证host
            val host = uri.host
            if (host.isNullOrBlank()) return false
            
            // 验证host不包含非法字符
            if (host.contains(" ") || host.contains("\t") || host.contains("\n")) return false
            
            // 验证port范围（如果指定）
            val port = uri.port
            if (port != -1 && (port < 1 || port > 65535)) return false
            
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 结果消息构建 ====================

    /**
     * 构建导入结果消息
     * 提供详细的导入结果信息
     */
    private fun buildImportResultMessage(result: ImportResult): String {
        val sb = StringBuilder()
        
        // 成功信息
        val successItems = mutableListOf<String>()
        if (result.configsImported > 0) {
            successItems.add("${result.configsImported}个API配置")
        }
        if (result.voiceConfigsImported > 0) {
            successItems.add("${result.voiceConfigsImported}个语音配置")
        }
        if (result.chatHistoryImported > 0) {
            successItems.add("${result.chatHistoryImported}个聊天会话")
        }
        if (result.imageHistoryImported > 0) {
            successItems.add("${result.imageHistoryImported}个图像会话")
        }
        
        if (successItems.isNotEmpty()) {
            sb.append("成功导入: ${successItems.joinToString(", ")}")
        }
        
        // 警告信息（显示前3条详情）
        if (result.warnings.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("⚠️ ${result.warnings.size}个警告")
            if (result.warnings.size <= 3) {
                result.warnings.forEach { sb.append("\n  - $it") }
            } else {
                result.warnings.take(3).forEach { sb.append("\n  - $it") }
                sb.append("\n  - ...还有${result.warnings.size - 3}个警告")
            }
        }
        
        // 错误信息（显示前3条详情）
        if (result.errors.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("❌ ${result.errors.size}个错误")
            if (result.errors.size <= 3) {
                result.errors.forEach { sb.append("\n  - $it") }
            } else {
                result.errors.take(3).forEach { sb.append("\n  - $it") }
                sb.append("\n  - ...还有${result.errors.size - 3}个错误")
            }
        }
        
        return if (sb.isEmpty()) "导入完成，未发现有效数据" else sb.toString()
    }
}