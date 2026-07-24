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
import java.util.Locale
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
) {

    internal val fileManager: FileManager by lazy { FileManager(application) }

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
        }
    }

    private fun ensureUserMessagePresent(
        messages: MutableList<AbstractApiMessage>,
        currentUserMessage: AbstractApiMessage
    ): MutableList<AbstractApiMessage> {
        val hasUserContent = when (currentUserMessage) {
            is SimpleTextApiMessage -> currentUserMessage.content.trim().isNotBlank()
            is PartsApiMessage -> currentUserMessage.parts.any { part ->
                when (part) {
                    is ApiContentPart.Text -> part.text.trim().isNotBlank()
                    is ApiContentPart.InlineData, is ApiContentPart.FileUri -> true
                }
            }
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
    ): MutableList<AbstractApiMessage> = ensureUserMessagePresent(messages, currentUserMessage)

    /**
     * 从Uri加载并压缩位图 - 新版本支持等比缩放
     * @param context 上下文
     * @param uri 图片Uri
     * @param isImageGeneration 是否为图像生成模式
     * @return 压缩后的位图，如果加载失败则返回null
     */
    internal suspend fun loadAndCompressBitmapFromUri(
        context: Context, 
        uri: Uri,
        isImageGeneration: Boolean = false
    ): Bitmap? {
        return fileManager.loadAndCompressBitmapFromUri(uri = uri, isImageGeneration = isImageGeneration)
    }

    internal suspend fun copyUriToAppInternalStorage(
        context: Context,
        sourceUri: Uri,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileName: String?
    ): String? {
        return fileManager.copyUriToAppInternalStorage(
            sourceUri = sourceUri,
            messageIdHint = messageIdHint,
            attachmentIndex = attachmentIndex,
            originalFileName = originalFileName
        )
    }

    internal suspend fun saveBitmapToAppInternalStorage(
        context: Context,
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileNameHint: String? = null,
        isImageGeneration: Boolean = false
    ): String? {
        return fileManager.saveBitmapToAppInternalStorage(
            bitmapToSave = bitmapToSave,
            messageIdHint = messageIdHint,
            attachmentIndex = attachmentIndex,
            originalFileNameHint = originalFileNameHint,
            isImageGeneration = isImageGeneration
        )
    }

    private fun persistBitmapData(
        item: SelectedMediaItem.ImageFromBitmap,
        messageIdHint: String,
        attachmentIndex: Int,
    ): String? {
        item.filePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }?.let { return it.absolutePath }
        if (item.bitmapData.isBlank()) return null
        return try {
            val encodedLength = item.bitmapData.count { !it.isWhitespace() }.toLong()
            val estimatedBytes = ((encodedLength + 3L) / 4L) * 3L
            if (estimatedBytes > FileManager.MAX_MESSAGE_IMAGE_BYTES) return null
            val decodedBytes = Base64.decode(item.bitmapData, Base64.DEFAULT)
            if (decodedBytes.isEmpty() || decodedBytes.size.toLong() > FileManager.MAX_MESSAGE_IMAGE_BYTES) return null
            val extension = when (item.mimeType.substringBefore(';').lowercase(Locale.ROOT)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val directory = File(application.filesDir, "chat_attachments").apply { mkdirs() }
            val targetFile = File(
                directory,
                "camera_${messageIdHint}_${attachmentIndex.coerceAtLeast(0)}_${System.currentTimeMillis()}.$extension",
            )
            val temporaryFile = File(directory, ".${targetFile.name}.tmp")
            try {
                FileOutputStream(temporaryFile).use { output ->
                    output.write(decodedBytes)
                    output.fd.sync()
                }
                if (!temporaryFile.renameTo(targetFile)) return null
                targetFile.takeIf { it.isFile && it.length() == decodedBytes.size.toLong() }?.absolutePath
            } finally {
                temporaryFile.delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("MessageSender", "保存相机图片失败", error)
            null
        }
    }

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
        isImageGeneration: Boolean = false
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
                        val bitmap = loadAndCompressBitmapFromUri(application, originalMediaItem.uri, isImageGeneration)
                        if (bitmap != null) {
                            saveBitmapToAppInternalStorage(application, bitmap, tempMessageIdForNaming, index, originalFileNameForHint, isImageGeneration)
                        } else {
                            showSnackbar("无法加载或压缩图片: $originalFileNameForHint")
                            return@withContext AttachmentProcessingResult(success = false)
                        }
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        persistBitmapData(originalMediaItem, tempMessageIdForNaming, index)
                    }
                    is SelectedMediaItem.GenericFile -> {
                        copyUriToAppInternalStorage(application, originalMediaItem.uri, tempMessageIdForNaming, index, originalMediaItem.displayName)
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
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.ImageFromBitmap -> {
                    // 使用本地文件路径而非 FileProvider URI，确保应用重启后图片仍可访问
                    imageUriStringsForUi.add(persistentFilePath!!)
                    SelectedMediaItem.ImageFromUri(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        mimeType = originalMediaItem.mimeType,
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
                if (originalMediaItem is SelectedMediaItem.ImageFromBitmap) {
                    val bitmapData = originalMediaItem.bitmapData.takeIf { it.isNotBlank() }
                        ?: uriToBase64Encoder(processedItemForUi.uri)
                    if (!bitmapData.isNullOrBlank()) {
                        apiContentParts.add(
                            ApiContentPart.InlineData(
                                mimeType = originalMediaItem.mimeType,
                                base64Data = bitmapData,
                            )
                        )
                    }
                } else {
                    val imageUri = processedItemForUi.uri
                    val base64Data = uriToBase64Encoder(imageUri)
                    val mimeType = application.contentResolver.getType(imageUri) ?: processedItemForUi.mimeType
                    if (base64Data != null) {
                        apiContentParts.add(ApiContentPart.InlineData(mimeType = mimeType, base64Data = base64Data))
                    }
                }
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
