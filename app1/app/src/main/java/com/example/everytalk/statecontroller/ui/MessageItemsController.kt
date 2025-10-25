package com.example.everytalk.statecontroller.ui

import android.util.Log
import com.example.everytalk.config.PerformanceConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.statecontroller.ViewModelStateHolder
import com.example.everytalk.ui.screens.MainScreen.chat.ChatListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import androidx.compose.runtime.snapshotFlow

/**
 * 将 AppViewModel 中与“AI气泡状态 + ChatListItem 构建”相关的大段逻辑外置。
 * 仅依赖 ViewModelStateHolder 与提供的 CoroutineScope，不涉及 UI 层组件。
 *
 * 提供两个对外 StateFlow:
 * - chatListItems: 文本对话的 ChatListItem 列表
 * - imageGenerationChatListItems: 图像对话的 ChatListItem 列表
 */
class MessageItemsController(
    private val stateHolder: ViewModelStateHolder,
    scope: CoroutineScope
) {

    private data class CacheEntry(
        val text: String,
        val reasoning: String?,
        val outputType: String,
        val hasReasoning: Boolean,
        val imageUrls: List<String>?,
        val contentStarted: Boolean,
        val items: List<ChatListItem>
    )

    private val chatListItemCache = mutableMapOf<String, CacheEntry>()
    private val imageGenerationChatListItemCache = mutableMapOf<String, CacheEntry>()

    // 采用轻量状态机统一驱动“连接中/思考/流式/完成/错误”的展示
    private val bubbleStateMachines = mutableMapOf<String, com.example.everytalk.ui.state.AiBubbleStateMachine>()

    private fun getBubbleStateMachine(messageId: String): com.example.everytalk.ui.state.AiBubbleStateMachine {
        return bubbleStateMachines.getOrPut(messageId) {
            com.example.everytalk.ui.state.AiBubbleStateMachine()
        }
    }

    val chatListItems: StateFlow<List<ChatListItem>> =
        combine(
            snapshotFlow { stateHolder.messages.toList() },
            stateHolder._isTextApiCalling,
            stateHolder._currentTextStreamingAiMessageId
        ) { messages, isApiCalling, currentStreamingAiMessageId ->
            messages
                .map { message ->
                    when (message.sender) {
                        Sender.AI -> {
                            val cached = chatListItemCache[message.id]
                            val hasReasoning = !message.reasoning.isNullOrBlank()
                            val isCurrentlyStreaming = isApiCalling && message.id == currentStreamingAiMessageId

                            val cacheValid = cached != null &&
                                cached.text == message.text &&
                                cached.reasoning == message.reasoning &&
                                cached.outputType == message.outputType &&
                                cached.hasReasoning == hasReasoning &&
                                cached.imageUrls == message.imageUrls &&
                                cached.contentStarted == message.contentStarted &&
                                (isCurrentlyStreaming == (cached.items.any {
                                    it is ChatListItem.LoadingIndicator ||
                                        it is ChatListItem.AiMessageStreaming ||
                                        it is ChatListItem.AiMessageCodeStreaming
                                }))

                            if (cacheValid) {
                                android.util.Log.d(
                                    "MessageItemsController",
                                    "Cache HIT for ${message.id.take(8)}, items=${cached!!.items.map { it::class.simpleName }}"
                                )
                                cached.items
                            } else {
                                android.util.Log.d(
                                    "MessageItemsController",
                                    "Cache MISS for ${message.id.take(8)}, text.len=${message.text.length}, " +
                                        "contentStarted=${message.contentStarted}, cached.contentStarted=${cached?.contentStarted}"
                                )
                                val newItems = createAiMessageItems(
                                    message,
                                    isApiCalling,
                                    currentStreamingAiMessageId
                                )

                                chatListItemCache[message.id] = CacheEntry(
                                    text = message.text,
                                    reasoning = message.reasoning,
                                    outputType = message.outputType,
                                    hasReasoning = hasReasoning,
                                    imageUrls = message.imageUrls,
                                    contentStarted = message.contentStarted,
                                    items = newItems
                                )
                                newItems
                            }
                        }
                        else -> createOtherMessageItems(message)
                    }
                }
                .flatten()
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val imageGenerationChatListItems: StateFlow<List<ChatListItem>> =
        combine(
            snapshotFlow { stateHolder.imageGenerationMessages.toList() },
            stateHolder._isImageApiCalling,
            stateHolder._currentImageStreamingAiMessageId
        ) { messages, isApiCalling, currentStreamingAiMessageId ->
            android.util.Log.d(
                "MessageItemsController",
                "🖼️ [IMAGE FLOW] Triggered - messages.size=${messages.size}, isApiCalling=$isApiCalling"
            )
            messages
                .map { message ->
                    when (message.sender) {
                        Sender.AI -> {
                            val cached = imageGenerationChatListItemCache[message.id]
                            val hasReasoning = !message.reasoning.isNullOrBlank()
                            val isCurrentlyStreaming = isApiCalling && message.id == currentStreamingAiMessageId

                            val cacheValid = cached != null &&
                                cached.text == message.text &&
                                cached.reasoning == message.reasoning &&
                                cached.outputType == message.outputType &&
                                cached.hasReasoning == hasReasoning &&
                                cached.imageUrls == message.imageUrls &&
                                (isCurrentlyStreaming == (cached.items.any { it is ChatListItem.LoadingIndicator }))

                            android.util.Log.d(
                                "MessageItemsController",
                                "🖼️ [IMAGE CACHE] messageId=${message.id.take(8)}, " +
                                    "cacheValid=$cacheValid, " +
                                    "cached.imageUrls=${cached?.imageUrls?.size}, " +
                                    "message.imageUrls=${message.imageUrls?.size}"
                            )

                            if (cacheValid) {
                                android.util.Log.d("MessageItemsController", "🖼️ [IMAGE CACHE HIT] Using cached items")
                                cached!!.items
                            } else {
                                android.util.Log.d("MessageItemsController", "🖼️ [IMAGE CACHE MISS] Recomputing items")
                                val newItems = createAiMessageItems(
                                    message,
                                    isApiCalling,
                                    currentStreamingAiMessageId,
                                    isImageGeneration = true
                                )

                                imageGenerationChatListItemCache[message.id] = CacheEntry(
                                    text = message.text,
                                    reasoning = message.reasoning,
                                    outputType = message.outputType,
                                    hasReasoning = hasReasoning,
                                    imageUrls = message.imageUrls,
                                    contentStarted = message.contentStarted,
                                    items = newItems
                                )
                                newItems
                            }
                        }
                        else -> createOtherMessageItems(message)
                    }
                }
                .flatten()
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private fun computeBubbleState(
        message: Message,
        isApiCalling: Boolean,
        currentStreamingAiMessageId: String?,
        isImageGeneration: Boolean
    ): com.example.everytalk.ui.state.AiBubbleState {
        if (message.isError) return com.example.everytalk.ui.state.AiBubbleState.Error(message.text)

        if (stateHolder._isStreamingPaused.value) {
            return com.example.everytalk.ui.state.AiBubbleState.Idle
        }

        val isCurrentStreaming = isApiCalling && message.id == currentStreamingAiMessageId
        val hasReasoning = !message.reasoning.isNullOrBlank()
        val reasoningCompleteMap =
            if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
        val reasoningComplete = reasoningCompleteMap[message.id] ?: false

        if (Log.isLoggable("AppViewModelVerbose", Log.VERBOSE)) {
            Log.v(
                "AppViewModelVerbose",
                "computeBubbleState: id=${message.id.take(8)}, " +
                    "isStreaming=$isCurrentStreaming, hasReasoning=$hasReasoning, " +
                    "reasoningComplete=$reasoningComplete, contentStarted=${message.contentStarted}, " +
                    "message.reasoning=${message.reasoning?.take(20)}"
            )
        }

        val state = when {
            isCurrentStreaming && hasReasoning && !message.contentStarted -> {
                com.example.everytalk.ui.state.AiBubbleState.Reasoning(
                    message.reasoning ?: "",
                    isComplete = reasoningComplete
                )
            }
            isCurrentStreaming && message.contentStarted -> {
                com.example.everytalk.ui.state.AiBubbleState.Streaming(
                    content = message.text,
                    hasReasoning = hasReasoning,
                    reasoningComplete = reasoningComplete
                )
            }
            isCurrentStreaming && !hasReasoning && !message.contentStarted -> {
                com.example.everytalk.ui.state.AiBubbleState.Connecting
            }
            (message.contentStarted || message.text.isNotBlank()) ->
                com.example.everytalk.ui.state.AiBubbleState.Complete(
                    content = message.text,
                    reasoning = message.reasoning
                )
            else -> com.example.everytalk.ui.state.AiBubbleState.Idle
        }

        if (isCurrentStreaming) {
            android.util.Log.d(
                "MessageItemsController",
                "🎯 BubbleState for ${message.id.take(8)}: ${state::class.simpleName}, " +
                    "isStreaming=$isCurrentStreaming, contentStarted=${message.contentStarted}, textLen=${message.text.length}"
            )
        }

        return state
    }

    private fun createAiMessageItems(
        message: Message,
        isApiCalling: Boolean,
        currentStreamingAiMessageId: String?,
        isImageGeneration: Boolean = false
    ): List<ChatListItem> {
        val sm = getBubbleStateMachine(message.id)
        val state = computeBubbleState(message, isApiCalling, currentStreamingAiMessageId, isImageGeneration)

        return when (state) {
            is com.example.everytalk.ui.state.AiBubbleState.Connecting -> {
                android.util.Log.d("MessageItemsController", "🎯 createAiMessageItems: Connecting -> LoadingIndicator")
                listOf(ChatListItem.LoadingIndicator(message.id))
            }
            is com.example.everytalk.ui.state.AiBubbleState.Reasoning -> {
                android.util.Log.d(
                    "MessageItemsController",
                    "🎯 createAiMessageItems: Reasoning -> AiMessageReasoning, reasoning=${message.reasoning?.take(30)}"
                )
                listOf(ChatListItem.AiMessageReasoning(message))
            }
            is com.example.everytalk.ui.state.AiBubbleState.Streaming -> {
                val items = mutableListOf<ChatListItem>()
                if (state.hasReasoning && state.reasoningComplete && !message.reasoning.isNullOrBlank()) {
                    items.add(ChatListItem.AiMessageReasoning(message))
                }

                val streamingItem: ChatListItem =
                    if (PerformanceConfig.USE_STREAMING_STATEFLOW_RENDERING) {
                        when (message.outputType) {
                            "code" -> ChatListItem.AiMessageCodeStreaming(message.id, state.hasReasoning)
                            else -> ChatListItem.AiMessageStreaming(message.id, state.hasReasoning)
                        }
                    } else {
                        when (message.outputType) {
                            "code" -> ChatListItem.AiMessageCode(message.id, message.text, state.hasReasoning)
                            else -> ChatListItem.AiMessage(message.id, message.text, state.hasReasoning)
                        }
                    }
                items.add(streamingItem)
                items
            }
            is com.example.everytalk.ui.state.AiBubbleState.Complete -> {
                val items = mutableListOf<ChatListItem>()
                if (!message.reasoning.isNullOrBlank()) {
                    items.add(ChatListItem.AiMessageReasoning(message))
                }

                val hasImageContent = !message.imageUrls.isNullOrEmpty()
                val hasTextContent = message.text.isNotBlank()

                if (hasTextContent || (isImageGeneration && hasImageContent)) {
                    items.add(
                        when (message.outputType) {
                            "code" -> ChatListItem.AiMessageCode(message.id, message.text, !message.reasoning.isNullOrBlank())
                            else -> ChatListItem.AiMessage(message.id, message.text, !message.reasoning.isNullOrBlank())
                        }
                    )
                    android.util.Log.d(
                        "MessageItemsController",
                        "🖼️ [COMPLETE STATE] Created AiMessage item: hasTextContent=$hasTextContent, " +
                            "hasImageContent=$hasImageContent, imageUrls=${message.imageUrls?.size}"
                    )
                }

                if (!message.webSearchResults.isNullOrEmpty()) {
                    items.add(ChatListItem.AiMessageFooter(message))
                }
                items
            }
            is com.example.everytalk.ui.state.AiBubbleState.Error -> {
                listOf(ChatListItem.ErrorMessage(message.id, message.text))
            }
            else -> emptyList()
        }
    }

    private fun createOtherMessageItems(message: Message): List<ChatListItem> {
        return when {
            message.sender == Sender.User ->
                listOf(
                    ChatListItem.UserMessage(
                        messageId = message.id,
                        text = message.text,
                        attachments = message.attachments
                    )
                )
            message.isError ->
                listOf(ChatListItem.ErrorMessage(messageId = message.id, text = message.text))
            else -> emptyList()
        }
    }
}