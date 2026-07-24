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

internal const val MAX_URI_BASE64_BYTES = 10L * 1024L * 1024L
internal const val TOOL_STATUS_TARGET_MAX_CHARS = 24

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

internal inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (t: Throwable) {
        t.rethrowIfCancellation()
        Result.failure(t)
    }
}

internal fun compactToolStatusTarget(value: String, maxChars: Int = TOOL_STATUS_TARGET_MAX_CHARS): String {
    val normalized = value.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return ""
    if (normalized.length <= maxChars) return normalized
    return normalized.take((maxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
}

internal fun buildToolStatus(prefix: String, target: String): String {
    val compactTarget = compactToolStatusTarget(target)
    return if (compactTarget.isBlank()) prefix else "$prefix · $compactTarget"
}

internal fun shouldSkipReloadingLoadedHistory(
    requestedIndex: Int,
    loadedIndex: Int?,
    hasLoadedMessages: Boolean,
): Boolean {
    return requestedIndex == loadedIndex && hasLoadedMessages
}

internal fun isCurrentHistoryLoad(
    requestGeneration: Long,
    currentGeneration: Long,
): Boolean = requestGeneration == currentGeneration

internal suspend fun executeSharedToolCall(
    toolName: String,
    arguments: JsonObject,
    updateStatus: suspend (String?) -> Unit = {},
    localWebFetchExecutor: suspend (JsonObject) -> JsonElement = { WebFetchToolExecutor.execute(it) },
    mcpWebFetchFallback: (suspend (JsonObject) -> JsonElement)? = null,
    localWebSearchExecutor: (suspend (String) -> JsonElement)? = null,
    localCurrentTimeExecutor: suspend () -> JsonElement = {
        val now = Date()
        val calendar = Calendar.getInstance()
        calendar.time = now
        val timezone = TimeZone.getDefault()
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = timezone
        }
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = timezone
        }
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = timezone
        }
        val displayFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = timezone
        }
        buildJsonObject {
            put("datetime", JsonPrimitive(isoFormatter.format(now)))
            put("date", JsonPrimitive(dateFormatter.format(now)))
            put("time", JsonPrimitive(timeFormatter.format(now)))
            put("local_time", JsonPrimitive(displayFormatter.format(now)))
            put("hour", JsonPrimitive(calendar.get(Calendar.HOUR_OF_DAY)))
            put("minute", JsonPrimitive(calendar.get(Calendar.MINUTE)))
            put("second", JsonPrimitive(calendar.get(Calendar.SECOND)))
            put("timezone", JsonPrimitive(timezone.id))
            put("timestamp_ms", JsonPrimitive(now.time))
        }
    },
    fallbackExecutor: suspend (String, JsonObject) -> JsonElement,
): JsonElement {
    if (toolName.equals(BUILT_IN_WEBFETCH_TOOL_NAME, ignoreCase = true)) {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        updateStatus(buildToolStatus("读取网页", url))
        val result = localWebFetchExecutor(arguments)
        val resultObj = result as? JsonObject
        val isSuccess = resultObj?.get("ok")?.jsonPrimitive?.booleanOrNull == true
        if (!isSuccess && mcpWebFetchFallback != null) {
            Log.d("ToolCall", "Jina Reader 失败，尝试 MCP webfetch fallback")
            updateStatus(buildToolStatus("MCP读取网页", url))
            val mcpResult = mcpWebFetchFallback(arguments)
            updateStatus(null)
            return mcpResult
        }
        updateStatus(null)
        return result
    }
    if (toolName.equals(BUILT_IN_CURRENT_TIME_TOOL_NAME, ignoreCase = true)) {
        updateStatus("获取当前时间")
        val result = localCurrentTimeExecutor()
        updateStatus(null)
        return result
    }
    if (toolName.equals(BUILT_IN_WEB_SEARCH_TOOL_NAME, ignoreCase = true) && localWebSearchExecutor != null) {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (query.isBlank()) {
            return buildJsonObject { put("error", JsonPrimitive("query is required")) }
        }
        updateStatus(buildToolStatus("搜索网页", query))
        val result = localWebSearchExecutor(query)
        updateStatus(null)
        return result
    }
    updateStatus(buildToolStatus("调用MCP", toolName))
    return try {
        fallbackExecutor(toolName, arguments)
    } finally {
        updateStatus(null)
    }
}

internal fun resolveHistoryIndexAfterSave(
    requestedIndex: Int,
    historyBeforeSave: List<List<Message>>,
    historyAfterSave: List<List<Message>>,
): Int {
    val clickedConversation = historyBeforeSave.getOrNull(requestedIndex)
    val clickedStableId = ConversationNameHelper.resolveStableId(clickedConversation)
        ?: return requestedIndex
    val completedDuplicateIndex = resolveCompletedDuplicateIndex(clickedConversation, historyAfterSave)
    if (completedDuplicateIndex != null) return completedDuplicateIndex
    return historyAfterSave.indexOfFirst { conversation ->
        ConversationNameHelper.resolveStableId(conversation) == clickedStableId
    }.takeIf { it >= 0 } ?: requestedIndex
}

internal fun resolveCompletedDuplicateIndex(
    clickedConversation: List<Message>?,
    historyAfterSave: List<List<Message>>,
): Int? {
    if (clickedConversation.isNullOrEmpty()) return null
    val clickedFingerprint = conversationDraftFingerprint(clickedConversation)
    if (clickedFingerprint.isBlank()) return null
    return historyAfterSave.indexOfFirst { conversation ->
        conversation.size > clickedConversation.size &&
            conversationDraftFingerprint(conversation).startsWith("$clickedFingerprint||")
    }.takeIf { it >= 0 }
}

internal fun conversationDraftFingerprint(messages: List<Message>): String {
    return messages
        .filter { it.sender != Sender.System }
        .joinToString("||") { message ->
            val senderTag = when (message.sender) {
                Sender.User -> "U"
                Sender.AI -> "A"
                Sender.System -> "S"
                else -> "O"
            }
            listOf(senderTag, message.text.trim(), message.reasoning.orEmpty().trim()).joinToString("::")
        }
}
