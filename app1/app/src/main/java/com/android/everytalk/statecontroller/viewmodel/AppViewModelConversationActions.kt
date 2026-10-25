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
import com.android.everytalk.data.DataClass.outputModalities
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
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



    internal fun AppViewModel.startNewImageGeneration() {
        historyController.cancelPendingImageHistoryLoad()
        if (isConversationSearchActive.value) setConversationSearchActive(false)
        restoredMessageDraft.value?.let(::consumeRestoredMessageDraft)
        dismissSourcesDialog()
        cancelPendingTextHistoryLoad()
        apiHandler.cancelCurrentApiJob("开始新的图像生成")
        viewModelScope.launch {
            try {
                // 修复：始终强制新建图像会话，避免复用上一会话
                simpleModeManager.switchToImageMode(forceNew = true)
                
                messagesMutex.withLock {
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error starting new image generation", e)
                showSnackbar("启动新图像生成失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.loadConversationFromHistory(index: Int) {
        historyController.cancelPendingImageHistoryLoad()
        if (isConversationSearchActive.value) setConversationSearchActive(false)
        if (shouldSkipReloadingLoadedHistory(
                requestedIndex = index,
                loadedIndex = stateHolder._loadedHistoryIndex.value,
                hasLoadedMessages = stateHolder.messages.isNotEmpty(),
            )
        ) {
            return
        }
        restoredMessageDraft.value?.let(::consumeRestoredMessageDraft)
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载文本模式历史索引 $index", isNewMessageSend = false, isImageGeneration = false)
        val historyBeforeSave = stateHolder._historicalConversations.value
        val loadGeneration = stateHolder._historyLoadGeneration.value + 1L
        stateHolder._historyLoadGeneration.value = loadGeneration
        stateHolder._isLoadingHistory.value = true
        val previousHistoryLoadJob = textHistoryLoadJob
        textHistoryLoadJob?.cancel()
        // 先同步保存当前会话，确保切换前最新 AI 回复已写入历史列表
        textHistoryLoadJob = viewModelScope.launch {
            try {
                previousHistoryLoadJob?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryNow(forceSave = true, isImageGeneration = false)
                }
                val resolvedIndex = resolveHistoryIndexAfterSave(
                    requestedIndex = index,
                    historyBeforeSave = historyBeforeSave,
                    historyAfterSave = stateHolder._historicalConversations.value,
                )
                if (!isCurrentHistoryLoad(loadGeneration, stateHolder._historyLoadGeneration.value)) return@launch
                historyController.loadTextHistory(resolvedIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error preparing text history load", e)
                if (isCurrentHistoryLoad(loadGeneration, stateHolder._historyLoadGeneration.value)) {
                    showSnackbar("加载文本历史对话失败: ${e.message}")
                }
            } finally {
                if (isCurrentHistoryLoad(loadGeneration, stateHolder._historyLoadGeneration.value)) {
                    stateHolder._isLoadingHistory.value = false
                }
            }
        }
    }

    internal fun AppViewModel.loadImageGenerationConversationFromHistory(index: Int) {
        if (isConversationSearchActive.value) setConversationSearchActive(false)
        if (shouldSkipReloadingLoadedHistory(
                requestedIndex = index,
                loadedIndex = stateHolder._loadedImageGenerationHistoryIndex.value,
                hasLoadedMessages = stateHolder.imageGenerationMessages.isNotEmpty(),
            )
        ) {
            // 等待另一条历史时重新选中当前会话，表示留在当前页，应撤销正在读取的目标。
            historyController.cancelPendingImageHistoryLoad()
            return
        }
        restoredMessageDraft.value?.let(::consumeRestoredMessageDraft)
        dismissSourcesDialog()
        cancelPendingTextHistoryLoad()
        apiHandler.cancelCurrentApiJob("加载图像模式历史索引 $index", isNewMessageSend = false, isImageGeneration = true)
        historyController.loadImageHistory(index)
    }

    internal fun AppViewModel.deleteConversation(indexToDelete: Int) {
        historyController.deleteConversation(indexToDelete, isImageGeneration = false)
        // 删除后清理置顶集合中已不存在的会话ID
        cleanupPinnedIds(isImageGeneration = false)
    }

    internal fun AppViewModel.deleteImageGenerationConversation(indexToDelete: Int) {
        historyController.cancelPendingImageHistoryLoad()
        historyController.deleteConversation(indexToDelete, isImageGeneration = true)
        // 删除后清理置顶集合中已不存在的会话ID
        cleanupPinnedIds(isImageGeneration = true)
    }

    internal fun AppViewModel.clearAllConversations() {
        restoredMessageDraft.value?.let(::consumeRestoredMessageDraft)
        dismissSourcesDialog()
        cancelPendingTextHistoryLoad()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        historyController.clearAllConversations(isImageGeneration = false)
        conversationPreviewController.clearAllCaches()
        // 清空所有文本置顶
        stateHolder.pinnedTextConversationIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.savePinnedIds(emptySet(), isImageGeneration = false)
        }
    }

    internal fun AppViewModel.clearAllImageGenerationConversations() {
        historyController.cancelPendingImageHistoryLoad()
        restoredMessageDraft.value?.let(::consumeRestoredMessageDraft)
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有图像生成历史记录")
        historyController.clearAllConversations(isImageGeneration = true)
        conversationPreviewController.clearAllCaches()
        // 清空所有图像置顶
        stateHolder.pinnedImageConversationIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.savePinnedIds(emptySet(), isImageGeneration = true)
        }
    }

    internal fun AppViewModel.showClearImageHistoryDialog() {
        dialogManager.showClearImageHistoryDialog()
    }

    internal fun AppViewModel.dismissClearImageHistoryDialog() {
        dialogManager.dismissClearImageHistoryDialog()
    }

    internal fun AppViewModel.showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources
            stateHolder._showSourcesDialog.value = true
        }
    }

    internal fun AppViewModel.dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) stateHolder._showSourcesDialog.value = false
        }
    }

    internal fun AppViewModel.copyToClipboard(text: String) {
        clipboardController.copyToClipboard(text)
    }

    internal fun AppViewModel.exportMessageText(text: String) {
        clipboardController.exportMessageText(text)
    }

    internal fun AppViewModel.downloadImageFromMessage(message: Message) {
        mediaController.downloadImageFromMessage(message)
    }

    internal fun AppViewModel.saveBitmapToDownloads(bitmap: Bitmap) {
        mediaController.saveBitmapToDownloads(bitmap)
    }

    internal fun AppViewModel.showImageViewer(url: String) {
        if (url.isBlank()) return
        _imageViewerUrl.value = url
        _imageViewerUrls.value = listOf(url)
        _imageViewerIndex.value = 0
        _showImageViewer.value = true
    }

    internal fun AppViewModel.showImageViewer(urls: List<String>, index: Int = 0) {
        if (urls.isEmpty()) return
        val safeIndex = index.coerceIn(urls.indices)
        _imageViewerUrl.value = urls[safeIndex]
        _imageViewerUrls.value = urls
        _imageViewerIndex.value = safeIndex
        _showImageViewer.value = true
    }

    internal fun AppViewModel.dismissImageViewer() {
        _showImageViewer.value = false
        _imageViewerUrl.value = null
        _imageViewerUrls.value = emptyList()
        _imageViewerIndex.value = 0
    }

    internal fun AppViewModel.downloadImage(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaController.downloadImage(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "Download image失败", e)
                showSnackbar("图片下载失败: ${e.message}")
            }
        }
    }

    internal fun AppViewModel.addConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.addConfig(config, isImageGen)


    internal fun AppViewModel.addMultipleConfigs(configs: List<ApiConfig>) {
        viewModelScope.launch {
            configFacade.addMultipleConfigs(configs)
        }
    }

    internal fun AppViewModel.updateConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.updateConfig(config, isImageGen)

    internal fun AppViewModel.deleteConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.deleteConfig(config, isImageGen)

    internal fun AppViewModel.deleteConfigGroup(
            representativeConfig: ApiConfig,
            isImageGen: Boolean = false
    ) {
        configFacade.deleteConfigGroup(representativeConfig, isImageGen)
    }

    internal fun AppViewModel.deleteImageGenConfigGroup(
            representativeConfig: ApiConfig
    ) {
        configFacade.deleteConfigGroup(representativeConfig, isImageGen = true)
    }

    internal fun AppViewModel.clearAllConfigs(isImageGen: Boolean = false) = configFacade.clearAllConfigs(isImageGen)

    internal fun AppViewModel.selectConfig(config: ApiConfig, isImageGen: Boolean = false) = configFacade.selectConfig(config, isImageGen)

    internal fun AppViewModel.clearSelectedConfig(isImageGen: Boolean = false) {
        configFacade.clearSelectedConfig(isImageGen)
    }

    /**
     * 图片快捷参数始终更新当前聊天选中的模型；旧图片页面仅在主选择不是图片模型时兜底。
     * 只允许调用方修改生成参数，内置模型的身份、接口和密钥仍不可编辑或删除。
     */
    private fun AppViewModel.updateSelectedImageParameters(transform: (ApiConfig) -> ApiConfig) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val current = stateHolder._selectedApiConfig.value?.takeIf { "image" in it.outputModalities() }
                ?: stateHolder._selectedImageGenApiConfig.value ?: return@launch
            val updated = transform(current)
            val isImageStorage = stateHolder._imageGenApiConfigs.value.any { it.id == current.id }
            val configs = if (isImageStorage) stateHolder._imageGenApiConfigs else stateHolder._apiConfigs
            if (configs.value.none { it.id == current.id }) return@launch
            configs.update { list -> list.map { if (it.id == current.id) updated else it } }
            if (stateHolder._selectedApiConfig.value?.id == current.id) stateHolder._selectedApiConfig.value = updated
            if (stateHolder._selectedImageGenApiConfig.value?.id == current.id) stateHolder._selectedImageGenApiConfig.value = updated
            try {
                withContext(Dispatchers.IO) { persistenceManager.saveApiConfigs(configs.value, isImageGen = isImageStorage) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("AppViewModel", "保存图片生成参数失败", error)
                showSnackbar("图片参数保存失败，请重试")
            }
        }
    }

    internal fun AppViewModel.updateImageNumInferenceStepsForSelectedConfig(steps: Int) =
        updateSelectedImageParameters { it.copy(numInferenceSteps = steps.coerceIn(1, 20)) }

    internal fun AppViewModel.updateImageGenerationParamsForSelectedConfig(steps: Int, guidance: Float) =
        updateSelectedImageParameters { it.copy(numInferenceSteps = steps.coerceIn(1, 50), guidanceScale = guidance.coerceIn(1f, 30f)) }

    internal fun AppViewModel.updateImageSizeForSelectedConfig(size: String) =
        updateSelectedImageParameters { it.copy(imageSize = size) }

    /** 比例与质量按模型保存，快捷菜单与模型参数面板使用同一字段。 */
    internal fun AppViewModel.updateImageAspectRatioForSelectedConfig(ratio: String?) =
        updateSelectedImageParameters { it.copy(modelParameters = it.modelParameters.copy(imageAspectRatio = ratio)) }

    internal fun AppViewModel.updateImageQualityForSelectedConfig(quality: String) =
        updateSelectedImageParameters { it.copy(modelParameters = it.modelParameters.copy(imageQuality = quality)) }

    internal fun AppViewModel.saveApiConfigs() {
        configFacade.saveApiConfigs()
    }

    internal fun AppViewModel.addProvider(providerName: String) {
        providerController.addProvider(providerName)
    }

    internal fun AppViewModel.deleteProvider(providerName: String) {
        providerController.deleteProvider(providerName)
    }

    internal fun AppViewModel.updateConfigGroup(
        representativeConfig: ApiConfig,
        newProvider: String,
        newAddress: String,
        newKey: String,
        newChannel: String,
        isImageGen: Boolean? = null,
        newEnableCodeExecution: Boolean? = null,
        newToolsJson: String? = null
    ) {
        configFacade.updateConfigGroup(
            representativeConfig = representativeConfig,
            newProvider = newProvider,
            newAddress = newAddress,
            newKey = newKey,
            newChannel = newChannel,
            isImageGen = isImageGen,
            newEnableCodeExecution = newEnableCodeExecution,
            newToolsJson = newToolsJson
        )
    }

    internal fun AppViewModel.updateConfigGroup(representativeConfig: ApiConfig, newAddress: String, newKey: String, providerToKeep: String, newChannel: String) {
        updateConfigGroup(representativeConfig, providerToKeep, newAddress, newKey, newChannel, null, null, null)
    }

    internal fun AppViewModel.onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            animationStateController.onAnimationComplete(messageId)
        }
    }

    internal fun AppViewModel.hasAnimationBeenPlayed(messageId: String): Boolean {
        return animationStateController.hasAnimationBeenPlayed(messageId)
    }

    internal fun AppViewModel.getConversationPreviewText(index: Int, isImageGeneration: Boolean = false): String {
        return conversationPreviewController.getConversationPreviewText(index, isImageGeneration)
    }

    internal fun AppViewModel.getConversationPreviewText(stableId: String, index: Int, isImageGeneration: Boolean = false): String {
        return conversationPreviewController.getConversationPreviewText(stableId, index, isImageGeneration)
    }

    internal fun AppViewModel.getConversationFullText(index: Int, isImageGeneration: Boolean = false): String {
        return historyController.getConversationFullText(index, isImageGeneration)
    }

    internal fun AppViewModel.renameConversation(index: Int, newName: String, isImageGeneration: Boolean = false) {
        // 获取会话以解析 stableId
        val history = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        val conversation = history.getOrNull(index)
        val stableId = com.android.everytalk.util.ConversationNameHelper.resolveStableId(conversation)

        historyController.renameConversation(index, newName, isImageGeneration)
        // 通过控制器更新本地预览缓存，避免在 VM 内直接操作 LruCache
        if (stableId != null) {
            conversationPreviewController.setCachedTitle(stableId, newName, isImageGeneration)
        }
    }

    internal fun AppViewModel.onAiMessageFullTextChanged(messageId: String, currentFullText: String) {
        messageContentController.onAiMessageFullTextChanged(messageId, currentFullText)
    }

    internal fun AppViewModel.exportSettings(includeHistory: Boolean = false) {
        settingsController.exportSettings(includeHistory)
    }
