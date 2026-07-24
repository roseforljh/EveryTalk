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



    internal fun AppViewModel.consumeLastSentUserMessageId(messageId: String) {
        stateHolder._lastSentUserMessageId.compareAndSet(messageId, null)
    }

    internal fun AppViewModel.consumeLastSentImageUserMessageId(messageId: String) {
        stateHolder._lastSentImageUserMessageId.compareAndSet(messageId, null)
    }

    internal fun AppViewModel.stageSettingsExport(data: Pair<String, String>) {
        pendingSettingsExport = data
    }

    internal fun AppViewModel.consumeSettingsExport(): Pair<String, String>? = pendingSettingsExport.also {
        pendingSettingsExport = null
    }

    internal fun AppViewModel.buildLocalWebSearchExecutor(): (suspend (String) -> JsonElement)? {
        if (!stateHolder._isWebSearchEnabled.value) return null
        val provider = selectedExternalWebSearchProvider
        val apiKey = selectedExternalWebSearchProviderApiKey
        if (provider != null && apiKey.isNotBlank()) {
            return { query ->
                val result = ExternalWebSearchService.search(provider, apiKey, query)
                result.fold(
                    onSuccess = { response ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("results", kotlinx.serialization.json.JsonArray(
                                response.results.map { r ->
                                    buildJsonObject {
                                        put("title", JsonPrimitive(r.title))
                                        put("url", JsonPrimitive(r.href))
                                        put("snippet", JsonPrimitive(r.snippet))
                                    }
                                }
                            ))
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("error", JsonPrimitive(e.message ?: "搜索失败"))
                        }
                    }
                )
            }
        }
        if (JinaSearchService.isAvailable) {
            return { query ->
                val result = JinaSearchService.search(query)
                result.fold(
                    onSuccess = { response ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("results", kotlinx.serialization.json.JsonArray(
                                response.results.map { r ->
                                    buildJsonObject {
                                        put("title", JsonPrimitive(r.title))
                                        put("url", JsonPrimitive(r.href))
                                        put("snippet", JsonPrimitive(r.snippet))
                                    }
                                }
                            ))
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("error", JsonPrimitive(e.message ?: "搜索失败"))
                        }
                    }
                )
            }
        }
        return null
    }

    internal fun AppViewModel.buildMcpWebFetchFallback(): (suspend (JsonObject) -> JsonElement)? {
        if (!stateHolder._isMcpEnabledForNextRequest.value) return null
        val webFetchTool = mcpManager.getDispatchCandidates()
            .firstOrNull { it.category == McpToolCategory.BROWSER }
            ?: return null
        return { arguments ->
            val url = arguments["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val mcpArgs = buildJsonObject {
                put("url", JsonPrimitive(url))
            }
            mcpManager.callTool(webFetchTool.toolName, mcpArgs)
        }
    }

    internal fun AppViewModel.getStreamingContent(messageId: String): StateFlow<String> {
        return messageContentController.getStreamingContent(messageId)
    }

    internal fun AppViewModel.getStreamingText(messageId: String): StateFlow<String> {
        return messageContentController.getStreamingText(messageId)
    }

    internal fun AppViewModel.getStreamingRenderState(messageId: String): StateFlow<StreamingRenderState> {
        return stateHolder.streamingMessageStateManager.getOrCreateRenderState(messageId)
    }

    internal fun AppViewModel.showAboutDialog() {
        dialogManager.showAboutDialog()
    }

    internal fun AppViewModel.dismissAboutDialog() {
        dialogManager.dismissAboutDialog()
    }

    internal fun AppViewModel.checkForUpdates() {
        // 需求变更：当用户手动点击检查更新时，
        // 如果已有自动弹出的更新对话在显示，先关闭它，再以“手动检查”的对话替换显示。
        if (updateManager.isUpdateDialogActive()) {
            updateManager.clearUpdateInfo() // 关闭前一个（丑的）对话
        }
        updateManager.checkForUpdates() // 重新以手动检查流程弹出（保留你想要的后者样式）
    }

    internal fun AppViewModel.checkForUpdatesSilently() {
        // 启动静默检查前先确认无对话激活，避免与手动检查叠加
        if (updateManager.isUpdateDialogActive()) {
            return
        }
        updateManager.checkForUpdatesSilently()
    }

    internal fun AppViewModel.clearUpdateInfo() {
        updateManager.clearUpdateInfo()
    }

    internal fun AppViewModel.getCurrentMode(): SimpleModeManager.ModeType {
        return simpleModeManager.getCurrentMode()
    }

    internal fun AppViewModel.isInImageMode(): Boolean {
        return simpleModeManager.isInImageMode()
    }

    internal fun AppViewModel.isInTextMode(): Boolean {
        return simpleModeManager.isInTextMode()
    }

    internal suspend fun AppViewModel.areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean = withContext(Dispatchers.Default) {
        if (list1 == null && list2 == null) return@withContext true
        if (list1 == null || list2 == null) return@withContext false

        // 统一规范化：完全忽略所有 System 消息，仅比较 User/AI 的“实质内容”
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return@withContext false

        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]

            val textMatch = msg1.text.trim() == msg2.text.trim()
            val reasoningMatch = (msg1.reasoning ?: "").trim() == (msg2.reasoning ?: "").trim()
            val attachmentsMatch = msg1.attachments.size == msg2.attachments.size &&
                msg1.attachments.map {
                    when (it) {
                        is SelectedMediaItem.ImageFromUri -> it.uri
                        is SelectedMediaItem.GenericFile -> it.uri
                        is SelectedMediaItem.Audio -> it.data
                        is SelectedMediaItem.ImageFromBitmap -> it.filePath
                    }
                }.filterNotNull().toSet() ==
                msg2.attachments.map {
                    when (it) {
                        is SelectedMediaItem.ImageFromUri -> it.uri
                        is SelectedMediaItem.GenericFile -> it.uri
                        is SelectedMediaItem.Audio -> it.data
                        is SelectedMediaItem.ImageFromBitmap -> it.filePath
                    }
                }.filterNotNull().toSet()

            // 图像内容等效性：仅比较是否存在及数量，不比较签名参数等易变部分
            val imagesCount1 = msg1.imageUrls?.size ?: 0
            val imagesCount2 = msg2.imageUrls?.size ?: 0
            val imagesMatch = imagesCount1 == imagesCount2

            // 忽略 id/timestamp/动画/占位等不稳定字段，仅对“角色 + 内容”判等
            if (
                msg1.sender != msg2.sender ||
                msg1.isError != msg2.isError ||
                !textMatch ||
                !reasoningMatch ||
                !attachmentsMatch ||
                !imagesMatch
            ) {
                return@withContext false
            }
        }
        return@withContext true
    }

    internal fun AppViewModel.filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.asSequence()
            .filter { !it.isError }
            .filter { msg ->
                when (msg.sender) {
                    Sender.User -> true
                    // 仅当AI具有“实际内容”时参与比较：文本/推理/图片三者任一存在
                    Sender.AI -> msg.text.isNotBlank() ||
                                 !(msg.reasoning ?: "").isBlank() ||
                                 ((msg.imageUrls?.isNotEmpty()) == true)
                    // 完全忽略 System（含占位标题与真实系统提示），避免系统提示差异导致的误判
                    Sender.System -> false
                    else -> true
                }
            }
            .map { it.copy(text = it.text.trim(), reasoning = it.reasoning?.trim()) }
            .toList()
    }

    internal fun AppViewModel.toggleWebSearchMode(enabled: Boolean) {
        stateHolder.updateCurrentConversationFunctionToggleState { it.copy(webSearchEnabled = enabled) }
        stateHolder._isWebSearchEnabled.value = enabled
        viewModelScope.launch {
            persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
        }
    }

    internal fun AppViewModel.updateExternalWebSearchProviderApiKey(
        provider: ExternalWebSearchProvider,
        apiKey: String,
    ) {
        val normalizedApiKey = apiKey.trim()
        _externalWebSearchConfigs.update { current ->
            current + (
                provider.providerId to ExternalWebSearchProviderConfig(
                    providerId = provider.providerId,
                    apiKey = normalizedApiKey,
                )
            )
        }
        if (normalizedApiKey.isNotBlank()) {
            _selectedExternalWebSearchProviderId.update { it ?: provider.providerId }
        }

        val updatedConfigs = _externalWebSearchConfigs.value
        val selectedProviderId = _selectedExternalWebSearchProviderId.value
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.saveExternalWebSearchConfigs(updatedConfigs.values.toList())
            persistenceManager.saveSelectedExternalWebSearchProviderId(selectedProviderId)
        }
    }

    internal fun AppViewModel.selectExternalWebSearchProvider(provider: ExternalWebSearchProvider) {
        _selectedExternalWebSearchProviderId.value = provider.providerId
        viewModelScope.launch(Dispatchers.IO) {
            persistenceManager.saveSelectedExternalWebSearchProviderId(provider.providerId)
        }
    }

    internal fun AppViewModel.canUseSelectedExternalWebSearchProvider(): Boolean {
        return selectedExternalWebSearchProvider != null && selectedExternalWebSearchProviderApiKey.isNotBlank()
    }

    internal fun AppViewModel.toggleCodeExecutionEnabled() {
        val newValue = !stateHolder._isCodeExecutionEnabled.value
        stateHolder.updateCurrentConversationFunctionToggleState { it.copy(codeExecutionEnabled = newValue) }
        stateHolder._isCodeExecutionEnabled.value = newValue
        viewModelScope.launch {
            persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
        }
    }

    internal fun AppViewModel.setMcpEnabledForNextRequest(enabled: Boolean) {
        stateHolder.updateCurrentConversationFunctionToggleState { it.copy(mcpEnabled = enabled) }
        stateHolder._isMcpEnabledForNextRequest.value = enabled
        viewModelScope.launch {
            persistenceManager.saveConversationFunctionToggleStates(stateHolder.conversationFunctionToggleStates.value)
        }
    }

    internal fun AppViewModel.showSnackbar(message: String) {
        showToast(message)
    }

    internal fun AppViewModel.showToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun AppViewModel.setSearchActiveInDrawer(isActive: Boolean) {
        drawerManager.setSearchActive(isActive)
    }

    internal fun AppViewModel.setExpandedDrawerItemIndex(index: Int?) {
        drawerManager.setExpandedItemIndex(index)
    }

    internal fun AppViewModel.onDrawerSearchQueryChange(query: String) {
        drawerManager.onSearchQueryChange(query)
    }

    internal fun AppViewModel.onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    internal fun AppViewModel.onSendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList(),
        audioBase64: String? = null,
        mimeType: String? = null,
        isImageGeneration: Boolean = false
    ) {
        Log.d("AppViewModel", "onSendMessage: isImage=$isImageGeneration, attachments=${attachments.size}")
        if (!isImageGeneration && stateHolder._isWebSearchEnabled.value) {
            val currentConfig = stateHolder._selectedApiConfig.value
            val supportsNative = com.android.everytalk.data.network.WebSearchSupport.supportsNativeWebSearch(currentConfig)
            if (!supportsNative && !canUseSelectedExternalWebSearchProvider() && !com.android.everytalk.data.network.WebSearchSupport.canUseJinaSearch()) {
                showSnackbar("请先在设置-联网搜索中配置并勾选一个搜索服务商")
                return
            }
        }
        if (
            attachments.isEmpty() &&
            audioBase64 == null &&
            !isImageGeneration &&
            handleSlashCommandIfNeeded(messageText)
        ) {
            return
        }

        // 仅在“接入系统提示”开启时，才把系统提示注入到本次会话
        val engaged = stateHolder.systemPromptEngagedState[stateHolder._currentConversationId.value] ?: false
        val promptToUse = if (engaged) systemPrompt.value else null
        messageSender.sendMessage(
            messageText,
            isFromRegeneration,
            attachments,
            audioBase64 = audioBase64,
            mimeType = mimeType,
            systemPrompt = promptToUse,
            isImageGeneration = isImageGeneration
        )
    }

    internal fun AppViewModel.handleSlashCommandIfNeeded(input: String): Boolean {
        val command = parseSlashCommand(input) ?: return false
        val config = stateHolder._selectedApiConfig.value ?: return false
        if (!shouldHandleOpenClawSlashCommandLocally(input, config.provider, config.channel)) {
            return false
        }
        addLocalSlashUserMessage(input)
        handleSlashCommand(command)
        return true
    }

    internal fun AppViewModel.handleSlashCommand(command: SlashCommand) {
        when (command) {
            SlashCommand.Help -> addLocalSlashReply(buildSlashHelpMessage(), command)
            is SlashCommand.Model -> handleModelCommand(command)
            is SlashCommand.Models -> handleModelsCommand(command)
            SlashCommand.New -> startNewChatWithSystemMessage()
            SlashCommand.Reset -> resetConversationOverrides()
            is SlashCommand.Reasoning -> updateReasoningState(command.enabled)
        }
    }

    internal fun AppViewModel.addLocalSlashUserMessage(text: String) {
        stateHolder.addMessage(
            Message(
                text = text,
                sender = Sender.User
            )
        )
        triggerScrollToBottom()
        saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)
    }

    internal fun AppViewModel.addLocalSlashReply(text: String, command: SlashCommand) {
        addLocalMessage(text = text, sender = localSlashReplySender(command))
    }

    internal fun AppViewModel.addLocalSlashReplyWithLoading(command: SlashCommand, loader: suspend () -> String) {
        val sender = localSlashReplySender(command)
        if (sender != Sender.AI) {
            viewModelScope.launch {
                addLocalMessage(loader(), sender)
            }
            return
        }

        val placeholderId = "slash_ai_${java.util.UUID.randomUUID()}"
        stateHolder.addMessage(
            Message(
                id = placeholderId,
                text = "",
                sender = Sender.AI,
                contentStarted = false
            )
        )
        stateHolder.startLocalSlashLoading(placeholderId)
        triggerScrollToBottom()
        saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)

        viewModelScope.launch {
            val finalText = runCatchingPreservingCancellation { loader() }
                .getOrElse { it.message ?: "命令执行失败" }
            Log.d("SlashCommand", "addLocalSlashReplyWithLoading finalTextChars=${finalText.length}")
            stateHolder.finishLocalSlashLoading(placeholderId, finalText)
            triggerScrollToBottom()
            saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)
        }
    }

    internal fun AppViewModel.addSystemMessage(text: String) {
        addLocalMessage(text = text, sender = Sender.System)
    }

    internal fun AppViewModel.addLocalMessage(text: String, sender: Sender) {
        stateHolder.addMessage(
            Message(
                text = text,
                sender = sender
            )
        )
        triggerScrollToBottom()
        saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)
    }

    internal fun AppViewModel.startNewChatWithSystemMessage() {
        startNewChat()
        viewModelScope.launch {
            withContext(Dispatchers.Main.immediate) {
                addSystemMessage("已开始新聊天。")
            }
        }
    }

    internal fun AppViewModel.buildSlashHelpMessage(): String {
        return listOf(
            "支持的本地命令：",
            "/help - 显示命令帮助",
            "/model - 查询 OpenClaw 后端当前 session 状态",
            "/models - 查询 OpenClaw 模型目录",
            "/new - 开始新聊天",
            "/reset - 重置当前会话参数覆盖",
            "/reasoning on - 开启推理输出",
            "/reasoning off - 关闭推理输出"
        ).joinToString(separator = "\n")
    }

    internal fun AppViewModel.handleModelsCommand(command: SlashCommand.Models) {
        Log.d("SlashCommand", "handleModelsCommand")
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 OpenClaw 配置。")
            return
        }

        val isOpenClaw = config.channel.contains("openclaw", ignoreCase = true) ||
            config.provider.contains("openclaw", ignoreCase = true) ||
            config.model.contains("openclaw", ignoreCase = true)
        if (!isOpenClaw) {
            addSystemMessage("当前会话不是 OpenClaw 配置，无法获取 provider 列表预览。")
            return
        }

        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "/models ${command.args}".trim())),
            provider = config.provider,
            channel = config.channel,
            apiAddress = config.address,
            apiKey = config.key,
            model = config.model,
            deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(getApplication()),
            conversationId = stateHolder._currentConversationId.value,
            openClawSessionId = stateHolder._currentOpenClawSessionId.value
        )

        addLocalSlashReplyWithLoading(command) {
            val result = withContext(Dispatchers.IO) {
                openClawRuntimeStatusService.queryModelsCatalog(request, command.args.takeIf { it.isNotBlank() })
            }
            buildModelsPreviewMessage(result, command.args)
        }
    }

    internal fun AppViewModel.buildModelsPreviewMessage(
        result: com.android.everytalk.data.network.openclaw.ModelsCatalogQueryResult,
        providerArg: String
    ): String {
        return formatModelsCommandMessage(result, providerArg)
    }

    internal fun AppViewModel.handleModelCommand(command: SlashCommand.Model) {
        Log.d("SlashCommand", "handleModelCommand -> proxy /model status")
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 OpenClaw 配置。")
            return
        }

        val isOpenClaw = config.channel.contains("openclaw", ignoreCase = true) ||
            config.provider.contains("openclaw", ignoreCase = true) ||
            config.model.contains("openclaw", ignoreCase = true)
        if (!isOpenClaw) {
            addSystemMessage("当前会话不是 OpenClaw 配置，无法查询后端真实模型状态。")
            return
        }

        val sessionKey = com.android.everytalk.data.network.openclaw.OpenClawGatewayClient.resolveSessionKey(
            ChatRequest(
                messages = emptyList(),
                provider = config.provider,
                channel = config.channel,
                apiAddress = config.address,
                apiKey = config.key,
                model = config.model,
                deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(getApplication()),
                conversationId = stateHolder._currentConversationId.value,
                openClawSessionId = stateHolder._currentOpenClawSessionId.value
            )
        )

        addLocalSlashReplyWithLoading(command) {
            runCatchingPreservingCancellation {
                val proxyRequest = ChatRequest(
                    messages = listOf(
                        com.android.everytalk.data.DataClass.SimpleTextApiMessage(
                            role = "user",
                            content = "/model status"
                        )
                    ),
                    provider = config.provider,
                    channel = config.channel,
                    apiAddress = config.address,
                    apiKey = config.key,
                    model = config.model,
                    deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(getApplication()),
                    conversationId = stateHolder._currentConversationId.value,
                    openClawSessionId = stateHolder._currentOpenClawSessionId.value
                )
                Log.d("SlashCommand", "proxying backend command: /model status, sessionKey=$sessionKey")
                openClawRuntimeStatusService.proxyModelStatusCommand(proxyRequest)
            }.fold(
                onSuccess = { backendReply ->
                    Log.d("SlashCommand", "backend /model status result=$backendReply")
                    buildRuntimeStatusMessage(backendReply)
                },
                onFailure = { error ->
                    val message = error.message ?: "未知错误"
                    Log.d("SlashCommand", "backend /model status result=$message")
                    formatModelCommandFailureMessage(message)
                }
            )
        }
    }

    internal fun AppViewModel.buildRuntimeStatusMessage(backendReply: String): String {
        return formatModelCommandMessage(backendReply = backendReply)
    }

    internal fun AppViewModel.resetConversationOverrides() {
        val config = stateHolder._selectedApiConfig.value
        if (config == null) {
            addSystemMessage("当前未选择 API 配置，无法重置会话参数。")
            return
        }

        val resetConfig = GenerationConfig(
            temperature = config.temperature,
            topP = config.topP,
            maxOutputTokens = null,
            thinkingConfig = null
        )
        persistConversationConfig(resetConfig)
        addLocalSlashReply("已重置当前会话参数覆盖，恢复为当前配置默认值。", SlashCommand.Reset)
    }

    internal fun AppViewModel.updateReasoningState(enabled: Boolean) {
        val currentConfig = stateHolder._selectedApiConfig.value
        if (currentConfig == null) {
            addSystemMessage("当前未选择 API 配置，无法更新 reasoning 状态。")
            return
        }

        val baseConfig = stateHolder.getCurrentConversationConfig() ?: GenerationConfig(
            temperature = currentConfig.temperature,
            topP = currentConfig.topP,
            maxOutputTokens = null,
            thinkingConfig = null
        )
        val isGeminiModel = WebSearchSupport.isGeminiModel(currentConfig)
        val thinkingConfig = if (enabled) {
            ThinkingConfig(
                includeThoughts = true,
                thinkingBudget = if (isGeminiModel) defaultReasoningBudgetForModel(currentConfig.model) else null
            )
        } else {
            ThinkingConfig(
                includeThoughts = false,
                thinkingBudget = 0
            )
        }
        persistConversationConfig(baseConfig.copy(thinkingConfig = thinkingConfig))
        addLocalSlashReply(if (enabled) "已开启 reasoning。" else "已关闭 reasoning。", SlashCommand.Reasoning(enabled))
    }

    internal fun AppViewModel.persistConversationConfig(config: GenerationConfig) {
        stateHolder.updateCurrentConversationConfig(config)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
            } catch (_: Exception) {
            }
        }
    }

    internal fun AppViewModel.addMediaItem(item: SelectedMediaItem) {
        stateHolder.selectedMediaItems.add(item)
    }

    internal fun AppViewModel.removeMediaItemAtIndex(index: Int) {
        if (index >= 0 && index < stateHolder.selectedMediaItems.size) {
            stateHolder.selectedMediaItems.removeAt(index)
        }
    }

    internal fun AppViewModel.clearMediaItems() {
        stateHolder.clearSelectedMedia()
    }

    internal fun AppViewModel.updateConversationParameters(temperature: Float, topP: Float, maxTokens: Int?) {
        val config = GenerationConfig(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens
        )
        // 1) 立即让本会话生效（UI与请求立刻可见）
        stateHolder.updateCurrentConversationConfig(config)
        // 2) 若会话非空，强制保存到历史，确保将参数映射迁移/写入稳定的 history_chat_{index} 键，避免重启后丢回默认
        if (stateHolder.messages.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = false)
                } catch (_: Exception) {
                    // 避免影响UI流
                }
            }
        }
    }

    internal fun AppViewModel.saveCurrentChatToHistory(forceSave: Boolean = true, isImageGeneration: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = forceSave, isImageGeneration = isImageGeneration)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "Failed to save chat to history", e)
            }
        }
    }

    internal fun AppViewModel.getCurrentConversationParameters(): GenerationConfig? {
        // 严格按会话返回；新会话默认无配置（maxTokens 关闭）
        return stateHolder.getCurrentConversationConfig()
    }

    internal fun AppViewModel.onEditDialogTextChanged(newText: String) {
        editMessageController.onEditDialogTextChanged(newText)
    }

    internal fun AppViewModel.requestEditMessage(message: Message, isImageGeneration: Boolean = false) {
        editMessageController.requestEditMessage(message, isImageGeneration)
    }

    internal fun AppViewModel.confirmMessageEdit() {
        editMessageController.confirmMessageEdit()
    }

    internal fun AppViewModel.confirmImageGenerationMessageEdit() {
        editMessageController.confirmImageGenerationMessageEdit()
    }

    internal fun AppViewModel.dismissEditDialog() {
        editMessageController.dismissEditDialog()
    }

    internal fun AppViewModel.cancelEditing() {
        editMessageController.cancelEditing()
    }

    internal fun AppViewModel.regenerateAiResponse(message: Message, isImageGeneration: Boolean = false, scrollToNewMessage: Boolean = false) {
        regenerateController.regenerateFrom(message, isImageGeneration, scrollToNewMessage)
    }

    internal fun AppViewModel.regenerateAiResponseWithConfig(
        message: Message,
        config: ApiConfig,
        isImageGeneration: Boolean = false,
        scrollToNewMessage: Boolean = false
    ) {
        selectConfig(config, isImageGeneration)
        regenerateController.regenerateFrom(message, isImageGeneration, scrollToNewMessage)
    }

    internal fun AppViewModel.clearSystemPrompt() {
        systemPromptController.clearSystemPrompt()
    }

    internal fun AppViewModel.saveSystemPrompt() {
        systemPromptController.saveSystemPrompt()
    }

    internal fun AppViewModel.triggerScrollToBottom() {
        viewModelScope.launch { stateHolder._scrollToBottomEvent.tryEmit(Unit) }
    }

    internal fun AppViewModel.triggerScrollToItem(messageId: String) {
        viewModelScope.launch { stateHolder._scrollToItemEvent.tryEmit(messageId) }
    }

    internal fun AppViewModel.onCancelAPICall() {
        // 根据当前模式取消对应的流/任务，确保图像模式可被中止
        val isImageMode = simpleModeManager.isInImageMode()
        apiHandler.cancelCurrentApiJob("用户取消操作", isNewMessageSend = false, isImageGeneration = isImageMode)
    }

    internal fun AppViewModel.toggleStreamingPause() = streamingControls.togglePause()


    internal fun AppViewModel.startNewChat() {
        dismissEditDialog()
        dismissSourcesDialog()
        cancelPendingTextHistoryLoad()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            try {
                // 使用新的模式管理器
                simpleModeManager.switchToTextMode(forceNew = true)

                messagesMutex.withLock {
                    if (stateHolder.shouldAutoScroll()) {
                        triggerScrollToBottom()
                    }
                    if (isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error starting new chat", e)
                showSnackbar("启动新聊天失败: ${e.message}")
            }
        }
    }
