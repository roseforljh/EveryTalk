package com.android.everytalk.ui.screens.MainScreen.chat.core
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.markdown.safeTextInNode
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
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


    data class AiMessageReasoning(val message: Message) : ChatListItem {
        override val stableId: String = "${message.id}_reasoning"
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
