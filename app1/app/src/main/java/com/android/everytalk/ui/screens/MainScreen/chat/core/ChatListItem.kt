package com.android.everytalk.ui.screens.MainScreen.chat.core
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.markdown.safeTextInNode
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamingRenderState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

// ponytail: 轻量节点按渲染成本小批量合并；公式成本高于纯文本，代码、表格和图片仍独占分块。
private const val STATIC_MARKDOWN_BLOCK_MAX_RENDER_COST = 4
private const val STATIC_MARKDOWN_BLOCK_MAX_SOURCE_CHARS = 2_000

enum class PlaceholderRole {
    User,
    Assistant,
}

sealed interface ChatListItem {
    val stableId: String

    data class UserMessage(
        val messageId: String,
        val text: String,
        val attachments: List<com.android.everytalk.models.SelectedMediaItem>
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class SystemMessage(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    // 完成态消息携带后台准备的渲染数据，流式消息继续由独立状态实时驱动。
    data class AiMessage(
        val message: Message,
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean,
        val blocksHash: String = "",
        val hasPendingMath: Boolean = false,
        val blocks: List<StreamBlock> = emptyList(),
        val displayText: String = text,
        val pageSources: List<WebSearchResult> = emptyList(),
        val preparedMessage: PreparedMessage? = null,
        val preparedMarkdownDocument: PreparedMarkdownDocument? = null,
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageCode(
        val message: Message,
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean,
        val blocks: List<StreamBlock> = emptyList(),
        val displayText: String = text,
        val pageSources: List<WebSearchResult> = emptyList(),
        val preparedMessage: PreparedMessage? = null,
        val preparedMarkdownDocument: PreparedMarkdownDocument? = null,
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageSources(
        val message: Message,
        val messageId: String,
        val pageSources: List<WebSearchResult>,
    ) : ChatListItem {
        override val stableId: String = "${messageId}_sources"
    }

    data class AiMarkdownNode(
        val message: Message,
        val messageId: String,
        val preparedMessage: PreparedMessage,
        val preparedMarkdownDocument: PreparedMarkdownDocument,
        val nodes: List<ASTNode>,
        val firstNodeIndex: Int,
        val lastNodeIndex: Int,
        val blockIndex: Int,
        val targetBlockIndexByUri: Map<String, Int>,
        val hasSourcesBefore: Boolean,
    ) : ChatListItem {
        init {
            require(nodes.isNotEmpty()) { "Markdown 分块至少包含一个节点" }
            require(firstNodeIndex >= 0 && lastNodeIndex >= firstNodeIndex) {
                "Markdown 分块节点索引无效"
            }
        }

        val node: ASTNode
            get() = nodes.first()

        val nodeIndex: Int
            get() = firstNodeIndex

        val nodeCount: Int
            get() = preparedMarkdownDocument.nodes.size

        override val stableId: String =
            "${messageId}_markdown_${nodes.first().startOffset}_${nodes.last().endOffset}"

        val isFirstNode: Boolean
            get() = firstNodeIndex == 0

        val isLastNode: Boolean
            get() = lastNodeIndex == nodeCount - 1
    }

    // 新增：流式渲染专用项（文本/数学/代码）
    // 仅携带 messageId（在 UI 层通过 StateFlow 收集流式文本），避免在数据层频繁变更 text
    data class AiMessageStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageCodeStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = messageId
    }
    // 新增结束


    data class AiMessageReasoning(
        val message: Message,
        val activityStatusText: String? = null,
    ) : ChatListItem {
        override val stableId: String = "${message.id}_reasoning"
    }

    /** 新版有序输出中的一段正式正文。 */
    data class AiMessageContentSegment(
        val sourceMessageId: String,
        val message: Message,
        val segmentIndex: Int,
        val text: String,
        val isStreaming: Boolean,
        val renderState: StreamingRenderState,
    ) : ChatListItem {
        override val stableId: String = "${sourceMessageId}_content_$segmentIndex"
    }

    /** 新版有序输出中一段连续的思考和工具过程。 */
    data class AiMessageProcessSegment(
        val messageId: String,
        val segmentIndex: Int,
        val events: List<ExecutionTraceEvent>,
        val detailStartIndex: Int,
        val activityStatusText: String?,
        val replyIsStreaming: Boolean,
        val processIsActive: Boolean,
        val webSearchResults: List<WebSearchResult>,
        val messageIsError: Boolean,
        val executionStartedAtMillis: Long?,
        val executionFinishedAtMillis: Long?,
    ) : ChatListItem {
        override val stableId: String = "${messageId}_process_$segmentIndex"
    }

    data class AiMessageFooter(val message: Message) : ChatListItem {
        override val stableId: String = "${message.id}_footer"
    }

    data class ErrorMessage(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class LoadingIndicator(
        val messageId: String, 
        val text: String? = null,
        val textResId: Int? = null
    ) : ChatListItem {
        override val stableId: String = "${messageId}_loading"
    }

    data class StatusIndicator(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = "${messageId}_status"
    }

    data class LoadingBubblePlaceholder(
        val id: String,
        val role: PlaceholderRole,
        val widthFraction: Float,
        val estimatedHeightDp: Int,
    ) : ChatListItem {
        override val stableId: String = id
    }
}

internal sealed interface OrderedAiOutputSegment {
    data class Content(
        val text: String,
        val startedAtMillis: Long?,
    ) : OrderedAiOutputSegment

    data class Process(
        val events: List<ExecutionTraceEvent>,
        val detailStartIndex: Int,
        val startedAtMillis: Long?,
        val finishedAtMillis: Long?,
    ) : OrderedAiOutputSegment
}

/** 相邻正文合并，相邻思考和工具合并；每个事件只处理一次，避免长工具链反复复制列表。 */
internal fun orderedAiOutputSegments(trace: List<ExecutionTraceEvent>): List<OrderedAiOutputSegment> {
    val result = mutableListOf<OrderedAiOutputSegment>()
    var content = StringBuilder()
    var processEvents = mutableListOf<ExecutionTraceEvent>()
    var processStartIndex = 0
    var processEventIndex = 0
    var contentStartedAtMillis: Long? = null

    fun flushContent() {
        // 正文和执行过程已经是两个独立列表项，边界换行不能继续交给 Markdown 渲染，
        // 否则过程段下方会比上方多出一整行空白。正文内部的换行保持不变。
        val text = content.toString().trim('\r', '\n')
        content = StringBuilder()
        if (text.isNotBlank()) {
            result += OrderedAiOutputSegment.Content(text, contentStartedAtMillis)
        }
        contentStartedAtMillis = null
    }

    fun flushProcess(finishedAtMillis: Long? = null) {
        if (processEvents.isNotEmpty()) {
            result += OrderedAiOutputSegment.Process(
                events = processEvents.toList(),
                detailStartIndex = processStartIndex,
                startedAtMillis = processEvents.firstNotNullOfOrNull { it.startedAtMillis },
                finishedAtMillis = finishedAtMillis,
            )
            processEvents = mutableListOf()
        }
    }

    trace.forEach { event ->
        when (event) {
            is ExecutionTraceEvent.Content -> {
                flushProcess(event.startedAtMillis)
                if (content.isEmpty()) contentStartedAtMillis = event.startedAtMillis
                content.append(event.text)
            }
            is ExecutionTraceEvent.Reasoning,
            is ExecutionTraceEvent.Tool,
            -> {
                flushContent()
                if (processEvents.isEmpty()) processStartIndex = processEventIndex
                processEvents += event
                processEventIndex++
            }
        }
    }
    flushContent()
    flushProcess()
    return result
}

internal fun expandStaticAiMessageItem(item: ChatListItem): List<ChatListItem> {
    val message: Message
    val messageId: String
    val pageSources: List<WebSearchResult>
    val preparedMessage: PreparedMessage
    val preparedMarkdownDocument: PreparedMarkdownDocument

    when (item) {
        is ChatListItem.AiMessage -> {
            message = item.message
            messageId = item.messageId
            pageSources = item.pageSources
            preparedMessage = item.preparedMessage ?: return listOf(item)
            preparedMarkdownDocument = item.preparedMarkdownDocument ?: return listOf(item)
        }

        is ChatListItem.AiMessageCode -> {
            message = item.message
            messageId = item.messageId
            pageSources = item.pageSources
            preparedMessage = item.preparedMessage ?: return listOf(item)
            preparedMarkdownDocument = item.preparedMarkdownDocument ?: return listOf(item)
        }

        else -> return listOf(item)
    }

    if (preparedMarkdownDocument.nodes.isEmpty()) return listOf(item)

    val nodeBlocks = buildStaticMarkdownNodeBlocks(
        nodes = preparedMarkdownDocument.nodes,
        standaloneNodeIndices = preparedMarkdownDocument.targetNodeIndexByUri.values.toSet(),
        content = preparedMarkdownDocument.state.content,
    )
    val blockIndexByNodeIndex = IntArray(preparedMarkdownDocument.nodes.size)
    var nextNodeIndex = 0
    nodeBlocks.forEachIndexed { blockIndex, nodes ->
        repeat(nodes.size) {
            blockIndexByNodeIndex[nextNodeIndex++] = blockIndex
        }
    }
    val targetBlockIndexByUri = buildMap {
        preparedMarkdownDocument.targetNodeIndexByUri.forEach { (uri, nodeIndex) ->
            if (nodeIndex in blockIndexByNodeIndex.indices) {
                put(uri, blockIndexByNodeIndex[nodeIndex])
            }
        }
    }

    return buildList {
        if (pageSources.isNotEmpty()) {
            add(
                ChatListItem.AiMessageSources(
                    message = message,
                    messageId = messageId,
                    pageSources = pageSources,
                )
            )
        }
        var firstNodeIndex = 0
        nodeBlocks.forEachIndexed { blockIndex, nodes ->
            val lastNodeIndex = firstNodeIndex + nodes.lastIndex
            add(
                ChatListItem.AiMarkdownNode(
                    message = message,
                    messageId = messageId,
                    preparedMessage = preparedMessage,
                    preparedMarkdownDocument = preparedMarkdownDocument,
                    nodes = nodes,
                    firstNodeIndex = firstNodeIndex,
                    lastNodeIndex = lastNodeIndex,
                    blockIndex = blockIndex,
                    targetBlockIndexByUri = targetBlockIndexByUri,
                    hasSourcesBefore = pageSources.isNotEmpty(),
                )
            )
            firstNodeIndex = lastNodeIndex + 1
        }
    }
}

internal fun buildStaticMarkdownNodeBlocks(
    nodes: List<ASTNode>,
    standaloneNodeIndices: Set<Int> = emptySet(),
    content: String? = null,
): List<List<ASTNode>> {
    if (nodes.isEmpty()) return emptyList()

    val blocks = ArrayList<List<ASTNode>>()
    val current = ArrayList<ASTNode>(STATIC_MARKDOWN_BLOCK_MAX_RENDER_COST)
    var currentSourceChars = 0
    var currentRenderCost = 0
    var currentContainsStandaloneNode = false

    fun flushCurrent() {
        if (current.isEmpty()) return
        blocks.add(current.toList())
        current.clear()
        currentSourceChars = 0
        currentRenderCost = 0
        currentContainsStandaloneNode = false
    }

    nodes.forEachIndexed { nodeIndex, node ->
        val sourceChars = (node.endOffset - node.startOffset).coerceAtLeast(0)
        val renderCost = node.staticMarkdownRenderCost(content)
        if (
            nodeIndex in standaloneNodeIndices ||
            node.requiresStandaloneStaticMarkdownBlock(content)
        ) {
            if (currentRenderCost > 0 || currentContainsStandaloneNode) flushCurrent()
            current.add(node)
            currentSourceChars += sourceChars
            currentRenderCost += renderCost
            currentContainsStandaloneNode = true
            return@forEachIndexed
        }

        if (
            renderCost > 0 &&
            (currentContainsStandaloneNode ||
                (currentRenderCost > 0 &&
                    (currentRenderCost + renderCost > STATIC_MARKDOWN_BLOCK_MAX_RENDER_COST ||
                        currentSourceChars + sourceChars > STATIC_MARKDOWN_BLOCK_MAX_SOURCE_CHARS)))
        ) {
            flushCurrent()
        }
        current.add(node)
        currentSourceChars += sourceChars
        currentRenderCost += renderCost
    }
    flushCurrent()
    return blocks
}

private fun ASTNode.staticMarkdownRenderCost(content: String?): Int = when (type) {
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE -> 0

    else -> {
        val source = content?.let(::safeTextInNode)
        if (
            source?.contains(INLINE_FORMULA_SCHEME) == true ||
            source?.contains(BLOCK_FORMULA_FENCE_LANGUAGE) == true
        ) {
            2
        } else {
            1
        }
    }
}

private fun ASTNode.requiresStandaloneStaticMarkdownBlock(content: String?): Boolean {
    if (
        (type == MarkdownElementTypes.CODE_FENCE && !isInternalMathFence(content)) ||
        type == MarkdownElementTypes.CODE_BLOCK ||
        type == MarkdownElementTypes.IMAGE ||
        type == GFMElementTypes.TABLE
    ) {
        return true
    }
    return children.any { child -> child.requiresStandaloneStaticMarkdownBlock(content) }
}

private fun ASTNode.isInternalMathFence(content: String?): Boolean {
    if (type != MarkdownElementTypes.CODE_FENCE || content == null) return false
    val openingLine = safeTextInNode(content)
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?: return false
    return openingLine == "```$BLOCK_FORMULA_FENCE_LANGUAGE" ||
        openingLine == "~~~$BLOCK_FORMULA_FENCE_LANGUAGE"
}
