package com.android.everytalk.statecontroller

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.content.FileProvider
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.image.ImagePersistenceResult
import com.android.everytalk.util.image.ImagePersistenceFailure
import com.android.everytalk.util.image.toUserImageMessage
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.util.storage.ImagePersistenceService
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
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerErrorCodes
import com.android.everytalk.data.computer.ComputerToolCatalog
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.PreparedComputerRequest
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.data.network.MAX_ATTACHMENT_PAGE_CHARS
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
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

internal const val BUILT_IN_WEBFETCH_TOOL_NAME = "webfetch"
internal const val BUILT_IN_CURRENT_TIME_TOOL_NAME = "get_current_datetime"
internal const val BUILT_IN_WEB_SEARCH_TOOL_NAME = "web_search"
internal const val BUILT_IN_READ_ATTACHMENT_TOOL_NAME = "read_attachment"

internal val MCP_SEARCH_TOOL_NAME_KEYWORDS = listOf(
    "search", "web", "exa", "news", "query", "browser", "crawl", "scrape", "fetch"
)

internal val MCP_SEARCH_TOOL_DESCRIPTION_KEYWORDS = listOf(
    "搜索", "检索", "网页", "页面", "新闻", "热点", "最新", "查询", "抓取", "爬取", "浏览",
    "search", "web", "page", "news", "latest", "query", "crawl", "scrape", "browser", "fetch"
)

internal data class McpToolClassification(
    val isSearchLike: Boolean,
)

internal fun classifyMcpTool(toolDefinition: Map<String, Any>): McpToolClassification {
    val functionDefinition = toolDefinition["function"] as? Map<*, *>
    val toolName = (functionDefinition?.get("name") as? String)
        ?: (toolDefinition["name"] as? String)
        ?: ""
    val toolDescription = (functionDefinition?.get("description") as? String)
        ?: (toolDefinition["description"] as? String)
        ?: ""
    return classifyMcpTool(toolName, toolDescription)
}

internal fun classifyMcpTool(
    toolName: String,
    toolDescription: String,
): McpToolClassification {
    val normalizedName = toolName.lowercase()
    val normalizedDescription = toolDescription.lowercase()
    val isSearchLike =
        MCP_SEARCH_TOOL_NAME_KEYWORDS.any { keyword -> keyword in normalizedName } ||
            MCP_SEARCH_TOOL_DESCRIPTION_KEYWORDS.any { keyword -> keyword in normalizedDescription }
    return McpToolClassification(
        isSearchLike = isSearchLike,
    )
}

internal fun builtInWebFetchToolDefinition(): Map<String, Any> {
    return mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to BUILT_IN_WEBFETCH_TOOL_NAME,
            "description" to "Fetch a web page and return its text content. Use when the user provides a URL or when you need content from a specific webpage.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf(
                        "type" to "string",
                        "description" to "HTTP or HTTPS URL to fetch."
                    ),
                    "max_chars" to mapOf(
                        "type" to "integer",
                        "description" to "Maximum characters to return."
                    )
                ),
                "required" to listOf("url")
            )
        )
    )
}

internal fun builtInCurrentTimeToolDefinition(): Map<String, Any> {
    return mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to BUILT_IN_CURRENT_TIME_TOOL_NAME,
                "description" to "Get the current local date, time, hour, minute, second, timezone, and Unix timestamp from the device. You MUST call this tool whenever the user asks anything related to the current time, date, day of week, or any time-sensitive question. Do not guess the time.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        )
    )
}

internal fun builtInWebSearchToolDefinition(): Map<String, Any> {
    return mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to BUILT_IN_WEB_SEARCH_TOOL_NAME,
            "description" to "Search the web and return results. Use when the question needs up-to-date information you don't have.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "The search query to look up."
                    )
                ),
                "required" to listOf("query")
            )
        )
    )
}

internal fun appendBuiltInWebFetchToolIfNeeded(
    tools: List<Map<String, Any>>,
): List<Map<String, Any>> {
    val hasWebFetchTool = tools.any { toolDefinition ->
        extractToolName(toolDefinition)?.equals(BUILT_IN_WEBFETCH_TOOL_NAME, ignoreCase = true) == true
    }
    if (hasWebFetchTool) {
        return tools
    }

    return tools + builtInWebFetchToolDefinition()
}

internal fun appendBuiltInCurrentTimeTool(
    tools: List<Map<String, Any>>,
): List<Map<String, Any>> {
    val hasCurrentTimeTool = tools.any { toolDefinition ->
        extractToolName(toolDefinition)?.equals(BUILT_IN_CURRENT_TIME_TOOL_NAME, ignoreCase = true) == true
    }
    if (hasCurrentTimeTool) {
        return tools
    }
    return tools + builtInCurrentTimeToolDefinition()
}

internal fun extractToolName(toolDefinition: Map<String, Any>): String? {
    val functionDefinition = toolDefinition["function"] as? Map<*, *>
    val functionName = functionDefinition?.get("name") as? String
    if (!functionName.isNullOrBlank()) {
        return functionName
    }
    return toolDefinition["name"] as? String
}

internal data class AttachmentProcessingResult(
    val success: Boolean,
    val processedAttachmentsForUi: List<SelectedMediaItem> = emptyList(),
    val imageUriStringsForUi: List<String> = emptyList(),
    val apiContentParts: List<ApiContentPart> = emptyList()
)

data class PreparedMcpDispatch(
    val intent: QueryIntent,
    val tools: List<Map<String, Any>>,
)

internal fun prepareMcpDispatch(
    messageText: String,
    allCandidates: List<McpToolCandidate>,
): PreparedMcpDispatch {
    val intent = classifyMcpIntent(messageText)
    val tools = selectMcpCandidates(intent, allCandidates).map { it.toToolDefinition() }
    return PreparedMcpDispatch(
        intent = intent,
        tools = tools,
    )
}

internal fun builtInReadAttachmentToolDefinition(): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
        "name" to BUILT_IN_READ_ATTACHMENT_TOOL_NAME,
        "description" to "读取当前会话中的文本附件。每次只读取一页，禁止并行读取多页；内容被截断时，使用返回的 next_offset 继续读取。",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "attachment_id" to mapOf(
                    "type" to "string",
                    "description" to "附件清单中的 attachment_id。",
                ),
                "offset" to mapOf(
                    "type" to "integer",
                    "description" to "从返回的 next_offset 继续读取，首次读取传 0。",
                    "minimum" to 0,
                ),
                "max_chars" to mapOf(
                    "type" to "integer",
                    "description" to "本页最多返回字符数。",
                    "minimum" to 1,
                    "maximum" to MAX_ATTACHMENT_PAGE_CHARS,
                ),
            ),
            "required" to listOf("attachment_id"),
        ),
    ),
)

internal fun appendBuiltInReadAttachmentTool(
    tools: List<Map<String, Any>>,
    enabled: Boolean,
): List<Map<String, Any>> {
    if (!enabled || tools.any {
            extractToolName(it)?.equals(BUILT_IN_READ_ATTACHMENT_TOOL_NAME, ignoreCase = true) == true
        }
    ) return tools
    return tools + builtInReadAttachmentToolDefinition()
}

/** Agent Tool 名称必须独占，避免自定义工具或 MCP 工具劫持本地服务器调用。 */
internal fun appendComputerTools(
    tools: List<Map<String, Any>>,
    enabled: Boolean,
    permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
): List<Map<String, Any>> {
    if (!enabled) return tools
    val conflicts = tools.mapNotNull(::extractToolName).filter { existingName ->
        ComputerToolNames.all.any { it.equals(existingName, ignoreCase = true) }
    }
    if (conflicts.isNotEmpty()) {
        throw ComputerException(
            ComputerErrorCodes.TOOL_NAME_CONFLICT,
            "Agent 工具名与现有工具冲突：${conflicts.distinct().joinToString()}",
        )
    }
    return tools + ComputerToolCatalog.definitions(permissionMode)
}

/**
 * 捕获并行的服务器准备错误，避免 async 子任务先把整条消息发送协程取消。
 * 用户主动取消仍需原样传播，确保停止按钮和页面退出能够立即终止发送。
 */
internal suspend fun captureComputerPreparation(
    block: suspend () -> PreparedComputerRequest?,
): Result<PreparedComputerRequest?> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

internal fun addOrReplaceRegeneratedUserMessage(
    messageList: MutableList<UiMessage>,
    newUserMessage: UiMessage,
    isFromRegeneration: Boolean,
    manualMessageId: String?,
): Int {
    return Snapshot.withMutableSnapshot {
        if (isFromRegeneration && !manualMessageId.isNullOrBlank()) {
            val existingIndex = messageList.indexOfFirst { it.id == manualMessageId }
            if (existingIndex >= 0) {
                if (existingIndex == messageList.lastIndex) {
                    messageList[existingIndex] = newUserMessage
                    return@withMutableSnapshot existingIndex
                }
                messageList.removeAt(existingIndex)
                messageList.add(newUserMessage)
                return@withMutableSnapshot messageList.lastIndex
            }
        }
        messageList.add(newUserMessage)
        messageList.lastIndex
    }
}

internal fun safeApiConfigSummary(config: ApiConfig?): String {
    if (config == null) return "null"
    val addressScheme = config.address.substringBefore("://", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.plus("://***")
        ?: "***"
    return "ApiConfig(id=${config.id}, nameChars=${config.name.length}, providerChars=${config.provider.length}, " +
        "modelChars=${config.model.length}, channelChars=${config.channel.length}, address=$addressScheme, key=***)"
}

 class MessageSender(
     internal val application: Application,
    internal val viewModelScope: CoroutineScope,
    internal val stateHolder: ViewModelStateHolder,
    internal val apiHandler: ApiHandler,
    internal val historyManager: HistoryManager,
    internal val showSnackbar: (String) -> Unit,
    internal val triggerScrollToBottom: () -> Unit,
    internal val uriToBase64Encoder: (Uri) -> String?,
    internal val getMcpDispatchCandidates: () -> List<McpToolCandidate> = { emptyList() },
    internal val getSelectedExternalWebSearchProvider: () -> ExternalWebSearchProvider? = { null },
    internal val getSelectedExternalWebSearchProviderApiKey: () -> String = { "" },
    internal val prepareComputerRequest: suspend (String, Boolean) -> PreparedComputerRequest? = { _, _ -> null },
) {

    internal val fileManager: FileManager by lazy { FileManager(application) }
    internal val imagePersistenceService: ImagePersistenceService by lazy { ImagePersistenceService(application) }
    internal val autoContextCompressionMutex = Mutex()
    internal val autoContextCompressionStates =
        mutableMapOf<AutoContextCompressionKey, com.android.everytalk.data.DataClass.ContextCompressionState>()

    internal fun logUiMessages(stage: String, messages: List<UiMessage>) {
        Log.d("MessageSender", "$stage.size=${messages.size}")
        messages.forEachIndexed { index, message ->
            Log.d(
                "MessageSender",
                "$stage[$index]: role=${message.role} textChars=${message.text.length} attachments=${message.attachments.size} sender=${message.sender}"
            )
        }
    }

    internal fun describeApiMessage(message: AbstractApiMessage): String {
        return when (message) {
            is SimpleTextApiMessage -> "textChars=${message.content.length}"
            is PartsApiMessage -> message.parts.joinToString(separator = " | ") { part ->
                when (part) {
                    is ApiContentPart.Text -> "textChars=${part.text.length}"
                    is ApiContentPart.InlineData -> "inlineData(${part.mimeType})"
                    is ApiContentPart.FileUri -> "fileUri(${part.mimeType})"
                }
            }
            else -> "agentMessage(role=${message.role})"
        }
    }

    internal fun logApiMessages(stage: String, messages: List<AbstractApiMessage>) {
        Log.d("MessageSender", "$stage.size=${messages.size}")
        messages.forEachIndexed { index, message ->
            Log.d(
                "MessageSender",
                "$stage[$index]: role=${message.role} summary=${describeApiMessage(message)}"
            )
        }
    }

    internal fun extractPrimaryText(message: AbstractApiMessage): String {
        return when (message) {
            is SimpleTextApiMessage -> message.content
            is PartsApiMessage -> message.parts.filterIsInstance<ApiContentPart.Text>().joinToString("\n") { it.text }
            else -> ""
        }
    }

    private fun ensureUserMessagePresent(
        messages: MutableList<AbstractApiMessage>,
        currentUserMessage: AbstractApiMessage,
        currentUserHasAttachments: Boolean,
    ): MutableList<AbstractApiMessage> {
        val hasUserContent = currentUserHasAttachments || when (currentUserMessage) {
            is SimpleTextApiMessage -> currentUserMessage.content.trim().isNotBlank()
            is PartsApiMessage -> currentUserMessage.parts.any { part ->
                when (part) {
                    is ApiContentPart.Text -> part.text.trim().isNotBlank()
                    is ApiContentPart.InlineData, is ApiContentPart.FileUri -> true
                }
            }
            else -> false
        }
        if (!hasUserContent) {
            return messages
        }
        val existingCurrentUserMessage = messages.any { message ->
            message.role == "user" && message.id == currentUserMessage.id
        }
        if (!existingCurrentUserMessage) {
            Log.d(
                "MessageSender",
                "current user input absent from request snapshot, injected fallback user message summary=${describeApiMessage(currentUserMessage)}"
            )
            messages.add(currentUserMessage)
        }
        return messages
    }

    internal fun ensureUserMessagePresentForRequest(
        messages: MutableList<AbstractApiMessage>,
        currentUserMessage: AbstractApiMessage,
        currentUserHasAttachments: Boolean = false,
    ): MutableList<AbstractApiMessage> = ensureUserMessagePresent(
        messages = messages,
        currentUserMessage = currentUserMessage,
        currentUserHasAttachments = currentUserHasAttachments,
    )

    internal fun deleteTemporaryCameraUri(uri: Uri) {
        if (uri.authority != "${application.packageName}.provider") return
        if (uri.pathSegments.firstOrNull() != "chat_images_temp") return
        runCatching { application.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w("MessageSender", "删除相机临时文件失败: $uri", it) }
    }

    internal suspend fun processAttachments(
        attachments: List<SelectedMediaItem>,
        shouldUsePartsApiMessage: Boolean,
        textToActuallySend: String,
    ): AttachmentProcessingResult = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            return@withContext AttachmentProcessingResult(
                success = true,
                apiContentParts = if (shouldUsePartsApiMessage && textToActuallySend.isNotBlank()) listOf(ApiContentPart.Text(text = textToActuallySend)) else emptyList()
            )
        }

        val processedAttachmentsForUi = mutableListOf<SelectedMediaItem>()
        val imageUriStringsForUi = mutableListOf<String>()
        val apiContentParts = mutableListOf<ApiContentPart>()

        if (shouldUsePartsApiMessage) {
            if (textToActuallySend.isNotBlank() || attachments.isNotEmpty()) {
                apiContentParts.add(ApiContentPart.Text(text = textToActuallySend))
            }
        }

        val tempMessageIdForNaming = UUID.randomUUID().toString().take(8)

        for ((index, originalMediaItem) in attachments.withIndex()) {
            var persistedImageMimeType: String? = null
            val itemUri = when (originalMediaItem) {
                is SelectedMediaItem.ImageFromUri -> originalMediaItem.uri
                is SelectedMediaItem.GenericFile -> originalMediaItem.uri
                is SelectedMediaItem.ImageFromBitmap -> Uri.EMPTY
                is SelectedMediaItem.Audio -> Uri.EMPTY
            }
            val originalFileNameForHint = (originalMediaItem as? SelectedMediaItem.GenericFile)?.displayName
                ?: getFileName(application.contentResolver, itemUri)
                ?: (if (originalMediaItem is SelectedMediaItem.ImageFromBitmap) "camera_shot" else "attachment")

            val persistentFilePath: String? = try {
                when (originalMediaItem) {
                    is SelectedMediaItem.ImageFromUri -> {
                        val existingPath = originalMediaItem.filePath
                            ?.let(::File)
                            ?.takeIf { it.isFile && it.length() > 0L }
                            ?.absolutePath
                        val existingValidation = existingPath?.let {
                            imagePersistenceService.validatePersistedUserImage(it)
                        }
                        val persistenceResult = when (existingValidation) {
                            is ImagePersistenceResult.Success -> existingValidation
                            is ImagePersistenceResult.Failure -> {
                                if (existingValidation.reason != ImagePersistenceFailure.UnsupportedSource) {
                                    existingValidation
                                } else {
                                    imagePersistenceService.persistUserImage(
                                        sourceUri = originalMediaItem.uri,
                                        fileName = originalFileNameForHint,
                                        messageIdHint = tempMessageIdForNaming,
                                        attachmentIndex = index,
                                    )
                                }
                            }
                            null -> imagePersistenceService.persistUserImage(
                                sourceUri = originalMediaItem.uri,
                                fileName = originalFileNameForHint,
                                messageIdHint = tempMessageIdForNaming,
                                attachmentIndex = index,
                            )
                        }
                        when (val result = persistenceResult) {
                            is ImagePersistenceResult.Success -> {
                                persistedImageMimeType = result.mimeType
                                result.filePath
                            }
                            is ImagePersistenceResult.Failure -> {
                                withContext(Dispatchers.Main.immediate) {
                                    showSnackbar(result.reason.toUserImageMessage(application, originalFileNameForHint))
                                }
                                return@withContext AttachmentProcessingResult(success = false)
                            }
                        }
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        originalMediaItem.filePath
                            ?.let(::File)
                            ?.takeIf { it.isFile && it.length() > 0L }
                            ?.let { existingFile ->
                                val existingValidation = imagePersistenceService.validatePersistedUserImage(
                                    existingFile.absolutePath,
                                )
                                val persistenceResult = if (
                                    existingValidation is ImagePersistenceResult.Failure &&
                                    existingValidation.reason == ImagePersistenceFailure.UnsupportedSource
                                ) {
                                    imagePersistenceService.persistUserImage(
                                        sourceUri = Uri.fromFile(existingFile),
                                        fileName = originalFileNameForHint,
                                        messageIdHint = tempMessageIdForNaming,
                                        attachmentIndex = index,
                                    )
                                } else {
                                    existingValidation
                                }
                                when (val result = persistenceResult) {
                                    is ImagePersistenceResult.Success -> {
                                        persistedImageMimeType = result.mimeType
                                        result.filePath
                                    }
                                    is ImagePersistenceResult.Failure -> {
                                        withContext(Dispatchers.Main.immediate) {
                                            showSnackbar(result.reason.toUserImageMessage(application, originalFileNameForHint))
                                        }
                                        return@withContext AttachmentProcessingResult(success = false)
                                    }
                                }
                            }
                            ?: when (val result = imagePersistenceService.persistEncodedUserImage(
                                base64Data = originalMediaItem.bitmapData,
                                declaredMimeType = originalMediaItem.mimeType,
                                messageIdHint = tempMessageIdForNaming,
                                attachmentIndex = index,
                            )) {
                                is ImagePersistenceResult.Success -> {
                                    persistedImageMimeType = result.mimeType
                                    result.filePath
                                }
                                is ImagePersistenceResult.Failure -> {
                                    withContext(Dispatchers.Main.immediate) {
                                        showSnackbar(result.reason.toUserImageMessage(application, originalFileNameForHint))
                                    }
                                    return@withContext AttachmentProcessingResult(success = false)
                                }
                            }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        fileManager.copyUriToAppInternalStorage(
                            sourceUri = originalMediaItem.uri,
                            messageIdHint = tempMessageIdForNaming,
                            attachmentIndex = index,
                            originalFileName = originalMediaItem.displayName,
                        )
                    }
                    is SelectedMediaItem.Audio -> {
                        // 音频数据已为Base64，无需额外处理
                        null
                    }
                }
            } finally {
                (originalMediaItem as? SelectedMediaItem.ImageFromUri)
                    ?.uri
                    ?.let(::deleteTemporaryCameraUri)
            }

            if (persistentFilePath == null && originalMediaItem !is SelectedMediaItem.Audio) {
                showSnackbar("无法处理附件: $originalFileNameForHint")
                return@withContext AttachmentProcessingResult(success = false)
            }

            val persistentFile = persistentFilePath?.let { File(it) }
            val authority = "${application.packageName}.provider"
            val persistentFileProviderUri = persistentFile?.let { FileProvider.getUriForFile(application, authority, it) }

            val processedItemForUi: SelectedMediaItem = when (originalMediaItem) {
                is SelectedMediaItem.ImageFromUri -> {
                    // 使用本地文件路径而非 FileProvider URI，确保应用重启后图片仍可访问
                    imageUriStringsForUi.add(persistentFilePath!!)
                    SelectedMediaItem.ImageFromUri(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        mimeType = persistedImageMimeType ?: originalMediaItem.mimeType,
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.ImageFromBitmap -> {
                    // 使用本地文件路径而非 FileProvider URI，确保应用重启后图片仍可访问
                    imageUriStringsForUi.add(persistentFilePath!!)
                    SelectedMediaItem.ImageFromUri(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        mimeType = persistedImageMimeType ?: originalMediaItem.mimeType,
                        filePath = persistentFilePath,
                    )
                }
                is SelectedMediaItem.GenericFile -> {
                    // The ApiClient now handles streaming, so we don't need to read the bytes here.
                    // We still add the item to the UI list.
                    SelectedMediaItem.GenericFile(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        displayName = originalFileNameForHint,
                        mimeType = originalMediaItem.mimeType,
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.Audio -> {
                    apiContentParts.add(ApiContentPart.InlineData(mimeType = originalMediaItem.mimeType, base64Data = originalMediaItem.data))
                    originalMediaItem
                }
            }
            processedAttachmentsForUi.add(processedItemForUi)

            // 为处理后的图片（现在拥有一个持久化的 URI）创建 API 内容部分
            if (shouldUsePartsApiMessage && processedItemForUi is SelectedMediaItem.ImageFromUri) {
                val base64Data = uriToBase64Encoder(processedItemForUi.uri)
                if (base64Data == null) {
                    withContext(Dispatchers.Main.immediate) {
                        showSnackbar("无法读取图片“$originalFileNameForHint”，请重新选择。")
                    }
                    return@withContext AttachmentProcessingResult(success = false)
                }
                apiContentParts.add(
                    ApiContentPart.InlineData(
                        mimeType = processedItemForUi.mimeType,
                        base64Data = base64Data,
                    ),
                )
            }
        }
        AttachmentProcessingResult(true, processedAttachmentsForUi, imageUriStringsForUi, apiContentParts)
    }


    fun sendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList(),
        audioBase64: String? = null,
        mimeType: String? = null,
        systemPrompt: String? = null,
        isImageGeneration: Boolean = false,
        manualMessageId: String? = null
    ) {
        sendMessageInternal(messageText, isFromRegeneration, attachments, audioBase64, mimeType, systemPrompt, isImageGeneration, manualMessageId)
    }
    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri == Uri.EMPTY) return null
        var fileName: String? = null
        try {
            if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex =
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            fileName = cursor.getString(displayNameIndex)
                        }
                    }
            }
            if (fileName == null) {
                fileName = uri.lastPathSegment
            }
        } catch (e: Exception) {
            fileName = uri.lastPathSegment
        }
        return fileName ?: "file_${System.currentTimeMillis()}"
    }
    
    internal fun hasImageGenerationKeywords(text: String?): Boolean {
        // 委托给 ApiHandler 中的实现，避免重复代码
        return apiHandler.hasImageGenerationKeywords(text)
    }

    // 识别"编辑/基于上一张修改"的语义，用于自动附带上一轮AI图片
    internal fun hasImageEditKeywords(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase().trim()
        val editKeywords = listOf(
            "改成", "换成", "替换", "修改", "调整", "改为", "基于上一张", "在上一张基础上",
            "把", "改一下", "修一下", "换一下", "同一张", "同这张", "继续修改",
            // 英文常见编辑意图
            "replace", "change to", "edit", "modify", "adjust", "based on previous", "on the previous image"
        )
        return editKeywords.any { k -> t.contains(k) }
    }

    // 辅助函数：递归将 JsonObject 转换为 Map<String, Any>
    internal fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        jsonObject.entries.forEach { (key, value) ->
            jsonElementToAny(value)?.let { map[key] = it }
        }
        return map
    }

    // 辅助函数：递归将 JsonArray 转换为 List<Any>
    private fun jsonArrayToList(jsonArray: JsonArray): List<Any> {
        val list = mutableListOf<Any>()
        jsonArray.forEach { element ->
            jsonElementToAny(element)?.let { list.add(it) }
        }
        return list
    }

    // 辅助函数：将 JsonElement 转换为 Any
    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> jsonArrayToList(element)
            is JsonPrimitive -> {
                if (element.isString) {
                    element.content
                } else {
                    element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.contentOrNull
                }
            }
        }
    }
}
