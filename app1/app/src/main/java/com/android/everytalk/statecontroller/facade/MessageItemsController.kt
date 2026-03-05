package com.android.everytalk.statecontroller.facade

import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import androidx.compose.runtime.snapshotFlow
import java.util.concurrent.ConcurrentHashMap

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
        val blocksHash: String,
        val hasPendingMath: Boolean,
        val imageUrls: List<String>?,
        val contentStarted: Boolean,
        val executionStatus: String?,
        val items: List<ChatListItem>
    )

    private val chatListItemCache = mutableMapOf<String, CacheEntry>()
    private val imageGenerationChatListItemCache = mutableMapOf<String, CacheEntry>()

    // 采用轻量状态机统一驱动"连接中/思考/流式/完成/错误"的展示
    private val bubbleStateMachines = mutableMapOf<String, com.android.everytalk.ui.state.AiBubbleStateMachine>()
    
    // 🔧 修复Loading不显示问题：记录每个消息开始流式传输的时间戳
    // 用于确保Loading状态至少显示一段时间（防止后端响应过快时跳过Connecting状态）
    private val streamingStartTimestamps = ConcurrentHashMap<String, Long>()
    
    // Loading状态最小显示时间（毫秒）- 确保用户能看到"正在连接"提示
    private val MIN_CONNECTING_DISPLAY_TIME_MS = 300L

    private fun getBubbleStateMachine(messageId: String): com.android.everytalk.ui.state.AiBubbleStateMachine {
        return bubbleStateMachines.getOrPut(messageId) {
            com.android.everytalk.ui.state.AiBubbleStateMachine()
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
                            val parseResult = StreamBlockParser.parse(message.text, message.id)

                            // 定义仅流式状态下允许存在的组件类型
                            val hasStreamingOnlyItems = cached?.items?.any {
                                it is ChatListItem.LoadingIndicator ||
                                    it is ChatListItem.AiMessageStreaming ||
                                    it is ChatListItem.AiMessageCodeStreaming ||
                                    it is ChatListItem.StatusIndicator
                            } ?: false

                            val cacheValid = cached != null &&
                                cached.reasoning == message.reasoning &&
                                cached.outputType == message.outputType &&
                                cached.hasReasoning == hasReasoning &&
                                cached.blocksHash == parseResult.blocksHash &&
                                cached.hasPendingMath == parseResult.hasPendingMath &&
                                cached.imageUrls == message.imageUrls &&
                                cached.contentStarted == message.contentStarted &&
                                cached.executionStatus == message.executionStatus &&
                                // 缓存校验增加对 webSearchResults 的检查，确保 Footer 变化能触发更新
                                (cached.items.any { it is ChatListItem.AiMessageFooter } == !message.webSearchResults.isNullOrEmpty()) &&
                                // 校验流式状态兼容性：
                                // 1. 如果当前是流式：我们接受任何缓存（因为 AiMessage 现在也用于流式），只要内容匹配
                                // 2. 如果当前非流式：缓存中不能包含“仅流式”组件（如 Loading/StatusIndicator）
                                (isCurrentlyStreaming || !hasStreamingOnlyItems)

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
                                        "contentStarted=${message.contentStarted}, cached.contentStarted=${cached?.contentStarted}, " +
                                        "blocksHash=${parseResult.blocksHash}, cachedHash=${cached?.blocksHash}, " +
                                        "pendingMath=${parseResult.hasPendingMath}, cachedPending=${cached?.hasPendingMath}"
                                )
                                val newItems = createAiMessageItems(
                                    message,
                                    isApiCalling,
                                    currentStreamingAiMessageId,
                                    parseResult = parseResult
                                )

                                chatListItemCache[message.id] = CacheEntry(
                                    text = message.text,
                                    reasoning = message.reasoning,
                                    outputType = message.outputType,
                                    hasReasoning = hasReasoning,
                                    blocksHash = parseResult.blocksHash,
                                    hasPendingMath = parseResult.hasPendingMath,
                                    imageUrls = message.imageUrls,
                                    contentStarted = message.contentStarted,
                                    executionStatus = message.executionStatus,
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
                "[IMAGE FLOW] Triggered - messages.size=${messages.size}, isApiCalling=$isApiCalling"
            )
            messages
                .map { message ->
                    when (message.sender) {
                        Sender.AI -> {
                            val cached = imageGenerationChatListItemCache[message.id]
                            val hasReasoning = !message.reasoning.isNullOrBlank()
                            val isCurrentlyStreaming = isApiCalling && message.id == currentStreamingAiMessageId
                            val parseResult = StreamBlockParser.parse(message.text, message.id)

                            val cacheValid = cached != null &&
                                cached.reasoning == message.reasoning &&
                                cached.outputType == message.outputType &&
                                cached.hasReasoning == hasReasoning &&
                                cached.blocksHash == parseResult.blocksHash &&
                                cached.hasPendingMath == parseResult.hasPendingMath &&
                                cached.imageUrls == message.imageUrls &&
                                (isCurrentlyStreaming == (cached.items.any { it is ChatListItem.LoadingIndicator }))

                            android.util.Log.d(
                                "MessageItemsController",
                                "[IMAGE CACHE] messageId=${message.id.take(8)}, " +
                                    "cacheValid=$cacheValid, " +
                                    "blocksHash=${parseResult.blocksHash}, " +
                                    "cachedHash=${cached?.blocksHash}, " +
                                    "cached.imageUrls=${cached?.imageUrls?.size}, " +
                                    "message.imageUrls=${message.imageUrls?.size}"
                            )

                            if (cacheValid) {
                                android.util.Log.d("MessageItemsController", "[IMAGE CACHE HIT] Using cached items")
                                cached!!.items
                            } else {
                                android.util.Log.d("MessageItemsController", "[IMAGE CACHE MISS] Recomputing items")
                                val newItems = createAiMessageItems(
                                    message,
                                    isApiCalling,
                                    currentStreamingAiMessageId,
                                    parseResult = parseResult,
                                    isImageGeneration = true
                                )

                                imageGenerationChatListItemCache[message.id] = CacheEntry(
                                    text = message.text,
                                    reasoning = message.reasoning,
                                    outputType = message.outputType,
                                    hasReasoning = hasReasoning,
                                    blocksHash = parseResult.blocksHash,
                                    hasPendingMath = parseResult.hasPendingMath,
                                    imageUrls = message.imageUrls,
                                    contentStarted = message.contentStarted,
                                    executionStatus = message.executionStatus,
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
    ): com.android.everytalk.ui.state.AiBubbleState {
        if (message.isError) return com.android.everytalk.ui.state.AiBubbleState.Error(message.text)

        if (stateHolder._isStreamingPaused.value) {
            return com.android.everytalk.ui.state.AiBubbleState.Idle
        }

        val isCurrentStreaming = isApiCalling && message.id == currentStreamingAiMessageId
        val hasReasoning = !message.reasoning.isNullOrBlank()
        val reasoningCompleteMap =
            if (isImageGeneration) stateHolder.imageReasoningCompleteMap else stateHolder.textReasoningCompleteMap
        val reasoningComplete = reasoningCompleteMap[message.id] ?: false

        // 🔧 修复Loading不显示问题：记录流式开始时间
        // 当开始流式传输时，记录时间戳；用于确保Loading状态至少显示MIN_CONNECTING_DISPLAY_TIME_MS
        if (isCurrentStreaming && !streamingStartTimestamps.containsKey(message.id)) {
            streamingStartTimestamps[message.id] = System.currentTimeMillis()
            android.util.Log.d(
                "MessageItemsController",
                "🔧 Registered streaming start time for message: ${message.id.take(8)}"
            )
        }
        
        // 🔧 计算是否仍在最小显示时间内
        val streamingStartTime = streamingStartTimestamps[message.id]
        val isWithinMinDisplayTime = if (streamingStartTime != null && isCurrentStreaming) {
            val elapsed = System.currentTimeMillis() - streamingStartTime
            elapsed < MIN_CONNECTING_DISPLAY_TIME_MS
        } else {
            false
        }

        if (Log.isLoggable("AppViewModelVerbose", Log.VERBOSE)) {
            Log.v(
                "AppViewModelVerbose",
                "computeBubbleState: id=${message.id.take(8)}, " +
                    "isStreaming=$isCurrentStreaming, hasReasoning=$hasReasoning, " +
                    "reasoningComplete=$reasoningComplete, contentStarted=${message.contentStarted}, " +
                    "message.reasoning=${message.reasoning?.take(20)}, isWithinMinDisplayTime=$isWithinMinDisplayTime"
            )
        }

        val state = when {
            // 🔧 关键修复：如果仍在最小显示时间内且没有推理内容，强制显示Connecting状态
            // 这确保了即使后端响应很快，用户也能看到"正在连接大模型..."提示
            isCurrentStreaming && !hasReasoning && isWithinMinDisplayTime -> {
                com.android.everytalk.ui.state.AiBubbleState.Connecting
            }
            isCurrentStreaming && hasReasoning && !message.contentStarted -> {
                com.android.everytalk.ui.state.AiBubbleState.Reasoning(
                    message.reasoning ?: "",
                    isComplete = reasoningComplete
                )
            }
            isCurrentStreaming && message.contentStarted -> {
                // 清理时间戳，因为已经开始流式输出
                streamingStartTimestamps.remove(message.id)
                com.android.everytalk.ui.state.AiBubbleState.Streaming(
                    content = message.text,
                    hasReasoning = hasReasoning,
                    reasoningComplete = reasoningComplete
                )
            }
            isCurrentStreaming && !hasReasoning && !message.contentStarted -> {
                com.android.everytalk.ui.state.AiBubbleState.Connecting
            }
            (message.contentStarted || message.text.isNotBlank()) -> {
                // 清理时间戳，因为消息已完成
                streamingStartTimestamps.remove(message.id)
                com.android.everytalk.ui.state.AiBubbleState.Complete(
                    content = message.text,
                    reasoning = message.reasoning
                )
            }
            else -> com.android.everytalk.ui.state.AiBubbleState.Idle
        }

        if (isCurrentStreaming) {
            android.util.Log.d(
                "MessageItemsController",
                "BubbleState for ${message.id.take(8)}: ${state::class.simpleName}, " +
                    "isStreaming=$isCurrentStreaming, contentStarted=${message.contentStarted}, " +
                    "textLen=${message.text.length}, isWithinMinDisplayTime=$isWithinMinDisplayTime"
            )
        }

        return state
    }

    private fun createAiMessageItems(
        message: Message,
        isApiCalling: Boolean,
        currentStreamingAiMessageId: String?,
        parseResult: StreamBlockParser.ParseResult? = null,
        isImageGeneration: Boolean = false
    ): List<ChatListItem> {
        val sm = getBubbleStateMachine(message.id)
        val state = computeBubbleState(message, isApiCalling, currentStreamingAiMessageId, isImageGeneration)
        val resolvedParseResult = parseResult ?: StreamBlockParser.parse(message.text, message.id)

        return when (state) {
            is com.android.everytalk.ui.state.AiBubbleState.Connecting -> {
                android.util.Log.d("MessageItemsController", "createAiMessageItems: Connecting -> LoadingIndicator")
                listOf(ChatListItem.LoadingIndicator(message.id))
            }
            is com.android.everytalk.ui.state.AiBubbleState.Reasoning -> {
                android.util.Log.d(
                    "MessageItemsController",
                    "createAiMessageItems: Reasoning -> AiMessageReasoning, reasoning=${message.reasoning?.take(30)}"
                )
                listOf(ChatListItem.AiMessageReasoning(message))
            }
            is com.android.everytalk.ui.state.AiBubbleState.Streaming -> {
                val items = mutableListOf<ChatListItem>()
                // 即使 reasoningComplete 为 false，只要有 reasoning 内容且进入 Streaming 阶段（意味着 contentStarted=true），
                // 我们也需要保留 AiMessageReasoning Item。
                // 这样 ThinkingUI 才能留在组件树中，执行“思考框消失 -> 小白点出现”的过渡动画。
                // 否则 Item 会被移除，导致动画状态丢失，直到 Complete 状态才重新出现。
                if (state.hasReasoning && !message.reasoning.isNullOrBlank()) {
                    items.add(ChatListItem.AiMessageReasoning(message))
                }

                // 始终使用“完成态”组件类型，依靠 EnhancedMarkdownText 的 isStreaming 实时订阅更新内容
                // 这样在 Finish 事件到来时不会切换 item 类型，避免 LazyColumn 发生一次布局重排导致页面跳动
                val streamingItem: ChatListItem = when (message.outputType) {
                    "code" -> ChatListItem.AiMessageCode(message.id, message.text, state.hasReasoning)
                    else -> ChatListItem.AiMessage(
                        messageId = message.id,
                        text = message.text,
                        hasReasoning = state.hasReasoning,
                        blocksHash = resolvedParseResult.blocksHash,
                        hasPendingMath = resolvedParseResult.hasPendingMath,
                        blocks = resolvedParseResult.blocks
                    )
                }
                items.add(streamingItem)
                
                // 添加执行状态指示器
                if (!message.executionStatus.isNullOrBlank()) {
                    items.add(ChatListItem.StatusIndicator(message.id, message.executionStatus))
                }

                // 提前显示 Footer（如果有搜索结果），减少 Finish 时的结构突变
                if (!message.webSearchResults.isNullOrEmpty()) {
                    items.add(ChatListItem.AiMessageFooter(message))
                }
                
                items
            }
            is com.android.everytalk.ui.state.AiBubbleState.Complete -> {
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
                            else -> ChatListItem.AiMessage(
                                messageId = message.id,
                                text = message.text,
                                hasReasoning = !message.reasoning.isNullOrBlank(),
                                blocksHash = resolvedParseResult.blocksHash,
                                hasPendingMath = resolvedParseResult.hasPendingMath,
                                blocks = resolvedParseResult.blocks
                            )
                        }
                    )
                    android.util.Log.d(
                        "MessageItemsController",
                        "[COMPLETE STATE] Created AiMessage item: hasTextContent=$hasTextContent, " +
                            "hasImageContent=$hasImageContent, imageUrls=${message.imageUrls?.size}"
                    )
                }

                if (!message.webSearchResults.isNullOrEmpty()) {
                    items.add(ChatListItem.AiMessageFooter(message))
                }

                items
            }
            is com.android.everytalk.ui.state.AiBubbleState.Error -> {
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

    /**
     * 清除指定消息的缓存，强制重新计算ChatListItem
     * 用于消息编辑后确保UI更新
     */
    fun clearCacheForMessage(messageId: String, isImageGeneration: Boolean = false) {
        if (isImageGeneration) {
            imageGenerationChatListItemCache.remove(messageId)
            android.util.Log.d("MessageItemsController", "Cleared IMAGE cache for message: ${messageId.take(8)}")
        } else {
            chatListItemCache.remove(messageId)
            android.util.Log.d("MessageItemsController", "Cleared TEXT cache for message: ${messageId.take(8)}")
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCaches() {
        chatListItemCache.clear()
        imageGenerationChatListItemCache.clear()
        streamingStartTimestamps.clear()
        android.util.Log.d("MessageItemsController", "Cleared all caches and streaming timestamps")
    }
    
    /**
     * 清理指定消息的流式时间戳
     * 在消息完成或取消时调用
     */
    fun clearStreamingTimestamp(messageId: String) {
        streamingStartTimestamps.remove(messageId)
        android.util.Log.d("MessageItemsController", "Cleared streaming timestamp for message: ${messageId.take(8)}")
    }
}
