package com.example.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.statecontroller.ViewModelStateHolder
import com.example.everytalk.data.DataClass.GenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.ImageLoader

class DataPersistenceManager(
    private val context: Context,
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val imageLoader: ImageLoader
) {
    private val TAG = "PersistenceManager"

    fun loadInitialData(
        loadLastChat: Boolean = true,
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "loadInitialData: 开始加载初始数据 (IO Thread)... loadLastChat: $loadLastChat")
            var initialConfigPresent = false
            var initialHistoryPresent = false

            try {
                // 第一阶段：快速加载API配置（优先级最高）
                Log.d(TAG, "loadInitialData: 阶段1 - 加载API配置...")
                val loadedConfigs: List<ApiConfig> = if (stateHolder._apiConfigs.value.isEmpty()) {
                    Log.d(TAG, "loadInitialData: API配置缓存未命中。从dataSource加载...")
                    dataSource.loadApiConfigs()
                } else {
                    Log.d(TAG, "loadInitialData: API配置缓存命中。使用现有数据。")
                    stateHolder._apiConfigs.value
                }
                initialConfigPresent = loadedConfigs.isNotEmpty()

                Log.d(TAG, "loadInitialData: 调用 dataSource.loadSelectedConfigId()...")
                val selectedConfigId: String? = dataSource.loadSelectedConfigId()
                var selectedConfigFromDataSource: ApiConfig? = null
                if (selectedConfigId != null) {
                    selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) {
                        Log.w(TAG, "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。")
                        dataSource.saveSelectedConfigId(null)
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(TAG, "loadInitialData: 无有效选中配置或之前未选中，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。")
                    dataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }

                // 立即更新API配置到UI，让用户可以开始使用
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 阶段1完成 - 更新API配置到UI...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                }

                // Load image generation configs
                val loadedImageGenConfigs: List<ApiConfig> = dataSource.loadImageGenApiConfigs()
                val selectedImageGenConfigId: String? = dataSource.loadSelectedImageGenConfigId()
                var selectedImageGenConfig: ApiConfig? = null
                if (selectedImageGenConfigId != null) {
                    selectedImageGenConfig = loadedImageGenConfigs.find { it.id == selectedImageGenConfigId }
                }
                if (selectedImageGenConfig == null && loadedImageGenConfigs.isNotEmpty()) {
                    selectedImageGenConfig = loadedImageGenConfigs.first()
                    dataSource.saveSelectedImageGenConfigId(selectedImageGenConfig.id)
                }
 
                 withContext(Dispatchers.Main.immediate) {
                     stateHolder._imageGenApiConfigs.value = loadedImageGenConfigs
                     stateHolder._selectedImageGenApiConfig.value = selectedImageGenConfig
                 }

                // 第二阶段：异步加载历史数据（延迟加载）
                launch {
                    Log.d(TAG, "loadInitialData: 阶段2 - 开始异步加载历史数据...")
                    
                    // 设置加载状态
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder._isLoadingHistoryData.value = true
                    }
                    
                    try {
                        // 检查是否需要加载历史数据
                        val shouldLoadHistory = stateHolder._historicalConversations.value.isEmpty()
                        val loadedHistory = if (shouldLoadHistory) {
                            Log.d(TAG, "loadInitialData: 从dataSource加载历史数据...")
                            val historyRaw = dataSource.loadChatHistory()
                            // 分批处理历史数据，避免一次性处理大量数据
                            historyRaw.chunked(10).flatMap { chunk ->
                                chunk.map { conversation ->
                                    conversation.map { message -> message }
                                }
                            }
                        } else {
                            Log.d(TAG, "loadInitialData: 使用缓存的历史数据。")
                            stateHolder._historicalConversations.value
                        }
                        
                        initialHistoryPresent = loadedHistory.isNotEmpty()
                        Log.i(TAG, "loadInitialData: 历史数据加载完成。数量: ${loadedHistory.size}")

                        // 🎯 自动修复消息parts - 检查并修复有问题的AI消息
                        val repairedHistory = loadedHistory.map { conversation ->
                            conversation.map { message ->
                                if (message.sender == com.example.everytalk.data.DataClass.Sender.AI && 
                                    message.text.isNotBlank() && 
                                    (message.parts.isEmpty() || 
                                     !message.parts.any { part ->
                                         when (part) {
                                             is com.example.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                                             is com.example.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
                                             // Math blocks removed
                                             // is com.example.everytalk.ui.components.MarkdownPart.Table -> part.tableData.headers.isNotEmpty()
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
                                val id = conversation.firstOrNull()?.id
                                if (id != null) {
                                    val prompt = conversation.firstOrNull { it.sender == com.example.everytalk.data.DataClass.Sender.System }?.text ?: ""
                                    stateHolder.systemPrompts[id] = prompt
                                }
                            }
                            stateHolder._isLoadingHistoryData.value = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadInitialData: 加载历史数据时发生错误", e)
                        withContext(Dispatchers.Main.immediate) {
                            stateHolder._historicalConversations.value = emptyList()
                            stateHolder._isLoadingHistoryData.value = false
                        }
                    }

                   // Load image generation history
                   val loadedImageGenHistory = dataSource.loadImageGenerationHistory()
                   withContext(Dispatchers.Main.immediate) {
                       stateHolder._imageGenerationHistoricalConversations.value = loadedImageGenHistory
                   }
                }

                // Phase 3: Load last open chats if needed
                if (loadLastChat) {
                    Log.d(TAG, "loadInitialData: Phase 3 - Loading last open chats...")
                    val lastOpenChat = dataSource.loadLastOpenChat()
                    val lastOpenImageGenChat = dataSource.loadLastOpenImageGenerationChat()
                    withContext(Dispatchers.Main.immediate) {
                        // 恢复消息列表
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(lastOpenChat)
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder.imageGenerationMessages.addAll(lastOpenImageGenChat)

                        // 为“文本模式/图像模式”恢复稳定的会话ID，保证后端多轮会话可延续
                        val textConvId = lastOpenChat.firstOrNull()?.id ?: "new_chat_${System.currentTimeMillis()}"
                        val imageConvId = lastOpenImageGenChat.firstOrNull()?.id ?: "image_resume_${System.currentTimeMillis()}"
                        stateHolder._currentConversationId.value = textConvId
                        stateHolder._currentImageGenerationConversationId.value = imageConvId

                        // 清空历史索引（处于“继续未存档会话”的状态）
                        stateHolder._loadedHistoryIndex.value = null
                        stateHolder._loadedImageGenerationHistoryIndex.value = null
                    }
                    Log.i(TAG, "loadInitialData: Last open chats loaded. Text: ${lastOpenChat.size}, Image: ${lastOpenImageGenChat.size}")
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.clear()
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder._loadedHistoryIndex.value = null
                        stateHolder._loadedImageGenerationHistoryIndex.value = null
                    }
                    Log.i(TAG, "loadInitialData: Skipped loading last open chats.")
                }
                onLoadingComplete(initialConfigPresent, initialHistoryPresent)

            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载初始数据时发生严重错误", e)
                withContext(Dispatchers.Main.immediate) {
                    stateHolder._apiConfigs.value = emptyList()
                    stateHolder._selectedApiConfig.value = null
                    stateHolder._historicalConversations.value = emptyList()
                    stateHolder.messages.clear()
                    stateHolder._loadedHistoryIndex.value = null
                    onLoadingComplete(false, false)
                }
            } finally {
                Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
            }
        }
    }


    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求 dataSource 清除聊天历史...")
            dataSource.clearChatHistory()
            dataSource.clearImageGenerationHistory()
            Log.i(TAG, "clearAllChatHistory: dataSource 已清除聊天历史。")
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个图像生成 API 配置到 dataSource...")
                dataSource.saveImageGenApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: 图像生成 API 配置已通过 dataSource 保存。")
            } else {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个 API 配置到 dataSource...")
                dataSource.saveApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: API 配置已通过 dataSource 保存。")
            }
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>, isImageGeneration: Boolean = false) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveChatHistory: 保存 ${historyToSave.size} 条对话到 dataSource...")
            if (isImageGeneration) {
                dataSource.saveImageGenerationHistory(historyToSave)
            } else {
                dataSource.saveChatHistory(historyToSave)
            }
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 dataSource 保存。")
        }
    }


    suspend fun saveSelectedConfigIdentifier(configId: String?, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中的图像生成配置ID '$configId' 到 dataSource...")
                dataSource.saveSelectedImageGenConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中的图像生成配置ID已通过 dataSource 保存。")
            } else {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中配置ID '$configId' 到 dataSource...")
                dataSource.saveSelectedConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 dataSource 保存。")
            }
        }
    }
    
    // 新增：持久化保存“会话ID -> GenerationConfig”映射
    suspend fun saveConversationParameters(parameters: Map<String, GenerationConfig>) {
        withContext(Dispatchers.IO) {
            try {
                dataSource.saveConversationParameters(parameters)
                Log.d(TAG, "saveConversationParameters: 已持久化 ${parameters.size} 个会话参数映射")
            } catch (e: Exception) {
                Log.e(TAG, "saveConversationParameters 失败", e)
            }
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 dataSource 清除API配置并取消选中...")
            dataSource.clearApiConfigs()
            dataSource.saveSelectedConfigId(null) // 确保选中的也被清掉
            dataSource.clearImageGenApiConfigs()
            dataSource.saveSelectedImageGenConfigId(null)
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 dataSource 清除。")
        }
    }
   suspend fun saveLastOpenChat(messages: List<Message>, isImageGeneration: Boolean = false) {
       android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT START ===")
       android.util.Log.d("DataPersistenceManager", "Saving ${messages.size} messages, isImageGeneration: $isImageGeneration")
       
       messages.forEachIndexed { index, message ->
           android.util.Log.d("DataPersistenceManager", "Message $index (${message.id}): text length=${message.text.length}, parts=${message.parts.size}, contentStarted=${message.contentStarted}")
           message.parts.forEachIndexed { partIndex, part ->
               android.util.Log.d("DataPersistenceManager", "  Part $partIndex: ${part::class.simpleName}")
           }
       }
       
       withContext(Dispatchers.IO) {
           Log.d(TAG, "saveLastOpenChat: Saving ${messages.size} messages for isImageGen=$isImageGeneration")
           try {
               if (isImageGeneration) {
                   dataSource.saveLastOpenImageGenerationChat(messages)
                   android.util.Log.d("DataPersistenceManager", "Image chat saved successfully")
               } else {
                   dataSource.saveLastOpenChat(messages)
                   android.util.Log.d("DataPersistenceManager", "Text chat saved successfully")
               }
           } catch (e: Exception) {
               android.util.Log.e("DataPersistenceManager", "Failed to save last open chat", e)
           }
       }
       android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT END ===")
   }

   suspend fun clearLastOpenChat(isImageGeneration: Boolean = false) {
       withContext(Dispatchers.IO) {
           if (isImageGeneration) {
               dataSource.saveLastOpenImageGenerationChat(emptyList())
           } else {
               dataSource.saveLastOpenChat(emptyList())
           }
           Log.d(TAG, "Cleared last open chat for isImageGeneration=$isImageGeneration")
       }
   }
   suspend fun deleteMediaFilesForMessages(conversations: List<List<Message>>) {
       withContext(Dispatchers.IO) {
           Log.d(TAG, "Starting deletion of media files for ${conversations.size} conversations.")
           var deletedFilesCount = 0
           val allFilePathsToDelete = mutableSetOf<String>()
           val allHttpUrisToClearFromCache = mutableSetOf<String>()

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
                                   allFilePathsToDelete.add(path)
                               }
                           }
                       } catch (e: Exception) {
                           // Fallback for non-URI strings that might be file paths
                           val file = File(urlString)
                           if (file.exists()) {
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
    * 清理孤立的附件文件（已删除会话但文件仍存在的情况）
    */
   suspend fun cleanupOrphanedAttachments() {
       withContext(Dispatchers.IO) {
           try {
               val chatAttachmentsDir = File(context.filesDir, "chat_attachments")
               if (!chatAttachmentsDir.exists()) return@withContext

               val allActiveFilePaths = mutableSetOf<String>()
               
               // 收集当前活跃会话中的所有文件路径
               val textHistory = stateHolder._historicalConversations.value
               val imageHistory = stateHolder._imageGenerationHistoricalConversations.value
               val currentTextMessages = stateHolder.messages.toList()
               val currentImageMessages = stateHolder.imageGenerationMessages.toList()
               
               listOf(textHistory, imageHistory, listOf(currentTextMessages), listOf(currentImageMessages))
                   .flatten()
                   .forEach { conversation ->
                       conversation.forEach { message ->
                           message.attachments.forEach { attachment ->
                               val path = when (attachment) {
                                   is SelectedMediaItem.ImageFromUri -> attachment.filePath
                                   is SelectedMediaItem.GenericFile -> attachment.filePath
                                   is SelectedMediaItem.Audio -> attachment.data
                                   is SelectedMediaItem.ImageFromBitmap -> attachment.filePath
                               }
                               if (!path.isNullOrBlank()) {
                                   allActiveFilePaths.add(path)
                               }
                           }
                       }
                   }

               // 扫描附件目录，删除不在活跃文件列表中的文件
               var orphanedCount = 0
               chatAttachmentsDir.listFiles()?.forEach { file ->
                   if (file.isFile && !allActiveFilePaths.contains(file.absolutePath)) {
                       try {
                           if (file.delete()) {
                               Log.d(TAG, "Deleted orphaned file: ${file.absolutePath}")
                               orphanedCount++
                           }
                       } catch (e: Exception) {
                           Log.w(TAG, "Failed to delete orphaned file: ${file.absolutePath}", e)
                       }
                   }
               }
               
               Log.i(TAG, "Cleanup completed. Deleted $orphanedCount orphaned files.")
           } catch (e: Exception) {
               Log.e(TAG, "Error during orphaned file cleanup", e)
           }
       }
   }
}