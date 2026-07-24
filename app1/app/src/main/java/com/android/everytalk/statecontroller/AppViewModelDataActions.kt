package com.android.everytalk.statecontroller

import android.app.Application
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.network.openclaw.OpenClawRuntimeStatusService
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.statecontroller.viewmodel.DrawerManager
import com.android.everytalk.statecontroller.viewmodel.ProviderManager
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import com.android.everytalk.statecontroller.viewmodel.UpdateManager
import com.android.everytalk.statecontroller.facade.MessageItemsController
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCategory
import com.android.everytalk.statecontroller.controller.systemprompt.SystemPromptController
import com.android.everytalk.statecontroller.controller.config.SettingsController
import com.android.everytalk.statecontroller.controller.conversation.HistoryController
import com.android.everytalk.statecontroller.controller.media.MediaController
import com.android.everytalk.statecontroller.controller.conversation.MessageContentController
import com.android.everytalk.ui.components.streaming.StreamingRenderState
import com.android.everytalk.statecontroller.controller.conversation.ConversationPreviewController
import com.android.everytalk.statecontroller.controller.config.ModelAndConfigController
import com.android.everytalk.statecontroller.controller.conversation.RegenerateController
import com.android.everytalk.statecontroller.controller.conversation.StreamingControls
import com.android.everytalk.statecontroller.facade.UiStateFacade
import com.android.everytalk.statecontroller.controller.lifecycle.LifecycleCoordinator
import com.android.everytalk.statecontroller.controller.conversation.ScrollStateController
import com.android.everytalk.statecontroller.controller.conversation.AnimationStateController
import com.android.everytalk.statecontroller.controller.conversation.EditMessageController
import com.android.everytalk.statecontroller.controller.media.ClipboardController
import com.android.everytalk.statecontroller.controller.config.ConfigFacade
import com.android.everytalk.statecontroller.controller.config.ProviderController
import com.android.everytalk.statecontroller.viewmodel.McpManager
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpStatus
import com.android.everytalk.data.network.GeminiDirectClient
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig
import com.android.everytalk.data.network.ExternalWebSearchService
import com.android.everytalk.data.network.JinaSearchService
import com.android.everytalk.data.network.OpenAIDirectClient
import com.android.everytalk.data.network.OpenAIResponsesClient
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.WebFetchToolExecutor
import com.android.everytalk.util.storage.readAtMost
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone



    internal fun AppViewModel.importSettings(jsonContent: String) {
        settingsController.importSettings(jsonContent)
    }

    internal fun AppViewModel.onAppStop() {
        lifecycleCoordinator.saveOnStop()
    }

    internal fun AppViewModel.clearFetchedModels() {
        modelAndConfigController.clearFetchedModels()
        stateHolder._showAutoFetchConfirmDialog.value = false
        stateHolder._pendingConfigParams.value = null
    }

    internal fun AppViewModel.addModelToConfigGroup(representativeConfig: ApiConfig, modelName: String) {
        modelAndConfigController.addModelToConfigGroup(representativeConfig, modelName)
    }

    internal fun AppViewModel.refreshModelsForConfig(config: ApiConfig) {
        modelAndConfigController.refreshModelsForConfig(config)
    }

    internal fun AppViewModel.getMessageById(id: String): Message? {
        return messages.find { it.id == id } ?: imageGenerationMessages.find { it.id == id }
    }

    internal fun AppViewModel.getStreamingReasoning(messageId: String) = stateHolder.getStreamingReasoning(messageId)


    internal fun AppViewModel.cacheScrollState(conversationId: String, scrollState: ConversationScrollState) {
        scrollStateController.saveScrollState(conversationId, scrollState)
    }

    internal fun AppViewModel.saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        scrollStateController.saveScrollState(conversationId, scrollState)
        if (!scrollStatesInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.saveConversationScrollStates(stateHolder.conversationScrollStates.toMap())
        }
    }

    internal fun AppViewModel.appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        messageContentController.appendReasoningToMessage(messageId, text, isImageGeneration)
    }

    internal fun AppViewModel.appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        messageContentController.appendContentToMessage(messageId, text, isImageGeneration)
    }

    internal fun AppViewModel.getScrollState(conversationId: String): ConversationScrollState? {
        return scrollStateController.getScrollState(conversationId)
    }

    internal fun AppViewModel.encodeUriAsBase64(uri: Uri): String? {
        return try {
            val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val bytes = readAtMost(stream, MAX_URI_BASE64_BYTES)
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AppViewModel", "Failed to encode URI to Base64: $uri", e)
            null
        }
    }

    internal fun AppViewModel.clearAllCaches() {
        lifecycleCoordinator.clearAllCaches()
    }

    internal fun AppViewModel.startAddConfigFlow(
        provider: String,
        address: String,
        key: String,
        channel: String,
        isImageGen: Boolean = false,
        enableCodeExecution: Boolean? = null,
        toolsJson: String? = null,
        imageSize: String? = null,
        numInferenceSteps: Int? = null,
        guidanceScale: Float? = null,
    ) {
        clearFetchedModels()
        stateHolder._pendingConfigParams.value = PendingConfigParams(
            provider = provider.trim(),
            address = address.trim(),
            key = key.trim(),
            channel = channel.trim(),
            isImageGen = isImageGen,
            enableCodeExecution = enableCodeExecution,
            toolsJson = toolsJson,
            imageSize = imageSize,
            numInferenceSteps = numInferenceSteps,
            guidanceScale = guidanceScale,
        )
        stateHolder._showAutoFetchConfirmDialog.value = true
    }

    internal fun AppViewModel.onConfirmAutoFetch() {
        stateHolder._showAutoFetchConfirmDialog.value = false
        val params = stateHolder._pendingConfigParams.value ?: return

        if (params.channel.equals("OpenClaw", ignoreCase = true) || params.provider.equals("OpenClaw", ignoreCase = true)) {
            onManualInput()
            return
        }

        modelAndConfigController.fetchModels(params.address, params.key, params.channel) { result ->
            if (stateHolder._pendingConfigParams.value != params) return@fetchModels

            result.fold(
                onSuccess = { models ->
                    if (models.isNotEmpty()) {
                        showSnackbar("获取到 ${models.size} 个模型")
                        stateHolder._showModelSelectionDialog.value = true
                    } else {
                        showSnackbar("未获取到模型，请手动输入模型名称")
                        showManualModelInput(params)
                    }
                },
                onFailure = { error ->
                    showSnackbar("获取模型失败: ${error.message}")
                    showManualModelInput(params)
                },
            )
        }
    }

    internal fun AppViewModel.onManualInput() {
        val params = stateHolder._pendingConfigParams.value ?: return
        modelAndConfigController.clearFetchedModels()
        showManualModelInput(params)
    }

    internal fun AppViewModel.showManualModelInput(params: PendingConfigParams) {
        stateHolder._showAutoFetchConfirmDialog.value = false
        stateHolder._showModelSelectionDialog.value = false
        viewModelScope.launch {
            if (stateHolder._pendingConfigParams.value != params) return@launch
            _showManualModelInputRequest.emit(AppViewModel.ManualModelInputRequest(isImageGen = params.isImageGen))
        }
    }

    internal fun AppViewModel.dismissManualModelInput() {
        clearFetchedModels()
    }

    internal fun AppViewModel.submitManualModel(modelName: String) {
        val params = stateHolder._pendingConfigParams.value ?: return
        val trimmedModelName = modelName.trim()
        if (trimmedModelName.isEmpty()) {
            showSnackbar("请输入模型名称")
            return
        }

        if (params.isRefresh) {
            modelAndConfigController.replaceModelsForConfigGroup(params, listOf(trimmedModelName))
        } else {
            modelAndConfigController.createMultipleConfigs(
                provider = params.provider,
                address = params.address,
                key = params.key,
                modelNames = listOf(trimmedModelName),
                channel = params.channel,
                isImageGen = params.isImageGen,
                enableCodeExecution = params.enableCodeExecution,
                toolsJson = params.toolsJson,
                imageSize = params.imageSize,
                numInferenceSteps = params.numInferenceSteps,
                guidanceScale = params.guidanceScale,
            )
        }
        clearFetchedModels()
    }

    internal fun AppViewModel.dismissAutoFetchConfirmDialog() {
        clearFetchedModels()
    }

    internal fun AppViewModel.dismissModelSelectionDialog() {
        clearFetchedModels()
    }

    internal fun AppViewModel.clearMessageCache(messageId: String, isImageGeneration: Boolean = false) {
        messageItemsController.clearCacheForMessage(messageId, isImageGeneration)
        // Also clear Markdown cache for this message to force re-rendering
    }

    internal fun AppViewModel.onSelectAllModels() {
        val params = stateHolder._pendingConfigParams.value ?: return
        val models = fetchedModels.value
        if (models.isEmpty()) {
            showSnackbar("没有可用的模型")
            return
        }

        if (params.isRefresh) {
            modelAndConfigController.replaceModelsForConfigGroup(params, models)
        } else {
            modelAndConfigController.createMultipleConfigs(
                provider = params.provider,
                address = params.address,
                key = params.key,
                modelNames = models,
                channel = params.channel,
                isImageGen = params.isImageGen,
                enableCodeExecution = params.enableCodeExecution,
                toolsJson = params.toolsJson,
                imageSize = params.imageSize,
                numInferenceSteps = params.numInferenceSteps,
                guidanceScale = params.guidanceScale,
            )
        }
        clearFetchedModels()
    }

    internal fun AppViewModel.onSelectModels(selectedModels: List<String>) {
        val params = stateHolder._pendingConfigParams.value ?: return
        if (selectedModels.isEmpty()) {
            showSnackbar("请至少选择一个模型")
            return
        }

        if (params.isRefresh) {
            modelAndConfigController.replaceModelsForConfigGroup(params, selectedModels)
        } else {
            modelAndConfigController.createMultipleConfigs(
                provider = params.provider,
                address = params.address,
                key = params.key,
                modelNames = selectedModels,
                channel = params.channel,
                isImageGen = params.isImageGen,
                enableCodeExecution = params.enableCodeExecution,
                toolsJson = params.toolsJson,
                imageSize = params.imageSize,
                numInferenceSteps = params.numInferenceSteps,
                guidanceScale = params.guidanceScale,
            )
        }
        clearFetchedModels()
    }

    internal fun AppViewModel.addMcpServer(config: com.android.everytalk.data.mcp.McpServerConfig) {
        viewModelScope.launch {
            try {
                mcpManager.addServer(config)
                showSnackbar("已添加服务器: ${config.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("添加服务器失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.removeMcpServer(serverId: String) {
        viewModelScope.launch {
            try {
                mcpManager.removeServer(serverId)
                showSnackbar("已移除服务器")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("移除服务器失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.updateMcpServer(config: com.android.everytalk.data.mcp.McpServerConfig) {
        viewModelScope.launch {
            try {
                mcpManager.updateServer(config)
                showSnackbar("已更新服务器: ${config.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("更新服务器失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.toggleMcpServer(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                mcpManager.toggleServer(serverId, enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("操作失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.onLowMemory() {
        lifecycleCoordinator.onLowMemory()
    }

    internal fun AppViewModel.resolveStableConversationId(conversation: List<Message>?): String? {
        return com.android.everytalk.util.ConversationNameHelper.resolveStableId(conversation)
    }

    internal fun AppViewModel.isConversationPinned(index: Int, isImageGeneration: Boolean): Boolean {
        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        
        val conversation = history.getOrNull(index) ?: return false
        val stableId = resolveStableConversationId(conversation) ?: return false
        
        val pinnedSet = if (isImageGeneration) {
            stateHolder.pinnedImageConversationIds.value
        } else {
            stateHolder.pinnedTextConversationIds.value
        }
        
        return pinnedSet.contains(stableId)
    }

    internal fun AppViewModel.togglePinForConversation(index: Int, isImageGeneration: Boolean) {
        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        
        val conversation = history.getOrNull(index)
        val stableId = resolveStableConversationId(conversation)
        
        if (stableId == null) {
            Log.w("AppViewModel", "togglePin: 无法解析会话稳定ID, index=$index")
            return
        }
        
        val flow = if (isImageGeneration) {
            stateHolder.pinnedImageConversationIds
        } else {
            stateHolder.pinnedTextConversationIds
        }
        
        val newSet = flow.value.toMutableSet().apply {
            if (!add(stableId)) {
                remove(stableId)
            }
        }.toSet()
        
        flow.value = newSet
        
        // 持久化
        viewModelScope.launch(Dispatchers.IO) {
            try {
                persistenceManager.savePinnedIds(newSet, isImageGeneration)
                Log.d("AppViewModel", "置顶状态已更新: id=$stableId, pinned=${newSet.contains(stableId)}, mode=${if (isImageGeneration) "IMAGE" else "TEXT"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "保存置顶状态失败", e)
            }
        }
    }

    internal fun AppViewModel.cleanupPinnedIds(isImageGeneration: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = if (isImageGeneration) {
                    stateHolder._imageGenerationHistoricalConversations.value
                } else {
                    stateHolder._historicalConversations.value
                }
                
                // 收集所有现存会话的稳定ID
                val existingIds = history.mapNotNull { conversation ->
                    resolveStableConversationId(conversation)
                }.toSet()
                
                val flow = if (isImageGeneration) {
                    stateHolder.pinnedImageConversationIds
                } else {
                    stateHolder.pinnedTextConversationIds
                }
                
                // 仅保留仍存在的ID
                val cleanedSet = flow.value.intersect(existingIds)
                
                if (cleanedSet.size != flow.value.size) {
                    flow.value = cleanedSet
                    persistenceManager.savePinnedIds(cleanedSet, isImageGeneration)
                    Log.d("AppViewModel", "置顶集合已清理: 移除 ${flow.value.size - cleanedSet.size} 个无效ID")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "清理置顶集合失败", e)
            }
        }
    }

    internal fun AppViewModel.createGroup(groupName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedGroups = persistenceManager.updateConversationGroups { currentGroups ->
                val mutableGroups = currentGroups.toMutableMap()
                if (!mutableGroups.containsKey(groupName)) {
                    mutableGroups[groupName] = emptyList()
                }
                mutableGroups
            }
            // 在 IO 线程中更新 UI 状态
            withContext(Dispatchers.Main) {
                stateHolder.conversationGroups.value = updatedGroups
            }
        }
    }

    internal fun AppViewModel.renameGroup(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedGroups = persistenceManager.updateConversationGroups { currentGroups ->
                val mutableGroups = currentGroups.toMutableMap()
                if (mutableGroups.containsKey(oldName) && !mutableGroups.containsKey(newName)) {
                    val items = mutableGroups.remove(oldName)
                    if (items != null) {
                        mutableGroups[newName] = items
                    }
                }
                mutableGroups
            }
            // 在 IO 线程中更新 UI 状态
            withContext(Dispatchers.Main) {
                stateHolder.conversationGroups.value = updatedGroups
            }
        }
    }

    internal fun AppViewModel.deleteGroup(groupName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedGroups = persistenceManager.updateConversationGroups { currentGroups ->
                val mutableGroups = currentGroups.toMutableMap()
                if (mutableGroups.containsKey(groupName)) {
                    mutableGroups.remove(groupName)
                }
                mutableGroups
            }
            // 在 IO 线程中更新 UI 状态
            withContext(Dispatchers.Main) {
                stateHolder.conversationGroups.value = updatedGroups
            }
        }
    }

    internal fun AppViewModel.moveConversationToGroup(conversationIndex: Int, groupName: String?, isImageGeneration: Boolean) {
        val conversation = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value.getOrNull(conversationIndex)
        } else {
            stateHolder._historicalConversations.value.getOrNull(conversationIndex)
        }
        val stableId = resolveStableConversationId(conversation) ?: return

        // 所有逻辑都在 persistenceManager.updateConversationGroups 内部执行，确保原子性
        viewModelScope.launch(Dispatchers.IO) {
            val updatedGroups = persistenceManager.updateConversationGroups { currentGroups ->
                val mutableGroups = currentGroups.toMutableMap()

                // 从所有分组中移除
                mutableGroups.keys.forEach { key ->
                    val items = mutableGroups[key]?.toMutableList()
                    if (items != null && items.remove(stableId)) {
                        mutableGroups[key] = items
                    }
                }

                // 添加到新分组
                if (groupName != null) {
                    val items = mutableGroups[groupName]?.toMutableList() ?: mutableListOf()
                    if (!items.contains(stableId)) {
                        items.add(stableId)
                        mutableGroups[groupName] = items
                    }
                }
                
                mutableGroups
            }
            
            // 在 IO 线程中更新 UI 状态
            withContext(Dispatchers.Main) {
                stateHolder.conversationGroups.value = updatedGroups
            }
        }
    }

    internal fun AppViewModel.toggleGroupExpanded(groupKey: String) {
        val currentExpanded = stateHolder.expandedGroups.value.toMutableSet()
        if (currentExpanded.contains(groupKey)) {
            currentExpanded.remove(groupKey)
        } else {
            currentExpanded.add(groupKey)
        }
        stateHolder.expandedGroups.value = currentExpanded
        
        // 持久化展开状态
        viewModelScope.launch(Dispatchers.IO) {
            try {
                persistenceManager.saveExpandedGroupKeys(currentExpanded)
                Log.d("AppViewModel", "分组展开状态已保存: groupKey=$groupKey, totalExpanded=${currentExpanded.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "保存分组展开状态失败", e)
            }
        }
    }

    internal fun AppViewModel.isGroupExpanded(groupKey: String): Boolean {
        return stateHolder.expandedGroups.value.contains(groupKey)
    }

    internal fun AppViewModel.shareConversation(index: Int, isImageGeneration: Boolean) {
        val conversations = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }

        val conversation = conversations.getOrNull(index)
        if (conversation == null) {
            Log.w("AppViewModel", "无法分享会话: 索引 $index 无效")
            showSnackbar("无法分享会话")
            return
        }

        viewModelScope.launch {
            try {
                val title = conversationPreviewController.getConversationPreviewText(index, isImageGeneration)
                com.android.everytalk.util.share.ConversationExporter.shareConversation(
                    context = getApplication(),
                    messages = conversation,
                    title = title
                )
                Log.d("AppViewModel", "会话分享已启动: index=$index, isImageGen=$isImageGeneration")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "分享会话失败", e)
                showSnackbar("分享失败: ${e.message}")
            }
        }
    }
