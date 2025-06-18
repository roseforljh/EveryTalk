package com.example.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.model.SelectedMediaItem
import com.example.everytalk.StateControler.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataPersistenceManager(
    private val context: Context,
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope
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
                Log.d(TAG, "loadInitialData: 检查API配置缓存...")
                val loadedConfigs: List<ApiConfig> = if (stateHolder._apiConfigs.value.isEmpty()) {
                    Log.d(TAG, "loadInitialData: API配置缓存未命中。从dataSource加载...")
                    dataSource.loadApiConfigs()
                } else {
                    Log.d(TAG, "loadInitialData: API配置缓存命中。使用现有数据。")
                    stateHolder._apiConfigs.value
                }
                initialConfigPresent = loadedConfigs.isNotEmpty()
                Log.i(
                    TAG,
                    "loadInitialData: API 配置加载完成。数量: ${loadedConfigs.size}, initialConfigPresent: $initialConfigPresent"
                )
                if (initialConfigPresent) {
                    loadedConfigs.forEachIndexed { index, cfg ->
                        Log.d(
                            TAG,
                            "Loaded Config[$index]: ID=${cfg.id.take(4)}, Model=${cfg.model}"
                        )
                    }
                }

                Log.d(TAG, "loadInitialData: 调用 dataSource.loadSelectedConfigId()...")
                val selectedConfigId: String? = dataSource.loadSelectedConfigId()
                Log.i(TAG, "loadInitialData: 加载到的选中配置ID: '$selectedConfigId'")
                var selectedConfigFromDataSource: ApiConfig? = null
                if (selectedConfigId != null) {
                    selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。"
                        )
                        dataSource.saveSelectedConfigId(null)
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(
                        TAG,
                        "loadInitialData: 无有效选中配置或之前未选中，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。"
                    )
                    dataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }
                Log.i(TAG, "loadInitialData: 最终选中的配置: ${finalSelectedConfig?.model ?: "无"}")

                Log.d(TAG, "loadInitialData: 检查聊天历史缓存...")
                val loadedHistoryRaw: List<List<Message>> = if (stateHolder._historicalConversations.value.isEmpty()) {
                    Log.d(TAG, "loadInitialData: 聊天历史缓存未命中。从dataSource加载...")
                    dataSource.loadChatHistory()
                } else {
                    Log.d(TAG, "loadInitialData: 聊天历史缓存命中。使用现有数据。")
                    stateHolder._historicalConversations.value
                }
                val loadedHistory = loadedHistoryRaw.map { conversation ->
                    conversation.map { message ->
                        message
                    }
                }
                initialHistoryPresent = loadedHistory.isNotEmpty()
                Log.i(
                    TAG,
                    "loadInitialData: 聊天历史加载并预处理完成。数量: ${loadedHistory.size}, initialHistoryPresent: $initialHistoryPresent"
                )

                val processedLastOpenChatMessages = if (loadLastChat) {
                    Log.d(TAG, "loadInitialData: 调用 dataSource.loadLastOpenChatInternal()...")
                    val lastOpenChatMessagesLoaded: List<Message> = dataSource.loadLastOpenChatInternal()
                    Log.i(TAG, "loadInitialData: 最后打开的聊天加载完成。消息数量: ${lastOpenChatMessagesLoaded.size}")

                    if (lastOpenChatMessagesLoaded.isNotEmpty()) {
                        Log.d(TAG, "loadInitialData: 开始为 lastOpenChatMessages 预处理 htmlContent 和 contentStarted...")
                        lastOpenChatMessagesLoaded.map { message ->
                            val updatedContentStarted = message.text.isNotBlank() || !message.reasoning.isNullOrBlank() || message.isError
                            message.copy(contentStarted = updatedContentStarted)
                        }
                    } else {
                        emptyList()
                    }
                } else {
                    Log.i(TAG, "loadInitialData: 跳过加载最后打开的聊天。")
                    emptyList()
                }
                Log.d(TAG, "loadInitialData: lastOpenChatMessages 的 htmlContent 和 contentStarted 预处理完成。")

                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 切换到主线程更新 StateHolder...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                    stateHolder._historicalConversations.value = loadedHistory

                    if (loadLastChat) {
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(processedLastOpenChatMessages)
                        stateHolder.messages.forEach { msg ->
                            if (msg.contentStarted || msg.isError) {
                                stateHolder.messageAnimationStates[msg.id] = true
                            }
                        }
                        Log.d(TAG, "loadInitialData: StateHolder.messages 已更新为最后打开的聊天。数量: ${stateHolder.messages.size}")
                    } else {
                        stateHolder.messages.clear()
                        Log.d(TAG, "loadInitialData: StateHolder.messages 已清空，因为 loadLastChat=false。")
                    }

                    stateHolder._loadedHistoryIndex.value = null
                    Log.d(
                        TAG,
                        "loadInitialData: _loadedHistoryIndex 已在 StateHolder 更新中重置为 null。"
                    )

                    Log.d(TAG, "loadInitialData: StateHolder 更新完成。即将调用 onLoadingComplete。")
                    onLoadingComplete(initialConfigPresent, initialHistoryPresent)
                }

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

    suspend fun saveLastOpenChat(messages: List<Message>) {
        withContext(Dispatchers.IO) {
            Log.d(
                TAG,
                "saveLastOpenChat: 请求 dataSource 保存最后打开的聊天 (${messages.size} 条)。"
            )
            dataSource.saveLastOpenChatInternal(messages)
            Log.i(TAG, "saveLastOpenChat: 最后打开的聊天已通过 dataSource 保存。")
        }
    }

    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求 dataSource 清除聊天历史...")
            dataSource.clearChatHistory()
            Log.i(TAG, "clearAllChatHistory: dataSource 已清除聊天历史。")
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个 API 配置到 dataSource...")
            dataSource.saveApiConfigs(configsToSave)
            Log.i(TAG, "saveApiConfigs: API 配置已通过 dataSource 保存。")
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveChatHistory: 保存 ${historyToSave.size} 条对话到 dataSource...")
            dataSource.saveChatHistory(historyToSave)
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 dataSource 保存。")
        }
    }

    suspend fun saveSelectedConfigIdentifier(configId: String?) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中配置ID '$configId' 到 dataSource...")
            dataSource.saveSelectedConfigId(configId)
            Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 dataSource 保存。")
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 dataSource 清除API配置并取消选中...")
            dataSource.clearApiConfigs()
            dataSource.saveSelectedConfigId(null) // 确保选中的也被清掉
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 dataSource 清除。")
        }
    }
    suspend fun deleteMediaFilesForMessages(conversations: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting deletion of media files for ${conversations.size} conversations.")
            var deletedFilesCount = 0
            conversations.forEach { conversation ->
                conversation.forEach { message ->
                    val urisToDelete = mutableSetOf<Uri>()

                    // Collect URIs from attachments
                    message.attachments?.forEach { attachment ->
                        when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> urisToDelete.add(attachment.uri)
                            is SelectedMediaItem.GenericFile -> urisToDelete.add(attachment.uri)
                            is SelectedMediaItem.ImageFromBitmap -> { /* In-memory, no file to delete */ }
                        }
                    }

                    // Collect URIs from imageUrls
                    message.imageUrls?.forEach { urlString ->
                        try {
                            urisToDelete.add(Uri.parse(urlString))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing URI from imageUrls: $urlString", e)
                        }
                    }

                    // Delete all collected unique URIs
                    urisToDelete.forEach { uri ->
                        try {
                            if ("file" == uri.scheme) {
                                val file = uri.path?.let { File(it) }
                                if (file?.exists() == true) {
                                    if (file.delete()) {
                                        Log.d(TAG, "Successfully deleted local file: $uri")
                                        deletedFilesCount++
                                    } else {
                                        Log.w(TAG, "Failed to delete local file: $uri")
                                    }
                                } else {
                                    Log.w(TAG, "Local file to delete does not exist: $uri")
                                }
                            } else {
                                val rowsDeleted = context.contentResolver.delete(uri, null, null)
                                if (rowsDeleted > 0) {
                                    Log.d(TAG, "Successfully deleted media via ContentResolver: $uri")
                                    deletedFilesCount++
                                } else {
                                    Log.w(TAG, "ContentResolver did not delete media, or it did not exist: $uri")
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception deleting media file: $uri", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting media file: $uri", e)
                        }
                    }
                }
            }
            Log.d(TAG, "Finished media file deletion. Total files deleted: $deletedFilesCount")
        }
    }
}