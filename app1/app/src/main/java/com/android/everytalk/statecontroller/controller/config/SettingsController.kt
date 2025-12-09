package com.android.everytalk.statecontroller.controller.config

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
        private const val EXPORT_VERSION = 2
        private const val MIN_API_KEY_LENGTH = 10
        private const val MAX_API_KEY_LENGTH = 500
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
                // 1. 导出所有 API 配置 (文本/图像)
                // 修复：导出所有配置，包括"默认"平台的配置
                // 每个配置都保留其完整的分组字段 (provider, address, key, channel)
                val mainConfigsToExport = stateHolder._apiConfigs.value.toList()
                val imageConfigsToExport = stateHolder._imageGenApiConfigs.value.toList()
                val allConfigsToExport = mainConfigsToExport + imageConfigsToExport
                
                // 详细日志：记录每个配置的关键信息
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
                
                // 3. 导出语音后端配置 (STT/Chat/TTS)
                // 修复：直接导出 stateHolder 中的所有语音配置，不再只导出当前使用的配置
                // 这样可以确保所有已配置的语音配置都被导出，而不是只导出正在使用的那一个
                val voiceConfigsToExport = stateHolder._voiceBackendConfigs.value.toList()
                
                Log.i(TAG, "Exporting voice configs: ${voiceConfigsToExport.size}")
                
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
            
            try {
                // 1. 解析配置
                val importedSettings = parseImportedSettings(jsonContent, result)
                
                // 2. 验证并导入API配置
                importApiConfigs(importedSettings, result)
                
                // 3. 导入自定义提供商
                importCustomProviders(importedSettings, result)
                
                // 4. 导入会话生成参数
                importConversationParameters(importedSettings, result)
                
                // 5. 导入语音后端配置
                importVoiceConfigs(importedSettings, result)
                
                // 6. 导入聊天历史
                importChatHistory(importedSettings, result)
                
                // 7. 导入图像生成历史
                importImageHistory(importedSettings, result)
                
                // 8. 导入置顶状态
                importPinnedIds(importedSettings, result)
                
                // 9. 导入分组信息
                importConversationGroups(importedSettings, result)
                
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
                Log.e(TAG, "Settings import failed", e)
                val errorMessage = when (e) {
                    is SerializationException -> "JSON格式错误: ${e.message?.take(100)}"
                    is IllegalStateException -> e.message ?: "解析失败"
                    else -> "导入失败: ${e.message ?: "未知错误"}"
                }
                withContext(Dispatchers.Main) {
                    showSnackbar(errorMessage)
                }
            }
        }
    }

    /**
     * 解析导入的设置文件
     */
    private fun parseImportedSettings(
        jsonContent: String,
        result: ImportResult
    ): ExportedSettings {
        // 尝试新格式
        try {
            val settings = json.decodeFromString<ExportedSettings>(jsonContent)
            if (settings.version < EXPORT_VERSION) {
                result.warnings.add("检测到旧版本格式 (v${settings.version})，已自动兼容")
            }
            return settings
        } catch (e: SerializationException) {
            Log.d(TAG, "Failed to parse as ExportedSettings, trying legacy format", e)
        }
        
        // 尝试旧格式兼容 (List<ApiConfig>)
        try {
            val oldList = json.decodeFromString<List<ApiConfig>>(jsonContent)
            result.warnings.add("检测到旧版本格式，已自动转换")
            return ExportedSettings(apiConfigs = oldList)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse as legacy format", e)
        }
        
        throw IllegalStateException("无法解析导入文件。请确保文件是有效的JSON格式，且来自本应用的导出。")
    }

    /**
     * 导入API配置（带验证）
     *
     * 目标：
     * - 保留本地已有的“默认配置卡片”等系统/设备级默认配置
     * - 同时完整合入导出文件中的所有配置（包括 Gemini、默认平台下的自定义模型等）
     * - 通过按 id 去重，避免重复配置
     */
    private suspend fun importApiConfigs(settings: ExportedSettings, result: ImportResult) {
        Log.i(TAG, "=== 导入配置开始 ===")
        Log.i(TAG, "待导入配置总数: ${settings.apiConfigs.size}")
        
        // 详细记录每个待导入的配置
        settings.apiConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  待导入[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}, model=${config.model}, modalityType=${config.modalityType}")
        }
        
        // 1) 逐条验证导入的配置（不按 provider 过滤，默认/自定义一视同仁）
        val validConfigs = settings.apiConfigs.mapNotNull { config ->
            when (val validationResult = validateApiConfig(config)) {
                is ValidationResult.Success -> config
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

        // 2) 文本配置：将"现有配置 + 导入配置"合并后按 id 去重，并移除完全重复的配置
        val existingMainConfigs = stateHolder._apiConfigs.value
        Log.i(TAG, "现有文本配置数: ${existingMainConfigs.size}")
        
        val mergedMainConfigs = (existingMainConfigs + importedMainConfigs)
            .associateBy { it.id }   // 同 id 以导入的为准
            .values
            .distinctBy {
                // 增强去重：如果除了ID外其他关键字段都相同，视为重复配置，保留第一个
                "${it.provider}|${it.address}|${it.key}|${it.model}|${it.channel}|${it.modalityType}"
            }
            .toList()
        
        Log.i(TAG, "合并后文本配置数: ${mergedMainConfigs.size}")
        mergedMainConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  合并后文本[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}")
        }

        stateHolder._apiConfigs.value = mergedMainConfigs
        persistenceManager.saveApiConfigs(mergedMainConfigs, isImageGen = false)

        // 选中项：优先保持原来的 id，如不存在则选第一个
        val previousSelectedMainId = stateHolder._selectedApiConfig.value?.id
        val newSelectedMain = when {
            previousSelectedMainId != null -> mergedMainConfigs.find { it.id == previousSelectedMainId }
            else -> mergedMainConfigs.firstOrNull()
        }
        stateHolder._selectedApiConfig.value = newSelectedMain
        persistenceManager.saveSelectedConfigIdentifier(newSelectedMain?.id, isImageGen = false)

        // 3) 图像配置：同样"现有 + 导入"合并 + 去重，并移除完全重复的配置
        val existingImageConfigs = stateHolder._imageGenApiConfigs.value
        Log.i(TAG, "现有图像配置数: ${existingImageConfigs.size}")
        
        val mergedImageConfigs = (existingImageConfigs + importedImageConfigs)
            .associateBy { it.id }
            .values
            .distinctBy {
                // 增强去重：如果除了ID外其他关键字段都相同，视为重复配置，保留第一个
                "${it.provider}|${it.address}|${it.key}|${it.model}|${it.channel}|${it.modalityType}"
            }
            .toList()
        
        Log.i(TAG, "合并后图像配置数: ${mergedImageConfigs.size}")
        mergedImageConfigs.forEachIndexed { index, config ->
            Log.i(TAG, "  合并后图像[$index]: id=${config.id}, name=${config.name}, provider=${config.provider}, channel=${config.channel}")
        }

        stateHolder._imageGenApiConfigs.value = mergedImageConfigs
        persistenceManager.saveApiConfigs(mergedImageConfigs, isImageGen = true)

        val previousSelectedImageId = stateHolder._selectedImageGenApiConfig.value?.id
        val newSelectedImage = when {
            previousSelectedImageId != null -> mergedImageConfigs.find { it.id == previousSelectedImageId }
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
     */
    private suspend fun importVoiceConfigs(settings: ExportedSettings, result: ImportResult) {
        val validVoiceConfigs = settings.voiceBackendConfigs.mapNotNull { config ->
            when (val validationResult = validateVoiceConfig(config)) {
                is ValidationResult.Success -> config
                is ValidationResult.Failure -> {
                    validationResult.errors.forEach { error ->
                        result.errors.add("语音配置 '${config.name}': $error")
                    }
                    null
                }
            }
        }
        
        if (validVoiceConfigs.isNotEmpty()) {
            val previousSelectedId = stateHolder._selectedVoiceConfig.value?.id
            val newSelected = validVoiceConfigs.find { it.id == previousSelectedId }
                ?: validVoiceConfigs.firstOrNull()

            stateHolder._voiceBackendConfigs.value = validVoiceConfigs
            stateHolder._selectedVoiceConfig.value = newSelected
            persistenceManager.saveVoiceBackendConfigs(validVoiceConfigs)
            persistenceManager.saveSelectedVoiceConfigId(newSelected?.id)
            
            // 注意：语音配置现在完全由 Room 数据库管理，不再需要同步到 SharedPreferences
            
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
     * 导入置顶状态
     */
    private suspend fun importPinnedIds(settings: ExportedSettings, result: ImportResult) {
        if (settings.pinnedTextIds.isNotEmpty()) {
            val currentPinned = stateHolder.pinnedTextConversationIds.value.toMutableSet()
            currentPinned.addAll(settings.pinnedTextIds)
            stateHolder.pinnedTextConversationIds.value = currentPinned
            persistenceManager.savePinnedIds(currentPinned, isImageGeneration = false)
        }
        
        if (settings.pinnedImageIds.isNotEmpty()) {
            val currentPinned = stateHolder.pinnedImageConversationIds.value.toMutableSet()
            currentPinned.addAll(settings.pinnedImageIds)
            stateHolder.pinnedImageConversationIds.value = currentPinned
            persistenceManager.savePinnedIds(currentPinned, isImageGeneration = true)
        }
    }

    /**
     * 导入分组信息
     */
    private suspend fun importConversationGroups(settings: ExportedSettings, result: ImportResult) {
        if (settings.conversationGroups.isNotEmpty()) {
            val currentGroups = stateHolder.conversationGroups.value.toMutableMap()
            settings.conversationGroups.forEach { (groupName, ids) ->
                val existingIds = currentGroups[groupName]?.toMutableList() ?: mutableListOf()
                existingIds.addAll(ids.filter { it !in existingIds })
                currentGroups[groupName] = existingIds
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
        
        // 验证URL格式
        if (config.address.isNotBlank() && !isValidUrl(config.address)) {
            errors.add("API地址格式无效")
        }
        
        // 验证API密钥长度 - 仅验证最大长度，不再验证最小长度
        // 修复：导入/导出应该信任原始配置数据，不应拒绝短密钥
        // 用户可能使用后端代理、短密钥或自定义密钥方案
        if (config.key.isNotBlank() && config.key.length > MAX_API_KEY_LENGTH) {
            errors.add("API密钥长度过长 (最多${MAX_API_KEY_LENGTH}个字符)")
        }
        
        // 验证模型名称
        if (config.model.isBlank()) {
            errors.add("模型名称不能为空")
        }
        
        // 验证温度范围
        if (config.temperature < 0f || config.temperature > 2f) {
            errors.add("温度值超出范围 (0-2): ${config.temperature}")
        }
        
        // 验证maxTokens
        config.maxTokens?.let { tokens ->
            if (tokens < 1 || tokens > 1000000) {
                errors.add("maxTokens超出范围 (1-1000000): $tokens")
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
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val trimmedUrl = url.trim().trimEnd('#')
            val uri = java.net.URI(trimmedUrl)
            uri.scheme?.lowercase() in listOf("http", "https") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 结果消息构建 ====================

    /**
     * 构建导入结果消息
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
        
        // 警告信息
        if (result.warnings.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("⚠️ ${result.warnings.size}个警告")
        }
        
        // 错误信息
        if (result.errors.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("❌ ${result.errors.size}个错误")
        }
        
        return if (sb.isEmpty()) "导入完成，未发现有效数据" else sb.toString()
    }
}