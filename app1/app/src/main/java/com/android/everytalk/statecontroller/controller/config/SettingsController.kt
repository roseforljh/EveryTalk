package com.android.everytalk.statecontroller.controller.config

import android.util.Base64
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.rethrowIfCancellation
import com.android.everytalk.statecontroller.safeApiConfigSummary
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
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
    internal val context: android.content.Context,
    internal val stateHolder: ViewModelStateHolder,
    internal val persistenceManager: DataPersistenceManager,
    internal val historyManager: HistoryManager,
    internal val providerManager: ProviderManager,
    internal val exportManager: ExportManager,
    internal val json: Json,
    internal val showSnackbar: (String) -> Unit,
    internal val scope: CoroutineScope
) {
    companion object {
        internal const val TAG = "SettingsController"
        internal const val EXPORT_VERSION = 3 // 升级版本以支持密钥混淆
        private const val MIN_API_KEY_LENGTH = 10
        internal const val MAX_API_KEY_LENGTH = 500
        private const val MAX_IMPORT_FILE_SIZE = 50 * 1024 * 1024 // 50MB
        private const val OBFUSCATION_PREFIX = "EZT_OBF_V1:" // 混淆标记前缀
        
        // 密钥混淆警告消息
        const val EXPORT_SECURITY_WARNING = "⚠️ 导出文件包含敏感API密钥，请妥善保管，切勿分享给他人！"
    }

    internal fun safeUrlSummary(url: String): String {
        return url.substringBefore("://", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.plus("://***")
            ?: "***"
    }

    internal fun safeVoiceConfigSummary(config: VoiceBackendConfig?): String {
        if (config == null) return "null"
        return "VoiceConfig(id=${config.id}, nameChars=${config.name.length}, providerChars=${config.provider.length}, " +
            "sttPlatformChars=${config.sttPlatform.length}, sttModelChars=${config.sttModel.length}, sttUrl=${safeUrlSummary(config.sttApiUrl)}, sttKey=***, " +
            "chatPlatformChars=${config.chatPlatform.length}, chatModelChars=${config.chatModel.length}, chatUrl=${safeUrlSummary(config.chatApiUrl)}, chatKey=***, " +
            "ttsPlatformChars=${config.ttsPlatform.length}, ttsModelChars=${config.ttsModel.length}, ttsUrl=${safeUrlSummary(config.ttsApiUrl)}, ttsKey=***, " +
            "voiceChars=${config.voiceName.length}, realtime=${config.useRealtimeStreaming})"
    }

    // ==================== 密钥混淆工具方法 ====================
    
    /**
     * 混淆API密钥（简单的Base64编码 + XOR混淆）
     * 注意：这不是加密，仅用于防止密钥明文暴露
     */
    internal fun obfuscateKey(key: String): String {
        if (key.isBlank()) return key
        return try {
            // 敏感字段保护方式。
            // 这里使用固定字节 0x5A 对 API Key 的 UTF-8 字节逐个 XOR，再用 Base64 转成可存储字符串。
            // 示例：原始 key -> UTF-8 字节 -> 每个字节 xor 0x5A -> Base64 -> 加上 EZT_OBF_V1: 前缀。
            // 该方案属于轻量混淆，方便导入导出和兼容旧数据；不是 SQLCipher 这种数据库整库加密。
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
    internal fun deobfuscateKey(obfuscatedKey: String): String {
        if (obfuscatedKey.isBlank()) return obfuscatedKey
        if (!obfuscatedKey.startsWith(OBFUSCATION_PREFIX)) return obfuscatedKey // 兼容旧版本明文密钥
        return try {
            // 毕设讲解点：解密/还原过程。
            // 先识别 EZT_OBF_V1: 前缀，再 Base64 解码得到 XOR 后的字节。
            // XOR 是可逆运算，所以再次对每个字节 xor 0x5A，就能还原原始 API Key。
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
    internal fun obfuscateApiConfig(config: ApiConfig): ApiConfig {
        // 只处理 key 字段，地址、模型名、供应商等非密钥字段保持原样。
        return config.copy(key = obfuscateKey(config.key))
    }
    
    /**
     * 还原ApiConfig中的密钥
     */
    internal fun deobfuscateApiConfig(config: ApiConfig): ApiConfig {
        return config.copy(key = deobfuscateKey(config.key))
    }
    
    /**
     * 混淆VoiceBackendConfig中的密钥
     */
    internal fun obfuscateVoiceConfig(config: VoiceBackendConfig): VoiceBackendConfig {
        // 语音配置里有三类密钥：语音识别 STT、对话 Chat、语音合成 TTS，分别做同样的 XOR + Base64 混淆。
        return config.copy(
            sttApiKey = obfuscateKey(config.sttApiKey),
            chatApiKey = obfuscateKey(config.chatApiKey),
            ttsApiKey = obfuscateKey(config.ttsApiKey)
        )
    }
    
    /**
     * 还原VoiceBackendConfig中的密钥
     */
    internal fun deobfuscateVoiceConfig(config: VoiceBackendConfig): VoiceBackendConfig {
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
    internal data class ExportedSettings(
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
                    Log.i(TAG, "  文本配置[$index]: ${safeApiConfigSummary(config)}")
                }
                Log.i(TAG, "图像配置数量: ${imageConfigsToExport.size}")
                imageConfigsToExport.forEachIndexed { index, config ->
                    Log.i(TAG, "  图像配置[$index]: ${safeApiConfigSummary(config)}")
                }
                
                // 2. 导出会话生成参数 (conversationGenerationConfigs)
                val conversationParams = stateHolder.conversationGenerationConfigs.value
                
                // 3. 导出语音后端配置 (STT/Chat/TTS) - 混淆密钥后导出，并去重
                val rawVoiceConfigs = stateHolder._voiceBackendConfigs.value
                
                // 基于配置内容去重（忽略id、createdAt、updatedAt）
                // 使用配置的核心字段作为唯一性判断依据
                val deduplicatedVoiceConfigs = rawVoiceConfigs
                    .distinctBy { config ->
                        // 创建一个基于核心配置内容的唯一键
                        listOf(
                            config.name,
                            config.provider,
                            config.sttPlatform,
                            config.sttApiKey,
                            config.sttApiUrl,
                            config.sttModel,
                            config.chatPlatform,
                            config.chatApiKey,
                            config.chatApiUrl,
                            config.chatModel,
                            config.ttsPlatform,
                            config.ttsApiKey,
                            config.ttsApiUrl,
                            config.ttsModel,
                            config.voiceName,
                            config.useRealtimeStreaming
                        ).joinToString("|")
                    }
                
                val voiceConfigsToExport = deduplicatedVoiceConfigs.map { obfuscateVoiceConfig(it) }
                
                Log.i(TAG, "语音配置数量: 原始 ${rawVoiceConfigs.size}, 去重后 ${voiceConfigsToExport.size}")
                voiceConfigsToExport.forEachIndexed { index, config ->
                    Log.i(TAG, "  语音配置[$index]: ${safeVoiceConfigSummary(config)}")
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
                e.rethrowIfCancellation()
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
    internal fun exportConversation(messages: List<Message>): ExportedConversation? {
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
                e.rethrowIfCancellation()
                // 发生异常时回滚状态
                Log.e(TAG, "Settings import failed, rolling back", e)
                rollbackState(backupState)
                
                val errorMessage = when (e) {
                    is SerializationException -> "JSON格式错误，请检查文件是否损坏"
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
     * 备份状态数据类
     */

    private suspend fun rollbackState(backup: BackupState) = rollbackStateInternal(backup)

    private fun parseImportedSettings(jsonContent: String, result: ImportResult): ExportedSettings =
        parseImportedSettingsInternal(jsonContent, result)

    private fun migrateSettings(settings: ExportedSettings): ExportedSettings = migrateSettingsInternal(settings)

    private suspend fun importApiConfigs(settings: ExportedSettings, result: ImportResult) =
        importApiConfigsInternal(settings, result)

    private suspend fun importCustomProviders(settings: ExportedSettings, result: ImportResult) =
        importCustomProvidersInternal(settings, result)

    private suspend fun importConversationParameters(settings: ExportedSettings, result: ImportResult) =
        importConversationParametersInternal(settings, result)

    private suspend fun importVoiceConfigs(settings: ExportedSettings, result: ImportResult) =
        importVoiceConfigsInternal(settings, result)

    private suspend fun importChatHistory(settings: ExportedSettings, result: ImportResult) =
        importChatHistoryInternal(settings, result)

    private suspend fun importImageHistory(settings: ExportedSettings, result: ImportResult) =
        importImageHistoryInternal(settings, result)

    private fun importConversation(
        exported: ExportedConversation,
        result: ImportResult
    ): List<Message>? = importConversationInternal(exported, result)

    private suspend fun importPinnedIds(settings: ExportedSettings, result: ImportResult) =
        importPinnedIdsInternal(settings, result)

    private suspend fun importConversationGroups(settings: ExportedSettings, result: ImportResult) =
        importConversationGroupsInternal(settings, result)

    private fun validateApiConfig(config: ApiConfig): ValidationResult = validateApiConfigInternal(config)

    private fun validateVoiceConfig(config: VoiceBackendConfig): ValidationResult = validateVoiceConfigInternal(config)

    private fun isValidUrl(url: String): Boolean = isValidUrlInternal(url)

    private fun buildImportResultMessage(result: ImportResult): String =
        buildImportResultMessageInternal(result)
}
