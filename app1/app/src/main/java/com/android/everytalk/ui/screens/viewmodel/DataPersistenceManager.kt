package com.android.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.safeApiConfigSummary
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.util.ConversationNameHelper
import com.android.everytalk.util.message.findMarkdownImageReferences
import com.android.everytalk.util.message.replaceMarkdownImageSources
import com.android.everytalk.util.storage.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil3.ImageLoader
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal data class InlineImageMigrationResult(
    val messages: List<Message>,
    val changed: Boolean,
    val failed: Boolean,
    val persistedSources: Set<String> = emptySet(),
)

internal suspend fun migrateConversationInlineImages(
    messages: List<Message>,
    persistSource: suspend (source: String, messageId: String, index: Int) -> String?,
    deletePersistedSource: (String) -> Unit = {},
): InlineImageMigrationResult {
    val createdSources = linkedSetOf<String>()
    var completed = false
    try {
        val migratedMessages = mutableListOf<Message>()
        var changed = false

        messages.forEach { message ->
            val partSources = message.parts.filterIsInstance<MarkdownPart.InlineImage>()
                .map { part -> "data:${part.mimeType};base64,${part.base64Data}" }
            val sources = linkedSetOf<String>().apply {
                findMarkdownImageReferences(message.text)
                    .map { it.source }
                    .filterTo(this) { it.startsWith("data:image", ignoreCase = true) }
                message.imageUrls.orEmpty()
                    .filterTo(this) { it.startsWith("data:image", ignoreCase = true) }
                addAll(partSources)
            }
            if (sources.isEmpty()) {
                migratedMessages += message
                return@forEach
            }

            val replacements = linkedMapOf<String, String>()
            sources.forEachIndexed { index, source ->
                val persisted = persistSource(source, message.id, index)
                if (persisted.isNullOrBlank()) {
                    return InlineImageMigrationResult(messages, changed = false, failed = true)
                }
                replacements[source] = persisted
                createdSources += persisted
            }

            var migratedText = replaceMarkdownImageSources(message.text, replacements)
            partSources.forEach { source ->
                val persisted = replacements.getValue(source)
                if (!migratedText.contains(persisted)) {
                    migratedText = when {
                        migratedText.isBlank() -> "![Generated Image]($persisted)"
                        else -> migratedText.trimEnd() + "\n\n![Generated Image]($persisted)"
                    }
                }
            }
            val migratedParts = message.parts.mapNotNull { part ->
                when (part) {
                    is MarkdownPart.Text -> part.copy(
                        content = replaceMarkdownImageSources(part.content, replacements),
                    )
                    is MarkdownPart.InlineImage -> null
                    else -> part
                }
            }
            val migratedUrls = buildList {
                message.imageUrls.orEmpty().forEach { source -> add(replacements[source] ?: source) }
                replacements.values.forEach(::add)
            }.distinct()
            val migratedMessage = message.copy(
                text = migratedText,
                imageUrls = migratedUrls.takeIf { it.isNotEmpty() },
                parts = migratedParts,
            )
            changed = changed || migratedMessage != message
            migratedMessages += migratedMessage
        }

        completed = true
        return InlineImageMigrationResult(
            messages = migratedMessages,
            changed = changed,
            failed = false,
            persistedSources = createdSources,
        )
    } finally {
        if (!completed) {
            createdSources.forEach { source -> runCatching { deletePersistedSource(source) } }
        }
    }
}

class DataPersistenceManager(
    private val context: Context,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val imageLoader: ImageLoader
) {
    private val TAG = "PersistenceManager"
    private val conversationGroupsSaveMutex = kotlinx.coroutines.sync.Mutex()
    private val conversationScrollStatesSaveMutex = kotlinx.coroutines.sync.Mutex()
    
    // Room 数据源
    private val roomDataSource by lazy { RoomDataSource(context) }
    private val fileManager by lazy { FileManager(context) }
    private val protectedTextSessionIds = linkedSetOf<String>()
    private val protectedImageSessionIds = linkedSetOf<String>()
    @Volatile private var protectLastOpenTextSession = false
    @Volatile private var protectLastOpenImageSession = false
    
    // 默认配置初始化标志位 (存储在 Room 数据库中)
    private val KEY_DEFAULT_CONFIGS_INITIALIZED = "default_configs_initialized_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun loadCustomProviders(): Set<String> {
        return withContext(Dispatchers.IO) {
            roomDataSource.loadCustomProviders()
        }
    }

    suspend fun saveCustomProviders(providers: Set<String>) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveCustomProviders(providers)
        }
    }

    suspend fun loadExternalWebSearchConfigs() = withContext(Dispatchers.IO) {
        roomDataSource.loadExternalWebSearchConfigs()
    }

    suspend fun saveExternalWebSearchConfigs(configs: List<com.android.everytalk.data.network.ExternalWebSearchProviderConfig>) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveExternalWebSearchConfigs(configs)
        }
    }

    suspend fun loadSelectedExternalWebSearchProviderId(): String? = withContext(Dispatchers.IO) {
        roomDataSource.loadSelectedExternalWebSearchProviderId()
    }

    suspend fun saveSelectedExternalWebSearchProviderId(providerId: String?) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveSelectedExternalWebSearchProviderId(providerId)
        }
    }

    suspend fun persistMessageImageSource(
        source: String,
        messageId: String,
        index: Int,
    ): String? {
        return if (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        ) {
            withTimeoutOrNull(15_000L) {
                fileManager.persistMessageImageSource(source, messageId, index)
            }
        } else {
            fileManager.persistMessageImageSource(source, messageId, index)
        }
    }

    /** 将历史消息中的图片来源统一归档；失败时保留原记录，交由后续迁移重试。 */
    private suspend fun persistInlineAndRemoteImages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        return messages.map { message ->
            val originalUrls = message.imageUrls.orEmpty()
            if (originalUrls.isEmpty()) return@map message

            val persistedUrls = originalUrls.mapIndexed { index, source ->
                when {
                    source.startsWith("file://", ignoreCase = true) || source.startsWith("/") -> source
                    source.startsWith("data:image", ignoreCase = true) ||
                        source.startsWith("http://", ignoreCase = true) ||
                        source.startsWith("https://", ignoreCase = true) ||
                        source.startsWith("content://", ignoreCase = true) -> {
                        persistMessageImageSource(source, message.id, index) ?: source
                    }
                    else -> source
                }
            }
            if (persistedUrls == originalUrls) message else message.copy(imageUrls = persistedUrls)
        }
    }

    private fun protectedSessionIds(isImageGeneration: Boolean): Set<String> = synchronized(this) {
        if (isImageGeneration) protectedImageSessionIds.toSet() else protectedTextSessionIds.toSet()
    }

    private fun protectSessions(isImageGeneration: Boolean, sessionIds: Collection<String>) {
        synchronized(this) {
            val target = if (isImageGeneration) protectedImageSessionIds else protectedTextSessionIds
            target += sessionIds
        }
    }

    private fun unprotectSession(isImageGeneration: Boolean, sessionId: String) {
        synchronized(this) {
            val target = if (isImageGeneration) protectedImageSessionIds else protectedTextSessionIds
            target -= sessionId
        }
    }

    private fun deleteMigratedImageFile(path: String) {
        runCatching {
            val attachmentDir = File(context.filesDir, "chat_attachments").canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(attachmentDir.path + File.separator)) file.delete()
        }
    }

    private suspend fun migrateLoadedHistorySessions(
        loadResult: com.android.everytalk.data.database.SessionHistoryLoadResult,
        isImageGeneration: Boolean,
        onLoadWarning: suspend (String) -> Unit,
    ): List<List<Message>> {
        if (loadResult.failedSessionIds.isNotEmpty()) {
            protectSessions(isImageGeneration, loadResult.failedSessionIds)
            Log.w(
                TAG,
                "History load failed sessions: mode=${if (isImageGeneration) "image" else "text"}, ids=${loadResult.failedSessionIds}",
            )
            onLoadWarning("部分历史加载失败，原数据已保留")
        }

        var migrationWarningRequired = false
        return loadResult.sessions.map { loadedSession ->
            val migration = migrateConversationInlineImages(
                messages = loadedSession.messages,
                persistSource = ::persistMessageImageSource,
                deletePersistedSource = ::deleteMigratedImageFile,
            )
            if (migration.failed) {
                unprotectSession(isImageGeneration, loadedSession.sessionId)
                migrationWarningRequired = true
                loadedSession.messages
            } else {
                if (migration.changed) {
                    try {
                        roomDataSource.saveLoadedHistorySession(
                            sessionId = loadedSession.sessionId,
                            messages = migration.messages,
                            isImageGeneration = isImageGeneration,
                        )
                        unprotectSession(isImageGeneration, loadedSession.sessionId)
                        migration.messages
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        migration.persistedSources.forEach(::deleteMigratedImageFile)
                        unprotectSession(isImageGeneration, loadedSession.sessionId)
                        migrationWarningRequired = true
                        Log.w(
                            TAG,
                            "History image migration writeback failed: sessionId=${loadedSession.sessionId}, type=${exception::class.simpleName}",
                        )
                        loadedSession.messages
                    }
                } else {
                    unprotectSession(isImageGeneration, loadedSession.sessionId)
                    migration.messages
                }
            }
        }.also {
            if (migrationWarningRequired) {
                onLoadWarning("部分历史图片迁移失败，原数据已保留")
            }
        }
    }

    fun loadInitialData(
        loadLastChat: Boolean = true,
        onLoadWarning: (String) -> Unit = {},
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        stateHolder._isLoadingHistoryData.value = true
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "loadInitialData: 开始加载初始数据 (IO Thread)... loadLastChat: $loadLastChat")
            var initialConfigPresent = false
            var initialHistoryPresent = false
            var historyLoadingJob: Job? = null
            var loadingCompleteNotified = false
            val emittedLoadWarnings = mutableSetOf<String>()
            fun notifyLoadingComplete(configPresent: Boolean, historyPresent: Boolean) {
                if (loadingCompleteNotified) return
                loadingCompleteNotified = true
                onLoadingComplete(configPresent, historyPresent)
            }
            suspend fun warnOnce(message: String) {
                val shouldEmit = synchronized(emittedLoadWarnings) { emittedLoadWarnings.add(message) }
                if (shouldEmit) {
                    withContext(Dispatchers.Main.immediate) { onLoadWarning(message) }
                }
            }

            try {
                // 第一阶段：快速加载API配置（优先级最高）
                Log.d(TAG, "loadInitialData: 阶段1 - 加载API配置...")
                var loadedConfigs: List<ApiConfig> = if (stateHolder._apiConfigs.value.isEmpty()) {
                    Log.d(TAG, "loadInitialData: API配置缓存未命中。从RoomDataSource加载...")
                    roomDataSource.loadApiConfigs()
                } else {
                    Log.d(TAG, "loadInitialData: API配置缓存命中。使用现有数据。")
                    stateHolder._apiConfigs.value
                }
                
                // 清理旧的默认文本配置（如果存在）
                val hasLegacyDefaultConfigs = loadedConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default") &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.TEXT
                }
                if (hasLegacyDefaultConfigs) {
                    loadedConfigs = loadedConfigs.filter {
                        !(it.provider.trim().lowercase() in listOf("默认", "default") &&
                          it.modalityType == com.android.everytalk.data.DataClass.ModalityType.TEXT)
                    }
                    roomDataSource.clearApiConfigs()
                    roomDataSource.saveApiConfigs(loadedConfigs)
                    Log.i(TAG, "loadInitialData: 已清理旧的默认文本配置")
                }

                initialConfigPresent = loadedConfigs.isNotEmpty()

                Log.d(TAG, "loadInitialData: 调用 roomDataSource.loadSelectedConfigId()...")
                val selectedConfigId: String? = roomDataSource.loadSelectedConfigId()
                var selectedConfigFromDataSource: ApiConfig? = null
                if (selectedConfigId != null) {
                    selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) {
                        Log.w(TAG, "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。")
                        roomDataSource.saveSelectedConfigId(null)
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(TAG, "loadInitialData: 无有效选中配置或之前未选中，默认选择第一个: ${safeApiConfigSummary(finalSelectedConfig)}。将保存此选择。")
                    roomDataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }

                // 立即更新API配置到UI，让用户可以开始使用
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 阶段1完成 - 更新API配置到UI...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                }

                // Load image generation configs
                var loadedImageGenConfigs: List<ApiConfig> = roomDataSource.loadImageGenApiConfigs()

                val defaultConfigsInitialized = roomDataSource.getSetting(KEY_DEFAULT_CONFIGS_INITIALIZED, "false") == "true"

                // 自动创建默认图像配置（如果不存在且未初始化过）
                // 默认创建三个图像模型：Modal Z-Image-Turbo、Qwen 图像编辑、SiliconFlow
                val hasDefaultImageConfig = loadedImageGenConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default") &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
                }
                if (!hasDefaultImageConfig && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到默认图像配置且首次初始化，自动创建三个默认图像模型...")
                    
                    val newDefaultImageConfigs = mutableListOf<ApiConfig>()
                    
                    // 1. Modal Z-Image-Turbo 图像生成
                    newDefaultImageConfigs.add(ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Z-Image-Turbo (Modal)",
                        provider = "默认",
                        address = "",
                        key = "",
                        model = "z-image-turbo-modal",
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = "",
                        isValid = true,
                        numInferenceSteps = 4
                    ))
                    
                    // 2. Qwen 图像编辑
                    newDefaultImageConfigs.add(ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Qwen Image Edit",
                        provider = "默认",
                        address = "",
                        key = "",
                        model = "qwen-image-edit-modal",
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = "",
                        isValid = true,
                        numInferenceSteps = 30
                    ))
                    
                    // 3. SiliconFlow 图像生成 (Kwai-Kolors/Kolors)
                    newDefaultImageConfigs.add(ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Kwai-Kolors/Kolors",
                        provider = "默认",
                        address = "",
                        key = "",
                        model = "Kwai-Kolors/Kolors",
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = "",
                        isValid = true
                    ))
                    
                    loadedImageGenConfigs = loadedImageGenConfigs + newDefaultImageConfigs
                    Log.i(TAG, "loadInitialData: 已创建 ${newDefaultImageConfigs.size} 个默认图像配置")
                }
                
                // 修复：去重逻辑，移除完全重复的配置（除了ID不同）
                // 对于"默认"provider的配置，只按model去重，忽略address、key和channel
                val uniqueImageConfigs = loadedImageGenConfigs.distinctBy { config ->
                    val isDefaultProvider = config.provider.trim().lowercase() in listOf("默认", "default")
                    if (isDefaultProvider) {
                        // 默认配置只按provider和model去重
                        "default|${config.model}|${config.modalityType}"
                    } else {
                        "${config.provider}|${config.address}|${config.key}|${config.model}|${config.channel}|${config.modalityType}"
                    }
                }
                if (uniqueImageConfigs.size < loadedImageGenConfigs.size) {
                    Log.i(TAG, "loadInitialData: 移除 ${loadedImageGenConfigs.size - uniqueImageConfigs.size} 个重复的图像配置")
                    loadedImageGenConfigs = uniqueImageConfigs
                    // 同时保存去重后的配置
                    roomDataSource.saveImageGenApiConfigs(loadedImageGenConfigs)
                }

                // 统一保存所有图像配置（包括新增的）
                if (!hasDefaultImageConfig && !defaultConfigsInitialized) {
                    roomDataSource.saveImageGenApiConfigs(loadedImageGenConfigs)
                    Log.i(TAG, "loadInitialData: 已保存更新后的图像配置列表")
                }
                
                // 标记默认配置已初始化 (保存到 Room 数据库)
                if (!defaultConfigsInitialized) {
                    roomDataSource.setSetting(KEY_DEFAULT_CONFIGS_INITIALIZED, "true")
                }
                
                val selectedImageGenConfigId: String? = roomDataSource.loadSelectedImageGenConfigId()
                var selectedImageGenConfig: ApiConfig? = null
                if (selectedImageGenConfigId != null) {
                    selectedImageGenConfig = loadedImageGenConfigs.find { it.id == selectedImageGenConfigId }
                }
                if (selectedImageGenConfig == null && loadedImageGenConfigs.isNotEmpty()) {
                    selectedImageGenConfig = loadedImageGenConfigs.first()
                    roomDataSource.saveSelectedImageGenConfigId(selectedImageGenConfig.id)
                }
 
                 withContext(Dispatchers.Main.immediate) {
                     stateHolder._imageGenApiConfigs.value = loadedImageGenConfigs
                     stateHolder._selectedImageGenApiConfig.value = selectedImageGenConfig
                 }

                // 加载语音后端配置
                var loadedVoiceConfigs = roomDataSource.loadVoiceBackendConfigs()
                
                // 自动创建默认语音配置（如果不存在且未初始化过）
                // 注意：语音配置暂时复用同一个初始化标志位，或者如果需要独立控制可以新加
                val hasDefaultVoiceConfig = loadedVoiceConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default")
                }
                if (!hasDefaultVoiceConfig && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到默认语音配置且首次初始化，自动创建...")
                    val defaultVoiceConfig = com.android.everytalk.data.DataClass.VoiceBackendConfig.createDefault()
                    loadedVoiceConfigs = loadedVoiceConfigs + listOf(defaultVoiceConfig)
                    roomDataSource.saveVoiceBackendConfigs(loadedVoiceConfigs)
                    Log.i(TAG, "loadInitialData: 已创建默认语音配置")
                }
                
                val selectedVoiceConfigId: String? = roomDataSource.loadSelectedVoiceConfigId()
                var selectedVoiceConfig: com.android.everytalk.data.DataClass.VoiceBackendConfig? = null
                if (selectedVoiceConfigId != null) {
                    selectedVoiceConfig = loadedVoiceConfigs.find { it.id == selectedVoiceConfigId }
                }
                if (selectedVoiceConfig == null && loadedVoiceConfigs.isNotEmpty()) {
                    selectedVoiceConfig = loadedVoiceConfigs.first()
                    roomDataSource.saveSelectedVoiceConfigId(selectedVoiceConfig.id)
                }
                
                withContext(Dispatchers.Main.immediate) {
                    stateHolder._voiceBackendConfigs.value = loadedVoiceConfigs
                    stateHolder._selectedVoiceConfig.value = selectedVoiceConfig
                }
                Log.i(TAG, "loadInitialData: 已加载 ${loadedVoiceConfigs.size} 个语音配置")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载配置时发生错误", e)
                warnOnce("部分初始数据加载失败，原数据已保留")
            }

            try {
                // 第二阶段：异步加载历史数据（延迟加载）
                historyLoadingJob = launch {
                    Log.d(TAG, "loadInitialData: 阶段2 - 开始异步加载历史数据...")

                    try {
                        // 检查是否需要加载历史数据
                        val shouldLoadHistory = stateHolder._historicalConversations.value.isEmpty()
                        val loadedHistory = if (shouldLoadHistory) {
                            Log.d(TAG, "loadInitialData: 从 Room 加载历史数据...")
                            migrateLoadedHistorySessions(
                                loadResult = roomDataSource.loadChatHistoryResult(),
                                isImageGeneration = false,
                                onLoadWarning = ::warnOnce,
                            )
                        } else {
                            Log.d(TAG, "loadInitialData: 使用缓存的历史数据。")
                            stateHolder._historicalConversations.value
                        }
                        
                        initialHistoryPresent = loadedHistory.isNotEmpty()
                        Log.i(TAG, "loadInitialData: 历史数据加载完成。数量: ${loadedHistory.size}")

                        // 加载会话配置映射
                        try {
                            val mapping = roomDataSource.loadConversationApiConfigIds()
                            withContext(Dispatchers.Main.immediate) {
                                stateHolder.conversationApiConfigIds.value = mapping
                            }
                            Log.d(TAG, "loadInitialData: 会话配置映射已加载 - 共 ${mapping.size} 条")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load conversation api config mapping", e)
                        }

                        // 自动修复消息parts - 检查并修复有问题的AI消息
                        val repairedHistory = loadedHistory.map { conversation ->
                            conversation.map { message ->
                                if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                    message.text.isNotBlank() && 
                                    (message.parts.isEmpty() || 
                                     !message.parts.any { part ->
                                         when (part) {
                                             is com.android.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                                             is com.android.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
                                             // Math blocks removed
                                             // is com.android.everytalk.ui.components.MarkdownPart.Table -> part.tableData.headers.isNotEmpty()
                                             else -> false
                                         }
                                     })) {
                                    // 需要修复的消息
                                    Log.d(TAG, "自动修复消息parts: messageId=${message.id}")
                                    // 这里可以调用MessageProcessor.finalizeMessageProcessing
                                    // 暂时先标记，稍后在渲染时修复
                                    message
                                } else {
                                    message
                                }
                            }
                        }

                        // 更新历史数据到UI
                        withContext(Dispatchers.Main.immediate) {
                            Log.d(TAG, "loadInitialData: 阶段2完成 - 更新历史数据到UI...")
                            stateHolder._historicalConversations.value = repairedHistory
                            repairedHistory.forEach { conversation ->
                                val id = ConversationNameHelper.resolveStableId(conversation)
                                if (id != null) {
                                    val prompt = conversation.firstOrNull { it.sender == com.android.everytalk.data.DataClass.Sender.System }?.text ?: ""
                                    stateHolder.systemPrompts[id] = prompt
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "loadInitialData: 加载历史数据时发生错误", e)
                        warnOnce("部分历史加载失败，原数据已保留")
                    }

                    try {
                        val convertedImageGenHistory = migrateLoadedHistorySessions(
                            loadResult = roomDataSource.loadImageGenerationHistoryResult(),
                            isImageGeneration = true,
                            onLoadWarning = ::warnOnce,
                        )
                        withContext(Dispatchers.Main.immediate) {
                            stateHolder._imageGenerationHistoricalConversations.value = convertedImageGenHistory
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "loadInitialData: 加载图像历史时发生错误", e)
                        warnOnce("部分历史加载失败，原数据已保留")
                    }
                }

                // Phase 3: Load last open chats if needed
                if (loadLastChat) {
                    Log.d(TAG, "loadInitialData: Phase 3 - Loading last open chats...")
                    var lastOpenChat: List<Message>? = null
                    var lastOpenImageGenChat: List<Message>? = null

                    var loadedOriginalTextChat: List<Message>? = null
                    try {
                        val original = roomDataSource.loadLastOpenChat()
                        loadedOriginalTextChat = original
                        val migration = migrateConversationInlineImages(
                            messages = original,
                            persistSource = ::persistMessageImageSource,
                            deletePersistedSource = ::deleteMigratedImageFile,
                        )
                        if (migration.failed) {
                            protectLastOpenTextSession = false
                            lastOpenChat = original
                            warnOnce("部分历史图片迁移失败，原数据已保留")
                        } else {
                            try {
                                if (migration.changed) {
                                    roomDataSource.saveLastOpenChat(migration.messages)
                                }
                                lastOpenChat = migration.messages
                                protectLastOpenTextSession = false
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                migration.persistedSources.forEach(::deleteMigratedImageFile)
                                lastOpenChat = original
                                protectLastOpenTextSession = false
                                Log.w(TAG, "Last-open text migration writeback failed: type=${exception::class.simpleName}")
                                warnOnce("部分历史图片迁移失败，原数据已保留")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastOpenChat = loadedOriginalTextChat
                        protectLastOpenTextSession = loadedOriginalTextChat == null
                        Log.w(TAG, "Last-open text load or migration failed: type=${e::class.simpleName}")
                        warnOnce("部分历史加载失败，原数据已保留")
                    }

                    var loadedOriginalImageChat: List<Message>? = null
                    try {
                        val original = roomDataSource.loadLastOpenImageGenerationChat()
                        loadedOriginalImageChat = original
                        val migration = migrateConversationInlineImages(
                            messages = original,
                            persistSource = ::persistMessageImageSource,
                            deletePersistedSource = ::deleteMigratedImageFile,
                        )
                        if (migration.failed) {
                            protectLastOpenImageSession = false
                            lastOpenImageGenChat = original
                            warnOnce("部分历史图片迁移失败，原数据已保留")
                        } else {
                            try {
                                if (migration.changed) {
                                    roomDataSource.saveLastOpenImageGenerationChat(migration.messages)
                                }
                                lastOpenImageGenChat = migration.messages
                                protectLastOpenImageSession = false
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                migration.persistedSources.forEach(::deleteMigratedImageFile)
                                lastOpenImageGenChat = original
                                protectLastOpenImageSession = false
                                Log.w(TAG, "Last-open image migration writeback failed: type=${exception::class.simpleName}")
                                warnOnce("部分历史图片迁移失败，原数据已保留")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastOpenImageGenChat = loadedOriginalImageChat
                        protectLastOpenImageSession = loadedOriginalImageChat == null
                        Log.w(TAG, "Last-open image load or migration failed: type=${e::class.simpleName}")
                        warnOnce("部分历史加载失败，原数据已保留")
                    }

                    withContext(Dispatchers.Main.immediate) {
                        lastOpenChat?.let { loadedMessages ->
                            stateHolder.messages.clear()
                            stateHolder.messages.addAll(loadedMessages)
                            stateHolder.textReasoningCompleteMap.clear()
                            loadedMessages.forEach { message ->
                                if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                    !message.reasoning.isNullOrBlank()
                                ) {
                                    stateHolder.textReasoningCompleteMap[message.id] = true
                                }
                            }
                            stateHolder._currentConversationId.value =
                                ConversationNameHelper.resolveStableId(loadedMessages)
                                    ?: "new_chat_${System.currentTimeMillis()}"
                            stateHolder.applyCurrentConversationFunctionToggleState()
                            stateHolder._loadedHistoryIndex.value = null
                        }
                        lastOpenImageGenChat?.let { loadedMessages ->
                            stateHolder.imageGenerationMessages.clear()
                            stateHolder.imageGenerationMessages.addAll(loadedMessages)
                            stateHolder.imageReasoningCompleteMap.clear()
                            loadedMessages.forEach { message ->
                                if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                    !message.reasoning.isNullOrBlank()
                                ) {
                                    stateHolder.imageReasoningCompleteMap[message.id] = true
                                }
                            }
                            stateHolder._currentImageGenerationConversationId.value =
                                loadedMessages.firstOrNull()?.id ?: "image_resume_${System.currentTimeMillis()}"
                            stateHolder._loadedImageGenerationHistoryIndex.value = null
                        }
                    }
                    Log.i(
                        TAG,
                        "loadInitialData: Last open chats loaded. Text: ${lastOpenChat?.size ?: -1}, Image: ${lastOpenImageGenChat?.size ?: -1}",
                    )
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.clear()
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder._loadedHistoryIndex.value = null
                        stateHolder._loadedImageGenerationHistoryIndex.value = null
                        // 若未加载"last open chat"，也重置推理完成映射
                        stateHolder.textReasoningCompleteMap.clear()
                        stateHolder.imageReasoningCompleteMap.clear()
                    }
                    Log.i(TAG, "loadInitialData: Skipped loading last open chats.")
                }
                historyLoadingJob.join()
                notifyLoadingComplete(initialConfigPresent, initialHistoryPresent)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载初始数据时发生严重错误", e)
                withContext(NonCancellable) {
                    historyLoadingJob?.cancelAndJoin()
                }
                warnOnce("部分初始数据加载失败，原数据已保留")
                notifyLoadingComplete(initialConfigPresent, initialHistoryPresent)
            } finally {
                withContext(NonCancellable) {
                    historyLoadingJob?.cancelAndJoin()
                    notifyLoadingComplete(initialConfigPresent, initialHistoryPresent)
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder._isLoadingHistoryData.value = false
                    }
                }
                Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
            }
        }
    }


    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求清除聊天历史...")
            // 清除 Room 数据库中的历史
            roomDataSource.clearChatHistory()
            roomDataSource.clearImageGenerationHistory()
            synchronized(this@DataPersistenceManager) {
                protectedTextSessionIds.clear()
                protectedImageSessionIds.clear()
            }
            protectLastOpenTextSession = false
            protectLastOpenImageSession = false
            Log.i(TAG, "clearAllChatHistory: Room 和 SP 中的聊天历史已清除。")
        }
    }

    suspend fun clearHistoryExplicitly(isImageGeneration: Boolean) {
        withContext(Dispatchers.IO) {
            if (isImageGeneration) {
                roomDataSource.clearImageGenerationHistory()
                roomDataSource.saveLastOpenImageGenerationChat(emptyList())
                synchronized(this@DataPersistenceManager) { protectedImageSessionIds.clear() }
                protectLastOpenImageSession = false
            } else {
                roomDataSource.clearChatHistory()
                roomDataSource.saveLastOpenChat(emptyList())
                synchronized(this@DataPersistenceManager) { protectedTextSessionIds.clear() }
                protectLastOpenTextSession = false
            }
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个图像生成 API 配置到 RoomDataSource...")
                roomDataSource.saveImageGenApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: 图像生成 API 配置已通过 RoomDataSource 保存。")
            } else {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个 API 配置到 RoomDataSource...")
                roomDataSource.saveApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: API 配置已通过 RoomDataSource 保存。")
            }
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>, isImageGeneration: Boolean = false) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveChatHistory: 保存 ${historyToSave.size} 条对话到 Room...")
            val finalHistory = if (isImageGeneration) {
                // 将 data:image 与 http(s) 图片先落盘，替换为本地路径，避免远端URL过期
                historyToSave.map { conv -> persistInlineAndRemoteImages(conv) }
            } else {
                historyToSave
            }
            // 使用 Room 保存历史
            if (isImageGeneration) {
                roomDataSource.saveImageGenerationHistory(
                    history = finalHistory,
                    protectedSessionIds = protectedSessionIds(isImageGeneration = true),
                )
            } else {
                roomDataSource.saveChatHistory(
                    history = finalHistory,
                    protectedSessionIds = protectedSessionIds(isImageGeneration = false),
                )
            }
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 Room 保存。")
        }
    }


    suspend fun saveSelectedConfigIdentifier(configId: String?, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中的图像生成配置ID '$configId' 到 RoomDataSource...")
                roomDataSource.saveSelectedImageGenConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中的图像生成配置ID已通过 RoomDataSource 保存。")
            } else {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中配置ID '$configId' 到 RoomDataSource...")
                roomDataSource.saveSelectedConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 RoomDataSource 保存。")
            }
        }
    }

    suspend fun saveConversationFunctionToggleStates(
        states: Map<String, com.android.everytalk.statecontroller.ConversationFunctionToggleState>
    ) {
        withContext(Dispatchers.IO) {
            val jsonString = json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    com.android.everytalk.statecontroller.ConversationFunctionToggleState.serializer()
                ),
                states
            )
            roomDataSource.setSetting("conversation_function_toggle_states", jsonString)
        }
    }

    suspend fun loadConversationFunctionToggleStates(): Map<String, com.android.everytalk.statecontroller.ConversationFunctionToggleState> {
        return withContext(Dispatchers.IO) {
            val jsonString = roomDataSource.getSetting("conversation_function_toggle_states")
            if (jsonString.isNullOrBlank()) {
                emptyMap()
            } else {
                try {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.MapSerializer(
                            String.serializer(),
                            com.android.everytalk.statecontroller.ConversationFunctionToggleState.serializer()
                        ),
                        jsonString
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "loadConversationFunctionToggleStates 失败", e)
                    emptyMap()
                }
            }
        }
    }
    
    // 新增：持久化保存"会话ID -> GenerationConfig"映射
    suspend fun saveConversationParameters(parameters: Map<String, GenerationConfig>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveConversationParameters(parameters)
                Log.d(TAG, "saveConversationParameters: 已持久化 ${parameters.size} 个会话参数映射")
            } catch (e: Exception) {
                Log.e(TAG, "saveConversationParameters 失败", e)
            }
        }
    }

    suspend fun loadConversationParameters(): Map<String, GenerationConfig> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadConversationParameters()
            } catch (e: Exception) {
                Log.e(TAG, "loadConversationParameters 失败", e)
                emptyMap()
            }
        }
    }

    suspend fun saveConversationApiConfigIds(mapping: Map<String, String>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveConversationApiConfigIds(mapping)
                Log.d(TAG, "saveConversationApiConfigIds: 已持久化 ${mapping.size} 个会话配置映射")
            } catch (e: Exception) {
                Log.e(TAG, "saveConversationApiConfigIds 失败", e)
            }
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 RoomDataSource 清除API配置并取消选中...")
            roomDataSource.clearApiConfigs()
            roomDataSource.saveSelectedConfigId(null) // 确保选中的也被清掉
            roomDataSource.clearImageGenApiConfigs()
            roomDataSource.saveSelectedImageGenConfigId(null)
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 RoomDataSource 清除。")
        }
    }
    suspend fun saveLastOpenChat(messages: List<Message>, isImageGeneration: Boolean = false) {
        if (isImageGeneration && protectLastOpenImageSession) return
        if (!isImageGeneration && protectLastOpenTextSession) return
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT START ===")
        android.util.Log.d("DataPersistenceManager", "Saving ${messages.size} messages, isImageGeneration: $isImageGeneration")
        
        messages.forEachIndexed { index, message -> 
            android.util.Log.d("DataPersistenceManager", "Message $index (${message.id}): text length=${message.text.length}, parts=${message.parts.size}, contentStarted=${message.contentStarted}")
            android.util.Log.d("DataPersistenceManager", "  Sender: ${message.sender}, IsError: ${message.isError}")
            message.parts.forEachIndexed { partIndex, part -> 
                android.util.Log.d("DataPersistenceManager", "  Part $partIndex: ${part::class.simpleName}")
            }
        }
        
        // 修复：确保AI消息的文本内容不会丢失
        val processedMessages = messages.map { message -> 
            if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                message.contentStarted &&
                message.text.isBlank() &&
                message.parts.isNotEmpty()) {
                
                android.util.Log.w("DataPersistenceManager", "Fixing AI message with blank text but has parts: ${message.id}")
                
                // 尝试从parts重建文本内容
                val rebuiltText = message.parts.toRecoveredMarkdown()
                
                if (rebuiltText.isNotBlank()) {
                    android.util.Log.d("DataPersistenceManager", "Rebuilt text from parts: length=${rebuiltText.length}")
                    message.copy(text = rebuiltText)
                } else {
                    // 如果无法重建，至少保留一个占位符
                    android.util.Log.w("DataPersistenceManager", "Could not rebuild text from parts, using placeholder")
                    message.copy(text = "...")
                }
            } else {
                message
            }
        }
        
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveLastOpenChat: Saving ${processedMessages.size} messages for isImageGen=$isImageGeneration to Room")
            try {
                val finalMessages = if (isImageGeneration) {
                    // 对"最后打开的图像会话"统一进行 data:image 与 http(s) 落盘与替换
                    persistInlineAndRemoteImages(processedMessages)
                } else {
                    processedMessages
                }
                // 使用 Room 保存最后打开的会话
                if (isImageGeneration) {
                    roomDataSource.saveLastOpenImageGenerationChat(finalMessages)
                    android.util.Log.d("DataPersistenceManager", "Image chat saved to Room successfully")
                } else {
                    roomDataSource.saveLastOpenChat(finalMessages)
                    android.util.Log.d("DataPersistenceManager", "Text chat saved to Room successfully")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DataPersistenceManager", "Failed to save last open chat to Room", e)
            }
        }
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT END ===")
    }

    suspend fun clearLastOpenChat(isImageGeneration: Boolean = false) {
        if (isImageGeneration && protectLastOpenImageSession) return
        if (!isImageGeneration && protectLastOpenTextSession) return
        withContext(Dispatchers.IO) {
            // 使用 Room 清除最后打开的会话
            if (isImageGeneration) {
                roomDataSource.saveLastOpenImageGenerationChat(emptyList())
            } else {
                roomDataSource.saveLastOpenChat(emptyList())
            }
            Log.d(TAG, "Cleared last open chat for isImageGeneration=$isImageGeneration from Room")
        }
    }
    suspend fun deleteMediaFilesForMessages(conversations: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting deletion of media files for ${conversations.size} conversations.")
            var deletedFilesCount = 0
            val allFilePathsToDelete = mutableSetOf<String>()
            val allHttpUrisToClearFromCache = mutableSetOf<String>()
            val chatAttachmentsDirPath = File(context.filesDir, "chat_attachments").absolutePath

            conversations.forEach { conversation ->
                conversation.forEach { message ->
                    message.attachments.forEach { attachment ->
                        val path = when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> attachment.filePath
                            is SelectedMediaItem.GenericFile -> attachment.filePath
                            is SelectedMediaItem.Audio -> attachment.data
                            is SelectedMediaItem.ImageFromBitmap -> attachment.filePath
                        }
                        if (!path.isNullOrBlank()) {
                            // 用户触发删除：始终释放占用空间
                            allFilePathsToDelete.add(path)
                        }
                    }

                    // 处理消息中的图片URL
                    message.imageUrls?.forEach { urlString ->
                        try {
                            val uri = Uri.parse(urlString)
                            if (uri.scheme == "http" || uri.scheme == "https") {
                                allHttpUrisToClearFromCache.add(urlString)
                            } else {
                                val path = uri.path
                                if (path != null) {
                                    // 用户触发删除：始终释放占用空间
                                    allFilePathsToDelete.add(path)
                                }
                            }
                        } catch (e: Exception) {
                            // Fallback for non-URI strings that might be file paths
                            val file = File(urlString)
                            if (file.exists()) {
                                // 用户触发删除：始终释放占用空间
                                allFilePathsToDelete.add(urlString)
                            }
                        }
                    }
                    
                    // 增强：处理消息中可能包含的其他媒体文件路径
                    // 检查消息文本中是否包含本地文件路径
                    val localFilePattern = Regex("file://[^\\s]+|/data/data/[^\\s]+|/storage/[^\\s]+")
                    localFilePattern.findAll(message.text).forEach { match ->
                        val filePath = match.value.removePrefix("file://")
                        val file = File(filePath)
                        if (file.exists() && (file.name.contains("chat_attachments") ||
                            filePath.contains(context.filesDir.absolutePath))) {
                            // 用户触发删除：始终释放占用空间
                            allFilePathsToDelete.add(filePath)
                        }
                    }
                }
            }

            // 删除文件
            allFilePathsToDelete.forEach { path ->
                try {
                    val file = File(path)
                    if (file.exists()) {
                        if (file.delete()) {
                            Log.d(TAG, "Successfully deleted media file: $path")
                            deletedFilesCount++
                        } else {
                            Log.w(TAG, "Failed to delete media file: $path")
                        }
                    } else {
                        Log.w(TAG, "Media file to delete does not exist: $path")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception deleting media file: $path", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting media file: $path", e)
                }
            }

            // 清理图片缓存
            allFilePathsToDelete.forEach { path ->
                imageLoader.diskCache?.remove(path)
                imageLoader.diskCache?.remove("file://$path")
            }

            allHttpUrisToClearFromCache.forEach { url ->
                imageLoader.diskCache?.remove(url)
            }

            Log.d(TAG, "Finished media file deletion. Total files deleted: $deletedFilesCount")
        }
    }

    /**
     * 清理孤立的附件文件与临时缓存（已删除会话但文件仍存在的情况），并回收图片缓存
     *
     * 覆盖范围：
     * - filesDir/chat_attachments 中不再被引用的图片文件
     * - cacheDir/preview_cache 预览生成的临时文件
     * - cacheDir/share_images 分享生成的临时文件
     * - Coil 内存/磁盘缓存
     *
     * 调用时机：清空历史、大批删除后
     */
    suspend fun cleanupOrphanedAttachments() {
        withContext(Dispatchers.IO) {
            try {
                val chatAttachmentsDir = File(context.filesDir, "chat_attachments")
                val previewCacheDir = File(context.cacheDir, "preview_cache")
                val shareImagesDir = File(context.cacheDir, "share_images")

                // 1) 收集当前所有会话中被引用的图片路径
                val referencedPaths = mutableSetOf<String>()

                // 从文本历史会话收集
                val textHistoryResult = roomDataSource.loadChatHistoryResult()
                textHistoryResult.sessions.forEach { loadedSession ->
                    val conv = loadedSession.messages
                    conv.forEach { msg ->
                        msg.imageUrls?.forEach { url ->
                            val path = url.removePrefix("file://")
                            if (path.startsWith("/")) {
                                referencedPaths.add(path)
                            }
                        }
                        msg.attachments.forEach { att ->
                            val path = when (att) {
                                is SelectedMediaItem.ImageFromUri -> att.filePath
                                is SelectedMediaItem.GenericFile -> att.filePath
                                is SelectedMediaItem.Audio -> att.data
                                is SelectedMediaItem.ImageFromBitmap -> att.filePath
                            }
                            if (!path.isNullOrBlank() && path.startsWith("/")) {
                                referencedPaths.add(path)
                            }
                        }
                    }
                }

                // 从图像生成历史会话收集
                val imageHistoryResult = roomDataSource.loadImageGenerationHistoryResult()
                imageHistoryResult.sessions.forEach { loadedSession ->
                    val conv = loadedSession.messages
                    conv.forEach { msg ->
                        msg.imageUrls?.forEach { url ->
                            val path = url.removePrefix("file://")
                            if (path.startsWith("/")) {
                                referencedPaths.add(path)
                            }
                        }
                    }
                }
                
                // 从最后打开的会话收集
                val lastOpenChat = roomDataSource.loadLastOpenChat()
                lastOpenChat.forEach { msg ->
                    msg.imageUrls?.forEach { url ->
                        val path = url.removePrefix("file://")
                        if (path.startsWith("/")) {
                            referencedPaths.add(path)
                        }
                    }
                }
                
                val lastOpenImageChat = roomDataSource.loadLastOpenImageGenerationChat()
                lastOpenImageChat.forEach { msg ->
                    msg.imageUrls?.forEach { url ->
                        val path = url.removePrefix("file://")
                        if (path.startsWith("/")) {
                            referencedPaths.add(path)
                        }
                    }
                }
                
                Log.d(TAG, "cleanupOrphanedAttachments: Found ${referencedPaths.size} referenced files")

                // 2) 清理 chat_attachments 中不再被引用的文件
                var orphanedCount = 0
                val hasUnloadedSessions = textHistoryResult.failedSessionIds.isNotEmpty() ||
                    imageHistoryResult.failedSessionIds.isNotEmpty()
                if (hasUnloadedSessions) {
                    Log.w(
                        TAG,
                        "cleanupOrphanedAttachments: skipped persistent attachment deletion because some sessions could not be loaded",
                    )
                } else if (chatAttachmentsDir.exists()) {
                    chatAttachmentsDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.absolutePath !in referencedPaths) {
                            try {
                                if (file.delete()) {
                                    orphanedCount++
                                    Log.d(TAG, "Deleted orphaned file: ${file.absolutePath}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete orphaned file: ${file.absolutePath}", e)
                            }
                        }
                    }
                }
                Log.i(TAG, "cleanupOrphanedAttachments: Deleted $orphanedCount orphaned files from chat_attachments")

                // 3) 清空预览/分享产生的临时缓存
                fun clearCacheDir(dir: File, label: String): Int {
                    if (!dir.exists()) return 0
                    var count = 0
                    dir.listFiles()?.forEach { f ->
                        try {
                            if (f.isFile) {
                                if (f.delete()) count++
                            } else {
                                if (f.deleteRecursively()) count++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete cache file in $label: ${f.absolutePath}", e)
                        }
                    }
                    Log.d(TAG, "Cleared $count files from $label")
                    return count
                }
                val clearedPreview = clearCacheDir(previewCacheDir, "preview_cache")
                val clearedShare = clearCacheDir(shareImagesDir, "share_images")

                // 4) 统一清理 Coil 内存/磁盘缓存
                runCatching {
                    imageLoader.memoryCache?.clear()
                    Log.d(TAG, "Coil memory cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil memory cache", e) }

                runCatching {
                    imageLoader.diskCache?.clear()
                    Log.d(TAG, "Coil disk cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil disk cache", e) }

                // 5) 执行数据库 VACUUM 回收 SQLite 空间
                runCatching {
                    roomDataSource.vacuumDatabase()
                    Log.d(TAG, "Database VACUUM completed")
                }.onFailure { e -> Log.w(TAG, "Failed to VACUUM database", e) }

                Log.i(TAG, "Cleanup completed. Deleted $orphanedCount orphaned files. Cleared preview=$clearedPreview, share=$clearedShare cache files.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during orphaned file cleanup", e)
            }
        }
    }

    // ========= 置顶集合：文本与图像 =========
    suspend fun savePinnedIds(ids: Set<String>, isImageGeneration: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                // 使用 Room 保存置顶状态
                if (isImageGeneration) {
                    roomDataSource.savePinnedImageIds(ids)
                } else {
                    roomDataSource.savePinnedTextIds(ids)
                }
                Log.d(TAG, "savePinnedIds: saved ${ids.size} ids for isImageGen=$isImageGeneration to Room")
            } catch (e: Exception) {
                Log.e(TAG, "savePinnedIds failed", e)
            }
        }
    }
    
    /**
     * 保存分组信息。使用 Mutex 确保并发安全。
     * 已迁移到 Room 数据库
     */
    suspend fun saveConversationGroups(groups: Map<String, List<String>>) {
        conversationGroupsSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                roomDataSource.saveConversationGroups(groups)
            }
        }
    }

    /**
     * 加载分组信息。
     * 已迁移到 Room 数据库
     */
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            roomDataSource.loadConversationGroups()
        }
    }

    /**
     * 原子性地更新分组信息。
     * 此方法确保更新操作是串行的，避免并发修改导致的数据丢失。
     * @param updateLambda 一个接收当前分组 Map 并返回新分组 Map 的函数。
     * @return 更新后的分组 Map。
     */
    suspend fun updateConversationGroups(updateLambda: (Map<String, List<String>>) -> Map<String, List<String>>): Map<String, List<String>> {
        return conversationGroupsSaveMutex.withLock {
            val currentGroups = loadConversationGroups()
            val updatedGroups = updateLambda(currentGroups)
            withContext(Dispatchers.IO) {
                roomDataSource.saveConversationGroups(updatedGroups)
            }
            updatedGroups
        }
    }

    suspend fun loadPinnedIds(isImageGeneration: Boolean): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 Room 加载置顶状态
                if (isImageGeneration) {
                    roomDataSource.loadPinnedImageIds()
                } else {
                    roomDataSource.loadPinnedTextIds()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPinnedIds failed", e)
                emptySet()
            }
        }
    }

    suspend fun saveConversationScrollStates(states: Map<String, ConversationScrollState>) {
        conversationScrollStatesSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val serialized = json.encodeToString(states)
                    roomDataSource.setSetting("conversation_scroll_states_v1", serialized)
                    Log.d(TAG, "saveConversationScrollStates: saved ${states.size} items")
                } catch (e: Exception) {
                    Log.e(TAG, "saveConversationScrollStates failed", e)
                }
            }
        }
    }

    suspend fun loadConversationScrollStates(): Map<String, ConversationScrollState> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = roomDataSource.getSetting("conversation_scroll_states_v1", "") ?: ""
                if (raw.isBlank()) return@withContext emptyMap()
                json.decodeFromString<Map<String, ConversationScrollState>>(raw)
            } catch (e: Exception) {
                Log.e(TAG, "loadConversationScrollStates failed", e)
                emptyMap()
            }
        }
    }

    // ========= 分组展开状态 =========

    /**
     * 保存分组展开状态
     * 已迁移到 Room 数据库
     */
    suspend fun saveExpandedGroupKeys(keys: Set<String>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveExpandedGroupKeys(keys)
                Log.d(TAG, "saveExpandedGroupKeys: saved ${keys.size} expanded group keys to Room")
            } catch (e: Exception) {
                Log.e(TAG, "saveExpandedGroupKeys failed", e)
            }
        }
    }
    
    /**
     * 加载分组展开状态
     * 已迁移到 Room 数据库
     */
    suspend fun loadExpandedGroupKeys(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadExpandedGroupKeys()
            } catch (e: Exception) {
                Log.e(TAG, "loadExpandedGroupKeys failed", e)
                emptySet()
            }
        }
    }

    // ========= 语音配置 =========

    /**
     * 保存语音后端配置列表
     */
    suspend fun saveVoiceBackendConfigs(configs: List<VoiceBackendConfig>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveVoiceBackendConfigs(configs)
                Log.d(TAG, "saveVoiceBackendConfigs: 已保存 ${configs.size} 个语音配置")
            } catch (e: Exception) {
                Log.e(TAG, "saveVoiceBackendConfigs 失败", e)
            }
        }
    }

    /**
     * 加载语音后端配置列表
     */
    suspend fun loadVoiceBackendConfigs(): List<VoiceBackendConfig> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadVoiceBackendConfigs()
            } catch (e: Exception) {
                Log.e(TAG, "loadVoiceBackendConfigs 失败", e)
                emptyList()
            }
        }
    }

    /**
     * 保存当前选中的语音配置ID
     */
    suspend fun saveSelectedVoiceConfigId(configId: String?) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveSelectedVoiceConfigId(configId)
                Log.d(TAG, "saveSelectedVoiceConfigId: 已保存选中的语音配置ID '$configId'")
            } catch (e: Exception) {
                Log.e(TAG, "saveSelectedVoiceConfigId 失败", e)
            }
        }
    }

    /**
     * 加载当前选中的语音配置ID
     */
    suspend fun loadSelectedVoiceConfigId(): String? {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadSelectedVoiceConfigId()
            } catch (e: Exception) {
                Log.e(TAG, "loadSelectedVoiceConfigId 失败", e)
                null
            }
        }
    }

    /**
     * 清除所有语音配置
     */
    suspend fun clearVoiceBackendConfigs() {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.clearVoiceBackendConfigs()
                roomDataSource.saveSelectedVoiceConfigId(null)
                Log.d(TAG, "clearVoiceBackendConfigs: 已清除所有语音配置")
            } catch (e: Exception) {
                Log.e(TAG, "clearVoiceBackendConfigs 失败", e)
            }
        }
    }
}
