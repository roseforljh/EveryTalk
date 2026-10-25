package com.android.everytalk.ui.screens.MainScreen.chat.core
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.agent.AgentControlToolNames
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

    /** 一段模型输出文本，包含执行旁白；显示后不再因工具到达而迁入执行区。 */
    data class AiMessageContentSegment(
        val sourceMessageId: String,
        val message: Message,
        val segmentIndex: Int,
        val text: String,
        val isStreaming: Boolean,
        val renderState: StreamingRenderState,
        // 历史正文复用已有 Markdown 分块；仍保留原消息和段序号，保证工具/用户边界顺序。
        val staticBlock: AiMarkdownNode? = null,
        val staticPageSources: List<WebSearchResult> = emptyList(),
    ) : ChatListItem {
        override val stableId: String = if (staticBlock == null || staticBlock.isFirstNode) {
            "${sourceMessageId}_content_$segmentIndex"
        } else staticBlock.stableId
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
    data class UserMessageBoundary(val messageId: String) : OrderedAiOutputSegment

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

/**
 * 返回聊天展示使用的执行轨迹。
 *
 * 工具调用前后的旁白仍保留为 Content，后续渲染会把它显示成普通文本；只有真正的
 * Reasoning 才使用思考样式。这里不改消息持久化数据，也不把旁白伪装成思考内容。
 */
internal fun executionTraceForDisplay(trace: List<ExecutionTraceEvent>): List<ExecutionTraceEvent> {
    return trace
}

/**
 * 找出执行详情里需要保留的旁白，即当前用户指令阶段最后一个工具之前的 Content。
 *
 * 详情中这类 Content 和工具步骤相邻显示；聊天列表中的正文组件不因此迁移。
 * 最后一个工具之后的 Content 仍然是正式回答。按用户边界分阶段计算，避免混入上一阶段的正文。
 * 向用户提问的工具不算执行步骤：提问前的 Content 是给用户看的回复，不能折叠进执行区。
 */
private fun executionNarrativeContentIndexes(
    trace: List<ExecutionTraceEvent>,
    toolCallWriting: Boolean = false,
): Set<Int> = buildSet {
    fun markPhase(startIndex: Int, endIndex: Int) {
        var lastToolIndex = -1
        for (index in startIndex until endIndex) {
            val event = trace[index]
            if (event is ExecutionTraceEvent.Tool &&
                event.step.labels.firstOrNull() != AgentControlToolNames.REQUEST_USER_INPUT
            ) lastToolIndex = index
        }
        if (lastToolIndex < 0) return
        for (index in startIndex until lastToolIndex) {
            if (trace[index] is ExecutionTraceEvent.Content) add(index)
        }
    }

    var phaseStart = 0
    trace.forEachIndexed { index, event ->
        if (event is ExecutionTraceEvent.UserMessageBoundary) {
            markPhase(phaseStart, index)
            phaseStart = index + 1
        }
    }
    markPhase(phaseStart, trace.size)
    // 参数增量已经证明当前阶段还要调用工具，不必等完整 Tool 事件才把旁白归入执行区。
    // 只处理最后一个用户边界之后的文本，停止后不保留虚构的工具步骤。
    if (toolCallWriting) {
        for (index in phaseStart until trace.size) {
            if (trace[index] is ExecutionTraceEvent.Content) add(index)
        }
    }
}

/** 只返回详情抽屉需要的执行事件，正式回答正文不会进入执行详情。 */
internal fun executionTraceForProcessDisplay(
    trace: List<ExecutionTraceEvent>,
    toolCallWriting: Boolean = false,
): List<ExecutionTraceEvent> {
    val narrativeIndexes = executionNarrativeContentIndexes(trace, toolCallWriting)
    return trace.mapIndexedNotNull { index, event ->
        when {
            event is ExecutionTraceEvent.Reasoning || event is ExecutionTraceEvent.Tool -> event
            index in narrativeIndexes -> event
            else -> null
        }
    }
}

/**
 * 按事件顺序合并相邻正文、相邻思考和工具。
 * 后续工具只能追加过程段，不能改变已显示正文的类型和段序号，否则 LazyColumn 会重建气泡。
 * 旁白索引仅用于对齐详情抽屉，聊天正文始终保留在原来的渲染组件中。
 */
internal fun orderedAiOutputSegments(
    trace: List<ExecutionTraceEvent>,
): List<OrderedAiOutputSegment> {
    val displayTrace = executionTraceForDisplay(trace)
    val narrativeContentIndexes = executionNarrativeContentIndexes(displayTrace)
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

    displayTrace.forEachIndexed { eventIndex, event ->
        when (event) {
            is ExecutionTraceEvent.UserMessageBoundary -> {
                flushContent()
                flushProcess(event.startedAtMillis)
                result += OrderedAiOutputSegment.UserMessageBoundary(event.messageId)
            }
            is ExecutionTraceEvent.Content -> {
                flushProcess(event.startedAtMillis)
                if (content.isEmpty()) contentStartedAtMillis = event.startedAtMillis
                content.append(event.text)
                if (eventIndex in narrativeContentIndexes) processEventIndex++
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
