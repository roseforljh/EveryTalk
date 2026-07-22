package com.android.everytalk.statecontroller

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.data.DataClass.Message as UiMessage
import com.android.everytalk.data.DataClass.Sender as UiSender
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.network.WebSearchSupport
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.statecontroller.defaultReasoningBudgetForModel
import com.android.everytalk.statecontroller.mcp.dispatch.McpDispatchIntent
import com.android.everytalk.statecontroller.mcp.dispatch.McpDispatchStrategy
import com.android.everytalk.statecontroller.mcp.dispatch.McpToolCandidate
import com.android.everytalk.statecontroller.mcp.dispatch.QueryIntent
import com.android.everytalk.statecontroller.mcp.dispatch.classifyMcpIntent
import com.android.everytalk.statecontroller.mcp.dispatch.selectMcpCandidates
import com.android.everytalk.statecontroller.mcp.dispatch.toToolDefinition
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

private val MCP_REALTIME_OR_NEWS_KEYWORDS = listOf(
    "今天", "今日", "刚刚", "刚才", "最新", "最近", "近期", "实时", "当前", "现在",
    "新闻", "热点", "热搜", "头条", "突发", "时事", "发生了什么", "进展", "动态",
    "股价", "市值", "行情", "比赛结果", "比分", "战绩", "天气", "汇率", "排名",
    "today", "latest", "recent", "breaking", "current", "real-time", "realtime",
    "news", "headline", "headlines", "hot topic", "update", "updates", "what happened",
    "stock", "stocks", "market", "price", "weather", "score", "match result", "who won", "ranking"
)

private val MCP_SEARCH_TOOL_NAME_KEYWORDS = listOf(
    "search", "web", "exa", "news", "query", "browser", "crawl", "scrape", "fetch"
)

private val MCP_SEARCH_TOOL_DESCRIPTION_KEYWORDS = listOf(
    "搜索", "检索", "网页", "页面", "新闻", "热点", "最新", "查询", "抓取", "爬取", "浏览",
    "search", "web", "page", "news", "latest", "query", "crawl", "scrape", "browser", "fetch"
)

private data class McpToolClassification(
    val isSearchLike: Boolean,
)

private const val MCP_BASE_USAGE_GUIDANCE = """
你已接入 MCP 工具，必须积极主动地使用它们来提升回答质量。

调用原则：
- 当问题涉及事实、数据、外部信息、最新动态时，必须先调用工具获取信息再回答，不要凭记忆猜测。
- 当用户的问题可能从工具获取更准确、更完整的答案时，主动调用工具。
- 宁可多调用一次工具确认，也不要在不确定时直接回答。
- 如果有多个工具可用，优先选择最相关、最直接的工具。
- 调用工具后，必须基于工具返回的结果进行回答，不要忽略工具返回内容。

只有当问题纯粹是闲聊、创意写作、或完全不涉及外部信息时，才可以不调用工具。
"""

private const val MCP_REALTIME_NEWS_GUIDANCE = """
当前问题明显依赖最新外部信息，你必须调用最合适的 MCP 工具获取实时数据后再回答，禁止凭记忆猜测。
"""

private const val MCP_WITH_WEB_SEARCH_GUIDANCE = """
当 MCP 与联网搜索同时启用时：
- 联网搜索适合获取通用网页信息和新闻。
- MCP 工具适合访问专用服务、数据库、文档库或执行特定操作。
- 两者互补，不要因为有联网搜索就放弃使用 MCP 工具。
- 如果 MCP 工具能提供更精准或更专业的结果，优先使用 MCP。
"""

internal fun looksLikeRealtimeOrNewsQuery(messageText: String): Boolean {
    val normalizedText = messageText.lowercase()
    return MCP_REALTIME_OR_NEWS_KEYWORDS.any { it in normalizedText }
}

internal fun buildMcpUsageGuidance(
    messageText: String,
    isMcpEnabled: Boolean,
    hasMcpTools: Boolean,
    hasEffectiveWebSearch: Boolean = false,
    dispatchIntent: McpDispatchIntent? = null,
): String? {
    if (!isMcpEnabled || !hasMcpTools) {
        return null
    }

    return buildString {
        if (hasEffectiveWebSearch) {
            append(MCP_WITH_WEB_SEARCH_GUIDANCE.trim())
        } else {
            append(MCP_BASE_USAGE_GUIDANCE.trim())
        }
        when (dispatchIntent?.primaryIntent) {
            QueryIntent.DOCS_LOOKUP -> {
                append("\n\n当前问题属于文档/API/SDK 查询，优先考虑文档类 MCP 工具和官方文档结果。")
            }
            QueryIntent.WEB_CONTENT_READ -> {
                append("\n\n当前问题属于网页内容读取，优先考虑网页读取类 MCP 工具。")
            }
            QueryIntent.REALTIME_INFO -> {
                append("\n\n当前问题属于最新信息查询，优先考虑搜索类或网页类 MCP 工具。")
            }
            else -> Unit
        }
        if (!hasEffectiveWebSearch && looksLikeRealtimeOrNewsQuery(messageText)) {
            append("\n\n")
            append(MCP_REALTIME_NEWS_GUIDANCE.trim())
        }
    }
}

internal fun filterMcpToolsForRequest(
    mcpTools: List<Map<String, Any>>,
    shouldFilterSearchLikeTools: Boolean,
): List<Map<String, Any>> {
    if (!shouldFilterSearchLikeTools) {
        return mcpTools
    }

    return mcpTools.filterNot { toolDefinition ->
        classifyMcpTool(toolDefinition).isSearchLike
    }
}

internal fun isSearchLikeMcpTool(
    toolName: String,
    toolDescription: String,
): Boolean {
    return classifyMcpTool(toolName, toolDescription).isSearchLike
}

private fun classifyMcpTool(toolDefinition: Map<String, Any>): McpToolClassification {
    val functionDefinition = toolDefinition["function"] as? Map<*, *>
    val toolName = (functionDefinition?.get("name") as? String)
        ?: (toolDefinition["name"] as? String)
        ?: ""
    val toolDescription = (functionDefinition?.get("description") as? String)
        ?: (toolDefinition["description"] as? String)
        ?: ""
    return classifyMcpTool(toolName, toolDescription)
}

private fun classifyMcpTool(
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

internal fun mergeSystemPromptWithGuidance(
    systemPrompt: String?,
    guidance: String?,
): String? {
    val basePrompt = systemPrompt?.trim().orEmpty()
    val extraGuidance = guidance?.trim().orEmpty()
    return when {
        basePrompt.isBlank() && extraGuidance.isBlank() -> null
        basePrompt.isBlank() -> extraGuidance
        extraGuidance.isBlank() -> basePrompt
        else -> "$basePrompt\n\n$extraGuidance"
    }
}

internal fun buildCurrentTimeGuidance(now: Date = Date(), hasWebSearchTool: Boolean = false): String {
    val timezone = TimeZone.getDefault()
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    formatter.timeZone = timezone
    val currentTime = formatter.format(now)

    val webSearchDesc = if (hasWebSearchTool) {
        "\n\n3. **web_search**：联网搜索。当你无法确认答案是否准确或需要实时信息时调用。"
    } else ""

    return """
当前本地时间：$currentTime
时区：${timezone.id}

## 工具使用规则

你拥有多种工具，全部列在本次请求的 tools 列表中，按需主动调用。以下是内建工具说明：

1. **get_current_datetime**：获取当前时间。涉及时间、日期、星期的问题必须先调用。

2. **webfetch**：抓取指定 URL 的网页正文。用户给了链接直接抓；你自己知道的网址也可以主动抓取。$webSearchDesc

除以上工具外，tools 列表中的其他工具同样可用，根据用户需求主动选择最合适的工具。

## 图像能力说明

如果你具备图像识别能力，工具返回的图片你可以正常查看和分析。如果你不具备图像识别能力，请优先选择纯文本来源获取信息，避免依赖图片内容作答。

## 格式要求

输出表格时，分隔行必须严格使用标准格式：|:---|:---:|---:|，不要出现多余冒号（如 ::---:）。
""".trim()
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

private fun extractToolName(toolDefinition: Map<String, Any>): String? {
    val functionDefinition = toolDefinition["function"] as? Map<*, *>
    val functionName = functionDefinition?.get("name") as? String
    if (!functionName.isNullOrBlank()) {
        return functionName
    }
    return toolDefinition["name"] as? String
}

private data class AttachmentProcessingResult(
    val success: Boolean,
    val processedAttachmentsForUi: List<SelectedMediaItem> = emptyList(),
    val imageUriStringsForUi: List<String> = emptyList(),
    val apiContentParts: List<ApiContentPart> = emptyList()
)

data class PreparedMcpDispatch(
    val intent: McpDispatchIntent,
    val tools: List<Map<String, Any>>,
    val guidance: String?,
)

internal fun buildToolsForMessage(
    messageText: String,
    allCandidates: List<McpToolCandidate>,
): List<Map<String, Any>> {
    return prepareMcpDispatch(messageText, allCandidates).tools
}

internal fun prepareMcpDispatch(
    messageText: String,
    allCandidates: List<McpToolCandidate>,
    strategy: McpDispatchStrategy = McpDispatchStrategy.modelLedDefault(),
    isMcpEnabled: Boolean = true,
    hasEffectiveWebSearch: Boolean = false,
): PreparedMcpDispatch {
    val intent = classifyMcpIntent(messageText)
    val plan = selectMcpCandidates(intent, allCandidates, strategy)
    val tools = plan.exposedTools.map { it.toToolDefinition() }
    val guidance = buildMcpUsageGuidance(
        messageText = messageText,
        isMcpEnabled = isMcpEnabled,
        hasMcpTools = tools.isNotEmpty(),
        hasEffectiveWebSearch = hasEffectiveWebSearch,
        dispatchIntent = intent,
    )
    return PreparedMcpDispatch(
        intent = intent,
        tools = tools,
        guidance = guidance,
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
     private val application: Application,
    private val viewModelScope: CoroutineScope,
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val historyManager: HistoryManager,
    private val showSnackbar: (String) -> Unit,
    private val triggerScrollToBottom: () -> Unit,
    private val uriToBase64Encoder: (Uri) -> String?,
    private val getMcpToolsForRequest: () -> List<Map<String, Any>> = { emptyList() },
    private val getMcpDispatchCandidates: () -> List<McpToolCandidate> = { emptyList() },
    private val getSelectedExternalWebSearchProvider: () -> ExternalWebSearchProvider? = { null },
    private val getSelectedExternalWebSearchProviderApiKey: () -> String = { "" },
) {

    private val fileManager: FileManager by lazy { FileManager(application) }

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024 // 50MB 最大文件大小
        private const val CHAT_ATTACHMENTS_SUBDIR = "chat_attachments"
        
        // 保留兼容性的常量，但标记为过时
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxFileSize"))
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_WIDTH = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_HEIGHT = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.compressionQuality"))
        private const val JPEG_COMPRESSION_QUALITY = 80
    }

    private fun logUiMessages(stage: String, messages: List<UiMessage>) {
        Log.d("MessageSender", "$stage.size=${messages.size}")
        messages.forEachIndexed { index, message ->
            Log.d(
                "MessageSender",
                "$stage[$index]: role=${message.role} textChars=${message.text.length} attachments=${message.attachments.size} sender=${message.sender}"
            )
        }
    }

    private fun describeApiMessage(message: AbstractApiMessage): String {
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

    private fun logApiMessages(stage: String, messages: List<AbstractApiMessage>) {
        Log.d("MessageSender", "$stage.size=${messages.size}")
        messages.forEachIndexed { index, message ->
            Log.d(
                "MessageSender",
                "$stage[$index]: role=${message.role} summary=${describeApiMessage(message)}"
            )
        }
    }

    private fun extractPrimaryText(message: AbstractApiMessage): String {
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

    /**
     * 从Uri加载并压缩位图 - 新版本支持等比缩放
     * @param context 上下文
     * @param uri 图片Uri
     * @param isImageGeneration 是否为图像生成模式
     * @return 压缩后的位图，如果加载失败则返回null
     */
    private suspend fun loadAndCompressBitmapFromUri(
        context: Context, 
        uri: Uri,
        isImageGeneration: Boolean = false
    ): Bitmap? {
        return fileManager.loadAndCompressBitmapFromUri(uri = uri, isImageGeneration = isImageGeneration)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun copyUriToAppInternalStorage(
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

    private suspend fun saveBitmapToAppInternalStorage(
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

    private suspend fun processAttachments(
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

            val persistentFilePath: String? = when (originalMediaItem) {
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
                    originalMediaItem.bitmap?.let { bitmap ->
                        val prefs = com.android.everytalk.config.ImageCompressionPreferences(application)
                        val config = if (isImageGeneration) prefs.getImageGenerationModeConfig() else prefs.getChatModeConfig()
                        val (targetW, targetH) = ImageScaleCalculator.calculateProportionalScale(bitmap.width, bitmap.height, config)
                        val safeBitmap = try {
                            if ((targetW > 0 && targetH > 0) && (targetW != bitmap.width || targetH != bitmap.height)) {
                                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                            } else {
                                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                            }
                        } catch (_: OutOfMemoryError) {
                            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                        }
                        saveBitmapToAppInternalStorage(application, safeBitmap, tempMessageIdForNaming, index, originalFileNameForHint, isImageGeneration)
                    }
                }
                is SelectedMediaItem.GenericFile -> {
                    copyUriToAppInternalStorage(application, originalMediaItem.uri, tempMessageIdForNaming, index, originalMediaItem.displayName)
                }
                is SelectedMediaItem.Audio -> {
                    // 音频数据已为Base64，无需额外处理
                    null
                }
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
                    originalMediaItem.bitmap?.let { bitmap ->
                        SelectedMediaItem.ImageFromBitmap.fromBitmap(
                            bitmap = bitmap,
                            id = originalMediaItem.id,
                            filePath = persistentFilePath
                        )
                    } ?: originalMediaItem // 如果 bitmap 为 null，返回原始对象
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
            if (shouldUsePartsApiMessage && (processedItemForUi is SelectedMediaItem.ImageFromUri || processedItemForUi is SelectedMediaItem.ImageFromBitmap)) {
                val imageUri = (processedItemForUi as? SelectedMediaItem.ImageFromUri)?.uri
                    ?: (processedItemForUi as? SelectedMediaItem.ImageFromBitmap)?.let {
                        // 对于 Bitmap，我们需要一个 URI 来编码
                        persistentFileProviderUri
                    }

                if (imageUri != null) {
                    val base64Data = uriToBase64Encoder(imageUri)
                    val mimeType = application.contentResolver.getType(imageUri) ?: "image/jpeg"
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
        val textToActuallySend = messageText.trim()
        val allAttachments = attachments.toMutableList()
        if (audioBase64 != null) {
            allAttachments.add(SelectedMediaItem.Audio(id = "audio_${UUID.randomUUID()}", mimeType = mimeType ?: "audio/3gpp", data = audioBase64!!))
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
            val currentMap = stateHolder.conversationApiConfigIds.value.toMutableMap()
            if (currentMap[conversationId] != currentConfig.id) {
                currentMap[conversationId] = currentConfig.id
                stateHolder.conversationApiConfigIds.value = currentMap
                // 这里仅更新内存状态，HistoryManager.saveCurrentChatToHistoryIfNeededInternal 会负责持久化
            }
        } else {
            // 图像模式：绑定当前图像会话ID与配置ID
            val conversationId = stateHolder._currentImageGenerationConversationId.value
            val currentMap = stateHolder.conversationApiConfigIds.value.toMutableMap()
            if (currentMap[conversationId] != currentConfig.id) {
                currentMap[conversationId] = currentConfig.id
                stateHolder.conversationApiConfigIds.value = currentMap
                // 这里仅更新内存状态，HistoryManager 会负责持久化
            }
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
                            val refBitmap = fm.loadAndCompressBitmapFromUrl(refImageUrl, isImageGeneration = true)
                            if (refBitmap != null) {
                                allAttachments.add(
                                    SelectedMediaItem.ImageFromBitmap.fromBitmap(
                                        bitmap = refBitmap,
                                        id = "ref_${UUID.randomUUID()}"
                                    )
                                )
                                Log.d("MessageSender", "已自动附带上一轮AI图片作为参考: $refImageUrl")
                            } else {
                                Log.w("MessageSender", "未能下载上一轮AI图片，跳过自动引用")
                            }
                        }
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

                val apiMessagesForBackend = ensureUserMessagePresent(historyApiMessages, currentUserApiMessage)

                val isMcpEnabledForRequest = stateHolder._isMcpEnabledForNextRequest.value
                val rawMcpToolsForRequest = if (isMcpEnabledForRequest) {
                    getMcpToolsForRequest()
                } else {
                    emptyList()
                }
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
                val hasEffectiveWebSearch =
                    webSearchRouting.useNativeWebSearch || webSearchRouting.externalProvider != null || webSearchRouting.useJinaSearch
                val preparedMcpDispatch = if (isMcpEnabledForRequest && dispatchCandidates.isNotEmpty()) {
                    prepareMcpDispatch(
                        messageText = textToActuallySend,
                        allCandidates = dispatchCandidates,
                        isMcpEnabled = true,
                        hasEffectiveWebSearch = hasEffectiveWebSearch,
                    )
                } else {
                    PreparedMcpDispatch(
                        intent = classifyMcpIntent(textToActuallySend),
                        tools = emptyList(),
                        guidance = buildMcpUsageGuidance(
                            messageText = textToActuallySend,
                            isMcpEnabled = isMcpEnabledForRequest,
                            hasMcpTools = false,
                            hasEffectiveWebSearch = hasEffectiveWebSearch,
                            dispatchIntent = classifyMcpIntent(textToActuallySend),
                        )
                    )
                }
                val mcpToolsForRequest = filterMcpToolsForRequest(
                    mcpTools = if (preparedMcpDispatch.tools.isNotEmpty()) preparedMcpDispatch.tools else rawMcpToolsForRequest,
                    shouldFilterSearchLikeTools = false,
                )
                val shouldEnableGoogleSearch = isGeminiChannel && webSearchRouting.useNativeWebSearch
                val mcpHasSearchTool = mcpToolsForRequest.any { classifyMcpTool(it).isSearchLike }
                val shouldInjectWebSearchTool = !shouldEnableGoogleSearch && !mcpHasSearchTool
                        && (webSearchRouting.externalProvider != null || webSearchRouting.useJinaSearch)

                val systemPromptWithWebSearchAwareMcp = mergeSystemPromptWithGuidance(
                    systemPrompt = mergeSystemPromptWithGuidance(
                        systemPrompt = systemPrompt,
                        guidance = buildCurrentTimeGuidance(hasWebSearchTool = shouldInjectWebSearchTool)
                    ),
                    guidance = preparedMcpDispatch.guidance ?: buildMcpUsageGuidance(
                        messageText = textToActuallySend,
                        isMcpEnabled = isMcpEnabledForRequest,
                        hasMcpTools = mcpToolsForRequest.isNotEmpty(),
                        hasEffectiveWebSearch = hasEffectiveWebSearch,
                        dispatchIntent = preparedMcpDispatch.intent,
                    )
                )
                if (!systemPromptWithWebSearchAwareMcp.isNullOrBlank()) {
                    val systemMessage = SimpleTextApiMessage(role = "system", content = systemPromptWithWebSearchAwareMcp)
                    val existingSystemMessageIndex = apiMessagesForBackend.indexOfFirst { it.role == "system" }
                    if (existingSystemMessageIndex != -1) {
                        apiMessagesForBackend[existingSystemMessageIndex] = systemMessage
                    } else {
                        apiMessagesForBackend.add(0, systemMessage)
                    }
                }

                logApiMessages("finalMessages", apiMessagesForBackend)

                if (apiMessagesForBackend.isEmpty() || apiMessagesForBackend.lastOrNull()?.role != "user") {
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
                    messages = apiMessagesForBackend,
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
                    tools = run {
                        val toolsList = mutableListOf<Map<String, Any>>()
                        
                        // 1. 用户自定义工具
                        if (!currentConfig.toolsJson.isNullOrBlank()) {
                            try {
                                val jsonElement = Json.parseToJsonElement(currentConfig.toolsJson!!)
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
                    },
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

private suspend fun readTextFromUri(context: Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
                    reader?.readText()
                }
            } catch (e: Exception) {
                null
            }
        }
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
    
    private fun hasImageGenerationKeywords(text: String?): Boolean {
        // 委托给 ApiHandler 中的实现，避免重复代码
        return apiHandler.hasImageGenerationKeywords(text)
    }

    // 识别"编辑/基于上一张修改"的语义，用于自动附带上一轮AI图片
    private fun hasImageEditKeywords(text: String?): Boolean {
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
    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
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
