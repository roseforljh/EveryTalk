package com.android.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.local.SharedPreferencesDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.data.DataClass.GenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.ImageLoader
import com.android.everytalk.util.FileManager
import android.util.Base64
import java.io.FileOutputStream
import java.util.Locale

class DataPersistenceManager(
    private val context: Context,
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val imageLoader: ImageLoader
) {
    private val TAG = "PersistenceManager"

    /**
     * 将消息中的 data:image;base64,... 图片落盘为本地文件，并将 URL 替换为 file:// 或绝对路径
     * 这样可避免把巨大 Base64 串写入 SharedPreferences 导致超限/丢失，重启后可稳定恢复。
     */
    private fun persistInlineAndRemoteImages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val tempDir = File(context.filesDir, "chat_attachments").apply { mkdirs() }
 
        fun extFromMime(mime: String?): String {
            val m = (mime ?: "").lowercase(Locale.ROOT)
            return when {
                m.contains("png") -> "png"
                m.contains("jpeg") || m.contains("jpg") -> "jpg"
                m.contains("webp") -> "webp"
                m.contains("heic") -> "heic"
                m.contains("heif") -> "heif"
                else -> "png"
            }
        }

        fun saveDataUri(dataUri: String, fileNameHint: String): String? {
            return try {
                // 解析形如 data:image/png;base64,AAAA... 的数据
                val commaIndex = dataUri.indexOf(',')
                if (commaIndex <= 0) return null
                // 跳过前缀 "data:"
                val header = dataUri.substring(5, commaIndex)
                val mimePart = header.substringBefore(';', "")
                val mime = if (mimePart.isNotBlank()) mimePart else "image/png"
                val isBase64 = header.contains("base64", ignoreCase = true)
                val payload = dataUri.substring(commaIndex + 1)

                val bytes: ByteArray = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    // 非 base64 的 data:payload（较少见），按 URL 编码处理
                    Uri.decode(payload).toByteArray()
                }

                val ext = extFromMime(mime)
                val file = File(tempDir, "${fileNameHint}_${System.currentTimeMillis()}.$ext")
                FileOutputStream(file).use { fos ->
                    fos.write(bytes)
                }
                if (file.exists() && file.length() > 0) file.absolutePath else null
            } catch (e: Exception) {
                Log.w(TAG, "persistImages: failed to save bytes for $fileNameHint", e)
                null
            }
        }

        fun tryDownload(url: String): Pair<ByteArray, String?>? {
            return try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) return null
                val mime = conn.contentType
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                bytes to mime
            } catch (e: Exception) {
                Log.w(TAG, "persistImages: download failed for $url", e)
                null
            }
        }

        return messages.map { msg ->
            if (msg.imageUrls.isNullOrEmpty()) {
                msg
            } else {
                // 统一保存到 chat_attachments，通过保留期控制清理时机
                val currentDir = tempDir
                currentDir.mkdirs()

                val newUrls = msg.imageUrls.mapIndexed { idx, url ->
                    if (url.startsWith("data:image", ignoreCase = true)) {
                        val saved = saveDataUri(url, "img_${msg.id}_${idx}")
                        saved ?: url
                    } else {
                        url
                    }
                }
                if (newUrls == msg.imageUrls) msg else msg.copy(imageUrls = newUrls)
            }
        }
    }

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
                                val id = conversation.firstOrNull()?.id
                                if (id != null) {
                                    val prompt = conversation.firstOrNull { it.sender == com.android.everytalk.data.DataClass.Sender.System }?.text ?: ""
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
                   // 将历史中的 data:image 与 http(s) 图片统一落盘并替换为本地路径（一次性修复旧数据）
                   val convertedImageGenHistory = loadedImageGenHistory.map { conv -> persistInlineAndRemoteImages(conv) }
                   // 异步回写修复后的历史，避免后续重复转换
                   launch(Dispatchers.IO) {
                       try {
                           dataSource.saveImageGenerationHistory(convertedImageGenHistory)
                       } catch (e: Exception) {
                           Log.w(TAG, "Failed to persist converted image generation history", e)
                       }
                   }
                   withContext(Dispatchers.Main.immediate) {
                       stateHolder._imageGenerationHistoricalConversations.value = convertedImageGenHistory
                   }
                }

                // Phase 3: Load last open chats if needed
                if (loadLastChat) {
                    Log.d(TAG, "loadInitialData: Phase 3 - Loading last open chats...")
                    val lastOpenChat = dataSource.loadLastOpenChat()
                    val lastOpenImageGenChat = dataSource.loadLastOpenImageGenerationChat()
                    // 将“最后打开的图像会话”里的 data:image 与 http(s) 转为本地文件并替换
                    val finalLastOpenImageGen = persistInlineAndRemoteImages(lastOpenImageGenChat)
                    // 异步回写，确保下次启动直接使用文件路径
                    launch(Dispatchers.IO) {
                        try {
                            dataSource.saveLastOpenImageGenerationChat(finalLastOpenImageGen)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist converted last-open image chat", e)
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        // 恢复消息列表
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(lastOpenChat)
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder.imageGenerationMessages.addAll(finalLastOpenImageGen)

                        // ✅ 修复：为已恢复的对话补齐推理完成映射，保证“小白点”可见
                        // 文本模式
                        stateHolder.textReasoningCompleteMap.clear()
                        stateHolder.messages.forEach { msg ->
                            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                !msg.reasoning.isNullOrBlank()) {
                                stateHolder.textReasoningCompleteMap[msg.id] = true
                            }
                        }
                        // 图像模式
                        stateHolder.imageReasoningCompleteMap.clear()
                        stateHolder.imageGenerationMessages.forEach { msg ->
                            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                !msg.reasoning.isNullOrBlank()) {
                                stateHolder.imageReasoningCompleteMap[msg.id] = true
                            }
                        }

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
                        // 若未加载“last open chat”，也重置推理完成映射
                        stateHolder.textReasoningCompleteMap.clear()
                        stateHolder.imageReasoningCompleteMap.clear()
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
            val finalHistory = if (isImageGeneration) {
                // 将 data:image 与 http(s) 图片先落盘，替换为本地路径，避免SP超限与远端URL过期
                historyToSave.map { conv -> persistInlineAndRemoteImages(conv) }
            } else {
                historyToSave
            }
            if (isImageGeneration) {
                dataSource.saveImageGenerationHistory(finalHistory)
            } else {
                dataSource.saveChatHistory(finalHistory)
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
           android.util.Log.d("DataPersistenceManager", "  Text preview: '${message.text.take(50)}${if (message.text.length > 50) "..." else ""}'")
           android.util.Log.d("DataPersistenceManager", "  Sender: ${message.sender}, IsError: ${message.isError}")
           message.parts.forEachIndexed { partIndex, part ->
               android.util.Log.d("DataPersistenceManager", "  Part $partIndex: ${part::class.simpleName}")
           }
       }
       
       // 🔥 修复：确保AI消息的文本内容不会丢失
       val processedMessages = messages.map { message ->
           if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
               message.contentStarted &&
               message.text.isBlank() &&
               message.parts.isNotEmpty()) {
               
               android.util.Log.w("DataPersistenceManager", "Fixing AI message with blank text but has parts: ${message.id}")
               
               // 尝试从parts重建文本内容
               val rebuiltText = message.parts.filterIsInstance<com.android.everytalk.ui.components.MarkdownPart.Text>()
                   .joinToString("") { it.content }
               
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
           Log.d(TAG, "saveLastOpenChat: Saving ${processedMessages.size} messages for isImageGen=$isImageGeneration")
           try {
               val finalMessages = if (isImageGeneration) {
                   // 对“最后打开的图像会话”统一进行 data:image 与 http(s) 落盘与替换
                   persistInlineAndRemoteImages(processedMessages)
               } else {
                   processedMessages
               }
               if (isImageGeneration) {
                   dataSource.saveLastOpenImageGenerationChat(finalMessages)
                   android.util.Log.d("DataPersistenceManager", "Image chat saved successfully")
               } else {
                   dataSource.saveLastOpenChat(finalMessages)
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
    * - cacheDir/preview_cache 预览生成的临时文件
    * - cacheDir/share_images 分享生成的临时文件
    * - Coil 内存/磁盘缓存（在清空历史或大批删除后统一清理，防止残留占用）
    *
    * 注意：不再自动删除 filesDir/chat_attachments 下的文件（包括AI生成图片）。
    * 这些文件仅在“用户删除会话/历史”时释放，以符合“仅手动删除才清理”的预期。
    */
   suspend fun cleanupOrphanedAttachments() {
       withContext(Dispatchers.IO) {
           try {
               val chatAttachmentsDir = File(context.filesDir, "chat_attachments")
               val previewCacheDir = File(context.cacheDir, "preview_cache")
               val shareImagesDir = File(context.cacheDir, "share_images")

               // 1) 统计当前仍被引用的附件路径（仅 filesDir/chat_attachments 管理的持久附件）
               val allActiveFilePaths = mutableSetOf<String>()
               runCatching {
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
               }.onFailure { e ->
                   Log.w(TAG, "Failed to collect active file paths for orphan cleanup", e)
               }

               // 2) 跳过 chat_attachments 的自动删除：仅在“用户删除会话/历史”时释放占用
               var orphanedCount = 0
               if (chatAttachmentsDir.exists()) {
                   val skipped = chatAttachmentsDir.listFiles()?.count { it.isFile && !allActiveFilePaths.contains(it.absolutePath) } ?: 0
                   Log.i(TAG, "Skip auto-deletion for $skipped attachment file(s) in chat_attachments by policy (manual deletion only).")
               }

               // 3) 清空预览/分享产生的临时缓存（cacheDir），这些文件不持久化引用，直接安全删除
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

               // 4) 统一清理 Coil 内存/磁盘缓存，避免 URL/请求键不匹配导致的残留
               runCatching {
                   imageLoader.memoryCache?.clear()
                   Log.d(TAG, "Coil memory cache cleared")
               }.onFailure { e -> Log.w(TAG, "Failed to clear Coil memory cache", e) }

               runCatching {
                   imageLoader.diskCache?.clear()
                   Log.d(TAG, "Coil disk cache cleared")
               }.onFailure { e -> Log.w(TAG, "Failed to clear Coil disk cache", e) }

               Log.i(TAG, "Cleanup completed. Deleted $orphanedCount orphaned files. Cleared preview=$clearedPreview, share=$clearedShare cache files.")
           } catch (e: Exception) {
               Log.e(TAG, "Error during orphaned file cleanup", e)
           }
       }
   }
}