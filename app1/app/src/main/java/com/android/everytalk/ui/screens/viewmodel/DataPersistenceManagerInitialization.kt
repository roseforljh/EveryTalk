package com.android.everytalk.ui.screens.viewmodel
import com.android.everytalk.statecontroller.*

import android.content.Context
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.rethrowIfCancellation
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
import java.net.URI
import java.nio.file.Files

internal fun DataPersistenceManager.loadInitialDataInternal(
        loadLastChat: Boolean = true,
        onLoadWarning: (String) -> Unit = {},
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        stateHolder._isLoadingHistoryData.value = true
        viewModelScope.launch(Dispatchers.IO) {
            cleanupExpiredCameraFiles()
            Log.d(TAG, "loadInitialData: 开始加载初始数据 (IO Thread)... loadLastChat: $loadLastChat")
            var initialConfigPresent = false
            var initialHistoryPresent = false
            var historyLoadingJob: Job? = null
            var loadingCompleteNotified = false
            var imageConfigIdMigrations: Map<String, String> = emptyMap()
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
                
                // 修复：去重时记录旧ID到保留ID的映射，避免历史会话继续引用已删除ID。
                val retainedByKey = linkedMapOf<String, ApiConfig>()
                val duplicateImageConfigIds = linkedMapOf<String, String>()
                loadedImageGenConfigs.forEach { config ->
                    val isDefaultProvider = config.provider.trim().lowercase() in listOf("默认", "default")
                    val key = if (isDefaultProvider) {
                        "default|${config.model}|${config.modalityType}"
                    } else {
                        "${config.provider}|${config.address}|${config.key}|${config.model}|${config.channel}|${config.modalityType}"
                    }
                    val retained = retainedByKey[key]
                    if (retained == null) {
                        retainedByKey[key] = config
                    } else {
                        duplicateImageConfigIds[config.id] = retained.id
                    }
                }
                val uniqueImageConfigs = retainedByKey.values.toList()
                imageConfigIdMigrations = duplicateImageConfigIds
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
                val retainedSelectedImageGenConfigId = selectedImageGenConfigId?.let {
                    duplicateImageConfigIds[it] ?: it
                }
                var selectedImageGenConfig: ApiConfig? = null
                if (retainedSelectedImageGenConfigId != null) {
                    selectedImageGenConfig = loadedImageGenConfigs.find { it.id == retainedSelectedImageGenConfigId }
                }
                if (selectedImageGenConfig == null && loadedImageGenConfigs.isNotEmpty()) {
                    selectedImageGenConfig = loadedImageGenConfigs.first()
                    roomDataSource.saveSelectedImageGenConfigId(selectedImageGenConfig.id)
                } else if (retainedSelectedImageGenConfigId != selectedImageGenConfigId) {
                    roomDataSource.saveSelectedImageGenConfigId(retainedSelectedImageGenConfigId)
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
                        var textHistoryLoadedCompletely = false
                        val loadedHistory = if (shouldLoadHistory) {
                            Log.d(TAG, "loadInitialData: 从 Room 加载历史数据...")
                            val loadResult = roomDataSource.loadChatHistoryResult()
                            textHistoryLoadedCompletely = loadResult.failedSessionIds.isEmpty()
                            migrateLoadedHistorySessions(
                                loadResult = loadResult,
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
                            val migratedMapping = migrateApiConfigIds(mapping, imageConfigIdMigrations)
                            withContext(Dispatchers.Main.immediate) {
                                stateHolder.conversationApiConfigIds.value = migratedMapping
                            }
                            if (migratedMapping != mapping) {
                                roomDataSource.saveConversationApiConfigIds(migratedMapping)
                                val migratedCount = mapping.count { (conversationId, configId) ->
                                    migratedMapping[conversationId] != configId
                                }
                                Log.i(TAG, "loadInitialData: 已迁移 $migratedCount 条图像配置会话映射")
                            }
                            Log.d(TAG, "loadInitialData: 会话配置映射已加载 - 共 ${migratedMapping.size} 条")
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
                            if (textHistoryLoadedCompletely) {
                                stateHolder.markTextHistoryReadyForParameterCleanup()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "loadInitialData: 加载历史数据时发生错误", e)
                        warnOnce("部分历史加载失败，原数据已保留")
                    }

                    try {
                        val imageHistoryLoadResult = roomDataSource.loadImageGenerationHistoryResult()
                        val convertedImageGenHistory = migrateLoadedHistorySessions(
                            loadResult = imageHistoryLoadResult,
                            isImageGeneration = true,
                            onLoadWarning = ::warnOnce,
                        )
                        withContext(Dispatchers.Main.immediate) {
                            stateHolder._imageGenerationHistoricalConversations.value = convertedImageGenHistory
                            if (imageHistoryLoadResult.failedSessionIds.isEmpty()) {
                                stateHolder.markImageHistoryReadyForStateCleanup()
                            }
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


