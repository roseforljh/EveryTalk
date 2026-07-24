package com.android.everytalk.statecontroller

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.content.FileProvider
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.Message as UiMessage
import com.android.everytalk.data.DataClass.Sender as UiSender
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.PromptCapabilityCatalog
import com.android.everytalk.data.network.PromptCachePolicy
import com.android.everytalk.statecontroller.defaultReasoningBudgetForModel
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCandidate
import com.android.everytalk.statecontroller.mcp.dispatch.QueryIntent
import com.android.everytalk.statecontroller.mcp.dispatch.classifyMcpIntent
import com.android.everytalk.statecontroller.mcp.dispatch.selectMcpCandidates
import com.android.everytalk.statecontroller.mcp.dispatch.toToolDefinition
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun shouldUsePromptCapabilities(
    isImageGeneration: Boolean,
    provider: String,
    channel: String,
    model: String,
): Boolean = !isImageGeneration && listOf(provider, channel, model).none {
    it.contains("openclaw", ignoreCase = true)
}

internal fun MessageSender.sendMessageInternal(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList(),
        audioBase64: String? = null,
        mimeType: String? = null,
        systemPrompt: String? = null,
        isImageGeneration: Boolean = false,
        manualMessageId: String? = null
    ) {
        val textToActuallySend = messageText.trim()
        val allAttachments = attachments.toMutableList()
        if (audioBase64 != null) {
            allAttachments.add(SelectedMediaItem.Audio(id = "audio_${UUID.randomUUID()}", mimeType = mimeType ?: "audio/3gpp", data = audioBase64))
        }

        if (textToActuallySend.isBlank() && allAttachments.isEmpty()) {
            viewModelScope.launch { showSnackbar("请输入消息内容或选择项目") }
            return
        }
        
        // 🔥 关键调试：检查配置状态
        Log.d("MessageSender", "=== SEND MESSAGE DEBUG ===")
        Log.d("MessageSender", "inputChars=${messageText.length}, trimmedChars=${textToActuallySend.length}, attachments=${allAttachments.size}")
        Log.d("MessageSender", "textConversationId=${stateHolder._currentConversationId.value}")
        Log.d("MessageSender", "imageConversationId=${stateHolder._currentImageGenerationConversationId.value}")
        Log.d("MessageSender", "isImageGeneration: $isImageGeneration")
        Log.d("MessageSender", "selectedImageGenApiConfig: ${safeApiConfigSummary(stateHolder._selectedImageGenApiConfig.value)}")
        Log.d("MessageSender", "selectedApiConfig: ${safeApiConfigSummary(stateHolder._selectedApiConfig.value)}")
        Log.d("MessageSender", "imageGenerationMessages.size: ${stateHolder.imageGenerationMessages.size}")
        Log.d("MessageSender", "messages.size: ${stateHolder.messages.size}")
        
        val currentConfig = (if (isImageGeneration) stateHolder._selectedImageGenApiConfig.value else stateHolder._selectedApiConfig.value) ?: run {
            Log.e("MessageSender", "❌ No API config selected! isImageGeneration=$isImageGeneration")
            viewModelScope.launch { showSnackbar(if (isImageGeneration) "请先选择 图像生成 的API配置" else "请先选择 API 配置") }
            return
        }

        // 记录会话使用的配置ID
        if (!isImageGeneration) {
            val conversationId = stateHolder._currentConversationId.value
            stateHolder.conversationApiConfigIds.update { currentMap ->
                if (currentMap[conversationId] == currentConfig.id) currentMap
                else currentMap + (conversationId to currentConfig.id)
            }
            // 这里仅更新内存状态，HistoryManager.saveCurrentChatToHistoryIfNeededInternal 会负责持久化
        } else {
            // 图像模式：绑定当前图像会话ID与配置ID
            val conversationId = stateHolder._currentImageGenerationConversationId.value
            stateHolder.conversationApiConfigIds.update { currentMap ->
                if (currentMap[conversationId] == currentConfig.id) currentMap
                else currentMap + (conversationId to currentConfig.id)
            }
            // 这里仅更新内存状态，HistoryManager 会负责持久化
        }
        
        Log.d("MessageSender", "✅ Using config: ${safeApiConfigSummary(currentConfig)}")
        Log.d("MessageSender", "=== END SEND MESSAGE DEBUG ===")

        
        // 详细调试配置信息
        if (isImageGeneration) {
            Log.d("MessageSender", "=== IMAGE GEN CONFIG DEBUG ===")
            Log.d("MessageSender", "Selected config ID: ${currentConfig.id}")
            Log.d("MessageSender", "ConfigSummary: ${safeApiConfigSummary(currentConfig)}")
            Log.d("MessageSender", "ModalityType: ${currentConfig.modalityType}")
        }

        viewModelScope.launch {
            val modelIsGeminiType = WebSearchSupport.isGeminiModel(currentConfig)
            val shouldUsePartsApiMessage = modelIsGeminiType
            val providerForRequestBackend = currentConfig.provider
            val isDefaultProvider = currentConfig.provider.trim().lowercase() in listOf("默认", "default")

            // 自动注入"上一轮AI出图"作为参考，以支持"在上一张基础上修改"等编辑语义
            if (isImageGeneration && allAttachments.isEmpty()) {
                val t = textToActuallySend.lowercase()
                if (hasImageEditKeywords(t)) {
                    try {
                        // 找到最近一条包含图片的AI消息
                        val lastAiWithImage = stateHolder.imageGenerationMessages.lastOrNull {
                            it.sender == UiSender.AI && !it.imageUrls.isNullOrEmpty()
                        }
                        val refImageUrl = lastAiWithImage?.imageUrls?.lastOrNull()
                        if (!refImageUrl.isNullOrBlank()) {
                            // 下载并等比压缩该图片，作为位图附件加入
                            val fm = FileManager(application)
                            val referenceHeaders = buildMap {
                                currentConfig.key.takeIf { it.isNotBlank() }
                                    ?.let { put("Authorization", "Bearer $it") }
                                currentConfig.address.takeIf { it.isNotBlank() }
                                    ?.let { put("Referer", it) }
                            }
                            val refBitmap = fm.loadAndCompressBitmapFromUrl(
                                urlStr = refImageUrl,
                                isImageGeneration = true,
                                trustedOrigin = currentConfig.address,
                                headers = referenceHeaders,
                            )
                            if (refBitmap != null) {
                                val referenceAttachment = withContext(Dispatchers.Default) {
                                    try {
                                        SelectedMediaItem.ImageFromBitmap.fromBitmap(
                                            bitmap = refBitmap,
                                            id = "ref_${UUID.randomUUID()}"
                                        )
                                    } finally {
                                        if (!refBitmap.isRecycled) refBitmap.recycle()
                                    }
                                }
                                allAttachments.add(
                                    referenceAttachment
                                )
                                Log.d("MessageSender", "已自动附带上一轮AI图片作为参考: $refImageUrl")
                            } else {
                                Log.w("MessageSender", "未能下载上一轮AI图片，跳过自动引用")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("MessageSender", "自动引用上一轮AI图片失败: ${e.message}")
                    }
                }
            }

            val attachmentResult = processAttachments(allAttachments, shouldUsePartsApiMessage, textToActuallySend, isImageGeneration)
            if (!attachmentResult.success) {
                return@launch
            }

            // Always pass the attachments to the ApiClient.
            // The ApiClient will handle creating the multipart request.
            // The previous logic incorrectly sent an empty list for Gemini.
            val attachmentsForApiClient = attachmentResult.processedAttachmentsForUi

            val newUserMessageForUi = UiMessage(
                id = manualMessageId ?: "user_${UUID.randomUUID()}", text = textToActuallySend, sender = UiSender.User,
                timestamp = System.currentTimeMillis(), contentStarted = true,
                imageUrls = attachmentResult.imageUriStringsForUi,
                attachments = attachmentResult.processedAttachmentsForUi,
                modelName = currentConfig.model,
                providerName = currentConfig.provider
            )

            withContext(Dispatchers.Main.immediate) {
                val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                animationMap[newUserMessageForUi.id] = true
                val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
                addOrReplaceRegeneratedUserMessage(
                    messageList = messageList,
                    newUserMessage = newUserMessageForUi,
                    isFromRegeneration = isFromRegeneration,
                    manualMessageId = manualMessageId,
                )
                if (isImageGeneration) {
                    stateHolder._lastSentImageUserMessageId.value = newUserMessageForUi.id
                } else {
                    stateHolder._lastSentUserMessageId.value = newUserMessageForUi.id
                    stateHolder.persistPendingParamsIfNeeded(isImageGeneration = false)
                }
                if (!isFromRegeneration) {
                   stateHolder._text.value = ""
                   stateHolder.clearSelectedMedia()
                }
            }

            // 🔥 新增：当在新会话中发送第一条消息时，立即将其添加到历史记录中，以便在抽屉中即时可见
            val isNewTextChatFirstMessage = !isImageGeneration &&
                    stateHolder.messages.size == 1 &&
                    stateHolder._loadedHistoryIndex.value == null

            val isNewImageChatFirstMessage = isImageGeneration &&
                    stateHolder.imageGenerationMessages.size == 1 &&
                    stateHolder._loadedImageGenerationHistoryIndex.value == null

            if (isNewTextChatFirstMessage || isNewImageChatFirstMessage) {
                withContext(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                }
            }

            withContext(Dispatchers.IO) {
                val messagesInChatUiSnapshot = if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
                logUiMessages("rawMessages", messagesInChatUiSnapshot)
                val historyEndIndex = messagesInChatUiSnapshot.indexOfFirst { it.id == newUserMessageForUi.id }
                val historyUiMessagesRaw = if (historyEndIndex != -1) messagesInChatUiSnapshot.subList(0, historyEndIndex) else messagesInChatUiSnapshot

                // 当"系统提示接入"处于暂停状态时，过滤掉会话历史中的系统消息，避免仍然将 Prompt 注入到请求
                val engagedForThisConversation = stateHolder.systemPromptEngagedState[stateHolder._currentConversationId.value] ?: false
                val historyUiMessages = if (engagedForThisConversation) {
                    historyUiMessagesRaw
                } else {
                    historyUiMessagesRaw.filter { msg ->
                        val filteredOut = msg.sender == UiSender.System && !msg.isPlaceholderName
                        if (filteredOut) {
                            Log.d(
                                "MessageSender",
                                "filteredOutUiMessage: role=${msg.role} reason=systemPromptPaused textChars=${msg.text.length}"
                            )
                        }
                        !filteredOut
                    }
                }
                logUiMessages("filteredMessages", historyUiMessages)

                // 图像会话的稳定会话ID规则：
                // 第一次消息（historyEndIndex==0 且非从历史加载）时，用"首条用户消息ID"作为 conversationId，
                // 这样重启后根据第一条消息ID恢复，后端会话可继续（与 SimpleModeManager.loadImageHistory 的写法严格一致）。
                if (isImageGeneration) {
                    val isFirstMessageInThisSession = historyEndIndex == 0
                    val notFromHistory = stateHolder._loadedImageGenerationHistoryIndex.value == null
                    if (isFirstMessageInThisSession && notFromHistory) {
                        stateHolder._currentImageGenerationConversationId.value = newUserMessageForUi.id
                    }
                }

                // 🔥 修复：使用带Context的toApiMessage方法获取真实MIME类型
                val historyApiMessages = historyUiMessages.map { it.toApiMessage(uriToBase64Encoder, application) }.toMutableList()
                logApiMessages("historyApiMessages", historyApiMessages)

                val currentUserApiMessage = newUserMessageForUi.toApiMessage(uriToBase64Encoder, application)
                Log.d(
                    "MessageSender",
                    "currentUserApiMessage: role=${currentUserApiMessage.role} summary=${describeApiMessage(currentUserApiMessage)}"
                )

                val apiMessagesForBackend = ensureUserMessagePresentForRequest(historyApiMessages, currentUserApiMessage)

                val isMcpEnabledForRequest = stateHolder._isMcpEnabledForNextRequest.value
                val dispatchCandidates = if (isMcpEnabledForRequest) {
                    getMcpDispatchCandidates()
                } else {
                    emptyList()
                }
                // 规范化图像尺寸：为空或包含占位符时回退到 1024x1024（基础兜底）
                val baseSanitizedImageSize = currentConfig.imageSize?.takeIf { it.isNotBlank() && !it.contains("<") } ?: "1024x1024"
                
                // 根据模型家族 + 所选比例，推导 Kolors/Qwen 的精确分辨率（image_size）
                // - Kolors: 使用映射表或精确选择（含 3:4 的两个选项）
                // - Qwen-Image: 必须指定推荐分辨率；Qwen-Image-Edit 不支持 image_size（保持 null）
                val detectedFamilyForImage = com.android.everytalk.ui.components.ImageGenCapabilities.detectFamily(
                    modelName = currentConfig.model,
                    provider = currentConfig.provider,
                    apiAddress = currentConfig.address
                )
                val isQwenEditModel = currentConfig.model.contains("Image-Edit", ignoreCase = true)
                val selectedRatioForImage = stateHolder._selectedImageRatio.value
                
                val familyBasedImageSize: String? = when (detectedFamilyForImage) {
                    com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.KOLORS -> {
                        val labelFromRatio = "${selectedRatioForImage.width}x${selectedRatioForImage.height}"
                        val mapped = com.android.everytalk.ui.components.ImageGenCapabilities
                            .getKolorsSizesByRatio(selectedRatioForImage.displayName)
                            .firstOrNull()?.label
                        if (mapped.isNullOrBlank()) labelFromRatio else mapped
                    }
                    com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.QWEN -> {
                        if (isQwenEditModel) {
                            null // 按文档：Qwen-Image-Edit 不支持 image_size
                        } else {
                            val mapped = com.android.everytalk.ui.components.ImageGenCapabilities
                                .getQwenSizesByRatio(selectedRatioForImage.displayName)
                            (mapped.firstOrNull()?.label ?: "1328x1328")
                        }
                    }
                    com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.GPT_IMAGE -> {
                        if (selectedRatioForImage.isAuto) {
                            null
                        } else {
                            com.android.everytalk.ui.components.ImageGenCapabilities
                                .getGptImageSize(selectedRatioForImage.displayName)
                                ?.label
                        }
                    }
                    else -> null
                }
                
                val finalImageSize = familyBasedImageSize ?: baseSanitizedImageSize
                val hasAttachmentImages = attachmentsForApiClient.any {
                    it is com.android.everytalk.models.SelectedMediaItem.ImageFromUri ||
                        it is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap
                }
                val imageSizeForRequest: String? = when {
                    detectedFamilyForImage == com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.QWEN && isQwenEditModel -> null
                    detectedFamilyForImage == com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.GPT_IMAGE && selectedRatioForImage.isAuto && hasAttachmentImages -> null
                    else -> finalImageSize
                }
                // 检查是否包含图像生成关键词
                if (isImageGeneration && hasImageGenerationKeywords(textToActuallySend)) {
                    // 重置重试计数
                    stateHolder._imageGenerationRetryCount.value = 0
                    stateHolder._imageGenerationError.value = null
                    stateHolder._shouldShowImageGenerationError.value = false
                }

                val isGeminiChannel = WebSearchSupport.isGeminiNativeSearch(currentConfig)
                val supportsNativeWebSearch = WebSearchSupport.supportsNativeWebSearch(currentConfig)
                val selectedExternalProvider = getSelectedExternalWebSearchProvider()
                val selectedExternalProviderApiKey = getSelectedExternalWebSearchProviderApiKey()
                val webSearchRouting = WebSearchSupport.resolveWebSearchRouting(
                    config = currentConfig,
                    isWebSearchEnabled = stateHolder._isWebSearchEnabled.value,
                    selectedExternalProvider = selectedExternalProvider,
                    selectedExternalProviderApiKey = selectedExternalProviderApiKey,
                )
                val preparedMcpDispatch = if (isMcpEnabledForRequest && dispatchCandidates.isNotEmpty()) {
                    prepareMcpDispatch(
                        messageText = textToActuallySend,
                        allCandidates = dispatchCandidates,
                    )
                } else {
                    PreparedMcpDispatch(
                        intent = classifyMcpIntent(textToActuallySend),
                        tools = emptyList(),
                    )
                }
                val mcpToolsForRequest = preparedMcpDispatch.tools
                val shouldEnableGoogleSearch = isGeminiChannel && webSearchRouting.useNativeWebSearch
                val mcpHasSearchTool = mcpToolsForRequest.any { classifyMcpTool(it).isSearchLike }
                val shouldInjectWebSearchTool = !shouldEnableGoogleSearch && !mcpHasSearchTool
                        && (webSearchRouting.externalProvider != null || webSearchRouting.useJinaSearch)

                if (!systemPrompt.isNullOrBlank()) {
                    val systemMessage = SimpleTextApiMessage(role = "system", content = systemPrompt.trim())
                    val existingSystemMessageIndex = apiMessagesForBackend.indexOfFirst { it.role == "system" }
                    if (existingSystemMessageIndex != -1) {
                        apiMessagesForBackend[existingSystemMessageIndex] = systemMessage
                    } else {
                        apiMessagesForBackend.add(0, systemMessage)
                    }
                }

                val finalApiMessages = apiMessagesForBackend.toList()

                logApiMessages("finalMessages", finalApiMessages)

                if (finalApiMessages.isEmpty() || finalApiMessages.lastOrNull()?.role != "user") {
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.remove(newUserMessageForUi)
                        val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                        animationMap.remove(newUserMessageForUi.id)
                    }
                    return@withContext
                }

                Log.d(
                    "MessageSender",
                    "config=${safeApiConfigSummary(currentConfig)}, supportsNativeWebSearch: $supportsNativeWebSearch, webSearchEnabled: ${stateHolder._isWebSearchEnabled.value}, shouldEnableGoogleSearch: $shouldEnableGoogleSearch, externalProvider=${webSearchRouting.externalProvider?.providerId}"
                )

                // 🎯 优化：在开始可能的外部联网搜索之前，预先创建 AI 占位消息。
                // 这样用户在点击发送后能立即看到 UI 反馈（加载指示器），而不是等待搜索完成。
                val preCreatedAiMessageId = if (!isImageGeneration) {
                    apiHandler.cancelCurrentApiJob("发送新消息，预清理", isNewMessageSend = true, isImageGeneration = false)
                    apiHandler.prepareStreamingAiMessage(
                        modelName = currentConfig.model,
                        providerName = currentConfig.provider,
                        isImageGeneration = false,
                        afterUserMessageId = newUserMessageForUi.id,
                    )
                } else null

                // 3. 代码执行启用逻辑 - 用户全权控制
                val enableCodeExecutionForRequest: Boolean? =
                    if (!isGeminiChannel) {
                        null // 非 Gemini 渠道，不支持原生 code_execution，且不显示开关
                    } else {
                        // Gemini 渠道下，完全由 UI 开关决定：
                        // ON -> true (注入工具)
                        // OFF -> false (不注入)
                        // 这样就覆盖了底层的自动意图检测逻辑
                        stateHolder._isCodeExecutionEnabled.value
                    }

                val chatRequestForApi = ChatRequest(
                    messages = finalApiMessages,
                    provider = providerForRequestBackend,
                    channel = currentConfig.channel,
                    apiAddress = currentConfig.address,
                    apiKey = currentConfig.key,
                    model = currentConfig.model,
                    deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(application),
                    conversationId = stateHolder._currentConversationId.value,
                    openClawSessionId = stateHolder._currentOpenClawSessionId.value,
                    useWebSearch = webSearchRouting.useNativeWebSearch,
                    // 显式传递代码执行开关状态
                    enableCodeExecution = enableCodeExecutionForRequest,
                    // 新会话未设置时，只回落温度/TopP；maxTokens 一律保持关闭（null）
                    generationConfig = stateHolder.getCurrentConversationConfig() ?: GenerationConfig(
                        temperature = currentConfig.temperature,
                        topP = currentConfig.topP,
                        maxOutputTokens = null,
                        thinkingConfig = if (modelIsGeminiType) {
                            ThinkingConfig(
                                includeThoughts = true,
                                thinkingBudget = defaultReasoningBudgetForModel(currentConfig.model)
                            )
                        } else null
                    ).let { if (it.temperature != null || it.topP != null || it.maxOutputTokens != null || it.thinkingConfig != null) it else null },
                    qwenEnableSearch = if (WebSearchSupport.shouldEnableQwenNativeSearch(currentConfig, webSearchRouting.useNativeWebSearch)) true else null,
                    customModelParameters = if (modelIsGeminiType) {
                        // 为Gemini模型添加reasoning_effort参数
                        // 根据模型类型设置不同的思考级别
                        val reasoningEffort = when {
                            currentConfig.model.contains("flash", ignoreCase = true) -> "low"  // 对应1024个令牌
                            currentConfig.model.contains("pro", ignoreCase = true) -> "medium" // 对应8192个令牌
                            else -> "high" // 对应24576个令牌
                        }
                        mapOf("reasoning_effort" to reasoningEffort)
                    } else null,
                    // 工具注入逻辑
                    tools = PromptCachePolicy.normalizeTools(run {
                        val toolsList = mutableListOf<Map<String, Any>>()
                        
                        // 1. 用户自定义工具
                        val customToolsJson = currentConfig.toolsJson
                        if (!customToolsJson.isNullOrBlank()) {
                            try {
                                val jsonElement = Json.parseToJsonElement(customToolsJson)
                                if (jsonElement is JsonArray) {
                                    jsonElement.forEach { element: JsonElement ->
                                        if (element is JsonObject) {
                                            // 递归转换 JsonObject 为 Map
                                            val map = jsonObjectToMap(element)
                                            toolsList.add(map)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MessageSender", "Failed to parse custom tools JSON", e)
                            }
                        }
                        
                        // 2. 联网搜索 (Gemini Native)
                        if (shouldEnableGoogleSearch) {
                            Log.d("MessageSender", "启用Google搜索工具用于Gemini渠道")
                            toolsList.add(mapOf("googleSearch" to emptyMap<String, Any>()))
                        }
                        
                        // 3. 代码执行 (Gemini Native)
                        // 修复：显式注入 code_execution 工具，确保通过代理/后端请求时生效
                        // (GeminiDirectClient 会忽略此 tools 列表自行构建，因此不会冲突)
                        if (isGeminiChannel && stateHolder._isCodeExecutionEnabled.value) {
                            Log.d("MessageSender", "启用代码执行工具 (code_execution)")
                            toolsList.add(mapOf("code_execution" to emptyMap<String, Any>()))
                        }
                        
                        // 4. MCP 工具 (来自 MCP 服务器)
                        if (mcpToolsForRequest.isNotEmpty()) {
                            Log.d("MessageSender", "注入 ${mcpToolsForRequest.size} 个 MCP 工具")
                            toolsList.addAll(mcpToolsForRequest)
                        }

                        // 5. 联网搜索工具 (外部/Jina，模型自行决定是否调用)
                        if (shouldInjectWebSearchTool) {
                            Log.d("MessageSender", "注入内建 web_search 工具")
                            toolsList.add(builtInWebSearchToolDefinition())
                        }

                        if (shouldUsePromptCapabilities(
                                isImageGeneration = isImageGeneration,
                                provider = providerForRequestBackend,
                                channel = currentConfig.channel,
                                model = currentConfig.model,
                            )
                        ) {
                            toolsList.add(PromptCapabilityCatalog.selectionToolDefinition())
                        }

                        val effectiveTools = appendBuiltInWebFetchToolIfNeeded(
                            tools = toolsList,
                        )
                        if (effectiveTools.size != toolsList.size) {
                            Log.d("MessageSender", "注入内建 webfetch 工具")
                        }
                        val effectiveToolsWithCurrentTime = appendBuiltInCurrentTimeTool(effectiveTools)
                        if (effectiveToolsWithCurrentTime.size != effectiveTools.size) {
                            Log.d("MessageSender", "注入内建当前时间工具")
                        }

                        effectiveToolsWithCurrentTime.ifEmpty { null }
                    }),
                    imageGenRequest = if (isImageGeneration) {
                        // 调试信息：检查发送的配置
                        Log.d("MessageSender", "Image generation config: ${safeApiConfigSummary(currentConfig)}")
                        
                        // 计算上游完整图片生成端点（默认平台交由后端注入，避免相对路径）
                        val upstreamApiForImageGen = if (isDefaultProvider) {
                            ""
                        } else {
                            val upstreamBase = currentConfig.address.trim().trimEnd('/')
                            if (upstreamBase.endsWith("/v1/images/generations")) {
                                upstreamBase
                            } else {
                                "$upstreamBase/v1/images/generations"
                            }
                        }

                        // 构建"无状态历史摘要"，保证每个会话自带记忆（即使后端会话未命中）
                        // 仅提取纯文本轮次（user/model），避免把图片当作历史内容。
                        val historyForStatelessMemory: List<Map<String, String>> = run {
                            val maxTurns = 6 // 最近6轮（user/model合计），可按需调整
                            val turns = mutableListOf<Map<String, String>>()
                            historyUiMessages
                                .asReversed() // 从末尾向前
                                .asSequence()
                                .filter { it.text.isNotBlank() }
                                .map { msg ->
                                    val role = if (msg.sender == UiSender.User) "user" else "model"
                                    role to msg.text.trim()
                                }
                                .filter { (_, text) -> text.isNotBlank() }
                                .take(maxTurns)
                                .toList()
                                .asReversed() // 恢复正序
                                .forEach { (role, text) ->
                                    turns.add(mapOf("role" to role, "text" to text))
                                }
                            turns
                        }

                        // 依据文档：通过 config.response_modalities 与 image_config.aspect_ratio 控制输出
                        ImageGenRequest(
                            model = currentConfig.model,
                            prompt = textToActuallySend,
                            imageSize = imageSizeForRequest, // Kolors/Qwen 生效；Qwen-Image-Edit 禁用
                            batchSize = 1,
                            numInferenceSteps = currentConfig.numInferenceSteps,
                            guidanceScale = currentConfig.guidanceScale,
                            // 默认平台：apiAddress/apiKey 留空，由后端从 .env 注入
                            apiAddress = if (isDefaultProvider) "" else upstreamApiForImageGen,
                            apiKey = if (isDefaultProvider) "" else currentConfig.key,
                            // 渠道控制路由：默认平台传"默认"，非默认按"渠道"字段（OpenAI兼容/Gemini）
                            provider = if (isDefaultProvider) currentConfig.provider else currentConfig.channel,
                            responseModalities = listOf("Image"),
                            aspectRatio = stateHolder._selectedImageRatio.value.let { r ->
                                if (r.isAuto) null else r.displayName
                            },
                            // 严格会话隔离：把当前图像历史项ID透传到后端
                            conversationId = stateHolder._currentImageGenerationConversationId.value,
                            // 额外兜底：把最近若干轮文本摘要也发给后端，确保"该会话独立记忆"不依赖服务端状态
                            history = historyForStatelessMemory.ifEmpty { null },
                            // 禁用水印（针对 Seedream 直连）
                            watermark = false,
                            // 将配置中的 imageSize (1K/2K/4K) 传递给 Gemini 专用字段
                            geminiImageSize = if (modelIsGeminiType) currentConfig.imageSize else null,
                            quality = if (detectedFamilyForImage == com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.GPT_IMAGE) {
                                stateHolder._gptImageQuality.value.apiValue
                            } else null
                        )
                    } else null
                )

                Log.d(
                    "MessageSender",
                    "Prompt tool profile=${PromptCachePolicy.toolProfile(chatRequestForApi.tools)} " +
                        "schema=${PromptCachePolicy.toolSchemaHash(chatRequestForApi.tools).take(16)}",
                )
                PromptCachePolicy.logToolProfile(chatRequestForApi.tools)

                apiHandler.streamChatResponse(
                    requestBody = chatRequestForApi,
                    attachmentsToPassToApiClient = attachmentsForApiClient,
                    applicationContextForApiClient = application,
                    userMessageTextForContext = textToActuallySend,
                    afterUserMessageId = newUserMessageForUi.id,
                    onMessagesProcessed = {
                        // 避免图像模式在AI占位阶段过早入库，仅文本模式此处保存
                        if (!isImageGeneration) {
                            viewModelScope.launch {
                                historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false)
                            }
                        }
                    },
                    onRequestFailed = { error ->
                        viewModelScope.launch(Dispatchers.Main) {
                            val errorMessage = "发送失败: ${error.message ?: "未知错误"}"
                            showSnackbar(errorMessage)
                        }
                    },
                    onNewAiMessageAdded = triggerScrollToBottom,
                    audioBase64 = audioBase64,
                    mimeType = mimeType,
                    isImageGeneration = isImageGeneration,
                    preCreatedAiMessageId = preCreatedAiMessageId
                )
            }
        }
    }

