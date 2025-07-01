package com.example.everytalk.ui.screens.MainScreen.chat

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.everytalk.R
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.example.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.example.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.example.everytalk.ui.theme.ChatDimensions
import com.example.everytalk.ui.theme.chatColors
import com.example.everytalk.util.CodeHighlighter
import com.example.everytalk.util.MarkdownBlock
import com.example.everytalk.util.IncrementalMarkdownParser
import com.example.everytalk.util.parseInlineMarkdownToAnnotatedString
import com.example.everytalk.util.RenderMode
import com.example.everytalk.util.parseMarkdownWithMode
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.example.everytalk.util.StreamingMarkdownRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LocalOnTextLayout = compositionLocalOf<((androidx.compose.ui.text.TextLayoutResult, AnnotatedString) -> Unit)?> { null }

@Composable
fun ChatMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: ChatScrollStateManager,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onThinkingBoxVisibilityChanged: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
 
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val renderer = remember { StreamingMarkdownRenderer() }
    var isUserScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var lastFirstVisibleItemIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                if (it < lastFirstVisibleItemIndex) {
                    isUserScrolling = true
                } else if (it > lastFirstVisibleItemIndex && !listState.canScrollForward) {
                    isUserScrolling = false
                }
                lastFirstVisibleItemIndex = it
            }
    }

    LaunchedEffect(chatItems) {
        if (chatItems.lastOrNull() is ChatListItem.AiMessageReasoning) {
            scrollStateManager.jumpToBottom()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollStateManager.nestedScrollConnection),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> item::class.java.simpleName }
        ) { index, item ->
            when (item) {
                is ChatListItem.UserMessage -> {
                    val message = viewModel.getMessageById(item.messageId)
                    if (message != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            if (!item.attachments.isNullOrEmpty()) {
                                AttachmentsContent(
                                    attachments = item.attachments,
                                    onAttachmentClick = { },
                                    maxWidth = bubbleMaxWidth * ChatDimensions.BUBBLE_WIDTH_RATIO,
                                    message = message,
                                    onEditRequest = { viewModel.requestEditMessage(it) },
                                    onRegenerateRequest = {
                                        viewModel.regenerateAiResponse(it)
                                        scrollStateManager.jumpToBottom()
                                    },
                                    scrollStateManager = scrollStateManager,
                                    onImageLoaded = onImageLoaded,
                                )
                            }
                            if (item.text.isNotBlank()) {
                                UserOrErrorMessageContent(
                                    message = message,
                                    displayedText = item.text,
                                    showLoadingDots = false,
                                    bubbleColor = MaterialTheme.chatColors.userBubble,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    isError = false,
                                    maxWidth = bubbleMaxWidth * ChatDimensions.BUBBLE_WIDTH_RATIO,
                                    onEditRequest = { viewModel.requestEditMessage(it) },
                                    onRegenerateRequest = {
                                        viewModel.regenerateAiResponse(it)
                                        scrollStateManager.jumpToBottom()
                                    },
                                    scrollStateManager = scrollStateManager,
                                )
                            }
                        }
                    }
                }

                is ChatListItem.AiMessageReasoning -> {
                    val reasoningCompleteMap = viewModel.reasoningCompleteMap
                    val isReasoningStreaming = remember(isApiCalling, item.message.reasoning, reasoningCompleteMap[item.message.id]) {
                        isApiCalling && item.message.reasoning != null && reasoningCompleteMap[item.message.id] != true
                    }
                    val isReasoningComplete = reasoningCompleteMap[item.message.id] ?: false

                    ReasoningToggleAndContent(
                        modifier = Modifier.fillMaxWidth(),
                        currentMessageId = item.message.id,
                        displayedReasoningText = item.message.reasoning ?: "",
                        isReasoningStreaming = isReasoningStreaming,
                        isReasoningComplete = isReasoningComplete,
                        messageIsError = item.message.isError,
                        mainContentHasStarted = item.message.contentStarted,
                        reasoningTextColor = MaterialTheme.chatColors.reasoningText,
                        reasoningToggleDotColor = MaterialTheme.colorScheme.onSurface,
                        onVisibilityChanged = onThinkingBoxVisibilityChanged
                    )
                }

                is ChatListItem.AiMessageBlock -> {
                    var lastHeight by remember { mutableStateOf(0) }
                    AiMessageBlockItem(
                        item = item,
                        maxWidth = bubbleMaxWidth,
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val message = viewModel.getMessageById(item.messageId)
                            message?.let { onShowAiMessageOptions(it) }
                        },
                        onTap = {},
                        onImageLoaded = onImageLoaded,
                        isStreaming = isApiCalling,
                        renderer = renderer,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            val newHeight = coordinates.size.height
                            if (lastHeight != 0 && lastHeight != newHeight) {
                                if (isUserScrolling && index < listState.firstVisibleItemIndex) {
                                    val heightDiff = newHeight - lastHeight
                                    if (heightDiff != 0) {
                                        coroutineScope.launch {
                                            listState.scrollBy(heightDiff.toFloat())
                                        }
                                    }
                                }
                            }
                            lastHeight = newHeight
                        }
                    )
                }

                is ChatListItem.AiMessageFooter -> {
                    AiMessageFooterItem(
                        message = item.message,
                        viewModel = viewModel,
                    )
                }

                is ChatListItem.ErrorMessage -> {
                    val message = viewModel.getMessageById(item.messageId)
                    if (message != null) {
                        UserOrErrorMessageContent(
                            message = message,
                            displayedText = item.text,
                            showLoadingDots = false,
                            bubbleColor = MaterialTheme.chatColors.aiBubble,
                            contentColor = MaterialTheme.chatColors.errorContent,
                            isError = true,
                            maxWidth = bubbleMaxWidth,
                            onEditRequest = { viewModel.requestEditMessage(it) },
                            onRegenerateRequest = {
                                viewModel.regenerateAiResponse(it)
                                scrollStateManager.jumpToBottom()
                            },
                            scrollStateManager = scrollStateManager,
                        )
                    }
                }

                is ChatListItem.LoadingIndicator -> {
                    Row(
                        modifier = Modifier
                            .padding(
                                start = ChatDimensions.HORIZONTAL_PADDING,
                                top = ChatDimensions.VERTICAL_PADDING,
                                bottom = ChatDimensions.VERTICAL_PADDING
                            ),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = stringResource(id = R.string.connecting_to_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(ChatDimensions.LOADING_SPACER_WIDTH))
                        CircularProgressIndicator(
                            modifier = Modifier.size(ChatDimensions.LOADING_INDICATOR_SIZE),
                            color = MaterialTheme.chatColors.loadingIndicator,
                            strokeWidth = ChatDimensions.LOADING_INDICATOR_STROKE_WIDTH
                        )
                    }
                }
            }
        }
        item(key = "chat_screen_footer_spacer_in_list") {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun AiMessageBlockItem(
    item: ChatListItem.AiMessageBlock,
    maxWidth: Dp,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onImageLoaded: () -> Unit,
    isStreaming: Boolean,
    renderer: StreamingMarkdownRenderer,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var annotatedString by remember { mutableStateOf<AnnotatedString?>(null) }

    val shape = getBubbleShape(
        isFirstBlock = item.isFirstBlock,
        hasReasoning = item.hasReasoning,
        isLastBlock = item.isLastBlock
    )
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .pointerInput(item.messageId, annotatedString) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onTap = { offset ->
                            val currentAnnotatedString = annotatedString
                            val currentLayoutResult = textLayoutResult
                            if (currentAnnotatedString != null && currentLayoutResult != null) {
                                val characterIndex = currentLayoutResult.getOffsetForPosition(offset)
                                currentAnnotatedString.getStringAnnotations(
                                    tag = "URL",
                                    start = characterIndex,
                                    end = characterIndex
                                ).firstOrNull()?.let { annotation ->
                                    try {
                                        uriHandler.openUri(annotation.item)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
                                    }
                                } ?: onTap()
                            } else {
                                onTap()
                            }
                        }
                    )
                }
                .semantics {
                    contentDescription = aiReplyMessageDescription
                },
            shape = shape,
            color = MaterialTheme.chatColors.aiBubble,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            CompositionLocalProvider(
                LocalOnTextLayout provides { result, string ->
                    textLayoutResult = result
                    annotatedString = string
                }
            ) {
                RenderMarkdownBlock(
                    block = item.block,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onImageLoaded = onImageLoaded,
                    isStreaming = isStreaming,
                    renderer = renderer
                )
            }
        }
    }
}

private fun getBubbleShape(isFirstBlock: Boolean, hasReasoning: Boolean, isLastBlock: Boolean): RoundedCornerShape {
    return RoundedCornerShape(
        topStart = if (isFirstBlock && !hasReasoning) ChatDimensions.CORNER_RADIUS_LARGE else ChatDimensions.CORNER_RADIUS_SMALL,
        topEnd = ChatDimensions.CORNER_RADIUS_LARGE,
        bottomStart = if (isLastBlock) ChatDimensions.CORNER_RADIUS_LARGE else ChatDimensions.CORNER_RADIUS_SMALL,
        bottomEnd = if (isLastBlock) ChatDimensions.CORNER_RADIUS_LARGE else ChatDimensions.CORNER_RADIUS_SMALL
    )
}

@Composable
private fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
) {
    if (!message.webSearchResults.isNullOrEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ChatDimensions.HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(
                onClick = {
                    viewModel.showSourcesDialog(message.webSearchResults)
                },
            ) {
                Text(stringResource(id = R.string.view_sources, message.webSearchResults.size))
            }
        }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MarkdownBlock,
    contentColor: Color,
    onImageLoaded: () -> Unit,
    isStreaming: Boolean,
    renderer: StreamingMarkdownRenderer
) {
    Box(
        modifier = Modifier
            .padding(
                horizontal = ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                vertical = ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
            )
    ) {
        when (block) {
            is MarkdownBlock.Header -> MarkdownHeader(block = block, contentColor = contentColor, isStreaming = isStreaming, renderer = renderer)

            is MarkdownBlock.Paragraph -> MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                isStreaming = isStreaming,
                renderer = renderer,
                messageId = block.hashCode().toString() // This is not ideal, but works for now
            )

            is MarkdownBlock.CodeBlock -> CodeBlock(
                rawText = block.rawText,
                language = block.language,
                contentColor = contentColor
            )

            is MarkdownBlock.UnorderedList -> {
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    block.items.forEach { itemText ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", style = MaterialTheme.typography.bodyLarge.copy(color = contentColor))
                            MarkdownText(
                                text = itemText,
                                style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                                modifier = Modifier.weight(1f),
                                isStreaming = isStreaming,
                                renderer = renderer,
                                messageId = itemText.hashCode().toString()
                            )
                        }
                    }
                }
            }

            is MarkdownBlock.OrderedList -> {
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    block.items.forEachIndexed { index, itemText ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("${index + 1}. ", style = MaterialTheme.typography.bodyLarge.copy(color = contentColor))
                            MarkdownText(
                                text = itemText,
                                style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                                modifier = Modifier.weight(1f),
                                isStreaming = isStreaming,
                                renderer = renderer,
                                messageId = itemText.hashCode().toString()
                            )
                        }
                    }
                }
            }

            is MarkdownBlock.Blockquote -> {
                Row {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(contentColor.copy(alpha = 0.3f))
                    )
                    Column {
                        block.blocks.forEach { nestedBlock ->
                            RenderMarkdownBlock(block = nestedBlock, contentColor = contentColor, onImageLoaded = onImageLoaded, isStreaming = isStreaming, renderer = renderer)
                        }
                    }
                }
            }

            is MarkdownBlock.HorizontalRule -> {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = contentColor.copy(alpha = 0.2f)
                )
            }

            is MarkdownBlock.Image -> {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(block.url)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .listener(onSuccess = { _, _ -> onImageLoaded() })
                        .build(),
                    contentDescription = block.altText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ChatDimensions.IMAGE_PADDING_VERTICAL)
                        .clip(RoundedCornerShape(ChatDimensions.TABLE_CORNER_RADIUS)),
                    error = painterResource(R.drawable.ic_launcher_foreground)
                )
            }

            is MarkdownBlock.Table -> MarkdownTable(
                header = block.header,
                rows = block.rows,
                contentColor = contentColor,
                isStreaming = isStreaming,
                renderer = renderer
            )
        }
    }
}

@Composable
private fun CodeBlock(rawText: String, language: String?, contentColor: Color) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val annotatedString by produceState(initialValue = AnnotatedString(rawText), rawText, language) {
        withContext(Dispatchers.Default) {
            value = CodeHighlighter.highlightToAnnotatedString(rawText, language)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.chatColors.codeBlockBackground,
                RoundedCornerShape(ChatDimensions.CODE_BLOCK_CORNER_RADIUS)
            )
            .padding(
                top = ChatDimensions.CODE_BLOCK_PADDING_TOP,
                start = ChatDimensions.CODE_BLOCK_PADDING_HORIZONTAL,
                end = ChatDimensions.CODE_BLOCK_PADDING_HORIZONTAL,
                bottom = ChatDimensions.CODE_BLOCK_PADDING_BOTTOM
            )
    ) {
        Text(
            text = annotatedString,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = ChatDimensions.CODE_FONT_SIZE,
                lineHeight = ChatDimensions.CODE_LINE_HEIGHT
            ),
            color = contentColor
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(rawText))
                    Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(id = R.string.copy),
                    modifier = Modifier.size(ChatDimensions.COPY_ICON_SIZE),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, contentColor: Color, isStreaming: Boolean, renderer: StreamingMarkdownRenderer) {
    val columnCount = remember(header) { header.size }
    if (columnCount == 0) return

    val cellWeight = remember(columnCount) { 1f / columnCount }

    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .padding(vertical = ChatDimensions.VERTICAL_PADDING)
                .clip(RoundedCornerShape(ChatDimensions.TABLE_CORNER_RADIUS))
                .border(
                    ChatDimensions.TABLE_BORDER_WIDTH,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    RoundedCornerShape(ChatDimensions.TABLE_CORNER_RADIUS)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            ) {
                header.forEachIndexed { index, text ->
                    TableCell(
                        text = text,
                        isHeader = true,
                        weight = cellWeight,
                        contentColor = contentColor,
                        isStreaming = isStreaming,
                        renderer = renderer
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))

            rows.forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (i in 0 until columnCount) {
                        val text = row.getOrNull(i) ?: ""
                        TableCell(
                            text = text,
                            isHeader = false,
                            weight = cellWeight,
                            contentColor = contentColor,
                            isStreaming = isStreaming,
                            renderer = renderer
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    isHeader: Boolean,
    weight: Float,
    contentColor: Color,
    isStreaming: Boolean,
    renderer: StreamingMarkdownRenderer
) {
    MarkdownText(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(ChatDimensions.TABLE_CELL_PADDING),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
            textAlign = TextAlign.Start
        ),
        isStreaming = isStreaming,
        renderer = renderer,
        messageId = text.hashCode().toString()
    )
}

@Composable
private fun MarkdownHeader(block: MarkdownBlock.Header, contentColor: Color, isStreaming: Boolean, renderer: StreamingMarkdownRenderer) {
    val typography = MaterialTheme.typography
    val headerStyle = remember(typography, block.level, contentColor) {
        typography.headlineSmall.copy(
            fontSize = when (block.level) {
                1 -> ChatDimensions.HEADER_1_SIZE
                2 -> ChatDimensions.HEADER_2_SIZE
                else -> ChatDimensions.HEADER_DEFAULT_SIZE
            },
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
    MarkdownText(
        text = block.text,
        style = headerStyle,
        isStreaming = isStreaming,
        renderer = renderer,
        messageId = block.text.hashCode().toString()
    )
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    isStreaming: Boolean,
    renderer: StreamingMarkdownRenderer,
    messageId: String
) {
    val onTextLayout = LocalOnTextLayout.current
    var displayText by remember { mutableStateOf(text) }

    LaunchedEffect(text) {
        delay(50) // 50ms debounce
        displayText = text
    }

    val annotatedString by produceState(initialValue = AnnotatedString(displayText), displayText, isStreaming) {
        withContext(Dispatchers.Default) {
            // By ignoring isUserScrolling, we ensure markdown format is preserved.
            value = renderer.renderStreaming(messageId, displayText, !isStreaming, isUserScrolling = false)
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier,
        style = style,
        onTextLayout = { layoutResult ->
            onTextLayout?.invoke(layoutResult, annotatedString)
        }
    )
}